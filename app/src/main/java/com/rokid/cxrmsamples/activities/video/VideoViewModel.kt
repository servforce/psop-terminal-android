package com.rokid.cxrmsamples.activities.video

// 文档参考：05录像操作（setVideoParams、controlScene VIDEO_RECORD、sceneStatusInfo）

import android.util.Size
import androidx.lifecycle.ViewModel
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.listeners.SceneStatusUpdateListener
import com.rokid.cxr.client.utils.ValueUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class VideoViewModel : ViewModel() {
    /**
     * Available video resolution options
     * Array of Size objects representing different supported resolutions
     */
    val videoSize: Array<Size> = arrayOf(
        Size(1920, 1080),
        Size(4032, 3024),
        Size(4000, 3000),
        Size(4032, 2268),
        Size(3264, 2448),
        Size(3200, 2400),
        Size(2268, 3024),
        Size(2876, 2156),
        Size(2688, 2016),
        Size(2582, 1936),
        Size(2400, 1800),
        Size(1800, 2400),
        Size(2560, 1440),
        Size(2400, 1350),
        Size(2048, 1536),
        Size(2016, 1512),
        Size(1600, 1200),
        Size(1440, 1080),
        Size(1280, 720),
        Size(720, 1280),
        Size(1024, 768),
        Size(800, 600),
        Size(648, 648),
        Size(854, 480),
        Size(800, 480),
        Size(640, 480),
        Size(480, 640),
        Size(352, 288),
        Size(320, 240),
        Size(320, 180),
        Size(176, 144)
    )

    /** State flow for selected video size */
    private val _selectedVideoSize = MutableStateFlow(videoSize[0])
    val selectedVideoSize = _selectedVideoSize.asStateFlow()

    /** State flow for recording duration */
    private val _duration = MutableStateFlow(10)
    val duration = _duration.asStateFlow()

    /** State flow for duration unit (seconds/minutes) */
    private val _durationUnit = MutableStateFlow(DurationUnit.SECONDS)
    val durationUnit = _durationUnit.asStateFlow()

    /** State flow for recording status */
    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    /**
     * Listener for scene status updates
     * Updates the recording status when notified by the CXR system
     */
    private val sceneStatusUpdateListener = SceneStatusUpdateListener { p0 ->
        p0?.isVideoRecordRunning?.let {
            _isRecording.value = it
        }
    }

    init {
        // Initialize recording status
        val sceneStatus = CxrApi.getInstance().sceneStatusInfo.isVideoRecordRunning
        _isRecording.value = sceneStatus
        // Set the scene status listener
        CxrApi.getInstance().setSceneStatusUpdateListener(sceneStatusUpdateListener)
        // Stop recording if it's already running
        if (sceneStatus){
            controlVideoScene(false)
        }
    }

    /**
     * Clean up resources when the ViewModel is cleared
     * Stops recording if it's currently running
     * Unsets the scene status listener
     */
    override fun onCleared() {
        if (_isRecording.value){
            controlVideoScene(false)
        }
        CxrApi.getInstance().setSceneStatusUpdateListener(null)
        super.onCleared()
    }

    /**
     * Update the selected video resolution
     * @param resolution the new video resolution to select
     */
    fun sizeChoose(resolution: Size) {
        _selectedVideoSize.value = resolution
    }

    /**
     * Set the recording duration
     * @param duration the duration value to set
     */
    fun setDuration(duration: Int) {
        _duration.value = duration
    }

    /**
     * Set the duration unit (seconds or minutes)
     * @param unit the duration unit to set
     */
    fun setDurationUnit(unit: DurationUnit) {
        _durationUnit.value = unit
    }

    /**
     * 文档 05录像操作：setVideoParams(duration, frameRate, width, height, timeUnit)
     */
    fun setVideoParams() {
        val size = _selectedVideoSize.value
        CxrApi.getInstance().setVideoParams(
            _duration.value,
            30, // Frame rate
            size.width,
            size.height,
            if (_durationUnit.value == DurationUnit.MINUTES) 0 else 1 // Time unit: 0 for minutes, 1 for seconds
        )
    }

    /**
     * Toggle video recording state
     * Starts recording if currently stopped, stops recording if currently running
     */
    fun toggleRecording() {
        if (_isRecording.value) {
            // Stop recording
            controlVideoScene(false)
        } else {
            // Start recording
            controlVideoScene(true)
        }
    }


    /** 文档 05录像操作：controlScene(VIDEO_RECORD, toOpen, null) */
    private fun controlVideoScene(toRecord: Boolean) {
        when(CxrApi.getInstance().controlScene(ValueUtil.CxrSceneType.VIDEO_RECORD, toRecord,  null)){
            ValueUtil.CxrStatus.REQUEST_SUCCEED->{
                // Request succeeded
            }
            ValueUtil.CxrStatus.REQUEST_FAILED->{
                // Request failed
            }

            ValueUtil.CxrStatus.REQUEST_WAITING->{
                // Request waiting
            }
            else -> {
                // Other status
            }
        }
    }

    /**
     * Enum for duration units
     */
    enum class DurationUnit {
        SECONDS, MINUTES
    }
}