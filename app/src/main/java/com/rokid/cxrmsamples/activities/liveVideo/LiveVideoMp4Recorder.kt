package com.rokid.cxrmsamples.activities.liveVideo

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaCodec.BufferInfo
import android.util.Log
import android.os.Environment
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 文档参考：09实时取流（可选录制成 MP4）。
 * Records H264/H265 encoded frames from the live camera stream into an MP4 file.
 */
class LiveVideoMp4Recorder {

    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var outputPath: String? = null
    private val isRecording = AtomicBoolean(false)
    private val trackAdded = AtomicBoolean(false)
    private var isH265 = false
    private var width = 0
    private var height = 0

    /** Max frames to wait for CSD (SPS/PPS) before giving up. */
    private var csdWaitCount = 0
    private val maxCsdWaitFrames = 120

    /** Accumulate SPS/PPS across frames (device may send one NAL per frame). */
    private var pendingSps: ByteArray? = null
    private var pendingPps: ByteArray? = null
    private var pendingVps: ByteArray? = null
    private var pendingHevcSps: ByteArray? = null
    private var pendingHevcPps: ByteArray? = null

    /** True if stream is AVCC (4-byte length prefix) rather than Annex B. */
    private var inputIsAvcc: Boolean? = null

    /** MPEG4Writer often requires codec config in the first keyframe (inband). */
    private var firstKeyFrameWritten = false

    /**
     * Start recording. Creates a new MP4 file under app-specific Movies directory.
     * Must not be called when already recording.
     */
    @Synchronized
    fun startRecording(context: Context, width: Int, height: Int, isH265: Boolean): String? {
        if (isRecording.get()) return outputPath
        this.width = width
        this.height = height
        this.isH265 = isH265
        trackAdded.set(false)
        csdWaitCount = 0
        pendingSps = null
        pendingPps = null
        pendingVps = null
        pendingHevcSps = null
        pendingHevcPps = null
        inputIsAvcc = null
        firstKeyFrameWritten = false

        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: context.filesDir
        val subDir = File(dir, "LiveVideo").apply { mkdirs() }
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val name = "live_${dateFormat.format(Date())}.mp4"
        val file = File(subDir, name)
        outputPath = file.absolutePath

        return try {
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            isRecording.set(true)
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            outputPath = null
            null
        }
    }

    /**
     * Feed one encoded frame. Call only when recording. If track is not yet added,
     * tries to extract CSD from this frame (SPS/PPS for H264, VPS/SPS/PPS for HEVC).
     */
    fun writeFrame(data: ByteArray, timestampMs: Long) {
        if (!isRecording.get() || muxer == null) return
        if (!trackAdded.get()) {
            collectCsdFromFrame(data)
            val ready = if (isH265) {
                pendingVps != null && pendingHevcSps != null && pendingHevcPps != null
            } else {
                pendingSps != null && pendingPps != null
            }
            if (ready) {
                Log.d(TAG, "addTrack: SPS=${pendingSps?.size}, PPS=${pendingPps?.size}, isH265=$isH265, inputIsAvcc=$inputIsAvcc")
                addTrackFromPending()
                // Write this frame if it contains video data (slice), not just SPS/PPS
                if (frameContainsVideoData(data)) {
                    writeSample(data, timestampMs)
                }
            } else {
                csdWaitCount++
                if (csdWaitCount >= maxCsdWaitFrames) {
                    Log.w(TAG, "No CSD after $maxCsdWaitFrames frames, stopping recording")
                    stopRecording()
                }
                return
            }
        } else {
            writeSample(data, timestampMs)
        }
    }

    @Synchronized
    fun stopRecording() {
        if (!isRecording.get()) return
        val m = muxer
        muxer = null
        videoTrackIndex = -1
        trackAdded.set(false)
        isRecording.set(false)
        if (m != null) {
            try {
                m.stop()
            } catch (e: Exception) {
                Log.w(TAG, "muxer.stop() failed (file may be incomplete): ${e.message}")
            }
            try {
                m.release()
            } catch (e: Exception) {
                Log.w(TAG, "muxer.release() failed: ${e.message}")
            }
        }
    }

    fun isRecording(): Boolean = isRecording.get()
    fun getOutputPath(): String? = outputPath

    private fun addTrackFromPending() {
        val m = muxer ?: return
        val mime = if (isH265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val format = MediaFormat.createVideoFormat(mime, width, height)
        if (isH265) {
            // Keep existing (simplified) hvcC builder.
            val vps = pendingVps
            val sps = pendingHevcSps
            val pps = pendingHevcPps
            if (vps == null || sps == null || pps == null) {
                Log.w(TAG, "addTrack failed: VPS/SPS/PPS missing for HEVC")
                return
            }
            val hvcc = buildHevcCsd(vps, sps, pps)
            format.setByteBuffer("csd-0", hvcc)
        } else {
            // AVC on Android muxer is more compatible with csd-0(SPS) + csd-1(PPS).
            val sps = pendingSps
            val pps = pendingPps
            if (sps == null || pps == null) {
                Log.w(TAG, "addTrack failed: SPS/PPS missing")
                return
            }
            val spsWithSc = withStartCode(sps)
            val ppsWithSc = withStartCode(pps)
            Log.d(TAG, "addTrack: SPS(raw)=${sps.size}B, SPS(+sc)=${spsWithSc.size}B [${spsWithSc.take(8).joinToString(" ") { "%02X".format(it) }}...]")
            Log.d(TAG, "addTrack: PPS(raw)=${pps.size}B, PPS(+sc)=${ppsWithSc.size}B [${ppsWithSc.take(8).joinToString(" ") { "%02X".format(it) }}...]")
            format.setByteBuffer("csd-0", ByteBuffer.wrap(spsWithSc))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(ppsWithSc))
        }
        videoTrackIndex = m.addTrack(format)
        m.start()
        trackAdded.set(true)
    }

    /** True if data starts with Annex B start code. */
    private fun isAnnexB(data: ByteArray): Boolean {
        if (data.size < 3) return false
        if (data[0] != 0.toByte() || data[1] != 0.toByte()) return false
        return data[2] == 1.toByte() || (data.size >= 4 && data[2] == 0.toByte() && data[3] == 1.toByte())
    }

    /** MediaMuxer with AVC is most compatible with Annex B samples. */
    private fun toAnnexBForMuxer(data: ByteArray): ByteArray {
        if (inputIsAvcc != true) return data
        val out = java.io.ByteArrayOutputStream(data.size + 16)
        var pos = 0
        while (pos + 4 <= data.size) {
            val len = ((data[pos].toInt() and 0xFF) shl 24) or
                ((data[pos + 1].toInt() and 0xFF) shl 16) or
                ((data[pos + 2].toInt() and 0xFF) shl 8) or
                (data[pos + 3].toInt() and 0xFF)
            if (len <= 0 || pos + 4 + len > data.size) break
            out.write(0)
            out.write(0)
            out.write(0)
            out.write(1)
            out.write(data, pos + 4, len)
            pos += 4 + len
        }
        return out.toByteArray()
    }

    private fun writeSample(data: ByteArray, timestampMs: Long) {
        val m = muxer ?: return
        if (videoTrackIndex < 0) return
        val isKeyFrame = isKeyFrame(data)
        // Skip non-keyframe until first keyframe.
        if (!firstKeyFrameWritten) {
            if (!isKeyFrame) return
            val sampleData = toAnnexBForMuxer(data)
            if (sampleData.isEmpty()) return
            firstKeyFrameWritten = true
            Log.d(TAG, "first keyframe written, sampleSize=${sampleData.size}")
            val bufferInfo = BufferInfo().apply {
                set(0, sampleData.size, timestampMs * 1000L, MediaCodec.BUFFER_FLAG_KEY_FRAME)
            }
            try {
                val buffer = ByteBuffer.wrap(sampleData)
                m.writeSampleData(videoTrackIndex, buffer, bufferInfo)
            } catch (e: Exception) {
                Log.e(TAG, "writeSampleData error (first keyframe)", e)
                stopRecording()
            }
            return
        }
        val sampleData = toAnnexBForMuxer(data)
        val bufferInfo = BufferInfo().apply {
            set(0, sampleData.size, timestampMs * 1000L, if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        }
        try {
            val buffer = ByteBuffer.wrap(sampleData)
            m.writeSampleData(videoTrackIndex, buffer, bufferInfo)
        } catch (e: Exception) {
            Log.e(TAG, "writeSampleData error", e)
            stopRecording()
        }
    }

    private fun withStartCode(nal: ByteArray): ByteArray {
        val out = ByteArray(4 + nal.size)
        out[0] = 0
        out[1] = 0
        out[2] = 0
        out[3] = 1
        System.arraycopy(nal, 0, out, 4, nal.size)
        return out
    }

    private fun toDirectByteBuffer(bytes: ByteArray): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(bytes.size)
        buf.put(bytes)
        buf.flip()
        return buf
    }

    /** NAL start codes: 0x00 0x00 0x01 or 0x00 0x00 0x00 0x01 */
    private fun findNextStartCode(data: ByteArray, start: Int): Int {
        var i = start
        while (i < data.size - 2) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) return i
                if (i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) return i
            }
            i++
        }
        return -1
    }

    private fun startCodeLength(data: ByteArray, offset: Int): Int {
        if (offset + 3 <= data.size &&
            data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() && data[offset + 2] == 0.toByte() && data[offset + 3] == 1.toByte())
            return 4
        if (offset + 2 <= data.size &&
            data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() && data[offset + 2] == 1.toByte())
            return 3
        return 0
    }

    /** H264 NAL type: (first byte after start code) & 0x1F. 7=SPS, 8=PPS, 5=IDR. */
    private fun h264NalType(data: ByteArray, nalStart: Int): Int {
        val scLen = startCodeLength(data, nalStart)
        if (nalStart + scLen >= data.size) return -1
        return (data[nalStart + scLen].toInt() and 0x1F)
    }

    /** HEVC NAL type: (first 2 bytes) (data[offset] >> 1) & 0x3F. 32=VPS, 33=SPS, 34=PPS, 19/20=IDR. */
    private fun hevcNalType(data: ByteArray, nalStart: Int): Int {
        val scLen = startCodeLength(data, nalStart)
        if (nalStart + scLen + 1 >= data.size) return -1
        return (data[nalStart + scLen].toInt() shr 1) and 0x3F
    }

    /** H264 NAL type from raw NAL first byte (no start code). */
    private fun h264NalTypeFromByte(nalFirstByte: Int): Int = nalFirstByte and 0x1F

    /** HEVC NAL type from raw NAL first byte. */
    private fun hevcNalTypeFromByte(nalFirstByte: Int): Int = (nalFirstByte shr 1) and 0x3F

    private fun isKeyFrame(data: ByteArray): Boolean {
        if (data.size < 4) return false
        if (inputIsAvcc == true) {
            var pos = 0
            while (pos + 4 <= data.size) {
                val len = ((data[pos].toInt() and 0xFF) shl 24) or
                    ((data[pos + 1].toInt() and 0xFF) shl 16) or
                    ((data[pos + 2].toInt() and 0xFF) shl 8) or
                    (data[pos + 3].toInt() and 0xFF)
                if (len <= 0 || pos + 4 + len > data.size) break
                val first = data[pos + 4].toInt() and 0xFF
                if (isH265) {
                    if (hevcNalTypeFromByte(first) in listOf(19, 20)) return true
                } else {
                    if (h264NalTypeFromByte(first) == 5) return true
                }
                pos += 4 + len
            }
            return false
        }
        var offset = findNextStartCode(data, 0)
        while (offset >= 0) {
            val scLen = startCodeLength(data, offset)
            val nalStart = offset + scLen
            if (isH265) {
                if (hevcNalType(data, offset) in listOf(19, 20)) return true
            } else {
                if (h264NalType(data, offset) == 5) return true
            }
            offset = findNextStartCode(data, nalStart + 1)
        }
        return false
    }

    /** Collect SPS/PPS (and HEVC VPS/SPS/PPS) from this frame; may span multiple frames. */
    private fun collectCsdFromFrame(data: ByteArray) {
        if (data.size < 4) return
        // Detect input format on first frame: Annex B (start code) vs AVCC (4-byte length)
        if (inputIsAvcc == null) {
            inputIsAvcc = !isAnnexB(data)
            val prefix = data.take(8).joinToString(" ") { "%02X".format(it) }
            Log.d(TAG, "collectCsdFromFrame: inputIsAvcc=$inputIsAvcc, first 8 bytes: $prefix")
        }
        if (inputIsAvcc == true) {
            collectCsdFromAvcc(data)
            return
        }
        var offset = findNextStartCode(data, 0)
        while (offset >= 0) {
            val scLen = startCodeLength(data, offset)
            val nalStart = offset + scLen
            val next = findNextStartCode(data, nalStart + 1)
            val nalEnd = if (next < 0) data.size else next
            if (isH265) {
                when (hevcNalType(data, offset)) {
                    32 -> pendingVps = data.copyOfRange(nalStart, nalEnd)
                    33 -> pendingHevcSps = data.copyOfRange(nalStart, nalEnd)
                    34 -> pendingHevcPps = data.copyOfRange(nalStart, nalEnd)
                }
            } else {
                when (h264NalType(data, offset)) {
                    7 -> {
                        pendingSps = data.copyOfRange(nalStart, nalEnd)
                        Log.d(TAG, "collected SPS (Annex B): size=${pendingSps?.size}")
                    }
                    8 -> {
                        pendingPps = data.copyOfRange(nalStart, nalEnd)
                        Log.d(TAG, "collected PPS (Annex B): size=${pendingPps?.size}")
                    }
                }
            }
            offset = next
        }
    }

    /** Parse AVCC: 4-byte big-endian length + NAL, repeat. Extract SPS/PPS. */
    private fun collectCsdFromAvcc(data: ByteArray) {
        var pos = 0
        while (pos + 4 <= data.size) {
            val len = ((data[pos].toInt() and 0xFF) shl 24) or
                ((data[pos + 1].toInt() and 0xFF) shl 16) or
                ((data[pos + 2].toInt() and 0xFF) shl 8) or
                (data[pos + 3].toInt() and 0xFF)
            if (len <= 0 || pos + 4 + len > data.size) break
            val nalStart = pos + 4
            val nalEnd = pos + 4 + len
            if (isH265) {
                val t = (data[nalStart].toInt() shr 1) and 0x3F
                when (t) {
                    32 -> pendingVps = data.copyOfRange(nalStart, nalEnd)
                    33 -> pendingHevcSps = data.copyOfRange(nalStart, nalEnd)
                    34 -> pendingHevcPps = data.copyOfRange(nalStart, nalEnd)
                }
            } else {
                val t = data[nalStart].toInt() and 0x1F
                when (t) {
                    7 -> {
                        pendingSps = data.copyOfRange(nalStart, nalEnd)
                        Log.d(TAG, "collected SPS (AVCC): size=${pendingSps?.size}")
                    }
                    8 -> {
                        pendingPps = data.copyOfRange(nalStart, nalEnd)
                        Log.d(TAG, "collected PPS (AVCC): size=${pendingPps?.size}")
                    }
                }
            }
            pos = nalEnd
        }
    }

    /** Build csd-0 from accumulated SPS/PPS; null until we have both (and for HEVC all three). */
    private fun buildCsdFromPending(): ByteBuffer? {
        return if (isH265) {
            val vps = pendingVps
            val sps = pendingHevcSps
            val pps = pendingHevcPps
            if (vps != null && sps != null && pps != null) buildHevcCsd(vps, sps, pps) else null
        } else {
            val sps = pendingSps
            val pps = pendingPps
            if (sps != null && pps != null) buildAvcCsd(sps, pps) else null
        }
    }

    /** True if frame contains at least one VCL NAL (slice) to write as sample. */
    private fun frameContainsVideoData(data: ByteArray): Boolean {
        if (data.size < 4) return false
        if (inputIsAvcc == true) {
            var pos = 0
            while (pos + 4 <= data.size) {
                val len = ((data[pos].toInt() and 0xFF) shl 24) or
                    ((data[pos + 1].toInt() and 0xFF) shl 16) or
                    ((data[pos + 2].toInt() and 0xFF) shl 8) or
                    (data[pos + 3].toInt() and 0xFF)
                if (len <= 0 || pos + 4 + len > data.size) break
                val first = data[pos + 4].toInt() and 0xFF
                if (isH265) {
                    val t = hevcNalTypeFromByte(first)
                    if (t == 0 || t == 1 || t == 19 || t == 20) return true
                } else {
                    val t = h264NalTypeFromByte(first)
                    if (t == 1 || t == 5) return true
                }
                pos += 4 + len
            }
            return false
        }
        var offset = findNextStartCode(data, 0)
        while (offset >= 0) {
            val scLen = startCodeLength(data, offset)
            val nalStart = offset + scLen
            if (isH265) {
                val t = hevcNalType(data, offset)
                if (t == 0 || t == 1 || t == 19 || t == 20) return true
            } else {
                val t = h264NalType(data, offset)
                if (t == 1 || t == 5) return true
            }
            offset = findNextStartCode(data, nalStart + 1)
        }
        return false
    }

    private fun buildAvcCsd(sps: ByteArray, pps: ByteArray): ByteBuffer {
        val capacity = 7 + 2 + sps.size + 1 + 2 + pps.size
        val out = ByteBuffer.allocate(capacity)
        out.put(1) // configurationVersion
        out.put(sps[1]) // AVCProfileIndication
        out.put(sps[2]) // profile_compatibility
        out.put(sps[3]) // AVCLevelIndication
        out.put(0xFF.toByte()) // 6 reserved bits (1) + lengthSizeMinusOne=3 (2 bits) per ISO 14496-15
        out.put(1) // numOfSequenceParameterSets = 1
        out.put((sps.size shr 8).toByte())
        out.put((sps.size and 0xFF).toByte())
        out.put(sps)
        out.put(1) // numOfPictureParameterSets
        out.put((pps.size shr 8).toByte())
        out.put((pps.size and 0xFF).toByte())
        out.put(pps)
        out.flip()
        return out
    }

    private fun buildHevcCsd(vps: ByteArray, sps: ByteArray, pps: ByteArray): ByteBuffer {
        // Build hvcC (simplified): configurationVersion=1, general_profile_space/tier/profile/level from SPS,
        // then lengthSizeMinusOne, numOfArrays, then arrays for VPS(32), SPS(33), PPS(34)
        val capacity = 23 + 3 + (2 + vps.size) + (2 + sps.size) + (2 + pps.size)
        val out = ByteBuffer.allocate(capacity)
        out.put(1) // configurationVersion
        out.put(0x01) // general_profile_space, general_tier_flag, general_profile_idc (from SPS would be better)
        out.put(sps[1])
        out.put(sps[2])
        out.put(sps[3])
        out.put(sps[4])
        out.put(sps[5])
        out.put(sps[6]) // general_profile_compatibility_flags + general_constraint_indicator
        out.put(sps[7])
        out.put(sps[8])
        out.put(sps[9])
        out.put(sps[10])
        out.put(sps[11])
        out.put(sps[12])
        out.put(sps[13])
        out.put(sps[14])
        out.put(sps[15])
        out.put(sps[16]) // general_level_idc
        out.put(0xF0.toByte()) // min_spatial_segmentation_idc (12 bits) = 0
        out.put(0)
        out.put(0x03) // parallelismType = 0, chroma_format_idc = 3
        out.put(0x00) // bit_depth_luma_minus8, bit_depth_chroma_minus8
        out.put(0x00.toByte()) // avg_frame_rate (16 bits) = 0
        out.put(0)
        out.put(0x03) // constantFrameRate=0, numTemporalLayers=0, temporalIdNested=0, lengthSizeMinusOne=3
        out.put(3) // numOfArrays: VPS, SPS, PPS
        // Array 1: VPS (type 32)
        out.put(0x20.toByte()) // array_completeness=0, reserved=0, NAL_unit_type=32
        out.put(1) // numNalus
        out.put((vps.size shr 8).toByte())
        out.put((vps.size and 0xFF).toByte())
        out.put(vps)
        // Array 2: SPS (type 33)
        out.put(0x21.toByte()) // NAL_unit_type=33
        out.put(1)
        out.put((sps.size shr 8).toByte())
        out.put((sps.size and 0xFF).toByte())
        out.put(sps)
        // Array 3: PPS (type 34)
        out.put(0x22.toByte()) // NAL_unit_type=34
        out.put(1)
        out.put((pps.size shr 8).toByte())
        out.put((pps.size and 0xFF).toByte())
        out.put(pps)
        out.flip()
        return out
    }

    companion object {
        private const val TAG = "LiveVideoMp4Recorder"
    }
}
