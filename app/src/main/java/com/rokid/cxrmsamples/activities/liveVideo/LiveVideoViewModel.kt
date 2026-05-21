package com.rokid.cxrmsamples.activities.liveVideo

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.lifecycle.AndroidViewModel
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.listeners.MediaStreamListener
import com.rokid.cxr.client.utils.ValueUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * Video encoder mode for openCameraVideo: 1 = H264, 2 = H265.
 */
enum class VideoEncoderMode(val mode: Int, val label: String) {
    H264(1, "H264"),
    H265(2, "H265")
}

/**
 * 文档参考：09实时取流。openCameraVideo / closeCameraVideo / setMediaStreamListener / isGlassCameraInUse。
 * 帧经 LiveVideoFrameBuffer、LiveVideoPreviewRenderer；可选 MP4 录制见 LiveVideoMp4Recorder。
 */
class LiveVideoViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application = application

    /** Supported resolutions for live camera (plan: 640×480, 800×600). */
    val resolutions: Array<Size> = arrayOf(
        Size(640, 480),
        Size(800, 600)
    )

    /** State: selected resolution */
    private val _selectedResolution = MutableStateFlow(resolutions[0])
    val selectedResolution = _selectedResolution.asStateFlow()

    /** State: selected codec (H264 / H265) */
    private val _selectedCodec = MutableStateFlow(VideoEncoderMode.H264)
    val selectedCodec = _selectedCodec.asStateFlow()

    /** State: whether we have requested stream to be on (may still be opening) */
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming = _isStreaming.asStateFlow()

    /** State: camera actually opened (from onCameraOpened) */
    private val _cameraOpened = MutableStateFlow(false)
    val cameraOpened = _cameraOpened.asStateFlow()

    /** State: last camera error (from onCameraError) */
    private val _cameraError = MutableStateFlow(false)
    val cameraError = _cameraError.asStateFlow()

    /** State: total frames received (for UI) */
    private val _frameCount = MutableStateFlow(0L)
    val frameCount = _frameCount.asStateFlow()

    /** State: camera in use by other scene (isGlassCameraInUse) */
    private val _cameraInUse = MutableStateFlow(false)
    val cameraInUse = _cameraInUse.asStateFlow()

    /** State: user checkbox "record to MP4 file" */
    private val _recordToFile = MutableStateFlow(false)
    val recordToFile = _recordToFile.asStateFlow()

    /** State: currently recording to file (recorder active) */
    private val _isRecordingToFile = MutableStateFlow(false)
    val isRecordingToFile = _isRecordingToFile.asStateFlow()

    /** State: current recording file path (for UI display) */
    private val _currentRecordPath = MutableStateFlow<String?>(null)
    val currentRecordPath = _currentRecordPath.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var frameBuffer: LiveVideoFrameBuffer? = null
    private var previewRenderer: LiveVideoPreviewRenderer? = null
    private var mp4Recorder: LiveVideoMp4Recorder? = null

    /**
     * MediaStreamListener: forward frames to buffer and update UI state on main thread.
     * Callbacks may run on SDK thread; we only enqueue in listener and post state updates.
     */
    private val mediaStreamListener = object : MediaStreamListener {
        override fun onCameraOpened() {
            scope.launch {
                withContext(Dispatchers.Main) {
                    _cameraOpened.value = true
                    _cameraError.value = false
                }
            }
        }

        override fun onCameraClosed() {
            scope.launch {
                withContext(Dispatchers.Main) {
                    _cameraOpened.value = false
                }
            }
        }

        override fun onCameraError() {
            scope.launch {
                withContext(Dispatchers.Main) {
                    _cameraError.value = true
                }
            }
        }

        override fun onCameraFrame(data: ByteArray?, timestamp: Long) {
            if (data == null) return
            frameBuffer?.enqueueFrame(data, timestamp)
            previewRenderer?.feedFrame(data, timestamp)
            // Start MP4 recorder on first frame (open → first frame can have long delay)
            if (_recordToFile.value && mp4Recorder == null) {
                synchronized(this) {
                    if (mp4Recorder == null) {
                        val width = _selectedResolution.value.width
                        val height = _selectedResolution.value.height
                        val isH265 = _selectedCodec.value == VideoEncoderMode.H265
                        val recorder = LiveVideoMp4Recorder()
                        val path = recorder.startRecording(app, width, height, isH265)
                        if (path != null) {
                            mp4Recorder = recorder
                            scope.launch {
                                withContext(Dispatchers.Main) {
                                    _isRecordingToFile.value = true
                                    _currentRecordPath.value = path
                                }
                            }
                            recorder.writeFrame(data, timestamp)
                        }
                    } else {
                        mp4Recorder?.writeFrame(data, timestamp)
                    }
                }
            } else {
                mp4Recorder?.writeFrame(data, timestamp)
            }
            scope.launch {
                withContext(Dispatchers.Main) {
                    _frameCount.value = _frameCount.value + 1
                }
            }
        }
    }

    init {
        _cameraInUse.value = CxrApi.getInstance().isGlassCameraInUse
    }

    override fun onCleared() {
        if (_isStreaming.value) {
            stopCamera()
        }
        CxrApi.getInstance().setMediaStreamListener(null)
        frameBuffer = null
        previewRenderer?.release()
        previewRenderer = null
        mp4Recorder?.stopRecording()
        mp4Recorder = null
        super.onCleared()
    }

    fun setRecordToFile(record: Boolean) {
        _recordToFile.value = record
    }

    /**
     * Set the Surface for live preview. Call from UI when SurfaceView is ready (or with null when destroyed).
     */
    fun setPreviewSurface(surface: android.view.Surface?) {
        previewRenderer?.setSurface(surface)
    }

    fun selectResolution(size: Size) {
        _selectedResolution.value = size
    }

    fun selectCodec(mode: VideoEncoderMode) {
        _selectedCodec.value = mode
    }

    /** Start live camera: set listener then open with selected resolution and codec. */
    fun startCamera() {
        if (_isStreaming.value) return
        _cameraInUse.value = CxrApi.getInstance().isGlassCameraInUse
        if (_cameraInUse.value) return

        frameBuffer = LiveVideoFrameBuffer(targetFps = 30)
        val width = _selectedResolution.value.width
        val height = _selectedResolution.value.height
        val isH265 = _selectedCodec.value == VideoEncoderMode.H265
        previewRenderer = LiveVideoPreviewRenderer(width, height, isH265)
        CxrApi.getInstance().setMediaStreamListener(mediaStreamListener)

        val frameRotate = 0
        val videoEncoderMode = _selectedCodec.value.mode

        // 文档 09实时取流：openCameraVideo(width, height, frameRotate, videoEncoderMode)，1=H264 2=H265
        val status = CxrApi.getInstance().openCameraVideo(width, height, frameRotate, videoEncoderMode)
        when (status) {
            ValueUtil.CxrStatus.REQUEST_SUCCEED -> {
                _isStreaming.value = true
            }
            ValueUtil.CxrStatus.REQUEST_WAITING -> {
                _isStreaming.value = true
            }
            ValueUtil.CxrStatus.REQUEST_FAILED -> {
                CxrApi.getInstance().setMediaStreamListener(null)
                frameBuffer = null
                previewRenderer?.release()
                previewRenderer = null
            }
            else -> {
                CxrApi.getInstance().setMediaStreamListener(null)
                frameBuffer = null
                previewRenderer?.release()
                previewRenderer = null
            }
        }
    }

    /** Stop live camera: close then clear listener. */
    fun stopCamera() {
        if (!_isStreaming.value) return

        val recordedPath = mp4Recorder?.getOutputPath()
        mp4Recorder?.stopRecording()
        mp4Recorder = null
        _isRecordingToFile.value = false
        _currentRecordPath.value = null

        if (!recordedPath.isNullOrEmpty()) {
            scope.launch {
                addRecordedVideoToGallery(recordedPath)
            }
        }

        // 文档 09：closeCameraVideo()
        val status = CxrApi.getInstance().closeCameraVideo()
        when (status) {
            ValueUtil.CxrStatus.REQUEST_SUCCEED,
            ValueUtil.CxrStatus.REQUEST_WAITING -> { }
            else -> { }
        }
        CxrApi.getInstance().setMediaStreamListener(null)
        frameBuffer = null
        previewRenderer?.release()
        previewRenderer = null
        _isStreaming.value = false
        _cameraOpened.value = false
    }

    fun toggleStreaming() {
        if (_isStreaming.value) stopCamera() else startCamera()
    }

    /** Refresh camera-in-use state (e.g. from UI). */
    fun refreshCameraInUse() {
        _cameraInUse.value = CxrApi.getInstance().isGlassCameraInUse
    }

    /**
     * Copy the recorded MP4 to MediaStore so it appears in the system gallery.
     * Runs on Dispatchers.IO.
     */
    private suspend fun addRecordedVideoToGallery(filePath: String) {
        withContext(Dispatchers.IO) {
            val file = File(filePath)
            if (!file.exists() || !file.canRead()) {
                Log.w(TAG, "addRecordedVideoToGallery: file missing or unreadable path=$filePath")
                return@withContext
            }
            val fileLen = file.length()
            if (fileLen < 1024) {
                Log.w(TAG, "addRecordedVideoToGallery: file too small size=$fileLen path=$filePath")
                return@withContext
            }
            val displayName = file.name
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/LiveVideo")
                }
            }
            val resolver = app.contentResolver
            val uri: Uri? = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                Log.e(TAG, "addRecordedVideoToGallery: MediaStore insert failed path=$filePath")
                return@withContext
            }
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(file).use { input ->
                        val buffer = ByteArray(8192)
                        var len: Int
                        while (input.read(buffer).also { len = it } != -1) {
                            out.write(buffer, 0, len)
                        }
                    }
                }
                resolver.notifyChange(uri, null)
                Log.d(TAG, "addRecordedVideoToGallery: added to gallery uri=$uri")
            } catch (e: Exception) {
                Log.e(TAG, "addRecordedVideoToGallery: copy failed", e)
                try {
                    resolver.delete(uri, null, null)
                } catch (_: Exception) { }
            }
        }
    }

    companion object {
        private const val TAG = "LiveVideoViewModel"
    }
}
