package com.rokid.cxrmsamples.activities.psopDemo

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.PhotoResultCallback
import com.rokid.cxr.client.extend.callbacks.SyncStatusCallback
import com.rokid.cxr.client.extend.callbacks.WifiP2PStatusCallback
import com.rokid.cxr.client.extend.listeners.AiEventListener
import com.rokid.cxr.client.extend.listeners.AudioStreamListener
import com.rokid.cxr.client.extend.listeners.MediaFilesUpdateListener
import com.rokid.cxr.client.extend.listeners.MediaStreamListener
import com.rokid.cxr.client.utils.ValueUtil
import com.rokid.cxrmsamples.activities.liveVideo.LiveVideoMp4Recorder
import com.rokid.cxrmsamples.asr.SherpaAsrEngine
import com.rokid.cxrmsamples.dataBeans.selfView.LinearLayoutProps
import com.rokid.cxrmsamples.dataBeans.selfView.SelfViewJson
import com.rokid.cxrmsamples.dataBeans.selfView.TextViewProps
import com.rokid.cxrmsamples.dataBeans.selfView.UpdateViewJson
import com.rokid.cxrmsamples.network.ConnectionState
import com.rokid.cxrmsamples.network.PsopConfig
import com.rokid.cxrmsamples.network.PsopRepository
import com.rokid.cxrmsamples.network.models.InvocationListResponse
import com.rokid.cxrmsamples.network.models.SkillSummaryResponse
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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID


data class MessagePart(
    val partId: String,
    val kind: String,       // "image", "text", "audio", "video"
    val mimeType: String,
    val contentUrl: String  // 完整的图片下载 URL
)

data class TerminalMessage(
    val id: String,
    val direction: String, // "input" or "output"
    val content: String,
    val timestamp: String,
    val seqNo: Int = 0,
    val eventKind: String = "terminal.text.input.v1",
    val mimeType: String = "text/plain",
    val artifactObjectId: String? = null,
    val parts: List<MessagePart> = emptyList()
)

enum class InteractionMode {
    IDLE,            // 未启动
    LISTENING,       // 语音监听中（waiting_input 状态下可长按说话）
    PROCESSING,      // 后端处理中
    PHOTO_CAPTURE,   // 拍照中
    VIDEO_RECORDING, // 录像中
    COMPLETED        // 巡检完成
}

enum class InspectionScreen {
    SKILL_LIST,
    INVOCATION_LIST,
    INTERACTION
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
    val asrText: String = "",  // 当前 ASR 识别到的文字（实时显示用）
    val currentScreen: InspectionScreen = InspectionScreen.SKILL_LIST,
    val skills: List<SkillSummaryResponse> = emptyList(),
    val selectedSkill: SkillSummaryResponse? = null,
    val invocations: List<InvocationListResponse> = emptyList(),
    val isLoadingSkills: Boolean = false,
    val isLoadingInvocations: Boolean = false
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

    // ===== 眼镜硬件拍照按钮 → 同步照片到对话 =====

    /** WiFi P2P 是否已连接（用于媒体文件同步） */
    private var isMediaP2PConnected = false

    /** 是否正在同步媒体文件（防重复触发） */
    private var isMediaSyncInProgress = false

    /** 本轮同步中收集到的文件路径（仅上传最新一张） */
    private val syncedFilesThisRound = mutableListOf<File>()

    /** 眼镜硬件拍照后，媒体文件更新监听器 */
    private val mediaFilesUpdateListener = MediaFilesUpdateListener {
        Log.d(TAG, "onMediaFilesUpdated: glasses took a photo via hardware button")
        if (_uiState.value.runId == null) {
            Log.w(TAG, "No active run, ignoring hardware photo button")
            return@MediaFilesUpdateListener
        }
        if (isMediaSyncInProgress) {
            Log.w(TAG, "Media sync already in progress, ignoring")
            return@MediaFilesUpdateListener
        }
        viewModelScope.launch {
            triggerMediaPhotoSync()
        }
    }

    /** 照片同步回调：同步完成后只上传最新一张 */
    private val mediaSyncStatusCallback = object : SyncStatusCallback {
        override fun onSyncStart() {
            Log.d(TAG, "Media sync started (hardware photo)")
            isMediaSyncInProgress = true
            syncedFilesThisRound.clear()
        }

        override fun onSingleFileSynced(localPath: String?) {
            Log.d(TAG, "Media single file synced: $localPath")
            if (localPath.isNullOrBlank()) return
            val file = File(localPath)
            if (!file.exists() || file.length() < 1024) {
                Log.w(TAG, "Synced file invalid or too small: $localPath")
                return
            }
            // 只收集，不立即上传
            syncedFilesThisRound.add(file)
        }

        override fun onSyncFailed() {
            Log.e(TAG, "Media sync failed (hardware photo)")
            isMediaSyncInProgress = false
            syncedFilesThisRound.clear()
        }

        override fun onSyncFinished() {
            Log.d(TAG, "Media sync finished (hardware photo), ${syncedFilesThisRound.size} files synced")
            isMediaSyncInProgress = false
            // 只上传最新的一张（按 lastModified 排序）
            val latestFile = syncedFilesThisRound.maxByOrNull { it.lastModified() }
            syncedFilesThisRound.clear()
            if (latestFile != null) {
                Log.d(TAG, "Uploading only latest photo: ${latestFile.name}")
                viewModelScope.launch {
                    uploadSyncedPhotoToConversation(latestFile)
                }
            }
        }
    }

    /**
     * 触发眼镜硬件拍照后的照片同步流程：
     * 1. 若 P2P 已连接，直接 startSync 下载照片
     * 2. 若 P2P 未连接，先建立连接再 startSync
     */
    private fun triggerMediaPhotoSync() {
        if (CxrApi.getInstance().isWifiP2PConnected) {
            Log.d(TAG, "P2P already connected, starting sync directly")
            startMediaSync()
            return
        }

        // 显示提示
        val msg = TerminalMessage(
            id = "hw-photo-connect-${System.currentTimeMillis()}",
            direction = "input",
            content = "📷 眼镜拍照，正在建立传输连接...",
            timestamp = ""
        )
        _uiState.update { it.copy(messages = it.messages + msg) }

        // 建立 P2P 连接后启动同步
        CxrApi.getInstance().initWifiP2P(object : WifiP2PStatusCallback {
            override fun onConnected() {
                Log.d(TAG, "P2P connected for media sync")
                isMediaP2PConnected = true
                startMediaSync()
            }

            override fun onDisconnected() {
                Log.w(TAG, "P2P disconnected during media sync setup")
                isMediaP2PConnected = false
            }

            override fun onFailed(errorCode: ValueUtil.CxrWifiErrorCode?) {
                Log.e(TAG, "P2P connection failed for media sync: $errorCode")
                isMediaP2PConnected = false
                _uiState.update { state ->
                    val updatedMessages = state.messages.map {
                        if (it.id == msg.id) it.copy(content = "❌ 眼镜连接失败，无法获取照片")
                        else it
                    }
                    state.copy(messages = updatedMessages)
                }
            }

            override fun onP2pDeviceAvailable(
                name: String?,
                macAddress: String?,
                deviceType: String?
            ) {
                // initWifiP2P 模式下此处一般不触发
            }
        })
    }

    /**
     * 调用 startSync 下载眼镜上的照片文件到手机本地
     */
    private fun startMediaSync() {
        val savePath = File(getApplication<Application>().cacheDir, "psop_photos").apply {
            if (!exists()) mkdirs()
        }.absolutePath + "/"

        val mediaTypes = arrayOf(ValueUtil.CxrMediaType.PICTURE)
        val started = CxrApi.getInstance().startSync(savePath, mediaTypes, mediaSyncStatusCallback)
        if (!started) {
            Log.e(TAG, "startSync failed")
            isMediaSyncInProgress = false
        } else {
            Log.d(TAG, "startSync started, savePath=$savePath")
        }
    }

    /**
     * 将同步到本地的眼镜照片上传到对话中
     */
    private suspend fun uploadSyncedPhotoToConversation(file: File) {
        val runId = _uiState.value.runId ?: return
        val msgId = "hw-photo-${System.currentTimeMillis()}"
        val msg = TerminalMessage(
            id = msgId,
            direction = "input",
            content = "📷 正在上传眼镜照片...",
            timestamp = ""
        )
        _uiState.update { it.copy(messages = it.messages + msg) }

        try {
            doUploadTerminalFile(runId, file, "眼镜拍照取证")
            val imagePart = MessagePart(
                partId = "local-${file.name}",
                kind = "image",
                mimeType = "image/jpeg",
                contentUrl = "file://${file.absolutePath}"
            )
            _uiState.update { state ->
                val updatedMessages = state.messages.map {
                    if (it.id == msgId) it.copy(content = "", parts = listOf(imagePart))
                    else it
                }
                state.copy(messages = updatedMessages)
            }
            Log.d(TAG, "Hardware photo uploaded to conversation: ${file.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Hardware photo upload failed", e)
            _uiState.update { state ->
                val updatedMessages = state.messages.map {
                    if (it.id == msgId) it.copy(content = "❌ 眼镜照片上传失败: ${e.message}")
                    else it
                }
                state.copy(messages = updatedMessages)
            }
        }
    }

    // AI事件监听器 — 眼镜触摸板长按/释放触发
    private val aiEventListener = object : AiEventListener {
        override fun onAiKeyDown() {
            // 长按触摸板 → 关闭眼镜 CustomView（释放触摸板） → 开始 ASR 监听
            val currentStatus = _uiState.value.runStatus
            Log.d(TAG, "onAiKeyDown: runStatus=$currentStatus, isRunning=${_uiState.value.isRunning}")
            // 防御性关闭：若 CustomView 仍在显示，立即关闭以释放 ASR
            closeTeleprompter()
            viewModelScope.launch {
                startAsrListening()
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

    // 音频流监听器 — 接收眼镜麦克风音频数据（常驻注册，用 isAsrActive 控制是否缓冲）
    private val asrAudioListener = object : AudioStreamListener {
        override fun onStartAudioStream(id: Int, codecType: Int, streamType: String?) {
            Log.d(TAG, "ASR audio stream started: codec=$codecType, type=$streamType, isAsrActive=$isAsrActive")
        }

        override fun onAudioStream(id: Int, data: ByteArray?, offset: Int, size: Int) {
            // 只在 ASR 激活时累积 PCM 音频数据
            if (isAsrActive && data != null && size > 0) {
                audioBuffer.write(data, offset, size)
            }
        }

        override fun onAudioStreamFinish(p0: Int) {
            // 音频流结束 — 一次 ASR 识别完成
            Log.d(TAG, "ASR audio stream finished, isAsrActive=$isAsrActive")
            if (isAsrActive) {
                handleAsrComplete()
            }
        }
    }

    init {
        // 注册 Rokid AI 事件监听器（眼镜触摸板长按触发 ASR）
        CxrApi.getInstance().setAiEventListener(aiEventListener)
        // 注册媒体文件更新监听器（眼镜硬件拍照按钮触发照片同步）
        CxrApi.getInstance().setMediaFilesUpdateListener(mediaFilesUpdateListener)

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

        loadSkills()
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

    fun loadSkills() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingSkills = true, error = null) }
            try {
                val skills = repository.listSkills()
                _uiState.update { it.copy(skills = skills, isLoadingSkills = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoadingSkills = false, error = "加载技能列表失败: ${e.message}") }
            }
        }
    }

    fun selectSkill(skill: SkillSummaryResponse) {
        _uiState.update { it.copy(selectedSkill = skill, currentScreen = InspectionScreen.INVOCATION_LIST) }
        loadInvocations(skill.key)
    }

    private fun loadInvocations(skillKey: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingInvocations = true) }
            try {
                val invocations = repository.listInvocations(skillKey)
                _uiState.update { it.copy(invocations = invocations, isLoadingInvocations = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoadingInvocations = false, error = "加载调用记录失败: ${e.message}") }
            }
        }
    }

    fun navigateBack() {
        val current = _uiState.value.currentScreen
        when (current) {
            InspectionScreen.INVOCATION_LIST -> _uiState.update { it.copy(currentScreen = InspectionScreen.SKILL_LIST) }
            InspectionScreen.INTERACTION -> {
                _uiState.update { it.copy(currentScreen = InspectionScreen.INVOCATION_LIST) }
                // 返回时刷新 Invocations 列表，确保新启动的巡检显示出来
                _uiState.value.selectedSkill?.key?.let { loadInvocations(it) }
                // 清理本次会话中的本地图片缓存
                cleanupPhotoCache()
            }
            else -> {}
        }
    }

    /** 清理本次会话中的本地图片和视频缓存 */
    private fun cleanupPhotoCache() {
        try {
            // 清理图片缓存
            val photoDir = File(getApplication<Application>().cacheDir, "psop_photos")
            if (photoDir.exists()) {
                photoDir.listFiles()?.forEach { it.delete() }
            }
            // 清理录像文件
            val videoDir = File(
                getApplication<Application>().getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES),
                "LiveVideo"
            )
            if (videoDir.exists()) {
                videoDir.listFiles()?.forEach { it.delete() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup media cache", e)
        }
    }

    // ========== 眼镜端文字显示（CustomView 时间窗口模式，后期支持图片/视频） ==========

    /** CustomView 自动关闭延迟（秒），展示期间内收到新消息会重置计时器 */
    private val GLASSES_DISPLAY_TIMEOUT_MS = 15_000L
    private var glassesDisplayJob: Job? = null
    private var isGlassesDisplayOpen = false

    /**
     * 眼镜端 CustomView 布局 — 半透明悬浮卡片，定位于视野右下方
     * 根容器透明（不独占视觉焦点），内层卡片固定宽度 + 半透明背景
     * 后期可扩展图片/视频节点
     */
    private val glassesDisplayView: SelfViewJson by lazy {
        SelfViewJson().apply {
            type = "LinearLayout"
            props = LinearLayoutProps().apply {
                id = "root"
                layout_width = "match_parent"
                layout_height = "match_parent"
                backgroundColor = "#00000000"   // 完全透明，不遮挡视野
                orientation = "vertical"
                gravity = "bottom"              // 内容沉底
                paddingEnd = "16dp"
                paddingBottom = "16dp"
                paddingStart = "16dp"
            }.toJson()
            children = listOf(
                SelfViewJson().apply {
                    type = "LinearLayout"
                    props = LinearLayoutProps().apply {
                        id = "card"
                        layout_width = "320dp"     // 固定宽度，非全屏
                        layout_height = "wrap_content"
                        layout_gravity = "end"     // 靠右对齐
                        backgroundColor = "#CC222222" // 80% 半透明深色底
                        orientation = "vertical"
                        gravity = "center"
                        paddingStart = "14dp"
                        paddingEnd = "14dp"
                        paddingTop = "10dp"
                        paddingBottom = "10dp"
                        marginTop = "8dp"
                    }.toJson()
                    children = listOf(
                        SelfViewJson().apply {
                            type = "TextView"
                            props = TextViewProps().apply {
                                id = "contentText"
                                layout_width = "match_parent"
                                layout_height = "wrap_content"
                                text = " "
                                textColor = "#FFFFFF"
                                textSize = "15sp"
                                gravity = "start"
                            }.toJson()
                        }
                    )
                }
            )
        }
    }

    /** 打开眼镜端 CustomView 并启动自动关闭计时器 */
    private fun openTeleprompter() {
        if (!isGlassesDisplayOpen) {
            CxrApi.getInstance().openCustomView(glassesDisplayView.toJson())
            isGlassesDisplayOpen = true
            Log.d(TAG, "Glasses CustomView opened")
        }
    }

    /** 更新眼镜端显示文字（直接替换，不追加），重置自动关闭计时器 */
    private fun sendTextToTeleprompter(text: String) {
        openTeleprompter()
        val escaped = text.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
            .replace("\t", "\\t")
        val updateJson = UpdateViewJson().apply {
            updateList.add(UpdateViewJson.UpdateJson(id = "contentText").apply {
                props["text"] = escaped
            })
        }
        CxrApi.getInstance().updateCustomView(updateJson.toJson())
        // 重置自动关闭计时器
        glassesDisplayJob?.cancel()
        glassesDisplayJob = viewModelScope.launch {
            delay(GLASSES_DISPLAY_TIMEOUT_MS)
            closeTeleprompter()
        }
    }

    /** 关闭眼镜端 CustomView，取消自动关闭计时器 */
    private fun closeTeleprompter() {
        glassesDisplayJob?.cancel()
        glassesDisplayJob = null
        if (isGlassesDisplayOpen) {
            CxrApi.getInstance().closeCustomView()
            isGlassesDisplayOpen = false
            Log.d(TAG, "Glasses CustomView closed")
        }
    }

    fun startSkill(skillKey: String? = null) {
        val key = skillKey ?: _uiState.value.selectedSkill?.key ?: return
        _uiState.update { it.copy(currentScreen = InspectionScreen.INTERACTION) }
        // 重新注册 AI 事件监听器（上次巡检结束时会被置 null）
        CxrApi.getInstance().setAiEventListener(aiEventListener)
        // 重新注册媒体文件更新监听器
        CxrApi.getInstance().setMediaFilesUpdateListener(mediaFilesUpdateListener)
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
                val response = repository.createInvocation(key)
                val runId = response.runId ?: return@launch
                _uiState.update { it.copy(runId = runId) }
                // 连接 WebSocket 接收实时事件
                repository.connectWebSocket(runId)
            } catch (e: Exception) {
                _uiState.update { it.copy(isRunning = false, error = e.message ?: "启动失败") }
            }
        }
    }

    /**
     * 恢复/查看历史 Invocation：
     * - 加载历史事件
     * - 如果状态是 running/waiting_input，连接 WebSocket 继续会话
     * - 如果状态是终态，只展示历史记录
     */
    fun resumeInvocation(invocation: InvocationListResponse) {
        val runId = invocation.runId ?: return
        // 重新注册 AI 事件监听器（上次巡检结束时会被置 null）
        CxrApi.getInstance().setAiEventListener(aiEventListener)
        // 重新注册媒体文件更新监听器
        CxrApi.getInstance().setMediaFilesUpdateListener(mediaFilesUpdateListener)
        _uiState.update {
            it.copy(
                currentScreen = InspectionScreen.INTERACTION,
                runId = runId,
                isRunning = true,
                error = null,
                isCompleted = false,
                messages = emptyList(),
                runStatus = invocation.status
            )
        }
        lastSeqNo = 0
        viewModelScope.launch {
            try {
                // 加载历史事件
                val events = repository.getTerminalEvents(runId, null)
                val messages = events.map { event ->
                    val imageParts = event.parts
                        .filter { it.kind == "image" || it.kind == "video" }
                        .map { part ->
                            MessagePart(
                                partId = part.partId,
                                kind = part.kind,
                                mimeType = part.mimeType,
                                contentUrl = "${PsopConfig.baseUrl}terminal/sessions/${runId}/events/${event.id}/parts/${part.partId}/content"
                            )
                        }
                    TerminalMessage(
                        id = event.id,
                        direction = event.direction,
                        content = if (event.payloadInline is String) event.payloadInline else "",
                        timestamp = event.occurredAt,
                        parts = imageParts
                    )
                }
                if (events.isNotEmpty()) {
                    lastSeqNo = events.maxOf { it.seqNo }
                }
                _uiState.update { it.copy(messages = messages) }

                // 如果还在运行中，连接 WebSocket 继续会话
                val activeStates = setOf("running", "waiting_input", "accepted")
                if (invocation.status in activeStates) {
                    repository.connectWebSocket(runId)
                    // 立即同步 Run 真实状态（invocation.status 可能只是 "running"，实际 run 可能已是 "waiting_input"）
                    syncRunStatus(runId)
                } else {
                    // 终态，标记已完成
                    _uiState.update { it.copy(isRunning = false, isCompleted = true) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "加载历史记录失败: ${e.message}") }
            }
        }
    }

    fun submitInput(text: String) {
        android.util.Log.e("PSOP_DEBUG", ">>> submitInput called, text='$text'")
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
                doUploadTerminalFile(runId, file, "现场拍照取证")
                // 上传成功，直接显示图片
                val imagePart = MessagePart(
                    partId = "local-${file.name}",
                    kind = "image",
                    mimeType = "image/jpeg",
                    contentUrl = "file://${file.absolutePath}"
                )
                _uiState.update { state ->
                    val updatedMessages = state.messages.map {
                        if (it.id == msg.id) it.copy(
                            content = "",
                            parts = listOf(imagePart)
                        )
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
                    val photoCapturedAt = System.currentTimeMillis()
                    Log.e("PSOP_PERF", "[图片] 拍照完成, dataSize=${imageData.size} bytes, timestamp=$photoCapturedAt")
                    val file = saveImageToFile(imageData)
                    if (file != null) {
                        val saveCompleteAt = System.currentTimeMillis()
                        Log.e("PSOP_PERF", "[图片] 编码保存完成, fileSize=${file.length()} bytes, 耗时=${saveCompleteAt - photoCapturedAt}ms")
                        try {
                            Log.e("PSOP_PERF", "[图片] 开始上传, 距拍照完成=${System.currentTimeMillis() - photoCapturedAt}ms")
                            doUploadTerminalFile(runId, file, "现场拍照取证")
                            val uploadDoneAt = System.currentTimeMillis()
                            Log.e("PSOP_PERF", "[图片] 上传完成, 端到端总耗时=${uploadDoneAt - photoCapturedAt}ms")
                            // 上传成功，直接显示图片（用本地文件路径）
                            val imagePart = MessagePart(
                                partId = "local-${file.name}",
                                kind = "image",
                                mimeType = "image/jpeg",
                                contentUrl = "file://${file.absolutePath}"
                            )
                            _uiState.update { state ->
                                val updatedMessages = state.messages.map {
                                    if (it.id == msgId) it.copy(
                                        content = "",
                                        parts = listOf(imagePart)
                                    )
                                    else it
                                }
                                state.copy(messages = updatedMessages)
                            }
                            // 不删除文件，保留给 Coil 加载显示
                        } catch (e: Exception) {
                            val errorDetail = if (e is retrofit2.HttpException) {
                                "[${e.code()}] ${e.response()?.errorBody()?.string()}"
                            } else e.message ?: "unknown"
                            Log.e("PSOP_DEBUG", ">>> photo upload FAILED: $errorDetail", e)
                            _uiState.update { state ->
                                val updatedMessages = state.messages.map {
                                    if (it.id == msgId) it.copy(content = "\u274C 图片上传失败: ${e.message}")
                                    else it
                                }
                                state.copy(messages = updatedMessages)
                            }
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
     * 将拍照回调的 ByteArray 保存为临时 JPEG 文件（存到 app cacheDir，清缓存可删除）
     */
    private fun saveImageToFile(imageData: ByteArray): File? {
        return try {
            val cacheDir = File(getApplication<Application>().cacheDir, "psop_photos")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val file = File.createTempFile("psop_photo_", ".jpg", cacheDir)
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
     * 统一的文件上传工具方法：构造 event JSON、filePart、caption 并调用 Repository
     */
    private suspend fun doUploadTerminalFile(runId: String, file: File, caption: String?) {
        Log.e("PSOP_DEBUG", ">>> doUploadTerminalFile called, runId=$runId, file=${file.name}, caption=$caption", Throwable("callstack"))
        // 根据文件扩展名推断 MIME 类型
        val mimeType = when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            else -> "application/octet-stream"
        }
        val mediaType = mimeType.toMediaTypeOrNull()
        val requestFile = file.asRequestBody(mediaType)
        val filePart = MultipartBody.Part.createFormData("file", file.name, requestFile)
        val captionBody = caption?.toRequestBody("text/plain".toMediaType())

        // 构造 event JSON
        val eventJson = JSONObject().apply {
            put("direction", "input")
            put("event_kind", "terminal.multimodal.input.v1")
            put("mime_type", "multipart/mixed")
            put("source", JSONObject().apply { put("kind", "android") })
        }.toString()
        val eventBody = eventJson.toRequestBody("application/json".toMediaType())

        repository.uploadTerminalFile(
            runId = runId,
            idempotencyKey = UUID.randomUUID().toString(),
            event = eventBody,
            file = filePart,
            caption = captionBody,
            externalEventId = null
        )
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
                    val content: String = if (event.parts.isNotEmpty()) {
                        val textParts = event.parts.filter { it.kind == "text" }.map { it.text }
                        val mediaParts = event.parts.filter { it.kind != "text" }.map { part ->
                            when (part.kind) {
                                "image" -> "[图片]"
                                "audio" -> "[音频]"
                                "video" -> "[视频]"
                                else -> "[文件]"
                            }
                        }
                        (textParts + mediaParts).joinToString(" ")
                    } else {
                        when {
                            event.eventKind.contains("image") -> event.payloadInline?.toString() ?: "[图片]"
                            event.eventKind.contains("audio") -> event.payloadInline?.toString() ?: "[音频]"
                            event.eventKind.contains("video") -> event.payloadInline?.toString() ?: "[视频]"
                            else -> event.payloadInline?.toString() ?: ""
                        }
                    }
                    TerminalMessage(
                        id = event.id,
                        direction = event.direction,
                        content = content,
                        timestamp = event.occurredAt,
                        seqNo = event.seqNo,
                        eventKind = event.eventKind,
                        mimeType = event.mimeType,
                        artifactObjectId = event.artifactObjectId
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

                // 只对有文本内容且非纯媒体的消息 TTS 播报 + 眼镜端显示
                newMessages
                    .filter { it.direction == "output" && it.content.isNotBlank() && it.content != "[图片]" && it.content != "[音频]" && it.content != "[视频]" && it.content != "[文件]" }
                    .forEach {
                        CxrApi.getInstance().sendGlobalTtsContent(it.content)
                        sendTextToTeleprompter(it.content)
                    }
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
                "waiting_input" -> {
                    // 后端等待用户输入 → 立即关闭眼镜 CustomView，释放 ASR 触摸板交互
                    closeTeleprompter()
                    state.copy(
                        isRunning = true,
                        isCompleted = false,
                        runStatus = status,
                        interactionMode = InteractionMode.LISTENING
                    )
                }
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
                    closeTeleprompter()
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
                    closeTeleprompter()
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
                    closeTeleprompter()
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
                    closeTeleprompter()
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
                val seqNo = (payload["seq_no"] as? Number)?.toInt() ?: event.seqNo
                val eventKind = payload["event_kind"] as? String ?: "terminal.multimodal.input.v1"
                val mimeType = payload["mime_type"] as? String ?: "multipart/mixed"
                val artifactObjectId = payload["artifact_object_id"] as? String

                // 解析 parts（如果存在）
                val partsRaw = payload["parts"] as? List<*>
                val content: String
                if (!partsRaw.isNullOrEmpty()) {
                    // 从 parts 中提取文本
                    val textParts = partsRaw.filterIsInstance<Map<*, *>>()
                        .filter { it["kind"] == "text" }
                        .mapNotNull { it["text"] as? String }
                    val mediaParts = partsRaw.filterIsInstance<Map<*, *>>()
                        .filter { it["kind"] != "text" }
                        .map { part ->
                            when (part["kind"]) {
                                "image" -> "[图片]"
                                "audio" -> "[音频]"
                                "video" -> "[视频]"
                                else -> "[文件]"
                            }
                        }
                    content = (textParts + mediaParts).joinToString(" ")
                } else {
                    // 向后兼容：fallback 到 payload_inline
                    content = when {
                        eventKind.contains("image") -> payload["payload_inline"]?.toString() ?: "[图片]"
                        eventKind.contains("audio") -> payload["payload_inline"]?.toString() ?: "[音频]"
                        eventKind.contains("video") -> payload["payload_inline"]?.toString() ?: "[视频]"
                        else -> payload["payload_inline"]?.toString() ?: ""
                    }
                }

                val msg = TerminalMessage(
                    id = payload["id"]?.toString() ?: "ws-${System.currentTimeMillis()}",
                    direction = direction,
                    content = content,
                    timestamp = event.occurredAt ?: "",
                    seqNo = seqNo,
                    eventKind = eventKind,
                    mimeType = mimeType,
                    artifactObjectId = artifactObjectId
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
                    // 收到 output 后，若仍处于 PROCESSING，说明后端已响应，切回 LISTENING 等待下一轮输入
                    if (_uiState.value.interactionMode == InteractionMode.PROCESSING) {
                        _uiState.update { it.copy(interactionMode = InteractionMode.LISTENING) }
                    }
                    // 只对有文本内容且非纯媒体的消息 TTS 播报 + 眼镜端显示
                    val hasTextContent = content.isNotBlank() && content != "[图片]" && content != "[音频]" && content != "[视频]" && content != "[文件]"
                    if (hasTextContent) {
                        CxrApi.getInstance().sendGlobalTtsContent(content)
                        sendTextToTeleprompter(content)
                    }
                }
            }
            "run.completed" -> {
                _uiState.update { it.copy(isRunning = false, isCompleted = true, runStatus = "succeeded") }
                closeTeleprompter()
                repository.disconnectWebSocket()
            }
            "run.failed" -> {
                _uiState.update { it.copy(isRunning = false, error = "运行失败", runStatus = "failed") }
                closeTeleprompter()
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
        val videoStoppedAt = System.currentTimeMillis()
        val recorder = mp4Recorder
        val videoPath = recorder?.getOutputPath()
        recorder?.stopRecording()
        mp4Recorder = null

        Log.e("PSOP_PERF", "[视频] 录像停止, file=$videoPath, timestamp=$videoStoppedAt")
        Log.d(TAG, "Video recording stopped, file: $videoPath")

        // 上传视频文件
        viewModelScope.launch {
            try {
                val videoFile = if (videoPath != null) File(videoPath) else null
                if (videoFile != null && videoFile.exists() && videoFile.length() > 1024) {
                    val fileReadyAt = System.currentTimeMillis()
                    Log.e("PSOP_PERF", "[视频] 文件就绪, fileSize=${videoFile.length() / 1024}KB, 距录像停止=${fileReadyAt - videoStoppedAt}ms")
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

                        Log.e("PSOP_PERF", "[视频] 开始上传, 距录像停止=${System.currentTimeMillis() - videoStoppedAt}ms")
                        doUploadTerminalFile(runId, videoFile, "现场录像取证")
                        val uploadDoneAt = System.currentTimeMillis()
                        Log.e("PSOP_PERF", "[视频] 上传完成, 端到端总耗时=${uploadDoneAt - videoStoppedAt}ms")

                        // 上传成功，直接显示视频（用本地文件路径）
                        val videoPart = MessagePart(
                            partId = "local-${videoFile.name}",
                            kind = "video",
                            mimeType = "video/mp4",
                            contentUrl = "file://${videoFile.absolutePath}"
                        )
                        _uiState.update { state ->
                            val updatedMessages = state.messages.map {
                                if (it.id == uploadMsgId) it.copy(
                                    content = "",
                                    parts = listOf(videoPart)
                                )
                                else it
                            }
                            state.copy(messages = updatedMessages)
                        }
                        Log.d(TAG, "Video uploaded successfully")
                        // 不删除文件，保留给 UI 播放
                    }
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

    // ===== ASR 离线识别 =====

    /**
     * 初始化离线 ASR 引擎（应在 Activity 创建时调用）
     */
    fun initAsrEngine() {
        val success = SherpaAsrEngine.initialize(getApplication())
        if (!success) {
            Log.e(TAG, "ASR engine init failed! Model path: ${SherpaAsrEngine.getModelPath(getApplication())}")
        }
    }

    /**
     * 使用 Sherpa-ONNX 离线引擎识别 PCM 音频数据
     */
    private suspend fun transcribeAudio(pcmData: ByteArray): String {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val result = SherpaAsrEngine.recognize(pcmData)
            val elapsed = System.currentTimeMillis() - startTime
            Log.d("PSOP_PERF", "ASR offline recognition: ${elapsed}ms, text=$result")
            result
        }
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
        // 停止正在进行的媒体同步
        if (isMediaSyncInProgress) {
            CxrApi.getInstance().stopSync()
            isMediaSyncInProgress = false
        }
        // 清理 WiFi P2P 资源（媒体文件同步用）
        if (isMediaP2PConnected) {
            CxrApi.getInstance().deinitWifiP2P()
            isMediaP2PConnected = false
        }
        CxrApi.getInstance().setMediaFilesUpdateListener(null)
        stopAsrListening()
        CxrApi.getInstance().setAiEventListener(null)
        closeTeleprompter()
        repository.disconnectWebSocket()
    }
}
