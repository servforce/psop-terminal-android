package com.rokid.cxrmsamples.activities.liveVideo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

// 文档参考：09实时取流（帧缓冲与插帧，可选）

/**
 * Holds a single frame and its timestamp (ms).
 */
data class FrameEntry(val data: ByteArray, val timestampMs: Long) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FrameEntry
        if (!data.contentEquals(other.data)) return false
        if (timestampMs != other.timestampMs) return false
        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + timestampMs.hashCode()
        return result
    }
}

/**
 * Receives camera frames with (possibly uneven) timestamps and can output at a target FPS
 * by choosing the nearest frame or repeating the previous frame (simple interpolation).
 * Enqueue from SDK callback thread; output runs on a coroutine.
 */
class LiveVideoFrameBuffer(
    private val targetFps: Int = 30,
    private val maxQueueSize: Int = 120
) {
    private val queue = ConcurrentLinkedQueue<FrameEntry>()
    private val queueSize = AtomicLong(0)
    private var lastOutputFrame: FrameEntry? = null
    private var outputJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    /**
     * Call from MediaStreamListener.onCameraFrame (may be SDK thread).
     * Drops oldest if over maxQueueSize.
     */
    fun enqueueFrame(data: ByteArray, timestampMs: Long) {
        val copy = data.copyOf()
        val entry = FrameEntry(copy, timestampMs)
        queue.offer(entry)
        val size = queueSize.incrementAndGet()
        if (size > maxQueueSize) {
            queue.poll()?.let { queueSize.decrementAndGet() }
        }
    }

    /**
     * Start the output loop that emits at target FPS by picking nearest or last frame.
     * Optional: pass a consumer for interpolated output (e.g. write to file or display).
     */
    fun startOutput(consumer: ((FrameEntry) -> Unit)? = null) {
        if (outputJob?.isActive == true) return
        val intervalMs = 1000L / targetFps.coerceIn(1, 60)
        outputJob = scope.launch {
            while (isActive) {
                val targetTs = System.currentTimeMillis()
                val frame = takeNearestOrLast(targetTs)
                frame?.let { consumer?.invoke(it) }
                delay(intervalMs)
            }
        }
    }

    fun stopOutput() {
        outputJob?.cancel()
        outputJob = null
    }

    /**
     * Get the frame with timestamp closest to targetTs, or last known frame if queue empty.
     */
    private fun takeNearestOrLast(targetTs: Long): FrameEntry? {
        var best: FrameEntry? = null
        var bestDiff = Long.MAX_VALUE
        val toReinsert = mutableListOf<FrameEntry>()
        while (queue.isNotEmpty()) {
            val e = queue.poll() ?: break
            queueSize.decrementAndGet()
            val diff = kotlin.math.abs(e.timestampMs - targetTs)
            if (diff < bestDiff) {
                best?.let { toReinsert.add(it) }
                best = e
                bestDiff = diff
            } else {
                toReinsert.add(e)
            }
        }
        toReinsert.forEach { queue.offer(it); queueSize.incrementAndGet() }
        val result = best ?: lastOutputFrame
        if (result != null) lastOutputFrame = result
        return result
    }

    fun clear() {
        queue.clear()
        queueSize.set(0)
        lastOutputFrame = null
    }
}
