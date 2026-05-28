package com.rokid.cxrmsamples.activities.psopDemo

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.PhotoResultCallback
import com.rokid.cxr.client.extend.listeners.AiEventListener
import com.rokid.cxr.client.extend.listeners.AudioStreamListener
import com.rokid.cxr.client.extend.listeners.MediaStreamListener
import com.rokid.cxr.client.utils.ValueUtil
import com.rokid.cxrmsamples.activities.liveVideo.LiveVideoMp4Recorder
import com.rokid.cxrmsamples.network.ConnectionState
import com.rokid.cxrmsamples.network.PsopConfig
import com.rokid.cxrmsamples.network.PsopRepository
import com.rokid.cxrmsamples.network.models.WebSocketEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

data class TerminalMessage(
    val id: String,
    val direction: String, // "input" or "output"
    val content: String,
    val timestamp: String,
    val seqNo: Int = 0
)

enum class InteractionMode {
    IDLE,            // 未启动
    LISTENING,       // 语音监听中（waiting_input 状态下可长按说话）
    PROCESSING,      // 后端处理中
    PHOTO_CAPTURE,   // 拍照中
    VIDEO_RECORDING, // 录像中
    COMPLETED        // 巡检完成
}

data class PsopDemoUiState(
    val isRunning: Boolean = false,
    val runId: String? = null,
    val messages: List<TerminalMessage> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val error: String? = null,
    val isCompleted: Boolean = false,
    val runStatus: String? = null, // "running", "waiting_input", "succeeded", "failed", "cancelled", "aborted"
    val interactionMode: InteractionMode = InteractionMode.IDLE,
    val asrText: String = ""  // 当前 ASR 识别到的文字（实时显示用）
)

class PsopDemoViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = PsopRepository()

    private val _uiState = MutableStateFlow(PsopDemoUiState())
    val uiState: StateFlow<PsopDemoUiState> = _uiState.asStateFlow()

    /** 本地已收到的最大 seq_no */
    private var lastSeqNo: Int = 0

    companion object {
        private const val TAG = "PsopDemoVM"
        private val TERMINAL_STATES = setOf("succeeded", "failed", "cancelled", "aborted")
    }

    // ===== ASR 相关字段 =====
    private var isAsrActive = false
    private var heartbeatJob: Job? = null
    private val audioBuffer = ByteArrayOutputStream()

    // ===== 录像相关字段 =====
    private var videoRecordingJob: Job? = null
    private var mp4Recorder: LiveVideoMp4Recorder? = null
    private var isVideoStreamOpen = false

    /** 录像用的 MediaStreamListener：接收视频帧喂给 MP4 录制器 */
    private val videoMediaStreamListener = object : MediaStreamListener {
        override fun onCameraOpened() {
            Log.d(TAG, "Video recording: camera opened")
        }

        override fun onCameraClosed() {
            Log.d(TAG, "Video recording: camera closed")
        }

        override fun onCameraError() {
            Log.e(TAG, "Video recording: camera error")
            viewModelScope.launch {
                stopVideoRecording()
            }
        }

        override fun onCameraFrame(data: ByteArray?, timestamp: Long) {
            if (data == null) return
            mp4Recorder?.writeFrame(data, timestamp)
        }
    }

    // AI事件监听器 — 眼镜触摸板长按/释放触发
    private val aiEventListener = object : AiEventListener {
        override fun onAiKeyDown() {
            // 长按触摸板 → 开始 ASR 监听（仅 waiting_input 状态下有效）
            viewModelScope.launch {
                if (_uiState.value.runStatus == "waiting_input") {
                    startAsrListening()
                }
            }
        }

        override fun onAiKeyUp() {
            // 释放触摸板 → 结束 ASR
            viewModelScope.launch {
                stopAsrListening()
            }
        }

        override fun onAiExit() {
            // AI 场景退出
            viewModelScope.launch {
                stopAsrListening()
            }
        }
    }

    // 音频流监听器 — 接收眼镜麦克风音频数据
    private val asrAudioListener = object : AudioStreamListener {
        override fun onStartAudioStream(id: Int, codecType: Int, streamType: String?) {
            Log.d(TAG, "ASR audio stream started: codec=$codecType, type=$streamType")
        }

        override fun onAudioStream(id: Int, data: ByteArray?, offset: Int, size: Int) {
            // 累积 PCM 音频数据，待音频流结束后统一发送给 ASR 服务
            if (data != null && size > 0) {
                audioBuffer.write(data, offset, size)
            }
        }

        override fun onAudioStreamFinish(p0: Int) {
            // 音频流结束 — 一次 ASR 识别完成
            Log.d(TAG, "ASR audio stream finished")
            handleAsrComplete()
        }
    }

    init {
        // 注册 Rokid AI 事件监听器（眼镜触摸板长按触发 ASR）
        CxrApi.getInstance().setAiEventListener(aiEventListener)

        setupReconnectCallbacks()

        // 监听连接状态
        viewModelScope.launch {
            repository.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
            }
        }

        // 监听 WebSocket 事件
        viewModelScope.launch {
            repository.wsEvents.collect { event ->
                handleWebSocketEvent(event)
            }
        }
    }

    /**
     * 设置断线重连回调：
     * 1. onBeforeReconnect: 重连前调用 getRun() 检查 Run 状态
     * 2. onReconnectRecoverEvents: 重连成功后补齐遗漏事件
     */
    private fun setupReconnectCallbacks() {
        // 重连前检查 Run 是否还在进行中
        repository.setOnBeforeReconnect { runId ->
            try {
                val run = repository.getRun(runId)
                Log.d(TAG, "Reconnect check: run status=${run.status}")
                if (run.status in TERMINAL_STATES) {
                    // Run 已结束，更新 UI 状态，不再重连
                    updateUiForRunStatus(run.status)
                    false
                } else {
                    // Run 还在进行中，允许重连
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check run status before reconnect", e)
                true // 无法获取状态，默认尝试重连
            }
        }

        // 重连成功后补齐断线期间遗漏的事件
        repository.setOnReconnectRecoverEvents { runId, fromSeq ->
            viewModelScope.launch {
                recoverMissedEvents(runId, fromSeq)
            }
        }
    }

    fun startSkill(skillKey: String = "skill-idkh6i") {
        viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(
                        isRunning = true,
                        error = null,
                        isCompleted = false,
                        messages = emptyList(),
                        runStatus = null
                    )
                }
                lastSeqNo = 0
                val response = repository.createInvocation(skillKey)
                val runId = response.runId ?: return@launch
                _uiState.update { it.copy(runId = runId) }
                // 连接 WebSocket 接收实时事件
                repository.connectWebSocket(runId)
            } catch (e: Exception) {
                _uiState.update { it.copy(isRunning = false, error = e.message ?: "启动失败") }
            }
        }
    }

    fun submitInput(text: String) {
        val runId = _uiState.value.runId ?: return
        viewModelScope.launch {
            try {
                // 先本地显示用户输入
                val msg = TerminalMessage(
                    id = "local-${System.currentTimeMillis()}",
                    direction = "input",
                    content = text,
                    timestamp = ""
                )
                _uiState.update { it.copy(messages = it.messages + msg) }
                // 发送到后端
                repository.appendTerminalEvent(runId, text)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "发送失败") }
            }
        }
    }

    fun uploadFile(file: File) {
        val runId = _uiState.value.runId ?: return
        viewModelScope.launch {
            try {
                // 本地显示上传中提示
                val msg = TerminalMessage(
                    id = "upload-${System.currentTimeMillis()}",
                    direction = "input",
                    content = "\uD83D\uDCF7 正在上传图片: ${file.name}",
                    timestamp = ""
                )
                _uiState.update { it.copy(messages = it.messages + msg) }
                // 调用 Repository 上传
                repository.uploadTerminalFile(
                    runId = runId,
                    file = file,
                    caption = "现场拍照取证"
                )
                // 上传成功，更新消息
                _uiState.update { state ->
                    val updatedMessages = state.messages.map {
                        if (it.id == msg.id) it.copy(content = "\u2705 已上传图片: ${file.name}")
                        else it
                    }
                    state.copy(messages = updatedMessages)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "图片上传失败: ${e.message}") }
            }
        }
    }

    /**
     * 使用 Rokid 眼镜拍照并上传
     */
    fun takePictureWithGlass() {
        val runId = _uiState.value.runId ?: return

        // 显示拍照中提示
        val msgId = "photo-${System.currentTimeMillis()}"
        val msg = TerminalMessage(
            id = msgId,
            direction = "input",
            content = "\uD83D\uDCF7 正在拍照...",
            timestamp = ""
        )
        _uiState.update { it.copy(messages = it.messages + msg) }

        val callback = PhotoResultCallback { status, imageData ->
            viewModelScope.launch {
                if (status == ValueUtil.CxrStatus.RESPONSE_SUCCEED && imageData != null) {
                    val file = saveImageToFile(imageData)
                    if (file != null) {
                        try {
                            repository.uploadTerminalFile(
                                runId = runId,
                                file = file,
                                caption = "现场拍照取证"
                            )
                            _uiState.update { state ->
                                val updatedMessages = state.messages.map {
                                    if (it.id == msgId) it.copy(content = "\u2705 已上传图片")
                                    else it
                                }
                                state.copy(messages = updatedMessages)
                            }
                        } catch (e: Exception) {
                            _uiState.update { state ->
                                val updatedMessages = state.messages.map {
                                    if (it.id == msgId) it.copy(content = "\u274C 图片上传失败: ${e.message}")
                                    else it
                                }
                                state.copy(messages = updatedMessages)
                            }
                        } finally {
                            file.delete()
                        }
                    } else {
                        _uiState.update { state ->
                            val updatedMessages = state.messages.map {
                                if (it.id == msgId) it.copy(content = "\u274C 图片保存失败")
                                else it
                            }
                            state.copy(messages = updatedMessages)
                        }
                    }
                } else {
                    _uiState.update { state ->
                        val updatedMessages = state.messages.map {
                            if (it.id == msgId) it.copy(content = "\u274C 拍照失败")
                            else it
                        }
                        state.copy(messages = updatedMessages)
                    }
                }
            }
        }

        when (CxrApi.getInstance().takeGlassPhotoGlobal(1920, 1080, 100, callback)) {
            ValueUtil.CxrStatus.REQUEST_SUCCEED -> {
                // 请求成功，等待回调
            }
            ValueUtil.CxrStatus.REQUEST_FAILED -> {
                _uiState.update { state ->
                    val updatedMessages = state.messages.map {
                        if (it.id == msgId) it.copy(content = "\u274C 拍照请求失败")
                        else it
                    }
                    state.copy(messages = updatedMessages)
                }
            }
            ValueUtil.CxrStatus.REQUEST_WAITING -> {
                _uiState.update { state ->
                    val updatedMessages = state.messages.map {
                        if (it.id == msgId) it.copy(content = "\u23F3 设备处理中，请稍候...")
                        else it
                    }
                    state.copy(messages = updatedMessages)
                }
            }
            else -> {
                _uiState.update { state ->
                    val updatedMessages = state.messages.map {
                        if (it.id == msgId) it.copy(content = "\u274C 拍照请求异常")
                        else it
                    }
                    state.copy(messages = updatedMessages)
                }
            }
        }
    }

    /**
     * 将拍照回调的 ByteArray 保存为临时 JPEG 文件
     */
    private fun saveImageToFile(imageData: ByteArray): File? {
        return try {
            val file = File.createTempFile("psop_photo_", ".jpg")
            val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            bitmap.recycle()
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save image to file", e)
            null
        }
    }

    /**
     * 补齐断线期间遗漏的终端事件
     */
    private suspend fun recoverMissedEvents(runId: String, fromSeq: Int) {
        try {
            Log.d(TAG, "Recovering missed events from seq=$fromSeq")
            val events = repository.recoverMissedEvents(runId, fromSeq)
            if (events.isEmpty()) return

            val existingIds = _uiState.value.messages.map { it.id }.toSet()

            val newMessages = events
                .filter { it.id !in existingIds } // 去重
                .map { event ->
                    TerminalMessage(
                        id = event.id,
                        direction = event.direction,
                        content = event.payloadInline?.toString() ?: "",
                        timestamp = event.occurredAt,
                        seqNo = event.seqNo
                    )
                }

            if (newMessages.isNotEmpty()) {
                _uiState.update { state ->
                    state.copy(messages = state.messages + newMessages)
                }
                // 更新本地 seq
                val maxSeq = events.maxOf { it.seqNo }
                lastSeqNo = maxOf(lastSeqNo, maxSeq)
                Log.d(TAG, "Recovered ${newMessages.size} events, lastSeqNo=$lastSeqNo")

                // TTS 朗读恢复的 output 事件
                newMessages
                    .filter { it.direction == "output" && it.content.isNotBlank() }
                    .forEach { CxrApi.getInstance().sendGlobalTtsContent(it.content) }
            }

            // 恢复事件后同步 Run 状态
            syncRunStatus(runId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to recover missed events", e)
        }
    }

    /**
     * 主动同步 Run 状态并更新 UI
     */
    private suspend fun syncRunStatus(runId: String) {
        try {
            val run = repository.getRun(runId)
            updateUiForRunStatus(run.status)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync run status", e)
        }
    }

    /**
     * 根据 Run 状态更新 UI
     */
    private fun updateUiForRunStatus(status: String) {
        _uiState.update { state ->
            when (status) {
                "waiting_input" -> state.copy(
                    isRunning = true,
                    isCompleted = false,
                    runStatus = status,
                    interactionMode = InteractionMode.LISTENING
                )
                "running" -> state.copy(
                    isRunning = true,
                    isCompleted = false,
                    runStatus = status,
                    interactionMode = InteractionMode.PROCESSING
                )
                "succeeded" -> {
                    repository.disconnectWebSocket()
                    stopAsrListening()
                    CxrApi.getInstance().setAiEventListener(null)
                    state.copy(
                        isRunning = false,
                        isCompleted = true,
                        runStatus = status,
                        interactionMode = InteractionMode.COMPLETED
                    )
                }
                "failed" -> {
                    repository.disconnectWebSocket()
                    stopAsrListening()
                    CxrApi.getInstance().setAiEventListener(null)
                    state.copy(
                        isRunning = false,
                        isCompleted = false,
                        error = "运行失败",
                        runStatus = status,
                        interactionMode = InteractionMode.COMPLETED
                    )
                }
                "cancelled" -> {
                    repository.disconnectWebSocket()
                    stopAsrListening()
                    CxrApi.getInstance().setAiEventListener(null)
                    state.copy(
                        isRunning = false,
                        isCompleted = false,
                        error = "运行已取消",
                        runStatus = status,
                        interactionMode = InteractionMode.COMPLETED
                    )
                }
                "aborted" -> {
                    repository.disconnectWebSocket()
                    stopAsrListening()
                    CxrApi.getInstance().setAiEventListener(null)
                    state.copy(
                        isRunning = false,
                        isCompleted = false,
                        error = "运行已中止",
                        runStatus = status,
                        interactionMode = InteractionMode.COMPLETED
                    )
                }
                else -> state.copy(runStatus = status)
            }
        }
    }

    private fun handleWebSocketEvent(event: WebSocketEvent) {
        // 更新本地已收到的最大 seq_no
        if (event.seqNo > 0) {
            lastSeqNo = maxOf(lastSeqNo, event.seqNo)
        }

        when (event.eventType) {
            "terminal.event.appended" -> {
                val payload = event.payload ?: return
                val direction = payload["direction"] as? String ?: return
                val content = payload["payload_inline"]?.toString() ?: ""
                val seqNo = (payload["seq_no"] as? Number)?.toInt() ?: event.seqNo
                val msg = TerminalMessage(
                    id = payload["id"]?.toString() ?: "ws-${System.currentTimeMillis()}",
                    direction = direction,
                    content = content,
                    timestamp = event.occurredAt ?: "",
                    seqNo = seqNo
                )
                // 只添加 output 事件（input 已本地显示），同时去重
                if (direction == "output") {
                    _uiState.update { state ->
                        if (state.messages.any { it.id == msg.id }) {
                            state // 已存在，跳过
                        } else {
                            state.copy(messages = state.messages + msg)
                        }
                    }
                    // 通过眼镜 TTS 朗读 PSOP 下发的内容
                    if (content.isNotBlank()) {
                        CxrApi.getInstance().sendGlobalTtsContent(content)
                    }
                }
            }
            "run.completed" -> {
                _uiState.update { it.copy(isRunning = false, isCompleted = true, runStatus = "succeeded") }
                repository.disconnectWebSocket()
            }
            "run.failed" -> {
                _uiState.update { it.copy(isRunning = false, error = "运行失败", runStatus = "failed") }
                repository.disconnectWebSocket()
            }
            "run.status_changed" -> {
                val payload = event.payload
                val status = payload?.get("status") as? String
                if (status != null) {
                    updateUiForRunStatus(status)
                }
            }
            "ws.connected" -> {
                // WebSocket 握手成功，补齐连接前已产生的事件
                val runId = _uiState.value.runId
                if (runId != null) {
                    viewModelScope.launch {
                        recoverMissedEvents(runId, lastSeqNo + 1)
                    }
                }
            }
        }
    }

    // ===== ASR 启动/停止/结果处理方法 =====

    private fun startAsrListening() {
        if (isAsrActive) return
        isAsrActive = true
        audioBuffer.reset()  // 清空之前的音频数据
        // 录像中不覆盖 VIDEO_RECORDING 状态，只清空 asrText
        if (_uiState.value.interactionMode == InteractionMode.VIDEO_RECORDING) {
            _uiState.update { it.copy(asrText = "") }
        } else {
            _uiState.update { it.copy(interactionMode = InteractionMode.LISTENING, asrText = "") }
        }

        CxrApi.getInstance().setAudioStreamListener(asrAudioListener)
        startHeartbeat()
        Log.d(TAG, "ASR listening started")
    }

    private fun stopAsrListening() {
        if (!isAsrActive) return
        isAsrActive = false

        CxrApi.getInstance().setAudioStreamListener(null)
        CxrApi.getInstance().notifyAsrEnd()
        stopHeartbeat()

        // 如果音频流回调尚未触发 handleAsrComplete，手动触发
        if (audioBuffer.size() > 0) {
            handleAsrComplete()
        }

        Log.d(TAG, "ASR listening stopped")
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch {
            while (true) {
                CxrApi.getInstance().sendAi_Heartbeat()
                delay(3000)
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * 音频流结束，将累积的 PCM 数据发送到 ASR 服务进行识别
     */
    private fun handleAsrComplete() {
        val pcmData = audioBuffer.toByteArray()
        audioBuffer.reset()

        if (pcmData.isEmpty()) {
            Log.w(TAG, "No audio data collected")
            // 回到 LISTENING 模式
            if (_uiState.value.runStatus == "waiting_input") {
                _uiState.update { it.copy(interactionMode = InteractionMode.LISTENING) }
            }
            return
        }

        Log.d(TAG, "ASR complete, PCM data size: ${pcmData.size} bytes")

        viewModelScope.launch {
            try {
                _uiState.update { it.copy(asrText = "识别中...") }
                val text = transcribeAudio(pcmData)
                if (text.isNotBlank()) {
                    _uiState.update { it.copy(asrText = text) }
                    handleVoiceResult(text)
                } else {
                    _uiState.update { it.copy(asrText = "") }
                    Log.w(TAG, "ASR returned empty text")
                    // 回到 LISTENING 模式
                    if (_uiState.value.runStatus == "waiting_input") {
                        _uiState.update { it.copy(interactionMode = InteractionMode.LISTENING) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ASR transcription failed", e)
                _uiState.update { it.copy(asrText = "识别失败") }
                // 回到 LISTENING 模式
                if (_uiState.value.runStatus == "waiting_input") {
                    _uiState.update { it.copy(interactionMode = InteractionMode.LISTENING) }
                }
            }
        }
    }

    private fun handleVoiceResult(text: String) {
        Log.d(TAG, "Voice result: $text")

        // 检测停止录像关键词（录像中优先响应停止）
        val stopVideoKeywords = listOf("停止", "结束录像", "停止录像", "停止录制", "结束录制")
        if (stopVideoKeywords.any { text.contains(it) } && (_uiState.value.interactionMode == InteractionMode.VIDEO_RECORDING || isVideoStreamOpen)) {
            stopVideoRecording()
            return
        }

        // 检测拍照关键词（录像中不响应拍照）
        val photoKeywords = listOf("拍照", "拍一下", "看看", "拍个照", "拍一张")
        if (photoKeywords.any { text.contains(it) } && _uiState.value.interactionMode != InteractionMode.VIDEO_RECORDING) {
            _uiState.update { it.copy(interactionMode = InteractionMode.PHOTO_CAPTURE) }
            takePictureWithGlass()
            return
        }

        // 检测开始录像关键词
        val startVideoKeywords = listOf("录像", "开始录像", "录个视频", "开始录制")
        if (startVideoKeywords.any { text.contains(it) }) {
            startVideoRecording()
            return
        }

        // 录像中不接受其他文本输入
        if (_uiState.value.interactionMode == InteractionMode.VIDEO_RECORDING) {
            Log.d(TAG, "Ignoring text input during recording: $text")
            return
        }

        // 作为文本输入提交
        _uiState.update { it.copy(interactionMode = InteractionMode.PROCESSING) }
        submitInput(text)
    }

    // ===== 录像控制方法 =====

    /**
     * 开始录像：使用 CxrApi 实时流 + LiveVideoMp4Recorder 保存为 MP4
     */
    private fun startVideoRecording() {
        if (_uiState.value.interactionMode == InteractionMode.VIDEO_RECORDING) {
            Log.w(TAG, "Already recording video")
            return
        }

        // 检查摄像头是否被占用
        if (CxrApi.getInstance().isGlassCameraInUse) {
            Log.w(TAG, "Glass camera is in use, cannot start recording")
            _uiState.update { it.copy(error = "摄像头被占用，无法录像") }
            return
        }

        _uiState.update { it.copy(interactionMode = InteractionMode.VIDEO_RECORDING) }

        // 显示录像开始提示
        val msgId = "video-${System.currentTimeMillis()}"
        val msg = TerminalMessage(
            id = msgId,
            direction = "input",
            content = "\uD83D\uDD34 开始录像...",
            timestamp = ""
        )
        _uiState.update { it.copy(messages = it.messages + msg) }

        // 初始化 MP4 录制器
        val recorder = LiveVideoMp4Recorder()
        val width = 640
        val height = 480
        val isH265 = false  // 使用 H264 编码，兼容性更好
        val path = recorder.startRecording(getApplication(), width, height, isH265)
        if (path == null) {
            Log.e(TAG, "Failed to start MP4 recorder")
            _uiState.update { state ->
                val updatedMessages = state.messages.map {
                    if (it.id == msgId) it.copy(content = "\u274C 录像启动失败")
                    else it
                }
                state.copy(
                    messages = updatedMessages,
                    interactionMode = InteractionMode.LISTENING
                )
            }
            return
        }
        mp4Recorder = recorder

        // 设置 MediaStreamListener 接收视频帧
        CxrApi.getInstance().setMediaStreamListener(videoMediaStreamListener)

        // 打开眼镜摄像头实时流
        val frameRotate = 0
        val videoEncoderMode = 1 // H264
        val status = CxrApi.getInstance().openCameraVideo(width, height, frameRotate, videoEncoderMode)
        when (status) {
            ValueUtil.CxrStatus.REQUEST_SUCCEED,
            ValueUtil.CxrStatus.REQUEST_WAITING -> {
                isVideoStreamOpen = true
                Log.d(TAG, "Video recording started, saving to: $path")
            }
            else -> {
                Log.e(TAG, "Failed to open camera video, status=$status")
                recorder.stopRecording()
                mp4Recorder = null
                CxrApi.getInstance().setMediaStreamListener(null)
                _uiState.update { state ->
                    val updatedMessages = state.messages.map {
                        if (it.id == msgId) it.copy(content = "\u274C 摄像头打开失败")
                        else it
                    }
                    state.copy(
                        messages = updatedMessages,
                        interactionMode = InteractionMode.LISTENING
                    )
                }
                return
            }
        }

        // 启动 60 秒自动停止计时器
        videoRecordingJob = viewModelScope.launch {
            delay(60_000L)
            Log.d(TAG, "Auto-stopping video recording after 60s")
            stopVideoRecording()
        }
    }

    /**
     * 停止录像：关闭实时流、停止录制、上传视频文件
     */
    private fun stopVideoRecording() {
        if (_uiState.value.interactionMode != InteractionMode.VIDEO_RECORDING) return

        // 取消自动停止计时器
        videoRecordingJob?.cancel()
        videoRecordingJob = null

        _uiState.update { it.copy(interactionMode = InteractionMode.PROCESSING) }

        // 关闭摄像头实时流
        if (isVideoStreamOpen) {
            CxrApi.getInstance().closeCameraVideo()
            CxrApi.getInstance().setMediaStreamListener(null)
            isVideoStreamOpen = false
        }

        // 停止 MP4 录制并获取文件路径
        val recorder = mp4Recorder
        val videoPath = recorder?.getOutputPath()
        recorder?.stopRecording()
        mp4Recorder = null

        Log.d(TAG, "Video recording stopped, file: $videoPath")

        // 上传视频文件
        viewModelScope.launch {
            try {
                val videoFile = if (videoPath != null) File(videoPath) else null
                if (videoFile != null && videoFile.exists() && videoFile.length() > 1024) {
                    val runId = _uiState.value.runId
                    if (runId != null) {
                        // 显示上传中提示
                        val uploadMsgId = "video-upload-${System.currentTimeMillis()}"
                        val uploadMsg = TerminalMessage(
                            id = uploadMsgId,
                            direction = "input",
                            content = "\uD83D\uDCE4 正在上传录像 (${videoFile.length() / 1024}KB)...",
                            timestamp = ""
                        )
                        _uiState.update { it.copy(messages = it.messages + uploadMsg) }

                        repository.uploadTerminalFile(runId, videoFile, "现场录像取证")

                        _uiState.update { state ->
                            val updatedMessages = state.messages.map {
                                if (it.id == uploadMsgId) it.copy(content = "\u2705 已上传录像")
                                else it
                            }
                            state.copy(messages = updatedMessages)
                        }
                        Log.d(TAG, "Video uploaded successfully")
                    }
                    // 上传后清理临时文件
                    videoFile.delete()
                } else {
                    Log.w(TAG, "Video file invalid or too small: path=$videoPath")
                    val errorMsg = TerminalMessage(
                        id = "video-err-${System.currentTimeMillis()}",
                        direction = "input",
                        content = "\u274C 录像文件无效",
                        timestamp = ""
                    )
                    _uiState.update { it.copy(messages = it.messages + errorMsg) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Video upload failed", e)
                val errorMsg = TerminalMessage(
                    id = "video-err-${System.currentTimeMillis()}",
                    direction = "input",
                    content = "\u274C 录像上传失败: ${e.message}",
                    timestamp = ""
                )
                _uiState.update { it.copy(messages = it.messages + errorMsg) }
            } finally {
                // 恢复 LISTENING 状态
                if (_uiState.value.runStatus == "waiting_input") {
                    _uiState.update { it.copy(interactionMode = InteractionMode.LISTENING) }
                }
            }
        }
    }

    // ===== ASR 网络调用与音频转换 =====

    /**
     * 将 PCM 音频数据转换为 WAV 并上传到 ASR 服务，返回识别文字
     */
    private suspend fun transcribeAudio(pcmData: ByteArray): String {
        return withContext(Dispatchers.IO) {
            val wavData = pcmToWav(pcmData, sampleRate = 16000, bitsPerSample = 16, channels = 1)

            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "audio.wav",
                    wavData.toRequestBody("audio/wav".toMediaType())
                )
                .addFormDataPart("language", "zh")
                .build()

            val request = Request.Builder()
                .url(PsopConfig.asrUrl)
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                throw Exception("ASR failed: ${response.code} $body")
            }

            val json = JSONObject(body)
            json.optString("text", "")
        }
    }

    /**
     * 将 PCM 原始音频数据转换为标准 WAV 格式（添加 44 字节 header）
     */
    private fun pcmToWav(pcmData: ByteArray, sampleRate: Int, bitsPerSample: Int, channels: Int): ByteArray {
        val dataSize = pcmData.size
        val totalSize = dataSize + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        val header = ByteArray(44)
        // RIFF header
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        writeInt(header, 4, totalSize)
        // WAVE
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        // fmt chunk
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        writeInt(header, 16, 16)
        writeShort(header, 20, 1)       // Audio format: PCM
        writeShort(header, 22, channels)
        writeInt(header, 24, sampleRate)
        writeInt(header, 28, byteRate)
        writeShort(header, 32, blockAlign)
        writeShort(header, 34, bitsPerSample)
        // data chunk
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        writeInt(header, 40, dataSize)

        return header + pcmData
    }

    private fun writeInt(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = (value shr 8 and 0xFF).toByte()
        buffer[offset + 2] = (value shr 16 and 0xFF).toByte()
        buffer[offset + 3] = (value shr 24 and 0xFF).toByte()
    }

    private fun writeShort(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = (value shr 8 and 0xFF).toByte()
    }

    override fun onCleared() {
        super.onCleared()
        // 停止录像（如果正在录制）
        if (_uiState.value.interactionMode == InteractionMode.VIDEO_RECORDING) {
            videoRecordingJob?.cancel()
            videoRecordingJob = null
            if (isVideoStreamOpen) {
                CxrApi.getInstance().closeCameraVideo()
                CxrApi.getInstance().setMediaStreamListener(null)
                isVideoStreamOpen = false
            }
            mp4Recorder?.stopRecording()
            mp4Recorder = null
        }
        stopAsrListening()
        CxrApi.getInstance().setAiEventListener(null)
        repository.disconnectWebSocket()
    }
}
