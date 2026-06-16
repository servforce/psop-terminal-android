package com.rokid.cxrmsamples.asr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import java.io.File
import java.io.FileOutputStream

/**
 * Sherpa-ONNX 离线 ASR 引擎封装
 *
 * 使用 Paraformer Small 中文模型（支持普通话+多种方言）
 * 模型文件打包在 assets/sherpa-models/ 中，首次启动时自动解压到内部存储。
 * assets 目录结构：
 *   assets/sherpa-models/
 *     ├── model.int8.onnx   (79MB, Paraformer Small)
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
        val modelFile = File(modelDir, "model.int8.onnx")
        val tokensFile = File(modelDir, "tokens.txt")

        // 首次启动时自动从 assets 解压模型到内部存储
        if (!modelFile.exists() || !tokensFile.exists()) {
            Log.i(TAG, "Model not found in filesDir, extracting from assets...")
            val extracted = extractModelsFromAssets(context, modelDir)
            if (!extracted) {
                Log.e(TAG, "Failed to extract model files from assets")
                return false
            }
        }
        
        return try {
            val config = OfflineRecognizerConfig(
                featConfig = getFeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    paraformer = OfflineParaformerModelConfig(
                        model = modelFile.absolutePath
                    ),
                    tokens = tokensFile.absolutePath,
                    numThreads = 2,
                    debug = false
                )
            )
            
            recognizer = OfflineRecognizer(null, config)
            isInitialized = true
            Log.i(TAG, "Sherpa-ONNX ASR engine initialized successfully")
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
            
            Log.d(TAG, "Recognition result: ${result.text}")
            result.text
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
        return File(modelDir, "model.int8.onnx").exists() &&
               File(modelDir, "tokens.txt").exists()
    }

    /**
     * 获取模型目录路径
     */
    fun getModelPath(context: Context): String {
        return File(context.filesDir, MODEL_DIR).absolutePath
    }

    /**
     * 从 assets 解压模型文件到内部存储
     * 首次安装/升级时执行一次，后续启动直接读取
     */
    private fun extractModelsFromAssets(context: Context, targetDir: File): Boolean {
        return try {
            if (!targetDir.exists()) targetDir.mkdirs()

            val filesToExtract = listOf("model.int8.onnx", "tokens.txt")
            for (fileName in filesToExtract) {
                val targetFile = File(targetDir, fileName)
                if (targetFile.exists()) continue  // 已存在则跳过

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
}
