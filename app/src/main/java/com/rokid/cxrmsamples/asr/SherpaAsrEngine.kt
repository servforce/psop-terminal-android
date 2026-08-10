package com.rokid.cxrmsamples.asr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sherpa-ONNX 离线 ASR 引擎封装
 *
 * 使用 Paraformer-large 模型（中英双语，支持普通话及多种方言）
 * 模型文件打包在 assets/sherpa-models/ 中，首次启动时自动解压到内部存储。
 * assets 目录结构：
 *   assets/sherpa-models/
 *     ├── model.onnx        (217MB, Paraformer-large int8)
 *     └── tokens.txt
 */
object SherpaAsrEngine {
    private const val TAG = "SherpaAsrEngine"
    private const val MODEL_DIR = "sherpa-models"
    private const val ASSETS_MODEL_DIR = "sherpa-models"

    private var recognizer: OfflineRecognizer? = null
    private var isInitialized = false

    /**
     * 初始化 ASR 引擎（含首次启动时从 assets 解压模型）
     * @return true 初始化成功，false 模型文件不存在或初始化失败
     */
    fun initialize(context: Context): Boolean {
        if (isInitialized) return true

        val modelDir = File(context.filesDir, MODEL_DIR)
        val modelFile = File(modelDir, "model.onnx")
        val tokensFile = File(modelDir, "tokens.txt")

        // 首次启动时自动从 assets 解压模型到内部存储
        // 同时校验文件大小，若 assets 中模型更新则强制重新解压
        if (!modelFile.exists() || !tokensFile.exists() || isModelOutdated(context, modelDir)) {
            Log.i(TAG, "Model not found or outdated, extracting from assets...")
            val extracted = extractModelsFromAssets(context, modelDir)
            if (!extracted) {
                Log.e(TAG, "Failed to extract model files from assets")
                return false
            }
        }
        
        // === 模型版本信息日志 ===
        logModelVersion(modelFile)
        logModelVersion(tokensFile)

        return try {
            val paraformerConfig = OfflineParaformerModelConfig(
                        model = modelFile.absolutePath,
                    )
            Log.d(TAG, "Paraformer config: model=${paraformerConfig.model}")
            val config = OfflineRecognizerConfig(
                featConfig = getFeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    paraformer = paraformerConfig,
                    tokens = tokensFile.absolutePath,
                    numThreads = 2,
                    debug = true,  // 开启 sherpa-onnx 内部调试日志
                    modelType = "paraformer",
                )
            )
            
            recognizer = OfflineRecognizer(null, config)
            isInitialized = true
            Log.i(TAG, "Sherpa-ONNX ASR engine initialized, model size=${modelFile.length()} bytes")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Sherpa-ONNX ASR engine", e)
            false
        }
    }
    
    /**
     * 识别 PCM 音频数据
     * @param pcmData 16bit 单声道 16kHz PCM 数据
     * @return 识别结果文本，失败返回空字符串
     */
    fun recognize(pcmData: ByteArray): String {
        val rec = recognizer ?: run {
            Log.e(TAG, "Recognizer not initialized")
            return ""
        }
        
        if (pcmData.isEmpty()) {
            Log.w(TAG, "Empty PCM data")
            return ""
        }
        
        return try {
            // 将 PCM int16 数据转换为 float 数组（sherpa-onnx 需要 -1.0 ~ 1.0 范围的浮点数）
            val samples = pcmToFloat(pcmData)
            
            val stream = rec.createStream()
            stream.acceptWaveform(samples, sampleRate = 16000)
            rec.decode(stream)
            
            val result = rec.getResult(stream)
            stream.release()
            
            // === ITN 调试日志 ===
            val rawText = result.text
            val tokens = result.tokens
            val hasItnToken = tokens.any { it.contains("itn", ignoreCase = true) }
            Log.d(TAG, "=== ASR DEBUG ===")
            Log.d(TAG, "Raw text: |$rawText|")
            Log.d(TAG, "Token count: ${tokens.size}")
            if (tokens.isNotEmpty()) {
                // 打印前 20 个 token 用于分析
                Log.d(TAG, "First tokens: ${tokens.take(20).joinToString(" | ")}")
            }
            Log.d(TAG, "Has ITN token: $hasItnToken")
            Log.d(TAG, "Contains Chinese digits: ${rawText.matches(Regex(".*[零一二三四五六七八九十百千万两]+.*"))}")
            Log.d(TAG, "=================")
            rawText
        } catch (e: Exception) {
            Log.e(TAG, "Recognition failed", e)
            ""
        }
    }
    
    /**
     * 释放引擎资源
     */
    fun release() {
        recognizer?.release()
        recognizer = null
        isInitialized = false
        Log.i(TAG, "Sherpa-ONNX ASR engine released")
    }
    
    /**
     * 检查模型文件是否已就绪（已解压到内部存储）
     */
    fun isModelReady(context: Context): Boolean {
        val modelDir = File(context.filesDir, MODEL_DIR)
        return File(modelDir, "model.onnx").exists() &&
               File(modelDir, "tokens.txt").exists()
    }

    /**
     * 获取模型目录路径
     */
    fun getModelPath(context: Context): String {
        return File(context.filesDir, MODEL_DIR).absolutePath
    }

    /**
     * 检查 assets 中的模型文件是否与 filesDir 中的版本一致（通过文件大小比较）
     * 若不一致说明模型已更新，需要重新解压
     */
    private fun isModelOutdated(context: Context, targetDir: File): Boolean {
        val filesToCheck = listOf("model.onnx", "tokens.txt")
        for (fileName in filesToCheck) {
            val targetFile = File(targetDir, fileName)
            if (!targetFile.exists()) return true
            try {
                val assetsSize = context.assets.openFd("$ASSETS_MODEL_DIR/$fileName").length
                if (targetFile.length() != assetsSize) {
                    Log.i(TAG, "Model file '$fileName' size mismatch: assets=$assetsSize, local=${targetFile.length()}")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot check assets size for $fileName, assuming up-to-date")
            }
        }
        return false
    }

    /**
     * 从 assets 解压模型文件到内部存储
     * 首次安装/升级时执行，后续启动直接读取
     */
    private fun extractModelsFromAssets(context: Context, targetDir: File): Boolean {
        return try {
            if (!targetDir.exists()) targetDir.mkdirs()

            val filesToExtract = listOf("model.onnx", "tokens.txt")
            for (fileName in filesToExtract) {
                val targetFile = File(targetDir, fileName)
                // 删除旧文件（如有），确保使用最新版本
                if (targetFile.exists()) targetFile.delete()

                Log.i(TAG, "Extracting $fileName from assets...")
                context.assets.open("$ASSETS_MODEL_DIR/$fileName").use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                    }
                }
                Log.i(TAG, "Extracted $fileName (${targetFile.length()} bytes)")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract models from assets", e)
            false
        }
    }
    
    /**
     * PCM int16 字节数组 → float 数组（归一化到 -1.0 ~ 1.0）
     */
    private fun pcmToFloat(pcmData: ByteArray): FloatArray {
        val numSamples = pcmData.size / 2
        val samples = FloatArray(numSamples)
        for (i in 0 until numSamples) {
            val low = pcmData[i * 2].toInt() and 0xFF
            val high = pcmData[i * 2 + 1].toInt()
            val sample = (high shl 8) or low  // Little-endian int16
            samples[i] = sample / 32768.0f
        }
        return samples
    }

    /**
     * 打印模型文件版本信息：大小、修改时间、前 1KB 的 MD5 哈希值
     * 用于确认设备上加载的是哪个版本的模型文件
     */
    private fun logModelVersion(file: File) {
        if (!file.exists()) {
            Log.w(TAG, "Model file not found: ${file.name}")
            return
        }
        val sizeMb = String.format(Locale.US, "%.1f", file.length() / 1024.0 / 1024.0)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val lastModified = dateFormat.format(Date(file.lastModified()))
        val md5 = try {
            val digest = MessageDigest.getInstance("MD5")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(1024)
                val read = fis.read(buffer)
                if (read > 0) digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "error: ${e.message}"
        }
        Log.i(TAG, "Model file: ${file.name} | size=${sizeMb}MB | modified=$lastModified | md5_1kb=$md5")
    }
}
