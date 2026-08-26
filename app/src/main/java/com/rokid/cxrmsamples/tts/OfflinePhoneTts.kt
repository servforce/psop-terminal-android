package com.rokid.cxrmsamples.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 手机模式专用的离线中文播报。
 *
 * 模型随 APK 放在 assets，首次使用时复制到应用私有目录，再由 Sherpa ONNX 合成 PCM，
 * 通过手机扬声器播放。它不依赖 Android 系统 TextToSpeech，也不会向眼镜端发任何音频。
 */
class OfflinePhoneTts(
    context: Context,
    private val onPlaybackCompleted: () -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "psop-offline-tts").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private var engine: OfflineTts? = null
    private var playbackJob: Job? = null
    private var activeTrack: AudioTrack? = null
    @Volatile private var released = false

    /** 新回复到来时替换上一条尚未播放完的播报。 */
    fun speak(text: String) {
        if (released) return
        val content = text.trim()
        if (content.isEmpty()) return

        playbackJob?.cancel()
        playbackJob = scope.launch {
            stopActiveTrack()
            val tts = getOrCreateEngine() ?: return@launch
            if (!isActive) return@launch

            try {
                val audio = tts.generate(text = content, sid = SPEAKER_ID, speed = SPEECH_SPEED)
                if (!isActive || audio.samples.isEmpty()) return@launch
                play(audio.samples, audio.sampleRate)
                if (isActive) onPlaybackCompleted()
            } catch (error: Exception) {
                Log.e(TAG, "Offline phone TTS generation failed", error)
            }
        }
    }

    fun release() {
        if (released) return
        released = true
        playbackJob?.cancel()
        playbackJob = scope.launch {
            stopActiveTrack()
            engine?.release()
            engine = null
            dispatcher.close()
        }
    }

    private fun getOrCreateEngine(): OfflineTts? {
        engine?.let { return it }
        return try {
            val modelDir = prepareModelFiles()
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    matcha = OfflineTtsMatchaModelConfig(
                        acousticModel = File(modelDir, ACOUSTIC_MODEL_FILE).absolutePath,
                        vocoder = File(modelDir, VOCODER_FILE).absolutePath,
                        lexicon = File(modelDir, LEXICON_FILE).absolutePath,
                        tokens = File(modelDir, TOKENS_FILE).absolutePath,
                        noiseScale = 0.667f,
                        lengthScale = 1.0f,
                    ),
                    numThreads = 2,
                    debug = true,
                    provider = "cpu",
                ),
                ruleFsts = RULE_FST_FILES.joinToString(",") { file ->
                    File(modelDir, file).absolutePath
                },
            )
            OfflineTts(config = config).also {
                engine = it
                Log.i(TAG, "Offline phone TTS initialized: sampleRate=${it.sampleRate()}")
            }
        } catch (error: Exception) {
            Log.e(TAG, "Offline phone TTS initialization failed", error)
            null
        }
    }

    private fun prepareModelFiles(): File {
        val targetDir = File(appContext.filesDir, MODEL_DIR)
        val acousticModelFile = File(targetDir, ACOUSTIC_MODEL_FILE)
        val requiredFiles = listOf(ACOUSTIC_MODEL_FILE, VOCODER_FILE, LEXICON_FILE, TOKENS_FILE) + RULE_FST_FILES
        val requiresExtraction = !acousticModelFile.exists() ||
            acousticModelFile.length() < MIN_ACOUSTIC_MODEL_BYTES ||
            requiredFiles.any { file -> !File(targetDir, file).isFile }
        if (requiresExtraction) {
            Log.i(TAG, "Extracting offline phone TTS model from assets")
            targetDir.deleteRecursively()
            copyAssetTree(ASSET_MODEL_DIR, targetDir)
        } else {
            // 词典会随版本迭代补充 PSOP 专用词与多音字，已安装用户无需重解压整套模型。
            copyAssetTree("$ASSET_MODEL_DIR/$LEXICON_FILE", File(targetDir, LEXICON_FILE))
        }
        check(requiredFiles.all { file -> File(targetDir, file).isFile }) {
            "Offline phone TTS files are incomplete: ${targetDir.absolutePath}"
        }
        return targetDir
    }

    private fun copyAssetTree(assetPath: String, target: File) {
        val children = appContext.assets.list(assetPath)
            ?: throw IOException("Unable to list TTS asset: $assetPath")
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            appContext.assets.open(assetPath).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output, COPY_BUFFER_SIZE) }
            }
            return
        }

        target.mkdirs()
        children.forEach { child ->
            copyAssetTree("$assetPath/$child", File(target, child))
        }
    }

    private suspend fun play(samples: FloatArray, sampleRate: Int) {
        val pcm = ShortArray(samples.size) { index ->
            (samples[index].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(minBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        activeTrack = track

        try {
            val startedAt = SystemClock.elapsedRealtime()
            track.play()
            track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            val audioDurationMs = pcm.size * 1000L / sampleRate
            val remainingMs = (audioDurationMs - (SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0)
            if (currentCoroutineContext().isActive && remainingMs > 0) delay(remainingMs)
        } finally {
            if (activeTrack === track) activeTrack = null
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun stopActiveTrack() {
        activeTrack?.let { track ->
            activeTrack = null
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            track.release()
        }
    }

    private companion object {
        const val TAG = "OfflinePhoneTts"
        const val ASSET_MODEL_DIR = "sherpa-tts-models"
        const val MODEL_DIR = "sherpa-matcha-zh-baker-v1"
        const val ACOUSTIC_MODEL_FILE = "model-steps-3.onnx"
        const val VOCODER_FILE = "vocos-22khz-univ.onnx"
        const val LEXICON_FILE = "lexicon.txt"
        const val TOKENS_FILE = "tokens.txt"
        val RULE_FST_FILES = listOf("phone.fst", "date.fst", "number.fst")
        const val MIN_ACOUSTIC_MODEL_BYTES = 50L * 1024L * 1024L
        const val COPY_BUFFER_SIZE = 32 * 1024
        const val SPEAKER_ID = 0
        const val SPEECH_SPEED = 1.0f
    }
}
