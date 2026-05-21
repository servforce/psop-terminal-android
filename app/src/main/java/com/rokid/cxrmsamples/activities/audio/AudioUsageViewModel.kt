package com.rokid.cxrmsamples.activities.audio

// 文档参考：03语音操作（openAudioRecord 三参、closeAudioRecord、changeAudioSceneId、controlScene AUDIO_RECORD、setSceneStatusUpdateListener）

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import androidx.lifecycle.ViewModel
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.listeners.AudioStreamListener
import com.rokid.cxr.client.extend.listeners.SceneStatusUpdateListener
import com.rokid.cxr.client.utils.ValueUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Enum representing different audio scene IDs.
 * NEAR: Near-field audio pickup
 * FAR: Far-field audio pickup
 * BOTH: Both near and far-field audio pickup
 * @param id The integer ID of the scene
 * @param sceneName The name of the scene
 */
enum class AudioSceneId(val id: Int, val sceneName: String) {
    NEAR(0, "近场"),
    FAR(1, "远场"),
    BOTH(2, "双场")
}

/**
 * Enum representing the possible states of audio playback.
 * PLAYING: Audio is currently playing
 * PAUSED: Audio playback is paused
 * STOPPED: Audio playback is stopped
 */
enum class PlayState {
    PLAYING, PAUSED, STOPPED
}

/**
 * ViewModel class for handling audio recording and playback functionality.
 * Manages the state of audio recording, playback, and related UI elements.
 *
 * This class handles:
 * - Audio recording through CXR API
 * - Audio playback using Android's AudioTrack
 * - Management of recording files
 * - Playback progress tracking
 */
class AudioUsageViewModel : ViewModel() {
    private val TAG = "AudioUsageViewModel"

    /** State flow indicating the current pickup type (near, far, both) */
    private val _pickUpType: MutableStateFlow<AudioSceneId> = MutableStateFlow(AudioSceneId.NEAR)
    val pickUpType = _pickUpType.asStateFlow()

    /** State flow indicating if audio scene is changing */
    private val _changing: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val changing = _changing.asStateFlow()

    /** State flow indicating if currently recording */
    private val _recording: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val recording = _recording.asStateFlow()

    /** Name of the current recording file */
    private var recordName = "audio.pcm"

    /** State flow containing list of recorded file names */
    private val _listRecordName: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
    val listRecordName = _listRecordName.asStateFlow()

    /** AudioTrack instance for playing back recordings */
    private var audioTrack: AudioTrack? = null

    /** State flow indicating current playback status */
    private val _playStatus = MutableStateFlow(PlayState.STOPPED)
    val playStatus = _playStatus.asStateFlow()

    // Playback progress related state
    /** State flow indicating current playback position in milliseconds */
    private val _currentPosition: MutableStateFlow<Long> = MutableStateFlow(0L)
    val currentPosition = _currentPosition.asStateFlow()

    /** State flow indicating total duration of the audio file in milliseconds */
    private val _duration: MutableStateFlow<Long> = MutableStateFlow(0L)
    val duration = _duration.asStateFlow()

    private val _systemAudioRecording: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val systemAudioRecording = _systemAudioRecording.asStateFlow()

    /** Path where audio recordings are stored */
    private val recordPath = "/sdcard/Download/Rokid/audioRecord"

    /** Listener for audio stream events from the CXR API */
    private val audioListener = object : AudioStreamListener {
//        /**
//         * When the audio stream starts recording, this method will be called.
//         * @param codeType Type of the audio stream: 1:PCM 16Bit 16KHz 1Channel
//         * @param streamType Name of the audio stream
//         */
//        override fun onStartAudioStream(
//
//            codeType: Int,
//            streamType: String?
//        ) {
//            // create a new file to record audio
//            recordName = "cxrM_${streamType}_${System.currentTimeMillis()}.pcm"
//            _listRecordName.value += recordName
//            Log.i(TAG, "onStartAudioStream: $recordName")
//        }

//        /**
//         * This method will be called when audio recording data is available.
//         * @param data audio data
//         * @param offset Offset of the data
//         * @param size Size of the data
//         */
//        @SuppressLint("SdCardPath")
//        override fun onAudioStream(
//            data: ByteArray?,
//            offset: Int,
//            size: Int
//        ) {
//            val realBytes = if (size > 0) {
//                data?.copyOfRange(offset, offset + size)
//            } else {
//                null
//            }
//
//            // audio data--save to app
//            val file = File(recordPath, recordName)
//            val parent = file.parentFile
//            if (!parent!!.exists()) {
//                Log.e(TAG, "create file error: ${file.absolutePath}")
//                parent.mkdirs()
//            }
//            if (!file.exists()) {
//                Log.e(TAG, "file not exists, create file: ${file.absolutePath}")
//                file.createNewFile()
//            }
//            val fos = FileOutputStream(file, true)
//            data?.let {
//                fos.write(it, offset, size)
//            }
//            fos.close()
//        }

        override fun onStartAudioStream(id: Int, codecType: Int, streamType: String?) {
//            TODO("Not yet implemented")
            // create a new file to record audio
            recordName = "cxrM_${streamType}_${System.currentTimeMillis()}.pcm"
            _listRecordName.value += recordName
            Log.i(TAG, "onStartAudioStream: $recordName")
        }

        override fun onAudioStream(
            id: Int,
            data: ByteArray?,
            offset: Int,
            size: Int
        ) {
//            TODO("Not yet implemented")
            val realBytes = if (size > 0) {
                data?.copyOfRange(offset, offset + size)
            } else {
                null
            }

            // audio data--save to app
            val file = File(recordPath, recordName)
            val parent = file.parentFile
            if (!parent!!.exists()) {
                Log.e(TAG, "create file error: ${file.absolutePath}")
                parent.mkdirs()
            }
            if (!file.exists()) {
                Log.e(TAG, "file not exists, create file: ${file.absolutePath}")
                file.createNewFile()
            }
            val fos = FileOutputStream(file, true)
            data?.let {
                fos.write(it, offset, size)
            }
            fos.close()
        }

        override fun onAudioStreamFinish(p0: Int) {
//            TODO("Not yet implemented")
        }
    }

    /**
     * Scene status update listener for audio recording.
     */
    private val sceneStatusUpdateListener: SceneStatusUpdateListener =
        SceneStatusUpdateListener { p0 ->
            p0?.isAudioRecordRunning?.let {
                _systemAudioRecording.value = it
            }
        }

    init {
        // get system audio recording status
        val isAudioSceneRunning = CxrApi.getInstance().sceneStatusInfo.isAudioRecordRunning
        _systemAudioRecording.value = isAudioSceneRunning
        // set scene status update listener
        CxrApi.getInstance().setSceneStatusUpdateListener(sceneStatusUpdateListener)
        if (isAudioSceneRunning) {// if system audio recording is running, stop it
            controlSystemAudioRecord(false)
        }
    }

    /**
     * Start audio streaming.
     * This method initializes the audio recording process.
     */
    fun startAudioStream() {
        // set audio stream listener first
        CxrApi.getInstance().setAudioStreamListener(audioListener)
        // start audio stream
        // 文档 03语音操作：openAudioRecord(codeType, secondParam, nameOfAudioStream)
        when (CxrApi.getInstance().openAudioRecord(1, 1,"audio_stream")) {
            ValueUtil.CxrStatus.REQUEST_SUCCEED -> { // success
                _recording.value = true
                Log.i(TAG, "startAudioStream: success")
            }

            ValueUtil.CxrStatus.REQUEST_FAILED -> { // failed
                _recording.value = false
                Log.e(TAG, "startAudioStream: failed")
            }

            ValueUtil.CxrStatus.REQUEST_WAITING -> { // waiting
                _recording.value = true
                Log.e(TAG, "startAudioStream: waiting")
            }

            else -> { // unknown
                _recording.value = false
                Log.e(TAG, "startAudioStream: unknown")
            }
        }
    }

    /**
     * Stop audio streaming.
     * This method stops the audio recording process and removes the listener.
     */
    fun stopAudioStream() {
        // stop audio stream
        when (CxrApi.getInstance().closeAudioRecord("audio_stream")) {
            ValueUtil.CxrStatus.REQUEST_SUCCEED -> { // success
                Log.i(TAG, "stopAudioStream: success")
                // remove listener
                CxrApi.getInstance().setAudioStreamListener(null)
                _recording.value = false
            }

            ValueUtil.CxrStatus.REQUEST_FAILED -> { // failed
                Log.e(TAG, "stopAudioStream: failed")
            }

            ValueUtil.CxrStatus.REQUEST_WAITING -> { // waiting
                Log.i(TAG, "stopAudioStream: waiting")
                // remove listener
                CxrApi.getInstance().setAudioStreamListener(null)
                _recording.value = false
            }

            else -> { // unknown
                Log.e(TAG, "stopAudioStream: unknown")
            }
        }
    }

    /**
     * Change the audio scene ID.
     * @param sceneId The desired audio scene ID to switch to.
     * @see AudioSceneId
     * @see AudioSceneId.NEAR Near-field sound pickup
     * @see AudioSceneId.FAR Far-field sound pickup
     * @see AudioSceneId.BOTH full-scenario sound pickup
     */
    fun changeAudioSceneId(sceneId: AudioSceneId) {
        _changing.value = true
        // change audio scene
        val result = CxrApi.getInstance().changeAudioSceneId(sceneId.id) { id, success ->
            _changing.value = false
            if (success) {
                when (id) {
                    0 -> {
                        _pickUpType.value = AudioSceneId.NEAR
                    }

                    1 -> {
                        _pickUpType.value = AudioSceneId.FAR
                    }

                    else -> {
                        _pickUpType.value = AudioSceneId.BOTH
                    }
                }
            }
        }
        when (result) {
            ValueUtil.CxrStatus.REQUEST_SUCCEED -> { // success
                Log.i(TAG, "changeAudioSceneId: success")
            }

            ValueUtil.CxrStatus.REQUEST_FAILED -> { // failed
                Log.e(TAG, "changeAudioSceneId: failed")
                _changing.value = false
            }

            ValueUtil.CxrStatus.REQUEST_WAITING -> { // waiting
                Log.i(TAG, "changeAudioSceneId: waiting")
            }

            else -> { // unknown
                Log.e(TAG, "changeAudioSceneId: unknown")
                _changing.value = false
            }
        }
    }

    /**
     * Start playing the recorded PCM audio file.
     * If already paused, resume playback. Otherwise, start a new playback session.
     */
    fun startPlayAudio() {
        Log.d(TAG, "startPlayAudio: $recordName")
        if (_playStatus.value == PlayState.PAUSED) {
            // Resume playback
            audioTrack?.play()
            _playStatus.value = PlayState.PLAYING
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val file = File(recordPath, recordName)
                if (!file.exists()) {
                    Log.e(TAG, "Audio file does not exist: ${file.absolutePath}")
                    return@launch
                }

                // Calculate total audio duration
                // Formula: (fileSizeInBytes) / (bytesPerSample * channels) = totalSamples
                // Then: (totalSamples / sampleRate) * 1000 = duration in milliseconds
                val fileSize = file.length()
                val bytesPerSample = 2 // 16Bit = 2 bytes
                val channels = 1 // Mono
                val sampleRateInHz = 16000 // 16K
                val totalSamples = fileSize / (bytesPerSample * channels)
                val durationMs = (totalSamples * 1000 / sampleRateInHz).toLong()

                withContext(Dispatchers.Main) {
                    _duration.value = durationMs
                    _currentPosition.value = 0L
                }

                val channelConfig = AudioFormat.CHANNEL_OUT_MONO // Mono
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT // 16Bit

                val bufferSizeInBytes =
                    AudioTrack.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)

                // Use AudioTrack.Builder instead of deprecated constructor
                // Configure audio attributes for media playback
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRateInHz)
                            .setEncoding(audioFormat)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSizeInBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack?.play()
                _playStatus.value = PlayState.PLAYING

                val dis = RandomAccessFile(file, "r")
                val data = ByteArray(bufferSizeInBytes)
                var totalReadBytes: Long = 0

                // Main playback loop - continue while in PLAYING state
                while (_playStatus.value == PlayState.PLAYING) {
                    var bytesRead = 0
                    // Read audio data chunk by chunk until buffer is full or EOF
                    while (bytesRead < data.size && dis.getFilePointer() < dis.length()) {
                        data[bytesRead] = dis.readByte()
                        bytesRead++
                    }

                    if (bytesRead > 0) {
                        audioTrack?.write(data, 0, bytesRead)

                        // Update playback progress
                        totalReadBytes += bytesRead.toLong()
                        val positionMs =
                            (totalReadBytes * 1000 / (sampleRateInHz * bytesPerSample * channels)).toLong()

                        withContext(Dispatchers.Main) {
                            _currentPosition.value = positionMs
                        }
                    }

                    if (dis.getFilePointer() >= dis.length()) {
                        // File reading completed
                        break
                    }
                }

                withContext(Dispatchers.Main) {
                    stopPlayAudio()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error playing audio", e)
                withContext(Dispatchers.Main) {
                    stopPlayAudio()
                }
            }
        }
    }

    /**
     * Pause audio playback.
     * This method pauses the currently playing audio track if it's playing.
     */
    fun pausePlayAudio() {
        _playStatus.value = PlayState.PAUSED
        audioTrack?.apply {
            if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                pause()
            }
        }
    }

    /**
     * Stop audio playback.
     * This method stops the audio track, releases resources, and sets the audio track to null.
     */
    fun stopPlayAudio() {
        _playStatus.value = PlayState.STOPPED
        audioTrack?.apply {
            if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                stop()
            }
            release()
        }
        audioTrack = null
        Log.d(TAG, "Audio playback stopped")
    }


    /**
     * Control system audio record.
     * This method controls the system audio record scene.
     *
     * @param toOpen Indicates whether to open the scene (true) or close it (false).
     */
    fun controlSystemAudioRecord(toOpen: Boolean) {

        when (CxrApi.getInstance()
            .controlScene(ValueUtil.CxrSceneType.AUDIO_RECORD, toOpen, null)) {
            ValueUtil.CxrStatus.REQUEST_SUCCEED -> {
                Log.d(TAG, "Audio record started")
            }

            ValueUtil.CxrStatus.REQUEST_FAILED -> {
                Log.e(TAG, "Failed to start audio record")
            }

            ValueUtil.CxrStatus.REQUEST_WAITING -> {
                Log.e(TAG, "Requested but Glasses is not ready")
            }

            else -> {
                Log.e(TAG, "Unknown error")
            }
        }
    }

    /**
     * Called when the ViewModel is cleared.
     * Ensures that audio playback is stopped and resources are released.
     */
    override fun onCleared() {
        stopPlayAudio()
        CxrApi.getInstance().setSceneStatusUpdateListener(null)
        super.onCleared()
    }
}
