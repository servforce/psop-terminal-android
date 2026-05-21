package com.rokid.cxrmsamples.activities.liveVideo

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 文档参考：09实时取流（本地 Surface 预览）。Decodes H264/H265 and renders to [Surface].
 */
class LiveVideoPreviewRenderer(
    private val width: Int,
    private val height: Int,
    private val isH265: Boolean
) {
    private val mimeType = if (isH265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
    private var decoder: MediaCodec? = null
    private var outputThread: Thread? = null
    private val running = AtomicBoolean(false)

    @Volatile
    private var currentSurface: Surface? = null

    /**
     * Set the Surface to render decoded frames to. Call with null to stop rendering.
     * Decoder is created when surface is non-null and released when set to null.
     */
    fun setSurface(surface: Surface?) {
        if (currentSurface == surface) return
        stopDecoder()
        currentSurface = surface
        if (surface != null) {
            startDecoder(surface)
        }
    }

    /**
     * Feed one encoded frame (NAL unit or access unit) to the decoder.
     * Safe to call from any thread.
     */
    fun feedFrame(data: ByteArray, timestampMs: Long) {
        val codec = decoder ?: return
        if (!running.get()) return
        try {
            val inputBufferIndex = codec.dequeueInputBuffer(10_000)
            if (inputBufferIndex >= 0) {
                val inputBuffer: ByteBuffer? = codec.getInputBuffer(inputBufferIndex)
                inputBuffer?.clear()
                inputBuffer?.put(data)
                codec.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    data.size,
                    timestampMs * 1000L,
                    0
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "feedFrame error", e)
        }
    }

    private fun startDecoder(surface: Surface) {
        try {
            val format = MediaFormat.createVideoFormat(mimeType, width, height)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height)
            val codec = MediaCodec.createDecoderByType(mimeType)
            codec.configure(format, surface, null, 0)
            codec.start()
            decoder = codec
            running.set(true)
            outputThread = thread(name = "LiveVideoDecoder-Output") {
                runOutputLoop(codec)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startDecoder failed", e)
        }
    }

    private fun runOutputLoop(codec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (running.get()) {
            try {
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> { }
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { }
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> { }
                    outputBufferIndex >= 0 -> {
                        codec.releaseOutputBuffer(outputBufferIndex, true)
                    }
                }
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "output loop error", e)
                break
            }
        }
    }

    private fun stopDecoder() {
        running.set(false)
        outputThread?.join(500)
        outputThread = null
        try {
            decoder?.stop()
            decoder?.release()
        } catch (_: Exception) { }
        decoder = null
    }

    fun release() {
        setSurface(null)
    }

    companion object {
        private const val TAG = "LiveVideoPreviewRenderer"
    }
}
