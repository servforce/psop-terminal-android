package com.rokid.cxrmsamples.activities.minicpm

import android.app.ActivityManager
import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.cxrmsamples.minicpm.BundledMiniCpmModelInstaller
import com.rokid.cxrmsamples.minicpm.MiniCpmEngine
import com.rokid.cxrmsamples.minicpm.NativeMiniCpmBridge
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MiniCpmPhase {
    CHECKING,
    COPYING,
    LOADING,
    READY,
    GENERATING,
    ERROR,
}

enum class MiniCpmRole { USER, ASSISTANT }

data class MiniCpmMessage(
    val id: Long,
    val role: MiniCpmRole,
    val text: String,
    val imageUri: String? = null,
    val stopped: Boolean = false,
)

data class MiniCpmDemoUiState(
    val phase: MiniCpmPhase = MiniCpmPhase.CHECKING,
    val statusText: String = "正在检查设备…",
    val progress: Float? = null,
    val inputText: String = "",
    val selectedImageUri: String? = null,
    val messages: List<MiniCpmMessage> = emptyList(),
    val imagePreparing: Boolean = false,
    val deviceSummary: String = "",
) {
    val ready: Boolean get() = phase == MiniCpmPhase.READY
    val generating: Boolean get() = phase == MiniCpmPhase.GENERATING
}

class MiniCpmDemoViewModel(application: Application) : AndroidViewModel(application) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "minicpm-v46-worker")
    }
    private val worker = executor.asCoroutineDispatcher()
    private val installer = BundledMiniCpmModelInstaller(application)
    private var engine: MiniCpmEngine? = null
    private var selectedImageBytes: ByteArray? = null
    private var nextMessageId = 1L

    private val _uiState = MutableStateFlow(MiniCpmDemoUiState())
    val uiState = _uiState.asStateFlow()

    init {
        initialize()
    }

    fun initialize() {
        if (_uiState.value.phase != MiniCpmPhase.ERROR &&
            _uiState.value.phase != MiniCpmPhase.CHECKING
        ) return

        _uiState.update {
            it.copy(phase = MiniCpmPhase.CHECKING, statusText = "正在检查设备…", progress = null)
        }
        viewModelScope.launch(worker) {
            runCatching {
                checkDevice()
                val models = installer.install { progress ->
                    _uiState.update {
                        it.copy(
                            phase = MiniCpmPhase.COPYING,
                            statusText = "正在释放 ${progress.fileName}…",
                            progress = progress.fraction,
                        )
                    }
                }
                _uiState.update {
                    it.copy(phase = MiniCpmPhase.LOADING, statusText = "正在加载模型…", progress = null)
                }
                NativeMiniCpmBridge(getApplication()).also {
                    it.load(models)
                    engine = it
                }
            }.onSuccess {
                _uiState.update {
                    it.copy(phase = MiniCpmPhase.READY, statusText = "模型已就绪", progress = null)
                }
            }.onFailure { error ->
                engine?.close()
                engine = null
                _uiState.update {
                    it.copy(
                        phase = MiniCpmPhase.ERROR,
                        statusText = error.message ?: "模型初始化失败",
                        progress = null,
                    )
                }
            }
        }
    }

    fun updateInput(value: String) {
        _uiState.update { it.copy(inputText = value) }
    }

    fun selectImage(uri: Uri?) {
        if (uri == null || !_uiState.value.ready) return
        _uiState.update { it.copy(imagePreparing = true, statusText = "正在处理图片…") }
        viewModelScope.launch(worker) {
            runCatching { decodeForInference(uri) }
                .onSuccess { bytes ->
                    selectedImageBytes = bytes
                    _uiState.update {
                        it.copy(
                            selectedImageUri = uri.toString(),
                            imagePreparing = false,
                            statusText = "图片已准备",
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            imagePreparing = false,
                            statusText = "图片处理失败：${error.message ?: "未知错误"}",
                        )
                    }
                }
        }
    }

    fun removeImage() {
        if (_uiState.value.generating) return
        selectedImageBytes = null
        _uiState.update { it.copy(selectedImageUri = null, statusText = "模型已就绪") }
    }

    fun send() {
        val snapshot = _uiState.value
        if (!snapshot.ready || snapshot.imagePreparing) return
        val prompt = snapshot.inputText.trim()
        val imageBytes = selectedImageBytes
        if (prompt.isEmpty() && imageBytes == null) return

        val actualPrompt = prompt.ifEmpty { "请描述这张图片。" }
        val userMessage = MiniCpmMessage(
            id = nextMessageId++,
            role = MiniCpmRole.USER,
            text = actualPrompt,
            imageUri = snapshot.selectedImageUri,
        )
        val assistantId = nextMessageId++
        val assistantMessage = MiniCpmMessage(
            id = assistantId,
            role = MiniCpmRole.ASSISTANT,
            text = "",
        )
        _uiState.update {
            it.copy(
                phase = MiniCpmPhase.GENERATING,
                statusText = "正在生成…",
                inputText = "",
                messages = it.messages + userMessage + assistantMessage,
            )
        }

        viewModelScope.launch(worker) {
            runCatching {
                checkNotNull(engine) { "模型尚未加载" }.generate(
                    prompt = actualPrompt,
                    encodedImage = imageBytes,
                ) { token ->
                    _uiState.update { state ->
                        state.copy(messages = state.messages.map { message ->
                            if (message.id == assistantId) {
                                message.copy(text = message.text + token)
                            } else {
                                message
                            }
                        })
                    }
                }
            }.onSuccess { result ->
                _uiState.update { state ->
                    state.copy(
                        phase = MiniCpmPhase.READY,
                        statusText = if (result.stopped) "已停止生成" else "生成完成",
                        messages = state.messages.map { message ->
                            if (message.id == assistantId) {
                                message.copy(
                                    text = message.text.ifEmpty { if (result.stopped) "（已停止）" else "（无输出）" },
                                    stopped = result.stopped,
                                )
                            } else {
                                message
                            }
                        },
                    )
                }
            }.onFailure { error ->
                _uiState.update { state ->
                    state.copy(
                        phase = MiniCpmPhase.READY,
                        statusText = "推理失败：${error.message ?: "未知错误"}",
                        messages = state.messages.filterNot {
                            it.id == assistantId && it.text.isEmpty()
                        },
                    )
                }
            }
        }
    }

    fun stopGeneration() {
        if (!_uiState.value.generating) return
        _uiState.update { it.copy(statusText = "正在停止…") }
        engine?.cancelGeneration()
    }

    fun clearConversation() {
        if (_uiState.value.generating) engine?.cancelGeneration()
        viewModelScope.launch(worker) {
            engine?.clearConversation()
            selectedImageBytes = null
            _uiState.update {
                it.copy(
                    phase = MiniCpmPhase.READY,
                    statusText = "会话已清空",
                    inputText = "",
                    selectedImageUri = null,
                    messages = emptyList(),
                )
            }
        }
    }

    private fun checkDevice() {
        check(Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
            "当前设备不是 arm64-v8a，无法运行本地模型。"
        }
        val activityManager = getApplication<Application>()
            .getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        _uiState.update {
            it.copy(
                deviceSummary = "arm64-v8a · RAM ${formatGb(memory.totalMem)} GB · 当前可用 ${formatGb(memory.availMem)} GB",
            )
        }
    }

    private fun decodeForInference(uri: Uri): ByteArray {
        val source = ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val width = info.size.width
            val height = info.size.height
            val longest = maxOf(width, height)
            if (longest > MAX_IMAGE_EDGE) {
                val scale = MAX_IMAGE_EDGE.toFloat() / longest
                decoder.setTargetSize(
                    maxOf(1, (width * scale).toInt()),
                    maxOf(1, (height * scale).toInt()),
                )
            }
        }
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    "无法编码图片"
                }
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun formatGb(bytes: Long): String = "%.1f".format(bytes / 1_073_741_824.0)

    override fun onCleared() {
        engine?.cancelGeneration()
        executor.execute {
            engine?.close()
            engine = null
        }
        executor.shutdown()
        super.onCleared()
    }

    private companion object {
        const val MAX_IMAGE_EDGE = 1024
        const val JPEG_QUALITY = 90
    }
}
