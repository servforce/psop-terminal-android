#pragma once

#include <android/log.h>
#include "ggml.h"

#define MINICPM_LOG_TAG "MiniCPMV46"

#define MC_LOGI(...) __android_log_print(ANDROID_LOG_INFO, MINICPM_LOG_TAG, __VA_ARGS__)
#define MC_LOGW(...) __android_log_print(ANDROID_LOG_WARN, MINICPM_LOG_TAG, __VA_ARGS__)
#define MC_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, MINICPM_LOG_TAG, __VA_ARGS__)

static inline void minicpm_log_callback(
        enum ggml_log_level level,
        const char * text,
        void *) {
    int priority = ANDROID_LOG_DEBUG;
    if (level == GGML_LOG_LEVEL_ERROR) priority = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) priority = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_INFO) priority = ANDROID_LOG_INFO;
    __android_log_write(priority, MINICPM_LOG_TAG, text);
}

