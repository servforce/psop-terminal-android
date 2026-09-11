#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "ggml-backend.h"
#include "llama.h"
#include "minicpm_logging.h"
#include "mtmd-helper.h"
#include "mtmd.h"
#include "sampling.h"

namespace {

constexpr int kDefaultContextSize = 8192;
constexpr int kDefaultBatchSize = 2048;
constexpr int kDefaultThreads = 4;
constexpr int kDefaultMaxTokens = 512;
constexpr int kContextHeadroom = 8;

std::mutex g_state_mutex;
std::atomic_bool g_cancel_requested{false};
bool g_backend_initialized = false;

llama_model * g_model = nullptr;
llama_context * g_context = nullptr;
const llama_vocab * g_vocab = nullptr;
mtmd_context * g_vision = nullptr;
common_sampler * g_sampler = nullptr;
common_chat_templates_ptr g_templates;
llama_batch g_batch{};
bool g_batch_initialized = false;

mtmd_bitmap * g_pending_bitmap = nullptr;
std::vector<common_chat_msg> g_chat_history;
std::vector<llama_token> g_generated_tokens;
llama_pos g_n_past = 0;
int g_generated_count = 0;
int g_max_tokens = kDefaultMaxTokens;
bool g_generating = false;
std::string g_last_error;
std::string g_utf8_pending;

void set_error(const std::string & message) {
    g_last_error = message;
    MC_LOGE("%s", message.c_str());
}

jstring to_jstring(JNIEnv * env, const std::string & value) {
    return env->NewStringUTF(value.c_str());
}

jstring success(JNIEnv * env) {
    return to_jstring(env, "");
}

void free_pending_bitmap() {
    if (g_pending_bitmap != nullptr) {
        mtmd_bitmap_free(g_pending_bitmap);
        g_pending_bitmap = nullptr;
    }
}

void finish_generation() {
    if (!g_generating) {
        return;
    }

    if (!g_generated_tokens.empty() && g_context != nullptr) {
        common_chat_msg assistant;
        assistant.role = "assistant";
        assistant.content = common_detokenize(g_context, g_generated_tokens);
        g_chat_history.push_back(std::move(assistant));
    }

    g_generating = false;
    g_generated_count = 0;
    g_max_tokens = kDefaultMaxTokens;
    g_generated_tokens.clear();
    g_utf8_pending.clear();
}

void clear_conversation_locked() {
    g_cancel_requested.store(false);
    g_generating = false;
    g_generated_count = 0;
    g_generated_tokens.clear();
    g_utf8_pending.clear();
    g_chat_history.clear();
    g_n_past = 0;
    free_pending_bitmap();
    if (g_context != nullptr) {
        llama_memory_clear(llama_get_memory(g_context), true);
    }
    if (g_sampler != nullptr) {
        common_sampler_reset(g_sampler);
    }
}

void unload_locked() {
    clear_conversation_locked();

    if (g_batch_initialized) {
        llama_batch_free(g_batch);
        g_batch = {};
        g_batch_initialized = false;
    }
    if (g_sampler != nullptr) {
        common_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    g_templates.reset();
    if (g_vision != nullptr) {
        mtmd_free(g_vision);
        g_vision = nullptr;
    }
    if (g_context != nullptr) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_vocab = nullptr;
}

// Returns 1 for valid UTF-8, 0 for an incomplete trailing sequence, and -1 for invalid input.
int utf8_state(const std::string & value) {
    size_t i = 0;
    while (i < value.size()) {
        const auto lead = static_cast<unsigned char>(value[i]);
        size_t continuation = 0;
        if ((lead & 0x80U) == 0) {
            continuation = 0;
        } else if ((lead & 0xE0U) == 0xC0U) {
            continuation = 1;
            if (lead < 0xC2U) return -1;
        } else if ((lead & 0xF0U) == 0xE0U) {
            continuation = 2;
        } else if ((lead & 0xF8U) == 0xF0U) {
            continuation = 3;
            if (lead > 0xF4U) return -1;
        } else {
            return -1;
        }

        if (i + continuation >= value.size()) {
            return 0;
        }
        for (size_t j = 1; j <= continuation; ++j) {
            if ((static_cast<unsigned char>(value[i + j]) & 0xC0U) != 0x80U) {
                return -1;
            }
        }
        i += continuation + 1;
    }
    return 1;
}

std::string format_user_message(const std::string & content) {
    common_chat_msg message;
    message.role = "user";
    message.content = content;
    return common_chat_format_single(
            g_templates.get(), g_chat_history, message, true, false);
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_rokid_cxrmsamples_minicpm_NativeMiniCpmBridge_nativeInitialize(
        JNIEnv * env, jobject, jstring native_lib_dir) {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    if (g_backend_initialized) {
        return success(env);
    }

    llama_log_set(minicpm_log_callback, nullptr);
    mtmd_helper_log_set(minicpm_log_callback, nullptr);

    const char * path = env->GetStringUTFChars(native_lib_dir, nullptr);
    if (path == nullptr) {
        return to_jstring(env, "无法读取 native 库目录");
    }
    ggml_backend_load_all_from_path(path);
    env->ReleaseStringUTFChars(native_lib_dir, path);
    llama_backend_init();
    g_backend_initialized = true;
    MC_LOGI("llama.cpp-omni backend initialized");
    return success(env);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rokid_cxrmsamples_minicpm_NativeMiniCpmBridge_nativeLoad(
        JNIEnv * env, jobject, jstring model_path, jstring mmproj_path,
        jint context_size, jint threads) {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    unload_locked();
    g_last_error.clear();

    const char * model_chars = env->GetStringUTFChars(model_path, nullptr);
    const char * mmproj_chars = env->GetStringUTFChars(mmproj_path, nullptr);
    if (model_chars == nullptr || mmproj_chars == nullptr) {
        if (model_chars != nullptr) env->ReleaseStringUTFChars(model_path, model_chars);
        if (mmproj_chars != nullptr) env->ReleaseStringUTFChars(mmproj_path, mmproj_chars);
        return to_jstring(env, "无法读取模型路径");
    }

    const std::string model_file(model_chars);
    const std::string mmproj_file(mmproj_chars);
    env->ReleaseStringUTFChars(model_path, model_chars);
    env->ReleaseStringUTFChars(mmproj_path, mmproj_chars);

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(model_file.c_str(), model_params);
    if (g_model == nullptr) {
        set_error("语言模型加载失败");
        unload_locked();
        return to_jstring(env, g_last_error);
    }

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = context_size > 0 ? context_size : kDefaultContextSize;
    context_params.n_batch = kDefaultBatchSize;
    context_params.n_ubatch = kDefaultBatchSize;
    context_params.n_threads = threads > 0 ? threads : kDefaultThreads;
    context_params.n_threads_batch = context_params.n_threads;
    g_context = llama_init_from_model(g_model, context_params);
    if (g_context == nullptr) {
        set_error("语言模型上下文创建失败");
        unload_locked();
        return to_jstring(env, g_last_error);
    }

    mtmd_context_params vision_params = mtmd_context_params_default();
    vision_params.use_gpu = false;
    vision_params.print_timings = true;
    vision_params.n_threads = context_params.n_threads;
    vision_params.image_max_slice_nums = 1;
    g_vision = mtmd_init_from_file(mmproj_file.c_str(), g_model, vision_params);
    if (g_vision == nullptr || !mtmd_support_vision(g_vision)) {
        set_error("视觉投影模型加载失败或模型不支持图片");
        unload_locked();
        return to_jstring(env, g_last_error);
    }

    common_params_sampling sampling;
    sampling.temp = 0.7f;
    sampling.top_k = 0;
    sampling.top_p = 1.0f;
    sampling.min_p = 0.0f;
    sampling.penalty_repeat = 1.0f;
    g_sampler = common_sampler_init(g_model, sampling);
    if (g_sampler == nullptr) {
        set_error("采样器创建失败");
        unload_locked();
        return to_jstring(env, g_last_error);
    }

    g_templates = common_chat_templates_init(g_model, "");
    if (!g_templates) {
        set_error("模型聊天模板加载失败");
        unload_locked();
        return to_jstring(env, g_last_error);
    }

    g_batch = llama_batch_init(1, 0, 1);
    g_batch_initialized = true;
    g_vocab = llama_model_get_vocab(g_model);
    clear_conversation_locked();
    MC_LOGI("MiniCPM-V 4.6 models loaded with %d threads", context_params.n_threads);
    return success(env);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rokid_cxrmsamples_minicpm_NativeMiniCpmBridge_nativeSetImage(
        JNIEnv * env, jobject, jbyteArray encoded_image) {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    if (g_vision == nullptr) {
        return to_jstring(env, "模型尚未加载");
    }

    free_pending_bitmap();
    if (encoded_image == nullptr) {
        return success(env);
    }
    const jsize size = env->GetArrayLength(encoded_image);
    if (size <= 0) {
        return to_jstring(env, "图片数据为空");
    }

    jbyte * bytes = env->GetByteArrayElements(encoded_image, nullptr);
    if (bytes == nullptr) {
        return to_jstring(env, "无法读取图片数据");
    }
    g_pending_bitmap = mtmd_helper_bitmap_init_from_buf(
            g_vision, reinterpret_cast<unsigned char *>(bytes), static_cast<size_t>(size));
    env->ReleaseByteArrayElements(encoded_image, bytes, JNI_ABORT);
    if (g_pending_bitmap == nullptr) {
        return to_jstring(env, "图片解码失败，请改用 JPG 或 PNG 图片");
    }
    return success(env);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rokid_cxrmsamples_minicpm_NativeMiniCpmBridge_nativeBeginPrompt(
        JNIEnv * env, jobject, jstring prompt, jint max_tokens) {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    if (g_context == nullptr || g_vision == nullptr || g_sampler == nullptr) {
        return to_jstring(env, "模型尚未加载");
    }
    if (g_generating) {
        return to_jstring(env, "已有生成任务正在运行");
    }

    const char * prompt_chars = env->GetStringUTFChars(prompt, nullptr);
    if (prompt_chars == nullptr) {
        return to_jstring(env, "无法读取问题文本");
    }
    std::string content(prompt_chars);
    env->ReleaseStringUTFChars(prompt, prompt_chars);
    if (content.empty()) {
        content = "请描述这张图片。";
    }
    if (g_pending_bitmap != nullptr) {
        content = std::string(mtmd_default_marker()) + content;
    }

    const std::string formatted = format_user_message(content);
    mtmd_input_text input_text{};
    input_text.text = formatted.c_str();
    input_text.add_special = g_chat_history.empty();
    input_text.parse_special = true;

    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    const mtmd_bitmap * bitmaps[1] = {g_pending_bitmap};
    const mtmd_bitmap ** bitmap_ptr = g_pending_bitmap != nullptr ? bitmaps : nullptr;
    const size_t bitmap_count = g_pending_bitmap != nullptr ? 1U : 0U;
    const int tokenize_result = mtmd_tokenize(
            g_vision, chunks, &input_text, bitmap_ptr, bitmap_count);
    if (tokenize_result != 0) {
        mtmd_input_chunks_free(chunks);
        free_pending_bitmap();
        return to_jstring(env, "问题或图片分词失败");
    }

    const int requested_max = std::clamp(
            static_cast<int>(max_tokens), 1, kDefaultMaxTokens);
    const llama_pos required_positions = mtmd_helper_get_n_pos(chunks) + requested_max;
    if (g_n_past + required_positions + kContextHeadroom >=
            static_cast<llama_pos>(llama_n_ctx(g_context))) {
        mtmd_input_chunks_free(chunks);
        free_pending_bitmap();
        return to_jstring(env, "上下文空间不足，请先清空会话");
    }

    llama_pos new_n_past = g_n_past;
    const int eval_result = mtmd_helper_eval_chunks(
            g_vision, g_context, chunks, g_n_past, 0,
            kDefaultBatchSize, true, &new_n_past);
    mtmd_input_chunks_free(chunks);
    free_pending_bitmap();
    if (eval_result != 0) {
        return to_jstring(env, "问题或图片编码失败");
    }

    common_chat_msg user_message;
    user_message.role = "user";
    user_message.content = content;
    g_chat_history.push_back(std::move(user_message));
    g_n_past = new_n_past;
    g_generated_tokens.clear();
    g_utf8_pending.clear();
    g_generated_count = 0;
    g_max_tokens = requested_max;
    g_cancel_requested.store(false);
    g_generating = true;
    return success(env);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rokid_cxrmsamples_minicpm_NativeMiniCpmBridge_nativeNextToken(
        JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    if (!g_generating || g_context == nullptr || g_sampler == nullptr) {
        return nullptr;
    }
    if (g_cancel_requested.load() || g_generated_count >= g_max_tokens) {
        finish_generation();
        return nullptr;
    }

    const llama_token token = common_sampler_sample(g_sampler, g_context, -1);
    common_sampler_accept(g_sampler, token, true);
    if (llama_vocab_is_eog(g_vocab, token)) {
        finish_generation();
        return nullptr;
    }

    g_generated_tokens.push_back(token);
    ++g_generated_count;
    const std::string piece = common_token_to_piece(g_context, token);

    common_batch_clear(g_batch);
    common_batch_add(g_batch, token, g_n_past++, {0}, true);
    const int decode_result = llama_decode(g_context, g_batch);
    if (decode_result != 0) {
        set_error("生成 token 时解码失败");
        finish_generation();
        return nullptr;
    }

    g_utf8_pending += piece;
    const int state = utf8_state(g_utf8_pending);
    if (state == 0) {
        return success(env);
    }
    if (state < 0) {
        g_utf8_pending.clear();
        return to_jstring(env, "�");
    }
    std::string output = std::move(g_utf8_pending);
    g_utf8_pending.clear();
    return to_jstring(env, output);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rokid_cxrmsamples_minicpm_NativeMiniCpmBridge_nativeIsGenerating(
        JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    return g_generating ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rokid_cxrmsamples_minicpm_NativeMiniCpmBridge_nativeTakeLastError(
        JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    std::string error = std::move(g_last_error);
    g_last_error.clear();
    return to_jstring(env, error);
}

extern "C" JNIEXPORT void JNICALL
Java_com_rokid_cxrmsamples_minicpm_NativeMiniCpmBridge_nativeCancel(
        JNIEnv *, jobject) {
    g_cancel_requested.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_rokid_cxrmsamples_minicpm_NativeMiniCpmBridge_nativeClear(
        JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    clear_conversation_locked();
}

extern "C" JNIEXPORT void JNICALL
Java_com_rokid_cxrmsamples_minicpm_NativeMiniCpmBridge_nativeUnload(
        JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    unload_locked();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rokid_cxrmsamples_minicpm_NativeMiniCpmBridge_nativeLastError(
        JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    return to_jstring(env, g_last_error);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rokid_cxrmsamples_minicpm_NativeMiniCpmBridge_nativeSystemInfo(
        JNIEnv * env, jobject) {
    return to_jstring(env, llama_print_system_info());
}
