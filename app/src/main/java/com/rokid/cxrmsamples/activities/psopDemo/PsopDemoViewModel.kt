package com.rokid.cxrmsamples.activities.psopDemo

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.cxr.client.extend.CxrApi
import android.util.Base64
import com.rokid.cxr.client.extend.callbacks.SendStatusCallback
import com.rokid.cxr.client.extend.callbacks.SyncStatusCallback
import com.rokid.cxr.client.extend.callbacks.UnsyncNumResultCallback
import com.rokid.cxr.client.extend.callbacks.WifiP2PStatusCallback
import com.rokid.cxr.client.extend.callbacks.GlassInfoResultCallback
import com.rokid.cxr.client.extend.infos.IconInfo
import com.rokid.cxr.client.extend.listeners.AiEventListener
import com.rokid.cxr.client.extend.listeners.AudioStreamListener
import com.rokid.cxr.client.extend.listeners.MediaFilesUpdateListener
import com.rokid.cxr.client.extend.listeners.MediaStreamListener
import com.rokid.cxr.client.extend.listeners.BatteryLevelUpdateListener
import com.rokid.cxr.client.utils.ValueUtil
import com.rokid.cxrmsamples.activities.liveVideo.LiveVideoMp4Recorder
import com.rokid.cxrmsamples.activities.mediaFile.P2PListener
import com.rokid.cxrmsamples.activities.mediaFile.P2PUtils
import com.rokid.cxrmsamples.asr.SherpaAsrEngine
import com.rokid.cxrmsamples.tts.OfflinePhoneTts
import com.rokid.cxrmsamples.dataBeans.selfView.ImageViewProps
import com.rokid.cxrmsamples.dataBeans.selfView.LinearLayoutProps
import com.rokid.cxrmsamples.dataBeans.selfView.SelfViewJson
import com.rokid.cxrmsamples.dataBeans.selfView.TextViewProps
import com.rokid.cxrmsamples.dataBeans.selfView.UpdateViewJson
import com.rokid.cxrmsamples.network.ConnectionState
import com.rokid.cxrmsamples.network.PsopConfig
import com.rokid.cxrmsamples.network.PsopRepository
import com.rokid.cxrmsamples.network.models.RunResponse
import com.rokid.cxrmsamples.network.models.SkillSummaryResponse
import com.rokid.cxrmsamples.network.models.TaskStatusResponse
import com.rokid.cxrmsamples.network.models.WebSocketEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
import java.util.Locale


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
    val eventKind: String = "terminal.multimodal.input.v1",
    val mimeType: String = "text/plain",
    val artifactObjectId: String? = null,
    val parts: List<MessagePart> = emptyList(),
    val uploadStatus: String = "none" // "none" | "uploading" | "success" | "failed"
)

enum class InteractionMode {
    IDLE,            // 未启动
    LISTENING,       // 语音监听中（waiting_input 状态下可长按说话）
    PROCESSING,      // 后端处理中
    PHOTO_CAPTURE,   // 拍照中
    PHOTO_CONFIRM,   // 拍照完成，等待用户在眼镜端确认（长按 TouchPad 确认）
    VIDEO_RECORDING, // 录像中
    COMPLETED        // 巡检完成
}

/** 眼镜端文字显示模式：CUSTOM_VIEW=现有分段轮播；TELEPROMPTER=提词器场景（WordTips）整段展示 */
enum class GlassesDisplayMode {
    CUSTOM_VIEW,
    TELEPROMPTER
}

/** PSOP 任务工作方式：眼镜模式保留既有完整能力；手机模式不触发任何眼镜端调用。 */
enum class PsopOperatingMode {
    GLASSES,
    MOBILE
}

enum class InspectionScreen {
    MODE_SELECTION,
    HOME,
    SKILL_LIST,
    /** 手机端任务详情：只介绍任务并提供启动入口，不展示历史运行记录。 */
    MOBILE_SKILL_DETAIL,
    INVOCATION_LIST,
    HISTORY,
    INTERACTION
}

enum class RunListScope { SKILL, ALL }

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
    val currentScreen: InspectionScreen = InspectionScreen.MODE_SELECTION,
    val operatingMode: PsopOperatingMode = PsopOperatingMode.GLASSES,
    val skills: List<SkillSummaryResponse> = emptyList(),
    val selectedSkill: SkillSummaryResponse? = null,
    val invocations: List<RunResponse> = emptyList(),
    /** 首页“继续巡检”目标：本机最近打开且仍在运行的任务，或最近更新的运行中任务。 */
    val homeResumeRun: RunResponse? = null,
    /** 首页“最近巡检”目标：所有状态中最后更新的一条任务。 */
    val homeRecentRun: RunResponse? = null,
    val isLoadingHomeRuns: Boolean = false,
    /** 仅在 CXR SDK 成功返回时展示，避免用占位电量误导现场人员。 */
    val glassBatteryLevel: Int? = null,
    val isGlassCharging: Boolean? = null,
    /** 首页只根据 SDK 当前连接状态显示“已连接”，不把已保存设备误当作已连接。 */
    val isGlassesConnected: Boolean = false,
    val isLoadingSkills: Boolean = false,
    val isLoadingInvocations: Boolean = false,
    val runStatusFilter: String = "running",
    val runListScope: RunListScope = RunListScope.SKILL,
    val taskStatus: TaskStatusResponse? = null,  // 任务进度状态
    val isTtsPlaying: Boolean = false,  // 眼镜端 TTS 是否正在播报（用于显示"停止播报"按钮）
    /** 手机端离线播报的完成序号，仅供手机 AR 提示卡在播报结束后自动收起。 */
    val phoneTtsCompletionSequence: Long = 0L,
    // ===== 眼镜端长文本轮播控制状态（供手机端翻页/暂停按钮显隐与页码显示） =====
    val carouselIndex: Int = 0,          // 当前轮播段索引（0 起）
    val carouselTotalCount: Int = 0,     // 轮播总段数（<=1 表示无多段轮播，控制区隐藏）
    val isCarouselPaused: Boolean = false, // 轮播是否被手动暂停
    // 眼镜端文字显示模式（默认 CustomView 轮播，可切换提词器场景）
    val glassesDisplayMode: GlassesDisplayMode = GlassesDisplayMode.CUSTOM_VIEW
)

class PsopDemoViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = PsopRepository()

    private val _uiState = MutableStateFlow(PsopDemoUiState())
    val uiState: StateFlow<PsopDemoUiState> = _uiState.asStateFlow()

    // 仅在手机模式首次收到 AI 回复时初始化；眼镜模式不创建离线 TTS 资源。
    private var phoneOfflineTts: OfflinePhoneTts? = null

    /** 本地已收到的最大 seq_no */
    private var lastSeqNo: Int = 0

    companion object {
        private const val TAG = "PsopDemoVM"
        private val TERMINAL_STATES = setOf("succeeded", "failed", "cancelled", "aborted")
        private const val HOME_RUN_PREFERENCES = "psop_home_run"
        private const val LAST_OPENED_ACTIVE_RUN_ID = "last_opened_active_run_id"
    }

    // ===== ASR 相关字段 =====
    private var isAsrActive = false
    private var heartbeatJob: Job? = null
    private var incrementalAsrJob: Job? = null
    @Volatile private var lastSpeechTime = 0L        // VAD：最近一次检测到说话的时间戳
    @Volatile private var hasDetectedSpeech = false   // VAD：本次录音是否检测到过说话
    private var asrStartTime = 0L                     // VAD：录音开始时间（无人说话超时用）
    private var lastVadLogTime = 0L                   // VAD：RMS日志节流（每秒一次，调阈值用）
    private val audioBuffer = ByteArrayOutputStream()

    // ===== 拍照确认相关字段 =====
    private var pendingPhotoFile: File? = null            // 暂存硬件同步下来的照片文件
    private var pendingPhotoMsgId: String? = null         // 暂存拍照对应的消息 ID
    private var pendingStatusMsgId: String? = null        // 暂存临时状态消息 ID（流程结束后清理）
    private var photoConfirmTimeoutJob: Job? = null       // 确认超时计时器（10 秒自动取消）
    private var photoPrepareJob: Job? = null              // 确认等待期提前压缩任务
    private var prePreparedPhotoFile: File? = null        // 提前压缩好的照片文件（确认后可直接上传）
    private var isPhotoConfirmViewOpen = false             // 确认 CustomView 是否已打开
    @Volatile private var expectProgrammaticClose = false  // 程序化关闭 CustomView 进行中（防止异步 onClosed/SceneStatus 回调被误判为系统手势关闭）
    private var expectCloseGuardResetJob: Job? = null      // 上述标记的短时自动复位 Job
    @Volatile private var customViewOpenAcked = false      // 是否已收到 onOpened 回执（用于判定 open/close 指令是否送达）
    private var photoConfirmFallbackJob: Job? = null       // 确认界面独立兜底关闭 Job（到期强制关闭，防界面永久残留）
    private var closeCustomViewRetryJob: Job? = null       // closeCustomView 延时补发 Job
    private var openCustomViewRetryJob: Job? = null        // openCustomView 延时补发 Job

    /** 拍照确认超时时间（毫秒）— CustomView 保持打开，用户在此期间长按 TouchPad 确认 */
    private val PHOTO_CONFIRM_TIMEOUT_MS = 15_000L

    // ===== 录像相关字段 =====
    private var videoRecordingJob: Job? = null
    private var mp4Recorder: LiveVideoMp4Recorder? = null
    private var isVideoStreamOpen = false

    // ===== 眼镜端图片轮播相关 =====
    private var slideshowJob: Job? = null
    private var slideshowIconNames: List<String> = emptyList()  // 已上传的图片 icon name 列表
    private var slideshowIndex: Int = 0

    // ===== 硬件拍照 WiFi P2P 同步相关 =====
    private var hwPhotoSyncMsgId: String? = null    // 硬件拍照同步对应的消息 ID
    private var hwPhotoConnectTimeoutJob: Job? = null  // 建连阶段等待提示计时器（到点仅更新文案，不判败）
    private var hwPhotoDiscoveryTtlJob: Job? = null    // 建连 TTL 终判计时器（到期未连成才真正判败）
    private var hwDiscoveryBusyRetryJob: Job? = null   // 发现 BUSY 延时重发现重试 Job（teardown/终态出口取消，防陈旧重试）
    private var preConnectWatchdogJob: Job? = null     // 预热轮整体看门狗（覆盖 init 响应+发现+建连全阶段，teardown 必须取消）
    private var periodicPreConnectJob: Job? = null     // 周期预热协程句柄（failFast 判败后定时再试，teardown/onCleared 必须取消）
    private var hwPhotoSyncTimeoutJob: Job? = null  // 文件同步阶段超时计时器
    private var hwPhotoSyncLivenessJob: Job? = null // 传输阶段活性探测计时器（无同步响应则判败重来）
    private var hwSyncActiveSeen = false            // 本轮同步是否已收到任何活性信号（onSyncStart/onSingleFileSynced）
    private var hwSyncFileSeen = false              // 本轮是否收到过文件回调（onSyncFinished 统一消费时的重试判据）
    private var hwSyncFullRetryUsed = false         // 活性判败后整轮重来（init+发现+连接）是否已用过（最多 1 次）
    private var hwPhotoSyncRound = 0                // 同步轮次 token：每次 syncHardwarePhoto/活性重来递增，隔离旧轮次 SDK 回调
    private var isHwPhotoSyncing = false             // 是否正在 P2P 同步中
    // 硬件拍照入口占位：回调可能来自非主线程，统一切到 Main 后先置位，
    // 再启动 3s 写盘等待，防止相邻回调各自启动一套 P2P 同步并覆盖共享状态。
    private var isHwPhotoRoundClaimed = false
    // ===== 拍照轮次串行排队（管线在途时新拍照键不打断，排队等当前轮终态后自动再来一轮） =====
    // 布尔足够无需真队列：startSync2 带回眼镜端全部未同步照片，排队轮一次消费全部，任何照片最多延迟一轮
    private var hwPhotoRoundPending = false          // 管线在途期间有新拍照键到达 → true，终态出口统一出队
    private var hwPhotoQueuedCount = 0               // 排队期间按键次数（仅气泡展示"还有 N 张待回传"，不参与调度）
    private var hwPhotoAutoRoundCount = 0            // 连续自动轮计数（防空轮无限链，上限 HW_PHOTO_AUTO_ROUND_LIMIT，手动按键重置）
    private val HW_PHOTO_AUTO_ROUND_LIMIT = 5        // 自动轮上限：达到后暂停自动回传（手动按键恢复）
    // 照片管线窗口内整条排队的 WS output 消息（含消息对象本身）：
    // 管线全程（拍照键按下→同步→自动确认→上传→重建完成）P2P 控制命令与重建握手走 BLE，
    // 消息的任何 BLE 副作用（入列表后的眼镜显示/TTS）都会与之争抢通道；
    // 故窗口内消息整条入队（不入列表/不显示/不 TTS），管线终态后由 flushQueuedPhotoMessages()
    // 按到达顺序逐条完成（入列表→眼镜显示→TTS）。读写均在 Main 线程
    private val pendingMessagesDuringPhoto = mutableListOf<TerminalMessage>()
    private val PHOTO_PIPELINE_QUEUE_CAPACITY = 50  // 队列容量上限：超限丢最旧并记日志，防泄漏
    private val HW_PHOTO_SYNC_DIR = "/sdcard/Download/Rokid/Media/"
    private val HW_PHOTO_CONNECT_TIMEOUT_MS = 15_000L   // 建连阶段等待预算（init+发现+连接）：到点仅更新等待文案不判败（终判交给 TTL）；
                                                        // 依据：实测 WPS 慢路径 5s，总建连 10.04s 曾被 10s 超时抢跑 41ms 误杀
    private val HW_PHOTO_DISCOVERY_TTL_MS = 90_000L     // 建连 TTL 终判窗口：从拍照触发起到期仍未连成才真正判败；
                                                        // 依据：固件“广播窗口”实测 43~52s 开一次窗，90s 覆盖至少一个窗口周期
    private val PRE_CONNECT_PERIOD_INTERVAL_MS = 18_000L // 周期预热轮次间隔（每轮 failFast 判败后等待再试，捕获固件广播窗口）
    private val HW_DISCOVERY_BUSY_RETRY_MS = 4_000L      // 硬件轮发现 BUSY(reason=2) 后延时重发发现的间隔（终判交给 TTL，不新建机制）
    private val PRE_CONNECT_WATCHDOG_MS = 25_000L        // 预热轮整体看门狗总时限（覆盖 init 响应+发现+建连，防 BLE 静默卡死停摆）
    private val HW_PHOTO_EMPTY_SYNC_MSG = "已连接眼镜但未取到照片（可能已失效），请重拍"  // 空同步/零待同步专用文案（区别于建连类失败）
    private val HW_PHOTO_SYNC_TIMEOUT_MS = 15_000L      // 文件同步阶段超时
    private val HW_PHOTO_SYNC_LIVENESS_MS = 5_500L      // 传输阶段活性探测窗口（无响应判败重来）
    private val P2P_CMD_RETRY_DELAY_MS = 1_200L         // P2P 控制命令失败重试延时（与 TTS 重试风格一致）

    /** 临时开关：是否在眼镜端显示照片确认界面（缩略图+长按确认）。
     *  当前设为 false：拍照后不再向眼镜投射确认界面，同步完成后走 1 秒自动确认上传。
     *  恢复显示时改回 true 即可（被开关绕过的代码均原地保留）。 */
    private val ENABLE_GLASSES_PHOTO_CONFIRM_VIEW = false

    // ===== P2P 预连接（进入对话页时建连，拍照时直接用，省去发现+连接耗时） =====
    private var cachedP2pIp: String? = null          // 缓存的 P2P 连接 IP
    private var isP2pPreConnected = false            // P2P 是否已预连接
    @Volatile private var isP2pConnecting = false        // P2P 建连进行中（预热/慢速路径互斥，任一时刻只允许一套流程）
    @Volatile private var p2pPreConnectActive = false    // 当前建连流程是否为预热（被慢速路径接管后置 false，使预热陈旧回调失效）
    private var hwConnectStartMs = 0L                  // 硬件轮 connect 发起时间戳（诊断：onConnected 时算建连耗时）

    // ===== P2P 连接常驻状态机（连接常驻复用 + 上传感知拆组重建） =====
    // IDLE=未建连；ESTABLISHING=建连中（预热/重建）；ESTABLISHED=已建连（cachedP2pIp 快路径主路径）；
    // TORN_DOWN_FOR_UPLOAD=同步成功后已拆组（P2P 组抢占 Wi-Fi 路由会拖垮上传），等上传终态重建；
    // RECONNECTING=rebuildPersistentConnection 三级重建中。
    // 读写均在 Main 线程（与既有 isP2pConnecting 等共享状态同款约束）
    private enum class P2pPersistState { IDLE, ESTABLISHING, ESTABLISHED, TORN_DOWN_FOR_UPLOAD, RECONNECTING }
    private var p2pPersistState = P2pPersistState.IDLE
    // 眼镜 P2P 设备快照（重建 Stage 1 按缓存 name/MAC discover+connect，优先不 initWifiP2P2
    // 避免触发固件 MAC 轮换）；存 ViewModel 侧——teardownP2P 的 clearTargetDevice 会清 P2PUtils 单例里的
    private var lastKnownP2pName: String? = null
    private var lastKnownP2pMac: String? = null
    private var rebuildStageJob: Job? = null           // 重建 Stage 1/2 限时 Job（每段独立 arm，teardown 必须取消）
    private var rebuildStageStartMs = 0L               // 重建当前 Stage 发起时间戳（诊断耗时）
    private val P2P_REBUILD_STAGE1_MS = 10_000L        // 重建 Stage 1 限时（不 init，按快照 discover+connect）
    private val P2P_REBUILD_STAGE2_MS = 25_000L        // 重建 Stage 2 限时（完整 init，与预热看门狗同预算）

    // ===== TTS 播报队列（避免连续消息时第二条被丢弃） =====
    // 队列/状态操作统一收敛到 Main 线程串行执行（见 enqueueTts/processNextTts），避免线程竞争
    private val ttsQueue = ArrayDeque<String>()
    @Volatile private var isTtsPlaying = false
    private var ttsEstimatedEndJob: Job? = null          // 预估播报时长定时器
    private var ttsWatchdogJob: Job? = null              // 防卡死保护 Job
    private var ttsRetryJob: Job? = null                 // 发送失败后的延时重试 Job
    private var ttsRetryCount = 0                        // 当前文本连续发送失败的重试次数
    private var ttsStaleEndsToIgnore = 0             // 抢占打断后待忽略的陈旧"0"结束回调数量（吸收多条补发回调）
    private var ttsPreemptFlagResetJob: Job? = null
    private val TTS_MAX_RETRY = 2                        // 发送失败最大重试次数
    private val TTS_RETRY_DELAY_MS = 1_200L              // 重试延时（等 BLE 通道空闲）
    /** TTS 预估播报时长：中文约 4 字/秒，额外加 1 秒缓冲，上限 60 秒 */
    private fun estimateTtsDurationMs(text: String): Long {
        val chars = text.length.coerceAtLeast(4)
        return (chars / 4.0 * 1000 + 1000).toLong().coerceIn(2000L, 60_000L)
    }

    /**
     * 标记抢占打断：接下来 staleEnds 条陈旧"0"结束回调将被逐条忽略。
     * 5 秒兜底复位（陈旧回调始终未补发时，避免误吞后续正常结束回调）
     */
    private fun armTtsPreemptGuard(staleEnds: Int) {
        ttsStaleEndsToIgnore += staleEnds
        ttsPreemptFlagResetJob?.cancel()
        ttsPreemptFlagResetJob = viewModelScope.launch(Dispatchers.Main) {
            delay(5_000L)
            ttsStaleEndsToIgnore = 0
        }
    }

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

    // ===== 眼镜硬件拍照按钮 → WiFi P2P 同步硬件拍摄的照片 =====

    /**
     * 眼镜硬件拍照后，媒体文件更新监听器
     * 通过 WiFi P2P 同步硬件拍摄的照片（保证照片是快门那一刻的，不会因人移动而拍错）
     */
    private val mediaFilesUpdateListener = MediaFilesUpdateListener {
        // CXR 回调线程不固定。所有拍照轮状态都收敛到 Main，保证“检查 + 占位”原子执行。
        viewModelScope.launch(Dispatchers.Main.immediate) {
            onHardwarePhotoCaptured()
        }
    }

    /** Main 线程上的眼镜硬件拍照入口：当前轮先占位，后续回调只排队。 */
    private fun onHardwarePhotoCaptured() {
        Log.d(TAG, "onMediaFilesUpdated: glasses hardware photo button pressed")
        Log.e("PSOP_DEBUG", ">>> HW BUTTON: runId=${_uiState.value.runId}, mode=${_uiState.value.interactionMode}")
        if (_uiState.value.runId == null) {
            Log.w(TAG, "No active run, ignoring hardware photo button")
            return
        }
        // 录像中：忽略（录像流程不可被拍照轮打断）
        if (_uiState.value.interactionMode == InteractionMode.VIDEO_RECORDING) {
            Log.w(TAG, "Busy (VIDEO_RECORDING), ignoring hardware photo button")
            return
        }
        // 排队守卫：照片管线窗口内（拍照/同步/确认/上传/重建全程）不打断在途流程——
        // 置排队标志，当前轮终态后由 tryStartQueuedPhotoRound() 自动再来一轮（startSync2
        // 带回全部未同步照片，不丢轮）。替代原 PHOTO_CAPTURE/PHOTO_CONFIRM 忽略分支，
        // 并精确拦住原入口缺陷场景（RECONNECTING 期间 mode=LISTENING 曾被直接放行导致卡死）
        if (isHwPhotoRoundClaimed || isPhotoPipelineActive()) {
            queueHardwarePhotoRound()
            return
        }
        startHardwarePhotoRound()
    }

    /** 将后续眼镜拍照合并为下一轮回传，不创建第二套同步/P2P 状态。 */
    private fun queueHardwarePhotoRound() {
        hwPhotoRoundPending = true
        hwPhotoQueuedCount++
        hwPhotoAutoRoundCount = 0  // 手动按键到达：重置自动轮计数（暂停状态也由此恢复）
        val roundMsgId = hwPhotoSyncMsgId
        if (roundMsgId != null) {
            _uiState.update { state ->
                state.copy(messages = state.messages.map {
                    if (it.id == roundMsgId) it.copy(content = "处理中，还有 $hwPhotoQueuedCount 张待回传…")
                    else it
                })
            }
        }
        Log.w(TAG, "photo round queued ($hwPhotoQueuedCount pending), mode=${_uiState.value.interactionMode}, p2pState=$p2pPersistState")
    }

    /** 启动一轮眼镜照片回传。调用方必须在 Main 线程，且会立即占位。 */
    private fun startHardwarePhotoRound() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Hardware photo round must start on Main" }
        isHwPhotoRoundClaimed = true
        viewModelScope.launch {
            Log.e("PSOP_DEBUG", ">>> HW BUTTON: switching to PHOTO_CAPTURE, starting P2P photo sync")
            _uiState.update { it.copy(interactionMode = InteractionMode.PHOTO_CAPTURE) }
            // 拍照前抢占 BLE：清空 TTS 队列并打断当前播报（不恢复），避免播报争抢无线通道拖慢照片同步
            stopTtsForPhotoCapture()
            delay(3000L)  // 等待眼镜端照片写入完成
            syncHardwarePhoto()
        }
    }

    /** 当前眼镜照片轮已到终态，释放入口占位；下一轮仍由既有排队出口启动。 */
    private fun releaseHardwarePhotoRound() {
        isHwPhotoRoundClaimed = false
    }

    // AI事件监听器 — 眼镜触摸板长按/释放触发
    private val aiEventListener = object : AiEventListener {
        override fun onAiKeyDown() {
            val currentMode = _uiState.value.interactionMode
            val currentStatus = _uiState.value.runStatus
            Log.d(TAG, "onAiKeyDown: mode=$currentMode, runStatus=$currentStatus")

            // 拍照确认模式：长按 TouchPad 直接确认上传（onAiKeyUp 不可靠，只用 onAiKeyDown）
            if (currentMode == InteractionMode.PHOTO_CONFIRM) {
                Log.e("PSOP_DEBUG", ">>> TOUCH DOWN in PHOTO_CONFIRM → confirmPhoto()")
                confirmPhoto()
                return
            }
            Log.e("PSOP_DEBUG", ">>> TOUCH DOWN in mode=$currentMode, starting ASR")

            // 其他模式：原有 ASR 逻辑
            // 防御性关闭：若 CustomView 仍在显示，立即关闭以释放 ASR
            closeTeleprompter()
            closePhotoConfirmView()
            viewModelScope.launch {
                startAsrListening()
            }
        }

        override fun onAiKeyUp() {
            val currentMode = _uiState.value.interactionMode
            Log.d(TAG, "onAiKeyUp: mode=$currentMode")
            Log.e("PSOP_DEBUG", ">>> TOUCH UP in mode=$currentMode")
            // 松手 → stopAsrListening 发 notifyAsrEnd 通知眼镜端结束录音 + 清理心跳/增量识别
            // 3秒断流已确认是 sendAsrContent 超时（增量ASR推送已修复），与 onAiKeyUp 无关，可安全恢复
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
                // VAD：检测当前帧语音能量，更新“最后说话时间”（用于判断说话结束）
                val rms = computeFrameRms(data, offset, size)
                val now = System.currentTimeMillis()
                if (now - lastVadLogTime > 1000) {  // 每秒打一次RMS（调阈值用）
                    Log.d(TAG, "VAD RMS: ${rms.toInt()}")
                    lastVadLogTime = now
                }
                if (rms > 200) {  // 语音能量阈值（从500调低）
                    if (!hasDetectedSpeech) Log.d(TAG, "VAD: speech detected")
                    lastSpeechTime = now
                    hasDetectedSpeech = true
                }
            }
        }

        override fun onAudioStreamFinish(p0: Int) {
            // 音频流结束 — 一次 ASR 识别完成
            Log.d(TAG, "ASR audio stream finished, isAsrActive=$isAsrActive")
            if (isAsrActive) {
                handleAsrComplete()   // 最终识别（buffer 已读出并重置）
                // onAiKeyUp 不可靠，用 onAudioStreamFinish 作为“松手/录音结束”的可靠信号：
                // 结束眼镜端 AI 场景（notifyAsrEnd + sendExitEvent 关弹窗）+ 清理心跳/增量识别
                // isAsrActive 守卫保证与 onAiKeyUp 路径不重复处理
                viewModelScope.launch {
                    stopAsrListening()
                }
            }
        }
    }

    init {
        // 注册 Rokid AI 事件监听器（眼镜触摸板长按触发 ASR）
        CxrApi.getInstance().setAiEventListener(aiEventListener)
        // 注册媒体文件更新监听器（眼镜硬件拍照按钮触发照片同步）
        CxrApi.getInstance().setMediaFilesUpdateListener(mediaFilesUpdateListener)
        // 注册 CustomView 监听器（检测 CustomView 何时被关闭，包括系统手势关闭）
        CxrApi.getInstance().setCustomViewListener(object : com.rokid.cxr.client.extend.listeners.CustomViewListener {
            override fun onIconsSent() { Log.e("PSOP_DEBUG", ">>> CustomView: onIconsSent") }
            override fun onOpened() {
                Log.e("PSOP_DEBUG", ">>> CustomView: onOpened")
                customViewOpenAcked = true
            }
            override fun onOpenFailed(p0: Int) {
                Log.e("PSOP_DEBUG", ">>> CustomView: onOpenFailed code=$p0")
                customViewOpenAcked = false
                isGlassesDisplayOpen = false
                val wasPhotoConfirm = _uiState.value.interactionMode == InteractionMode.PHOTO_CONFIRM
                isPhotoConfirmViewOpen = false
                glassesDisplayJob?.cancel()
                glassesDisplayJob = null
                photoConfirmTimeoutJob?.cancel()
                photoConfirmTimeoutJob = null
                photoConfirmFallbackJob?.cancel()
                photoConfirmFallbackJob = null
                openCustomViewRetryJob?.cancel()
                openCustomViewRetryJob = null
                if (wasPhotoConfirm) {
                    // 确认界面打开失败且 mode 停在 PHOTO_CONFIRM：走完整取消收敛，
                    // 复位 interactionMode 并清理暂存，避免长按 TouchPad 被劫持到 confirmPhoto
                    viewModelScope.launch { cancelPhotoConfirm() }
                }
            }
            override fun onUpdated() { Log.e("PSOP_DEBUG", ">>> CustomView: onUpdated") }
            override fun onClosed() {
                Log.e("PSOP_DEBUG", ">>> CustomView: onClosed, isPhotoConfirmViewOpen=$isPhotoConfirmViewOpen, expectProgrammaticClose=$expectProgrammaticClose, mode=${_uiState.value.interactionMode}")
                if (expectProgrammaticClose) {
                    // 程序化关闭（closeTeleprompter/closePhotoConfirmView）的异步回调：只复位打开回执，
                    // 不得当作系统手势处理；也不得清 isGlassesDisplayOpen（发起方已各自复位，
                    // 陈旧回执清标志会破坏守卫窗口内新开的提词器，导致后续自动关闭跳过 closeCustomView）
                    Log.e("PSOP_DEBUG", ">>> CustomView onClosed: programmatic close expected → ignore")
                    customViewOpenAcked = false
                    return
                }
                customViewOpenAcked = false
                if (isPhotoConfirmViewOpen && _uiState.value.interactionMode == InteractionMode.PHOTO_CONFIRM) {
                    Log.e("PSOP_DEBUG", ">>> CustomView closed by SYSTEM GESTURE (double-tap) → cancel photo")
                    isPhotoConfirmViewOpen = false
                    isGlassesDisplayOpen = false
                    glassesDisplayJob?.cancel()
                    glassesDisplayJob = null
                    photoConfirmTimeoutJob?.cancel()
                    photoConfirmTimeoutJob = null
                    viewModelScope.launch { cancelPhotoConfirm() }
                } else {
                    Log.e("PSOP_DEBUG", ">>> CustomView closed by SYSTEM GESTURE (not photo confirm) → reset teleprompter state")
                    isGlassesDisplayOpen = false
                    // 统一走 closeTeleprompter：取消轮播/自动关闭 Job 并重置轮播 UiState；
                    // isGlassesDisplayOpen 已置 false，其内部不会再调 closeCustomView，无递归风险
                    closeTeleprompter()
                }
            }
        })
        // 注册场景状态监听器（检测 isCustomViewRunning 变化）
        CxrApi.getInstance().setSceneStatusUpdateListener { sceneStatus ->
            if (isPhotoConfirmViewOpen) {
                Log.e("PSOP_DEBUG", ">>> SceneStatus: isCustomViewRunning=${sceneStatus.isCustomViewRunning}, isAiAssistRunning=${sceneStatus.isAiAssistRunning}")
                if (!sceneStatus.isCustomViewRunning && _uiState.value.interactionMode == InteractionMode.PHOTO_CONFIRM) {
                    if (expectProgrammaticClose) {
                        // 程序化关闭引起的场景状态变化，不是手势双击，不得取消照片确认
                        Log.e("PSOP_DEBUG", ">>> SceneStatus CustomView CLOSED is programmatic → ignore")
                    } else {
                        Log.e("PSOP_DEBUG", ">>> SceneStatus detected CustomView CLOSED → cancel photo (double-tap)")
                        isPhotoConfirmViewOpen = false
                        isGlassesDisplayOpen = false
                        glassesDisplayJob?.cancel()
                        glassesDisplayJob = null
                        photoConfirmTimeoutJob?.cancel()
                        photoConfirmTimeoutJob = null
                        viewModelScope.launch { cancelPhotoConfirm() }
                    }
                }
            }
            // 眼镜端双击退出提词器场景 = 临时清屏：保持 TELEPROMPTER mode 不变，仅复位场景标志；
            // 下一条消息到达时由 sendTextToWordTips 的“场景未开补开”防御自动重开并推送新内容。
            // mode 前置条件天然过滤手机端主动关闭的回声（toggle 主线程串行：先 closeWordTipsScene 后切 mode，
            // 回声异步到达 Main 时 mode 已为 CUSTOM_VIEW，不会命中本分支）。
            if (_uiState.value.glassesDisplayMode == GlassesDisplayMode.TELEPROMPTER && !sceneStatus.isWordTipsRunning) {
                // SDK 回调线程：状态更新投递 Main（与 TTS 状态监听同款写法）
                viewModelScope.launch(Dispatchers.Main) {
                    // Main 线程二次校验：mode 防陈旧事件与主动切换竞态；isWordTipsSceneOpen 防残留回声重复复位
                    if (_uiState.value.glassesDisplayMode == GlassesDisplayMode.TELEPROMPTER && isWordTipsSceneOpen) {
                        isWordTipsSceneOpen = false
                        Log.i(TAG, "WordTips scene exited on glasses (temp clear), keep TELEPROMPTER mode, will reopen on next message")
                    }
                }
            }
        }

        // 注册 TTS 状态监听器（跟踪播报开始/结束，驱动队列播放）
        CxrApi.getInstance().setGlassStatusUpdateListener(object : com.rokid.cxr.client.extend.listeners.GlassStatusUpdateListener {
            override fun onWearingStatusUpdated(p0: String?) {}
            override fun onGlassTempleStatusUpdated(p0: String?) {}
            override fun onGlassGlobalTtsStatusUpdated(status: String?) {
                Log.d(TAG, "TTS status updated: $status")
                // SDK 回调线程：只投递到 Main，TTS 状态机统一在 Main 线程串行处理
                viewModelScope.launch(Dispatchers.Main) {
                    if (status == "0") {
                        // 抢占打断后旧内容的结束回调先到达，逐条忽略，避免误消费新内容的队列/定时器
                        if (ttsStaleEndsToIgnore > 0) {
                            ttsStaleEndsToIgnore--
                            Log.d(TAG, "TTS end callback ignored (stale end, remaining=${ttsStaleEndsToIgnore})")
                            return@launch
                        }
                        // TTS 播报结束（回调优先于预估定时器），立即播放下一条
                        isTtsPlaying = false
                        ttsEstimatedEndJob?.cancel()
                        ttsEstimatedEndJob = null
                        processNextTts()
                    } else if (status == "1") {
                        // TTS 播报开始
                        isTtsPlaying = true
                    }
                }
            }
        })

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

        refreshGlassConnection()
        loadSkills()
        loadHomeRuns()
    }

    /** 从连接页返回首页时刷新，避免断开后首页仍显示“已连接”。 */
    fun refreshGlassConnection() {
        val connected = CxrApi.getInstance().isBluetoothConnected
        _uiState.update {
            it.copy(
                isGlassesConnected = connected,
                glassBatteryLevel = if (connected) it.glassBatteryLevel else null,
                isGlassCharging = if (connected) it.isGlassCharging else null
            )
        }
        if (connected) refreshGlassBattery()
    }

    private fun refreshGlassBattery() {
        CxrApi.getInstance().getGlassInfo(GlassInfoResultCallback { status, info ->
            if (status == ValueUtil.CxrStatus.RESPONSE_SUCCEED && info != null) {
                _uiState.update {
                    it.copy(
                        glassBatteryLevel = info.batteryLevel.takeIf { level -> level in 0..100 },
                        isGlassCharging = info.isCharging
                    )
                }
            }
        })
        CxrApi.getInstance().setBatteryLevelUpdateListener(
            BatteryLevelUpdateListener { level, isCharging ->
                _uiState.update {
                    it.copy(
                        glassBatteryLevel = level.takeIf { battery -> battery in 0..100 },
                        isGlassCharging = isCharging
                    )
                }
            }
        )
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
                Log.d(TAG, "Loading skills from API...")
                val skills = repository.listSkills()
                Log.d(TAG, "Skills loaded successfully: ${skills.size} items, first=${skills.firstOrNull()?.name}")
                _uiState.update { it.copy(skills = skills, isLoadingSkills = false) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load skills: ${e.javaClass.simpleName}: ${e.message}", e)
                _uiState.update { it.copy(isLoadingSkills = false, error = "加载技能列表失败: ${e.message}") }
            }
        }
    }

    fun selectSkill(skill: SkillSummaryResponse) {
        val isMobileMode = _uiState.value.operatingMode == PsopOperatingMode.MOBILE
        _uiState.update {
            it.copy(
                selectedSkill = skill,
                currentScreen = if (isMobileMode) {
                    InspectionScreen.MOBILE_SKILL_DETAIL
                } else {
                    InspectionScreen.INVOCATION_LIST
                },
                runListScope = RunListScope.SKILL
            )
        }
        // 手机任务详情只借助运行状态判断是否有可继续的任务，不在此展示历史列表。
        loadRuns(skill.id)
    }

    fun selectOperatingMode(mode: PsopOperatingMode) {
        _uiState.update {
            it.copy(
                operatingMode = mode,
                currentScreen = InspectionScreen.HOME,
                error = null
            )
        }
        loadHomeRuns()
    }

    fun openModeSelection() {
        _uiState.update { it.copy(currentScreen = InspectionScreen.MODE_SELECTION, error = null) }
    }

    fun openHome() {
        _uiState.update { it.copy(currentScreen = InspectionScreen.HOME, error = null) }
        loadHomeRuns()
    }

    fun openSkillList() {
        _uiState.update { it.copy(currentScreen = InspectionScreen.SKILL_LIST, error = null) }
        if (_uiState.value.skills.isEmpty()) loadSkills()
    }

    fun openHistory(status: String = "running") {
        _uiState.update {
            it.copy(
                currentScreen = InspectionScreen.HISTORY,
                runStatusFilter = status,
                runListScope = RunListScope.ALL,
                error = null
            )
        }
        loadAllRuns(status)
    }

    private fun statusListFor(status: String?): List<String>? = when (status) {
        "running" -> listOf("running", "waiting_input")
        else -> status?.let { listOf(it) }
    }

    private fun isActiveRun(run: RunResponse): Boolean =
        run.status in setOf("queued", "waiting_runtime", "accepted", "running", "waiting_input", "finalizing")

    /** 新建或打开运行中任务时记录，首页“继续巡检”优先恢复该任务。 */
    private fun rememberLastOpenedActiveRun(runId: String) {
        getApplication<Application>()
            .getSharedPreferences(HOME_RUN_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(LAST_OPENED_ACTIVE_RUN_ID, runId)
            .apply()
    }

    private fun lastOpenedActiveRunId(): String? =
        getApplication<Application>()
            .getSharedPreferences(HOME_RUN_PREFERENCES, Context.MODE_PRIVATE)
            .getString(LAST_OPENED_ACTIVE_RUN_ID, null)

    private fun clearLastOpenedActiveRun() {
        getApplication<Application>()
            .getSharedPreferences(HOME_RUN_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove(LAST_OPENED_ACTIVE_RUN_ID)
            .apply()
    }

    /**
     * 后端运行列表没有约定排序时，首页始终按最后更新时间降序选择。
     * ISO 时间解析异常的记录保持服务端原始相对顺序，作为稳定降级。
     */
    private fun sortRunsByUpdatedAt(runs: List<RunResponse>): List<RunResponse> =
        runs.withIndex().sortedWith { left, right ->
            val leftTime = parseRunTimestamp(left.value.updatedAt)
            val rightTime = parseRunTimestamp(right.value.updatedAt)
            when {
                leftTime != null && rightTime != null -> rightTime.compareTo(leftTime)
                leftTime != null -> -1
                rightTime != null -> 1
                else -> left.index.compareTo(right.index)
            }
        }.map { it.value }

    private fun parseRunTimestamp(value: String): Long? {
        if (value.isBlank()) return null
        return try {
            java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
        } catch (_: Exception) {
            try {
                java.time.Instant.parse(value).toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun loadHomeRuns() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingHomeRuns = true) }
            try {
                val runs = sortRunsByUpdatedAt(repository.listRuns())
                val activeRuns = runs.filter(::isActiveRun)
                val storedRunId = lastOpenedActiveRunId()
                val resumeRun = activeRuns.firstOrNull { it.id == storedRunId }
                    ?: activeRuns.firstOrNull()
                if (storedRunId != null && resumeRun?.id != storedRunId) {
                    // 本机记录已终态或不可见，移除后避免下次继续尝试陈旧任务。
                    clearLastOpenedActiveRun()
                }
                _uiState.update {
                    it.copy(
                        homeResumeRun = resumeRun,
                        homeRecentRun = runs.firstOrNull(),
                        isLoadingHomeRuns = false
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load home runs", e)
                _uiState.update { it.copy(isLoadingHomeRuns = false) }
            }
        }
    }

    fun loadRuns(skillId: String, status: String? = _uiState.value.runStatusFilter) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingInvocations = true, runStatusFilter = status ?: "") }
            try {
                // 将筛选值转换为 API 所需的状态数组
                // "运行中" 对应 running + waiting_input
                val statusList = statusListFor(status)
                val runs = repository.listRuns(skillId, statusList)
                Log.d(TAG, "Runs loaded: ${runs.size} items, status=$statusList")
                _uiState.update { it.copy(invocations = runs, isLoadingInvocations = false) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load runs", e)
                _uiState.update { it.copy(isLoadingInvocations = false, error = "加载记录失败: ${e.message}") }
            }
        }
    }

    fun loadAllRuns(status: String = _uiState.value.runStatusFilter) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingInvocations = true, runStatusFilter = status) }
            try {
                // “全部技能”仅指当前可用的技能集合；已删除或禁用而不再出现在技能列表中的记录不展示。
                val availableSkills = repository.listSkills()
                val availableSkillIds = availableSkills.mapTo(mutableSetOf()) { it.id }
                val runs = repository.listRuns(status = statusListFor(status))
                    .filter { it.skillDefinitionId in availableSkillIds }
                _uiState.update {
                    it.copy(
                        skills = availableSkills,
                        invocations = runs,
                        isLoadingInvocations = false
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load all runs", e)
                _uiState.update { it.copy(isLoadingInvocations = false, error = "加载历史记录失败: ${e.message}") }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun navigateBack() {
        val current = _uiState.value.currentScreen
        when (current) {
            InspectionScreen.MODE_SELECTION -> Unit
            InspectionScreen.SKILL_LIST, InspectionScreen.HISTORY -> openHome()
            InspectionScreen.MOBILE_SKILL_DETAIL, InspectionScreen.INVOCATION_LIST ->
                _uiState.update { it.copy(currentScreen = InspectionScreen.SKILL_LIST) }
            InspectionScreen.INTERACTION -> {
                val returnToHistory = _uiState.value.runListScope == RunListScope.ALL
                _uiState.update {
                    it.copy(
                        currentScreen = when {
                            returnToHistory -> InspectionScreen.HISTORY
                            it.operatingMode == PsopOperatingMode.MOBILE -> InspectionScreen.MOBILE_SKILL_DETAIL
                            else -> InspectionScreen.INVOCATION_LIST
                        }
                    )
                }
                if (returnToHistory) loadAllRuns() else _uiState.value.selectedSkill?.id?.let { loadRuns(it) }
                // 清理本次会话中的本地图片缓存
                cleanupPhotoCache()
                // 手机模式没有创建眼镜端 P2P 连接，避免触碰眼镜端状态。
                if (_uiState.value.operatingMode == PsopOperatingMode.GLASSES) {
                    teardownP2P()
                }
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

    // ========== TTS 播报队列 ==========

    /** 手机模式本地播报：不调用 CXR，也不会向眼镜发送任何文字。 */
    private fun speakOnPhone(text: String) {
        val tts = phoneOfflineTts ?: OfflinePhoneTts(getApplication<Application>()) {
            viewModelScope.launch(Dispatchers.Main) {
                _uiState.update { state ->
                    state.copy(phoneTtsCompletionSequence = state.phoneTtsCompletionSequence + 1)
                }
            }
        }.also { phoneOfflineTts = it }
        tts.speak(text)
    }

    /**
     * 将文本加入 TTS 队列，若当前空闲则立即播报，否则等待上一条播完后自动播报
     * @param preempt 是否抢占打断当前播报（新消息到达时使用，不等旧内容播完）
     */
    private fun enqueueTts(text: String, preempt: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            viewModelScope.launch(Dispatchers.Main) { enqueueTts(text, preempt) }
            return
        }
        if (preempt && (isTtsPlaying || ttsQueue.isNotEmpty())) {
            // 抢占：清空旧队列与定时器；紧随其后的 sendGlobalTtsContent 本身会打断眼镜端正在播报的内容
            Log.d(TAG, "TTS preempted by new message (queue=${ttsQueue.size}, playing=$isTtsPlaying)")
            if (isTtsPlaying) {
                // 标记抢占：旧内容被打断后 SDK 会补发一次结束回调，需忽略
                armTtsPreemptGuard(1)
            }
            ttsQueue.clear()
            ttsRetryCount = 0
            ttsRetryJob?.cancel(); ttsRetryJob = null
            ttsEstimatedEndJob?.cancel(); ttsEstimatedEndJob = null
            ttsWatchdogJob?.cancel(); ttsWatchdogJob = null
            isTtsPlaying = false
        }
        ttsQueue.add(text)
        Log.d(TAG, "TTS enqueued: '${text.take(30)}...', queueSize=${ttsQueue.size}, isPlaying=$isTtsPlaying")
        processNextTts()
    }

    /**
     * 长消息按段入队（约 160 字/段，与 CustomView 轮播段对齐），
     * 首段抢占：新消息直接打断旧内容开始播报；轮播间隔继续跟随 TTS 预估时长
     */
    private fun enqueueTtsForMessage(text: String) {
        val segments = splitTextToSegments(text.trim(), TEXT_SEGMENT_MAX_CHARS)
        segments.forEachIndexed { index, segment ->
            enqueueTts(segment, preempt = index == 0)
        }
    }

    /**
     * 手动停止 TTS 播报（SDK 无 stop API）：
     * 清空队列与所有定时器，并发送一条极短文本抢占打断眼镜端当前播报
     */
    fun stopTtsPlayback() {
        viewModelScope.launch(Dispatchers.Main) {
            val wasActive = isTtsPlaying || ttsQueue.isNotEmpty()
            ttsQueue.clear()
            ttsRetryCount = 0
            ttsRetryJob?.cancel(); ttsRetryJob = null
            ttsEstimatedEndJob?.cancel(); ttsEstimatedEndJob = null
            ttsWatchdogJob?.cancel(); ttsWatchdogJob = null
            isTtsPlaying = false
            _uiState.update { it.copy(isTtsPlaying = false) }
            if (wasActive) {
                // 陈旧回调防护：被打断内容的补发"0" + 占位符自己的"0"共两条，均需忽略，
                // 否则 800ms 内新消息到达时会被误当作新内容的正常结束 → 多段逐段被秒切
                armTtsPreemptGuard(2)
                try {
                    // 发送极短文本抢占，眼镜端会中断当前播报；后续状态已清空，其回调不会引发队列逻辑
                    CxrApi.getInstance().sendGlobalTtsContent("。")
                    Log.d(TAG, "TTS playback stopped by user (sent preempt placeholder)")
                } catch (e: Exception) {
                    Log.w(TAG, "stopTtsPlayback: failed to send preempt text", e)
                }
            }
        }
    }

    /**
     * 硬件拍照时抢占 BLE 通道：清空 TTS 队列并打断当前播报（不恢复）。
     * TTS 播报与 P2P/文件同步争抢无线通道会拖慢同步导致超时，拍照期间直接放弃未播内容。
     * 与确认窗口的 clearTtsQueueForPhotoConfirm 区分：后者不打断当前正在播报的段。
     */
    private fun stopTtsForPhotoCapture() {
        viewModelScope.launch(Dispatchers.Main) {
            val wasActive = isTtsPlaying || ttsQueue.isNotEmpty()
            ttsQueue.clear()
            ttsRetryCount = 0
            ttsRetryJob?.cancel(); ttsRetryJob = null
            ttsEstimatedEndJob?.cancel(); ttsEstimatedEndJob = null
            ttsWatchdogJob?.cancel(); ttsWatchdogJob = null
            isTtsPlaying = false
            _uiState.update { it.copy(isTtsPlaying = false) }
            if (wasActive) {
                // 陈旧回调防护：被打断内容的补发"0" + 占位符自己的"0"共两条，均需忽略（同 stopTtsPlayback）
                armTtsPreemptGuard(2)
                try {
                    // 发极短文本抢占打断眼镜端当前播报，避免播报继续争抢无线通道
                    CxrApi.getInstance().sendGlobalTtsContent("。")
                    Log.d(TAG, "TTS preempted for photo capture (queue cleared, not resumed)")
                } catch (e: Exception) {
                    Log.w(TAG, "stopTtsForPhotoCapture: failed to send preempt text", e)
                }
            }
        }
    }

    /**
     * 从队列取出下一条并播报；若正在播报则跳过。
     * 所有调用统一切到 Main 线程串行执行，保证"检查-取队列-置位"原子性
     */
    private fun processNextTts() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            viewModelScope.launch(Dispatchers.Main) { processNextTts() }
            return
        }
        if (isTtsPlaying) return
        val next = ttsQueue.removeFirstOrNull()
        if (next == null) {
            ttsRetryCount = 0
            if (_uiState.value.isTtsPlaying) {
                _uiState.update { it.copy(isTtsPlaying = false) }
            }
            return
        }
        Log.d(TAG, "TTS playing next: '${next.take(30)}...', remaining=${ttsQueue.size}")
        isTtsPlaying = true
        try {
            val status = CxrApi.getInstance().sendGlobalTtsContent(next)
            if (status != ValueUtil.CxrStatus.REQUEST_SUCCEED) {
                // BLE 通道争用（CustomView 等指令占用）：重新入队队首，延时重试
                Log.w(TAG, "TTS send returned $status, retry=$ttsRetryCount/$TTS_MAX_RETRY")
                isTtsPlaying = false
                if (ttsRetryCount < TTS_MAX_RETRY) {
                    ttsRetryCount++
                    ttsQueue.addFirst(next)
                    ttsRetryJob?.cancel()
                    ttsRetryJob = viewModelScope.launch(Dispatchers.Main) {
                        delay(TTS_RETRY_DELAY_MS)
                        processNextTts()
                    }
                } else {
                    // 重试耗尽：记录日志并继续处理下一条，不得卡死队列
                    Log.e(TAG, "TTS send failed after $TTS_MAX_RETRY retries, dropping: '${next.take(30)}...'")
                    ttsRetryCount = 0
                    processNextTts()
                }
                return
            }
            ttsRetryCount = 0
            if (!_uiState.value.isTtsPlaying) {
                _uiState.update { it.copy(isTtsPlaying = true) }
            }
        } catch (e: Exception) {
            // 异常防护：复位状态并继续消费队列，杜绝永久卡死
            Log.e(TAG, "TTS send exception, skipping segment", e)
            isTtsPlaying = false
            processNextTts()
            return
        }
        // 预估播报时长定时器：若回调未按时到达，主动触发下一条
        ttsEstimatedEndJob?.cancel()
        ttsWatchdogJob?.cancel()
        val estimatedMs = estimateTtsDurationMs(next)
        Log.d(TAG, "TTS estimated duration: ${estimatedMs}ms for ${next.length} chars")
        ttsEstimatedEndJob = viewModelScope.launch(Dispatchers.Main) {
            delay(estimatedMs)
            if (isTtsPlaying) {
                Log.d(TAG, "TTS estimated end reached, processing next")
                isTtsPlaying = false
                processNextTts()
            }
        }
        // 防卡死保护：超时未收到结束回调则强制重置（超时时长跟随预估时长，避免长文本被提前打断）
        val watchdogMs = maxOf(30_000L, estimatedMs + 10_000L)
        ttsWatchdogJob = viewModelScope.launch(Dispatchers.Main) {
            delay(watchdogMs)
            if (isTtsPlaying) {
                Log.w(TAG, "TTS stuck detected! Force resetting isTtsPlaying")
                isTtsPlaying = false
                ttsEstimatedEndJob?.cancel()
                ttsEstimatedEndJob = null
                processNextTts()
            }
        }
    }

    // ========== 眼镜端文字显示（CustomView 时间窗口模式，后期支持图片/视频） ==========

    /** CustomView 自动关闭延迟（秒），展示期间内收到新消息会重置计时器 */
    private val GLASSES_DISPLAY_TIMEOUT_MS = 15_000L
    private var glassesDisplayJob: Job? = null
    private var isGlassesDisplayOpen = false

    /** 长文本分段轮播 */
    private var textCarouselJob: Job? = null
    private var carouselSegments: List<String> = emptyList()  // 当前轮播的分段列表
    private var carouselIndex = 0                              // 当前显示段索引
    @Volatile private var isCarouselPaused = false             // 手动暂停标记
    private val TEXT_SEGMENT_MAX_CHARS = 160  // 每段最大字符数（约8行）
    private val TEXT_CAROUSEL_MIN_MS = 4_500L  // 轮播最小间隔（避免太短）

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
                                maxHeight = "480dp"  // 限制最大高度，避免超出眼镜屏幕被截断
                                text = " "
                                textColor = "#FFFFFF"
                                textSize = "15sp"
                                gravity = "start"
                            }.toJson()
                        },
                        SelfViewJson().apply {
                            type = "ImageView"
                            props = ImageViewProps().apply {
                                id = "contentImage"
                                layout_width = "match_parent"
                                layout_height = "200dp"
                                scaleType = "center_crop"
                            }.toJson()
                        },
                        SelfViewJson().apply {
                            type = "TextView"
                            props = TextViewProps().apply {
                                id = "imageCounter"
                                layout_width = "match_parent"
                                layout_height = "wrap_content"
                                text = ""
                                textColor = "#AAAAAA"
                                textSize = "12sp"
                                gravity = "center"
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

    // ========== 眼镜显示模式切换（CustomView 轮播 / 提词器场景 WordTips） ==========

    /** 提词器场景是否已开启（controlScene(WORD_TIPS, true) 后为 true） */
    private var isWordTipsSceneOpen = false

    /**
     * 切换眼镜端文字显示模式（按钮入口）。
     * 场景过渡：
     * - 切到 TELEPROMPTER：先取消当前轮播并关闭 CustomView 显示，再配置并开启提词器场景；
     * - 切回 CUSTOM_VIEW：退出提词器场景，后续消息恢复现有分段轮播。
     */
    fun toggleGlassesDisplayMode() {
        val next = when (_uiState.value.glassesDisplayMode) {
            GlassesDisplayMode.CUSTOM_VIEW -> GlassesDisplayMode.TELEPROMPTER
            GlassesDisplayMode.TELEPROMPTER -> GlassesDisplayMode.CUSTOM_VIEW
        }
        Log.i(TAG, "Glasses display mode switch: ${_uiState.value.glassesDisplayMode} -> $next")
        when (next) {
            GlassesDisplayMode.TELEPROMPTER -> {
                // 先尝试开启提词器场景，失败则不切模式（保留当前 CustomView 显示）
                if (!openWordTipsScene()) {
                    Log.w(TAG, "Switch to TELEPROMPTER aborted: WordTips scene open failed")
                    _uiState.update { it.copy(error = "提词器场景开启失败，请重试") }
                    return
                }
                // 边界：若正在轮播中，取消轮播 Job 并关闭 CustomView 显示（closeTeleprompter 内部完成）
                closeTeleprompter()
                // 切换成功后推送最后一条 AI 消息，提词器立即可见内容
                pushLastAiMessageToWordTips()
            }
            GlassesDisplayMode.CUSTOM_VIEW -> {
                closeWordTipsScene()
            }
        }
        _uiState.update { it.copy(glassesDisplayMode = next) }
    }

    /**
     * 切换到提词器时推送最后一条 AI 消息（direction == "output"）：
     * 按清洗模板去掉媒体占位符并 trim，非空才推送；无 AI 消息静默跳过。
     */
    private fun pushLastAiMessageToWordTips() {
        val lastAi = _uiState.value.messages.lastOrNull { it.direction == "output" } ?: return
        val cleaned = lastAi.content.replace("[图片]", "").replace("[音频]", "").replace("[视频]", "").replace("[文件]", "").trim()
        if (cleaned.isNotBlank()) {
            sendTextToWordTips(cleaned)
            Log.i(TAG, "pushed last AI message to WordTips on switch")
        }
    }

    /**
     * 开启提词器场景（参考 useTeleprompter 示例页）：
     * 先 configWordTipsText 配置参数，再 controlScene(WORD_TIPS, true)。
     * mode 取 "AI"：与实测可双击退出的示例页实际下发值一致（详见 mode 参数行注释）。
     */
    private fun openWordTipsScene(): Boolean {
        return try {
            CxrApi.getInstance().configWordTipsText(
                12f,     // textSize
                0f,      // lineSpace
                // mode 依据：可双击退出的示例页实际下发大写 "AI"（枚举 TeleprompterMode.AI("ai") 的构造参数被丢弃，
                // 下发用 .name 得到大写 "AI"，隐蔽陷阱）；双击退出手势为 AI 模式内建，normal 模式不接管触控板手势。
                // wiki 记载小写 "ai"/"normal" 语义，若真机大写不兼容可回退尝试小写 "ai"。
                "AI",    // mode
                // 显示区安全边距依据：实测左缘第一个字被水平裁切（眼镜端提词器画布 480×640，
                // 但实际可视区窄于 480，存在安全区/内部偏移）；X=23 为用户经官方示例页滑杆实测定值，
                // width=440，X+width=463 仍满足协议钳制上限 ≤480。
                23,      // startPointX（原 0→20→23，23 为用户经官方示例页滑杆实测定值）
                80,      // startPointY
                440,     // width（原 480，配合 startPointX=23 收缩，避免右缘越界）
                400      // height
            )
            val status = CxrApi.getInstance().controlScene(ValueUtil.CxrSceneType.WORD_TIPS, true, null)
            if (status == ValueUtil.CxrStatus.REQUEST_SUCCEED) {
                isWordTipsSceneOpen = true
                Log.d(TAG, "WordTips scene opened")
                true
            } else {
                Log.w(TAG, "WordTips scene open rejected by SDK: $status")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "WordTips scene open failed", e)
            false
        }
    }

    /** 退出提词器场景（幂等） */
    private fun closeWordTipsScene() {
        if (!isWordTipsSceneOpen) return
        try {
            CxrApi.getInstance().controlScene(ValueUtil.CxrSceneType.WORD_TIPS, false, null)
            Log.d(TAG, "WordTips scene closed")
        } catch (e: Exception) {
            Log.w(TAG, "WordTips scene close failed", e)
        }
        isWordTipsSceneOpen = false
    }

    /**
     * 文字消息按当前显示模式分发（实时/断线恢复/缓冲补发三条路径统一入口）：
     * - CUSTOM_VIEW：现有分段轮播逻辑（零改动）；
     * - TELEPROMPTER：提词器场景整段推送（sendStream WORD_TIPS），不做分段轮播。
     * TTS 播报不受模式影响，仍由调用方按原逻辑入队。
     */
    private fun displayGlassesText(text: String) {
        if (_uiState.value.operatingMode != PsopOperatingMode.GLASSES) {
            return
        }
        when (_uiState.value.glassesDisplayMode) {
            GlassesDisplayMode.CUSTOM_VIEW -> sendTextToTeleprompter(text)
            GlassesDisplayMode.TELEPROMPTER -> sendTextToWordTips(text)
        }
    }

    /** 提词器模式推送：整段文本经 sendStream(WORD_TIPS) 上传，替换上一条内容 */
    private fun sendTextToWordTips(text: String) {
        if (!isWordTipsSceneOpen) {
            // 防御：场景未开（如开启失败）时补开一次
            openWordTipsScene()
        }
        try {
            CxrApi.getInstance().sendStream(
                ValueUtil.CxrStreamType.WORD_TIPS,
                text.toByteArray(Charsets.UTF_8),
                "psop_msg_${System.currentTimeMillis()}.txt",
                wordTipsSendCallback
            )
            Log.d(TAG, "WordTips text pushed: '${text.take(50)}', length=${text.length}")
        } catch (e: Exception) {
            Log.w(TAG, "WordTips sendStream failed", e)
        }
    }

    /** 提词器文本上传结果回调（仅日志，不阻断流程） */
    private val wordTipsSendCallback = object : SendStatusCallback {
        override fun onSendSucceed() {
            Log.d(TAG, "WordTips stream send succeed")
        }

        override fun onSendFailed(code: ValueUtil.CxrSendErrorCode?) {
            Log.w(TAG, "WordTips stream send failed: $code")
        }
    }

    /** 更新眼镜端显示文字，长文本自动分段轮播，重置自动关闭计时器 */
    private fun sendTextToTeleprompter(text: String) {
        Log.d(TAG, "sendTextToTeleprompter: original='${text.take(50)}', length=${text.length}")
        openTeleprompter()
        // 取消上一轮轮播
        textCarouselJob?.cancel()
        textCarouselJob = null

        val cleanText = text.trim()
        val segments = splitTextToSegments(cleanText, TEXT_SEGMENT_MAX_CHARS)
        Log.d(TAG, "sendTextToTeleprompter: ${segments.size} segments")

        // 记录轮播状态（新消息重置暂停标记与索引）
        isCarouselPaused = false
        carouselSegments = segments
        carouselIndex = 0
        updateCarouselUiState()

        // 显示第一段
        showTextSegment(segments, 0)

        if (segments.size > 1) {
            // 多段：启动可暂停/可翻页的轮播（间隔跟随 TTS 预估时长，音画同步）
            startTextCarouselJob(fromIndex = 0)
        } else {
            // 单段：正常自动关闭
            glassesDisplayJob?.cancel()
            glassesDisplayJob = viewModelScope.launch {
                delay(GLASSES_DISPLAY_TIMEOUT_MS)
                closeTeleprompter()
            }
        }
    }

    /**
     * 启动（或重启）文字轮播协程：从 fromIndex 段开始，按 TTS 预估时长节奏自动推进；
     * 最后一段显示后等待 TTS 播完自动关闭（既有行为保留）。
     * 暂停实现方式：直接取消本 Job 并保留当前段；恢复时以当前索引重新调用本方法。
     */
    private fun startTextCarouselJob(fromIndex: Int) {
        textCarouselJob?.cancel()
        val segments = carouselSegments
        if (segments.size <= 1) return
        textCarouselJob = viewModelScope.launch {
            var i = fromIndex
            while (i < segments.size - 1) {
                val interval = estimateTtsDurationMs(segments[i])
                    .coerceAtLeast(TEXT_CAROUSEL_MIN_MS)
                delay(interval)
                i += 1
                carouselIndex = i
                updateCarouselUiState()
                showTextSegment(segments, i)
            }
            // 最后一段显示后，等待 TTS 播完再关闭
            val lastInterval = (estimateTtsDurationMs(segments.last()) + 1000L)
                .coerceAtLeast(GLASSES_DISPLAY_TIMEOUT_MS)
            delay(lastInterval)
            closeTeleprompter()
        }
    }

    /** 同步轮播状态到 UiState（按钮显隐 + 页码显示） */
    private fun updateCarouselUiState() {
        _uiState.update {
            it.copy(
                carouselIndex = carouselIndex,
                carouselTotalCount = carouselSegments.size,
                isCarouselPaused = isCarouselPaused
            )
        }
    }

    // ========== 手机端手动轮播控制（仅改眼镜端显示，不影响 TTS 播报队列） ==========

    /** 手动切换到上一段（首段时无效操作） */
    fun showPrevTextSegment() = moveToTextSegment(carouselIndex - 1)

    /** 手动切换到下一段（末段时无效操作） */
    fun showNextTextSegment() = moveToTextSegment(carouselIndex + 1)

    /**
     * 手动翻页：取消当前轮播等待，立即显示目标段。
     * 非暂停状态下从目标段按原节奏恢复轮播；暂停状态下停留在目标段。
     * 注意：只切换眼镜端显示内容，不触碰 TTS 队列，避免重复播报/错乱。
     */
    private fun moveToTextSegment(targetIndex: Int) {
        val segments = carouselSegments
        if (segments.size <= 1) return
        if (targetIndex < 0 || targetIndex >= segments.size) return
        if (targetIndex == carouselIndex) return
        textCarouselJob?.cancel()
        textCarouselJob = null
        carouselIndex = targetIndex
        updateCarouselUiState()
        showTextSegment(segments, targetIndex)
        Log.d(TAG, "Carousel manual move to segment ${targetIndex + 1}/${segments.size}, paused=$isCarouselPaused")
        if (!isCarouselPaused) {
            startTextCarouselJob(targetIndex)
        }
    }

    /** 暂停轮播：取消自动推进定时器，停留在当前段 */
    fun pauseTextCarousel() {
        if (carouselSegments.size <= 1 || isCarouselPaused) return
        isCarouselPaused = true
        textCarouselJob?.cancel()
        textCarouselJob = null
        updateCarouselUiState()
        Log.d(TAG, "Carousel paused at segment ${carouselIndex + 1}/${carouselSegments.size}")
    }

    /** 继续轮播：从当前段按原节奏（TTS 预估时长，最小 4.5s）恢复自动推进 */
    fun resumeTextCarousel() {
        if (!isCarouselPaused || carouselSegments.isEmpty()) return
        isCarouselPaused = false
        updateCarouselUiState()
        Log.d(TAG, "Carousel resumed from segment ${carouselIndex + 1}/${carouselSegments.size}")
        if (carouselSegments.size > 1) {
            startTextCarouselJob(carouselIndex)
        }
    }

    /** 显示指定段文字 + 页码 */
    private fun showTextSegment(segments: List<String>, index: Int) {
        val escaped = segments[index]
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
            .replace("\t", "\\t")
        val updateJson = UpdateViewJson().apply {
            updateList.add(UpdateViewJson.UpdateJson(id = "contentText").apply {
                props["text"] = escaped
            })
            if (segments.size > 1) {
                updateList.add(UpdateViewJson.UpdateJson(id = "imageCounter").apply {
                    props["text"] = "(${index + 1}/${segments.size})"
                })
            } else {
                updateList.add(UpdateViewJson.UpdateJson(id = "imageCounter").apply {
                    props["text"] = ""
                })
            }
        }
        CxrApi.getInstance().updateCustomView(updateJson.toJson())
    }

    /** 将长文本按段落/句子边界拆分为多段，每段不超过 maxChars 字符 */
    private fun splitTextToSegments(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val segments = mutableListOf<String>()
        var remaining = text
        while (remaining.length > maxChars) {
            var cutIndex = -1
            // 优先级 1：在 maxChars 范围内找最后一个段落分隔符（\n）
            for (i in maxChars - 1 downTo 0) {
                if (remaining[i] == '\n') {
                    cutIndex = i + 1
                    break
                }
            }
            // 优先级 2：找最后一个句子结束符（后半段范围内）
            if (cutIndex == -1) {
                for (i in maxChars - 1 downTo maxChars / 3) {
                    val c = remaining[i]
                    if (c == '。' || c == '！' || c == '？' || c == '；' || c == '.' || c == '!' || c == '?') {
                        cutIndex = i + 1
                        break
                    }
                }
            }
            // 优先级 3：找最后一个逗号/空格（避免截断词语）
            if (cutIndex == -1) {
                for (i in maxChars - 1 downTo maxChars / 2) {
                    val c = remaining[i]
                    if (c == '，' || c == '、' || c == ' ' || c == ',') {
                        cutIndex = i + 1
                        break
                    }
                }
            }
            // 实在找不到就硬切
            if (cutIndex == -1) cutIndex = maxChars
            segments.add(remaining.substring(0, cutIndex).trim())
            remaining = remaining.substring(cutIndex).trim()
        }
        if (remaining.isNotEmpty()) segments.add(remaining)
        return segments
    }

    /** 关闭眼镜端 CustomView，取消自动关闭计时器和文字轮播 */
    private fun closeTeleprompter() {
        glassesDisplayJob?.cancel()
        glassesDisplayJob = null
        textCarouselJob?.cancel()
        textCarouselJob = null
        // 清空轮播状态并隐藏手机端控制区
        carouselSegments = emptyList()
        carouselIndex = 0
        isCarouselPaused = false
        updateCarouselUiState()
        slideshowJob?.cancel()
        slideshowJob = null
        slideshowIconNames = emptyList()
        if (isGlassesDisplayOpen) {
            armProgrammaticCloseGuard()
            CxrApi.getInstance().closeCustomView()
            isGlassesDisplayOpen = false
            Log.d(TAG, "Glasses CustomView closed")
        }
    }

    /**
     * 标记即将发生的 CustomView 关闭为程序化关闭，短时后自动复位。
     * 防止 closeCustomView 的异步 onClosed/SceneStatus 回调被误判为系统手势双击关闭
     */
    private fun armProgrammaticCloseGuard() {
        expectProgrammaticClose = true
        expectCloseGuardResetJob?.cancel()
        expectCloseGuardResetJob = viewModelScope.launch(Dispatchers.Main) {
            delay(2_000L)
            expectProgrammaticClose = false
        }
    }

    /**
     * 发送图片到眼镜端提词器：下载图片 → 绿色通道处理 → 上传 → 启动轮播
     */
    private fun sendImagesToTeleprompter(imageParts: List<MessagePart>) {
        if (imageParts.isEmpty()) return
        Log.d(TAG, "sendImagesToTeleprompter: ${imageParts.size} images")
        for (p in imageParts) {
            Log.d(TAG, "  image part: kind=${p.kind}, url=${p.contentUrl}")
        }
        viewModelScope.launch {
            try {
                val icons = mutableListOf<IconInfo>()
                val names = mutableListOf<String>()
                for ((index, part) in imageParts.withIndex()) {
                    Log.d(TAG, "Downloading image $index: ${part.contentUrl}")
                    val icon = downloadAndProcessImage(part.contentUrl, "tele_img_$index")
                    if (icon == null) {
                        Log.e(TAG, "Failed to process image $index")
                        continue
                    }
                    Log.d(TAG, "Image $index processed OK")
                    icons.add(icon)
                    names.add("tele_img_$index")
                }
                if (icons.isEmpty()) return@launch

                // 上传所有图片
                CxrApi.getInstance().sendCustomViewIcons(icons)
                slideshowIconNames = names
                slideshowIndex = 0

                // 显示第一张图
                showSlideshowImage(0)

                // 多图启动轮播
                if (names.size > 1) {
                    slideshowJob?.cancel()
                    slideshowJob = viewModelScope.launch {
                        while (true) {
                            delay(4000L)
                            slideshowIndex = (slideshowIndex + 1) % slideshowIconNames.size
                            showSlideshowImage(slideshowIndex)
                        }
                    }
                }
                Log.d(TAG, "Teleprompter images sent: ${icons.size} images")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send images to teleprompter", e)
            }
        }
    }

    /** 显示轮播中的第 index 张图片 */
    private fun showSlideshowImage(index: Int) {
        if (slideshowIconNames.isEmpty()) return
        val name = slideshowIconNames[index]
        val updateJson = UpdateViewJson().apply {
            updateList.add(UpdateViewJson.UpdateJson(id = "contentImage").apply {
                props["name"] = name
            })
            if (slideshowIconNames.size > 1) {
                updateList.add(UpdateViewJson.UpdateJson(id = "imageCounter").apply {
                    props["text"] = "图片 ${index + 1}/${slideshowIconNames.size}"
                })
            }
        }
        CxrApi.getInstance().updateCustomView(updateJson.toJson())
    }

    /** 下载图片并转换为绿色通道 Base64 */
    private suspend fun downloadAndProcessImage(url: String, iconName: String): IconInfo? {
        return withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val bitmap = if (url.startsWith("file://")) {
                BitmapFactory.decodeFile(url.removePrefix("file://"))
            } else {
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                val inputStream = connection.inputStream
                val bmp = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                connection.disconnect()
                bmp
            } ?: return@withContext null

            // 缩放到适合眼镜屏幕的尺寸
            val scaled = Bitmap.createScaledBitmap(bitmap, 320, 240, true)
            if (scaled !== bitmap) bitmap.recycle()

            // 绿色通道处理：将每个像素的亮度映射到绿色通道
            val pixels = IntArray(scaled.width * scaled.height)
            scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
            for (i in pixels.indices) {
                val r = (pixels[i] shr 16) and 0xFF
                val g = (pixels[i] shr 8) and 0xFF
                val b = pixels[i] and 0xFF
                val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
                pixels[i] = (0xFF shl 24) or (luminance shl 8)  // 只保留绿色通道
            }
            val greenBitmap = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
            greenBitmap.setPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
            scaled.recycle()

            // Base64 编码
            val outputStream = java.io.ByteArrayOutputStream()
            greenBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            outputStream.close()
            greenBitmap.recycle()

            IconInfo(iconName, base64)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download/process image: $url", e)
            null
        }
        } // end withContext
    }

    fun startSkill(skillKey: String? = null) {
        val key = skillKey ?: _uiState.value.selectedSkill?.key ?: return
        _uiState.update { it.copy(currentScreen = InspectionScreen.INTERACTION) }
        if (_uiState.value.operatingMode == PsopOperatingMode.GLASSES) {
            // 眼镜模式保持现有完整初始化流程。
            CxrApi.getInstance().setAiEventListener(aiEventListener)
            CxrApi.getInstance().setMediaFilesUpdateListener(mediaFilesUpdateListener)
            try {
                P2PUtils.Instance.setToZuoyitong()
            } catch (e: Exception) {
                Log.w(TAG, "setToZuoyitong failed (ignored)", e)
            }
            preConnectP2P()
        }
        viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(
                        isRunning = true,
                        error = null,
                        isCompleted = false,
                        messages = emptyList(),
                        runStatus = null,
                        taskStatus = null  // 清除上次巡检的进度，避免显示旧数据
                    )
                }
                lastSeqNo = 0
                val response = repository.createInvocation(key)
                val runId = response.runId ?: return@launch
                rememberLastOpenedActiveRun(runId)
                _uiState.update { it.copy(runId = runId) }
                // 连接 WebSocket 接收实时事件
                repository.connectWebSocket(runId)
                // 加载新 run 的初始任务进度
                loadTaskStatus(runId)
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
    fun resumeInvocation(invocation: RunResponse) {
        val runId = invocation.id
        val matchingSkill = _uiState.value.skills.firstOrNull { it.id == invocation.skillDefinitionId }
        if (isActiveRun(invocation)) {
            rememberLastOpenedActiveRun(runId)
        }
        if (_uiState.value.operatingMode == PsopOperatingMode.GLASSES) {
            // 仅眼镜模式恢复眼镜事件、媒体同步和 P2P 预连接。
            CxrApi.getInstance().setAiEventListener(aiEventListener)
            CxrApi.getInstance().setMediaFilesUpdateListener(mediaFilesUpdateListener)
            preConnectP2P()
        }
        _uiState.update {
            it.copy(
                currentScreen = InspectionScreen.INTERACTION,
                selectedSkill = matchingSkill ?: it.selectedSkill,
                runId = runId,
                isRunning = true,
                error = null,
                isCompleted = false,
                messages = emptyList(),
                runStatus = invocation.status,
                taskStatus = null  // 先清除旧进度，loadTaskStatus 会加载新的
            )
        }
        lastSeqNo = 0
        viewModelScope.launch {
            try {
                // 加载历史事件
                val events = repository.getTerminalEvents(runId, null)
                val messages = events.map { event ->
                    // 从 parts 中提取文字和媒体占位符
                    val content: String = if (event.parts.isNotEmpty()) {
                        val textParts = event.parts.filter { it.kind == "text" || it.mimeType.startsWith("text/") }.map { it.text }
                        val mediaParts = event.parts.filter { it.mimeType.startsWith("image/") || it.mimeType.startsWith("audio/") || it.mimeType.startsWith("video/") }.map { part ->
                            when {
                                part.mimeType.startsWith("image/") -> "[图片]"
                                part.mimeType.startsWith("audio/") -> "[音频]"
                                part.mimeType.startsWith("video/") -> "[视频]"
                                else -> "[文件]"
                            }
                        }
                        (textParts + mediaParts).joinToString(" ")
                    } else {
                        ""
                    }
                    val imageParts = event.parts
                        .filter { it.mimeType.startsWith("image/") || it.mimeType.startsWith("video/") || it.mimeType.startsWith("audio/") }
                        .map { part ->
                            MessagePart(
                                partId = part.partId,
                                kind = part.kind,
                                mimeType = part.mimeType,
                                contentUrl = "${PsopConfig.baseUrl}terminal/sessions/${runId}/events/${event.id}/parts/${part.partId}/content"
                            )
                        }
                    android.util.Log.d("PSOP_DEBUG", "InitLoad: id=${event.id.take(8)} dir=${event.direction} parts=${event.parts.size} contentLen=${content.length}")
                    TerminalMessage(
                        id = event.id,
                        direction = event.direction,
                        content = content,
                        timestamp = event.occurredAt,
                        parts = imageParts
                    )
                }
                if (events.isNotEmpty()) {
                    lastSeqNo = events.maxOf { it.seqNo }
                }
                _uiState.update { it.copy(messages = messages) }

                // 加载任务进度状态
                loadTaskStatus(runId)

                // 如果还在运行中，连接 WebSocket 继续会话
                val activeStates = setOf("queued", "waiting_runtime", "accepted", "running", "waiting_input", "finalizing")
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
        uploadFiles(listOf(file))
    }

    /**
     * 多图上传：将多个文件作为一条消息发送（单个 event 包含多个 file parts）
     */
    fun uploadFiles(files: List<File>) {
        val runId = _uiState.value.runId ?: return
        viewModelScope.launch {
            val msgId = "upload-${System.currentTimeMillis()}"
            try {
                // 本地显示上传中提示
                val msg = TerminalMessage(
                    id = msgId,
                    direction = "input",
                    content = "",
                    timestamp = "",
                    uploadStatus = "uploading"
                )
                _uiState.update { it.copy(messages = it.messages + msg) }

                // 压缩超过 4MB 的图片（生成独立压缩副本，不覆盖原图）
                val compressedFiles = withContext(Dispatchers.IO) {
                    files.map { compressImageIfNeeded(it) }
                }

                // 构造多文件 parts
                val fileParts = compressedFiles.map { file ->
                    val mimeType = when (file.extension.lowercase()) {
                        "jpg", "jpeg" -> "image/jpeg"
                        "png" -> "image/png"
                        "gif" -> "image/gif"
                        "webp" -> "image/webp"
                        "mp4" -> "video/mp4"
                        else -> "application/octet-stream"
                    }
                    val mediaType = mimeType.toMediaTypeOrNull()
                    val requestFile = file.asRequestBody(mediaType)
                    MultipartBody.Part.createFormData("files", file.name, requestFile)
                }

                // 生成幂等键（重试时复用同一 key，保证重试安全）
                val idempotencyKey = repository.generateIdempotencyKey()

                // 构造 event JSON
                val eventJson = JSONObject().apply {
                    put("direction", "input")
                    put("event_kind", "terminal.multimodal.input.v1")
                    put("mime_type", "multipart/mixed")
                    put("external_event_id", idempotencyKey)
                    put("source", JSONObject().apply { put("kind", "external_terminal") })
                }.toString()
                val eventBody = eventJson.toRequestBody("application/json".toMediaType())

                // 一次性上传所有文件（失败自动重试 1 次，退避 1s，总时限 40s）
                var uploadAttempt = 0
                try {
                    withTimeout(40_000L) {
                        while (true) {
                            try {
                                repository.uploadTerminalFile(
                                    runId = runId,
                                    idempotencyKey = idempotencyKey,
                                    event = eventBody,
                                    files = fileParts
                                )
                                break
                            } catch (retryE: Exception) {
                                val retryable = retryE !is retrofit2.HttpException ||
                                    retryE.code() !in 400..499 || retryE.code() == 408 || retryE.code() == 429
                                if (uploadAttempt < 1 && retryable) {
                                    uploadAttempt++
                                    Log.w(TAG, "uploadFiles attempt $uploadAttempt failed, retrying in 1s...", retryE)
                                    delay(1_000L)
                                    continue
                                }
                                throw retryE
                            }
                        }
                    }
                } catch (t: TimeoutCancellationException) {
                    Log.e(TAG, "uploadFiles exceeded total time limit (40s), giving up", t)
                    throw RuntimeException("上传总时限(40s)已到", t)
                }

                // 上传成功，显示所有图片
                val imageParts = compressedFiles.map { file ->
                    MessagePart(
                        partId = "local-${file.name}",
                        kind = "image",
                        mimeType = "image/jpeg",
                        contentUrl = "file://${file.absolutePath}"
                    )
                }
                _uiState.update { state ->
                    val updatedMessages = state.messages.map {
                        if (it.id == msgId) it.copy(
                            content = "",
                            parts = imageParts,
                            uploadStatus = "success"
                        )
                        else it
                    }
                    state.copy(messages = updatedMessages)
                }
            } catch (e: Exception) {
                // 上传失败：记录详细错误信息并显示红色感叹号
                val errorDetail = if (e is retrofit2.HttpException) {
                    "[${e.code()}] ${e.response()?.errorBody()?.string()}"
                } else e.message ?: "unknown"
                Log.e("PSOP_DEBUG", ">>> uploadFiles FAILED: $errorDetail", e)
                _uiState.update { state ->
                    val updatedMessages = state.messages.map {
                        if (it.id == msgId) it.copy(
                            content = "❌ 图片上传失败: $errorDetail",
                            uploadStatus = "failed"
                        )
                        else it
                    }
                    state.copy(messages = updatedMessages)
                }
            }
        }
    }

    // ========== 眼镜端拍照确认（CustomView + TouchPad + TTS） ==========

    /**
     * 拍照确认用 CustomView 布局 — 全屏居中展示缩略图 + 底部操作提示
     */
    private val photoConfirmView: SelfViewJson by lazy {
        SelfViewJson().apply {
            type = "LinearLayout"
            props = LinearLayoutProps().apply {
                id = "confirmRoot"
                layout_width = "match_parent"
                layout_height = "match_parent"
                backgroundColor = "#CC000000"   // 80% 黑色半透明背景
                orientation = "vertical"
                gravity = "center"
            }.toJson()
            children = listOf(
                SelfViewJson().apply {
                    type = "ImageView"
                    props = ImageViewProps().apply {
                        id = "photoPreview"
                        layout_width = "400dp"
                        layout_height = "300dp"
                        name = "photo_thumb"       // 通过 sendCustomViewIcons 发送的图标名
                        scaleType = "center_crop"
                    }.toJson()
                },
                SelfViewJson().apply {
                    type = "TextView"
                    props = TextViewProps().apply {
                        id = "confirmHint"
                        layout_width = "match_parent"
                        layout_height = "wrap_content"
                        text = "长按TouchPad确认，否则自动上传"
                        textColor = "#FFFFFF"
                        textSize = "16sp"
                        gravity = "center"
                        paddingTop = "12dp"
                    }.toJson()
                }
            )
        }
    }

    /** 打开拍照确认 CustomView */
    private fun openPhotoConfirmView() {
        Log.e("PSOP_DEBUG", ">>> openPhotoConfirmView() called, isOpen=$isPhotoConfirmViewOpen")
        if (!isPhotoConfirmViewOpen) {
            // 降低确认窗口内 BLE 争用：丢弃队列中未播内容（不打断当前播报，让其自然结束）
            clearTtsQueueForPhotoConfirm()
            // 先关闭提词器 CustomView（互斥；内部已标记程序化关闭，其异步 onClosed 不会被误判为手势）
            closeTeleprompter()
            customViewOpenAcked = false
            try {
                CxrApi.getInstance().openCustomView(photoConfirmView.toJson())
            } catch (e: Exception) {
                Log.e(TAG, "openCustomView(photoConfirm) threw", e)
            }
            isPhotoConfirmViewOpen = true
            Log.e("PSOP_DEBUG", ">>> openCustomView() done, isPhotoConfirmViewOpen=true")
            // 延时补发：800ms 内未收到 onOpened 回执则补发一次 open（BLE 拥塞时指令可能丢失）
            openCustomViewRetryJob?.cancel()
            openCustomViewRetryJob = viewModelScope.launch(Dispatchers.Main) {
                delay(800L)
                if (isPhotoConfirmViewOpen && !customViewOpenAcked) {
                    Log.w(TAG, "openCustomView(photoConfirm) not acked in 800ms, resending")
                    try {
                        CxrApi.getInstance().openCustomView(photoConfirmView.toJson())
                    } catch (e: Exception) {
                        Log.w(TAG, "openCustomView resend failed", e)
                    }
                }
            }
            // 独立兜底关闭：自动确认定时器被跳过时仍能强制关闭界面并复位状态
            startPhotoConfirmFallbackTimer()
        }
    }

    /** 关闭拍照确认 CustomView（关闭指令带延时补发，确保可靠送达眼镜端） */
    private fun closePhotoConfirmView() {
        // 统一取消在途 1s 自动确认 Job（长按 confirmPhoto 必须借此阻止后续自动上传）；
        // 但若 pending 尚未消费（终态 run 状态 succeeded/failed 也会调本函数关界面，
        // 自动确认上传尚未发生），重新武装一个新 Job，避免照片永远卡"发送中"。
        // Job 到期经 pending 检查照常 confirmPhoto（confirmPhoto 再调本函数时 pending 已清，幂等）
        photoConfirmTimeoutJob?.cancel()
        photoConfirmTimeoutJob = null
        if (pendingPhotoFile != null || pendingPhotoMsgId != null) {
            Log.d(TAG, "closePhotoConfirmView: pending not consumed, re-arming 1s auto-confirm job")
            photoConfirmTimeoutJob = viewModelScope.launch {
                delay(1_000L)
                if (pendingPhotoFile != null || pendingPhotoMsgId != null) {
                    val mode = _uiState.value.interactionMode
                    if (mode != InteractionMode.PHOTO_CONFIRM) {
                        Log.w(TAG, "HW auto-confirm (rearmed): mode changed to $mode, still uploading (photo already local)")
                    } else {
                        Log.d(TAG, "HW auto-confirm (rearmed): uploading after 1s")
                    }
                    confirmPhoto()
                }
            }
        }
        photoConfirmFallbackJob?.cancel()
        photoConfirmFallbackJob = null
        openCustomViewRetryJob?.cancel()
        openCustomViewRetryJob = null
        if (isPhotoConfirmViewOpen) {
            isPhotoConfirmViewOpen = false  // 先标记为 false，防止 onClosed 误触发取消
            armProgrammaticCloseGuard()
            try {
                CxrApi.getInstance().closeCustomView()
            } catch (e: Exception) {
                Log.w(TAG, "closeCustomView(photoConfirm) threw", e)
            }
            Log.d(TAG, "Photo confirm CustomView closed by code")
            // 延时补发：800ms 后若仍未收到关闭回执且无新 CustomView 打开，补发一次关闭指令（BLE 拥塞时指令可能丢失）
            closeCustomViewRetryJob?.cancel()
            closeCustomViewRetryJob = viewModelScope.launch(Dispatchers.Main) {
                delay(800L)
                if (!isPhotoConfirmViewOpen && !isGlassesDisplayOpen && customViewOpenAcked) {
                    Log.w(TAG, "closeCustomView resend (ensure close command reaches glasses)")
                    try {
                        CxrApi.getInstance().closeCustomView()
                    } catch (e: Exception) {
                        Log.w(TAG, "closeCustomView resend failed", e)
                    }
                }
            }
        }
    }

    /**
     * 确认窗口 TTS 清理：清空队列中全部未播内容并取消待执行重试，
     * 但不发"。"占位打断——当前播报让其自然结束（结束回调/预估定时器驱动后续状态复位）。
     * 降低确认窗口内的 BLE 争用；确认/取消后不恢复
     */
    private fun clearTtsQueueForPhotoConfirm() {
        if (ttsQueue.isNotEmpty() || ttsRetryJob != null) {
            Log.d(TAG, "Photo confirm window: clearing TTS queue (dropped=${ttsQueue.size})")
        }
        ttsQueue.clear()
        ttsRetryCount = 0
        ttsRetryJob?.cancel(); ttsRetryJob = null
    }

    /** 确认界面独立兜底关闭：到期时界面仍打开则无条件取消并关闭（复用 PHOTO_CONFIRM_TIMEOUT_MS） */
    private fun startPhotoConfirmFallbackTimer() {
        photoConfirmFallbackJob?.cancel()
        photoConfirmFallbackJob = viewModelScope.launch {
            delay(PHOTO_CONFIRM_TIMEOUT_MS)
            if (isPhotoConfirmViewOpen) {
                Log.w(TAG, "Photo confirm fallback timeout (${PHOTO_CONFIRM_TIMEOUT_MS}ms), force cancel & close")
                cancelPhotoConfirm()
            }
        }
    }

    /**
     * 将拍照原始数据压缩为缩略图，通过 sendCustomViewIcons 发送到眼镜端
     */
    private fun sendPhotoThumbnail(imageData: ByteArray) {
        try {
            // 解码原图并缩放到小尺寸（320x240 适合眼镜屏幕）
            val originalBitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
                ?: return
            val thumbWidth = 640
            val thumbHeight = 480
            val thumbBitmap = Bitmap.createScaledBitmap(originalBitmap, thumbWidth, thumbHeight, true)
            if (thumbBitmap !== originalBitmap) originalBitmap.recycle()

            // 压缩为 JPEG 90% 并 Base64 编码（比 PNG 文件更小、画质更好）
            val outputStream = ByteArrayOutputStream()
            thumbBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            outputStream.close()
            thumbBitmap.recycle()

            // 发送到眼镜端
            val iconInfo = IconInfo("photo_thumb", base64)
            CxrApi.getInstance().sendCustomViewIcons(listOf(iconInfo))
            Log.d(TAG, "Photo thumbnail sent to glasses (${thumbWidth}x${thumbHeight})")
            // 延时补发一次：BLE 拥塞时图标指令可能丢失（仅在确认界面仍打开时补发）
            viewModelScope.launch(Dispatchers.Main) {
                delay(800L)
                if (isPhotoConfirmViewOpen) {
                    try {
                        CxrApi.getInstance().sendCustomViewIcons(listOf(iconInfo))
                        Log.d(TAG, "Photo thumbnail icon resent")
                    } catch (e: Exception) {
                        Log.w(TAG, "Photo thumbnail resend failed", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send photo thumbnail", e)
        }
    }

    /**
     * 用户确认上传：关闭确认界面，保存并上传照片
     */
    private fun confirmPhoto() {
        Log.e("PSOP_DEBUG", ">>> confirmPhoto() ENTERED, msgId=$pendingPhotoMsgId, runId=${_uiState.value.runId}")
        val photoFile = pendingPhotoFile
        val msgId = pendingPhotoMsgId
        val runId = _uiState.value.runId

        // 立即清理暂存状态 & 关闭确认界面
        pendingPhotoFile = null
        pendingPhotoMsgId = null
        closePhotoConfirmView()

        if (msgId == null || runId == null) {
            Log.w(TAG, "confirmPhoto: missing msgId ($msgId) or runId ($runId)")
            // 早退不再静默：消息落 ❌ 失败终态，堵住"永远转圈"缺口
            if (msgId != null) {
                _uiState.update { state ->
                    val updatedMessages = state.messages.map {
                        if (it.id == msgId) it.copy(
                            content = "❌ 图片上传失败: 无有效会话，请重新拍照",
                            uploadStatus = "failed"
                        )
                        else it
                    }
                    state.copy(messages = updatedMessages)
                }
            }
            _uiState.update { it.copy(interactionMode = InteractionMode.LISTENING) }
            // 既有缺口修复①：早退也是管线终态——触发常驻连接重建（状态多滞留 TORN_DOWN_FOR_UPLOAD），
            // 并 flush 排队消息（重建在进行中会被派发守卫重新入队，留到重建终态再发）
            releaseHardwarePhotoRound()
            rebuildPersistentConnection()
            flushQueuedPhotoMessages()
            return
        }
        // 必须有照片文件
        if (photoFile == null) {
            Log.w(TAG, "confirmPhoto: no photo file")
            _uiState.update { state ->
                val updatedMessages = state.messages.map {
                    if (it.id == msgId) it.copy(
                        content = "❌ 图片上传失败: 照片文件缺失，请重新拍照",
                        uploadStatus = "failed"
                    )
                    else it
                }
                state.copy(messages = updatedMessages)
            }
            _uiState.update { it.copy(interactionMode = InteractionMode.LISTENING) }
            // 既有缺口修复①：早退也是管线终态——触发重建 + flush 排队消息（重建在进行中会重新入队）
            releaseHardwarePhotoRound()
            rebuildPersistentConnection()
            flushQueuedPhotoMessages()
            return
        }

        _uiState.update { it.copy(interactionMode = InteractionMode.PROCESSING) }

        viewModelScope.launch {
            val photoCapturedAt = System.currentTimeMillis()
            var failContent: String? = null
            try {
                // 限时 3 秒等待确认等待期内的提前压缩任务；超时则放弃预压缩成果
                // （压缩 Job 可继续跑完但结果不再使用），回退到对 photoFile 现场压缩，
                // 避免转圈被慢压缩无限阻塞
                val prepareDone = withTimeoutOrNull(3_000L) {
                    photoPrepareJob?.join()
                    true
                } == true
                val prepared = if (prepareDone) prePreparedPhotoFile else null
                prePreparedPhotoFile = null
                photoPrepareJob = null

                // 优先使用提前压缩好的文件；否则回退：硬件同步文件再压缩
                val file: File? = when {
                    prepared != null && prepared.exists() -> prepared
                    photoFile.exists() ->
                        withContext(Dispatchers.IO) { compressImageIfNeeded(photoFile) }
                    else -> null
                }

                var uploadSuccess = false
                if (file != null) {
                    Log.e("PSOP_PERF", "[图片] 编码保存完成, fileSize=${file.length()} bytes")
                    try {
                        // 失败自动重试（最多 1 次，退避 1s/2s，总时限 40s，幂等键复用保证重试安全）
                        doUploadTerminalFileWithRetry(runId, file, "现场拍照取证", maxRetries = 1)
                        val uploadDoneAt = System.currentTimeMillis()
                        Log.e("PSOP_PERF", "[图片] 上传完成, 端到端总耗时=${uploadDoneAt - photoCapturedAt}ms")
                        uploadSuccess = true
                    } catch (e: Exception) {
                        val errorDetail = if (e is retrofit2.HttpException) {
                            "[${e.code()}] ${e.response()?.errorBody()?.string()}"
                        } else e.message ?: "unknown"
                        Log.e("PSOP_DEBUG", ">>> photo upload FAILED after retries: $errorDetail", e)
                        file.delete()
                        failContent = "❌ 图片上传失败: $errorDetail"
                    }
                } else {
                    failContent = "❌ 图片上传失败: 照片文件不存在"
                }
                // 更新拍照消息状态（成功显示图片 / 失败标记）
                pendingStatusMsgId = null  // 消息已被复用为照片消息，不再需要清理
                _uiState.update { state ->
                    val updatedMessages = state.messages.map {
                        if (it.id == msgId) {
                            if (uploadSuccess && file != null) {
                                val imagePart = MessagePart(
                                    partId = "local-${file.name}",
                                    kind = "image",
                                    mimeType = "image/jpeg",
                                    contentUrl = "file://${file.absolutePath}"
                                )
                                it.copy(content = "", parts = listOf(imagePart), uploadStatus = "success")
                            } else {
                                it.copy(content = failContent ?: "", uploadStatus = "failed")
                            }
                        } else it
                    }
                    state.copy(messages = updatedMessages)
                }
                // 恢复 interactionMode（按 runStatus 派生，与 updateUiForRunStatus 映射一致：
                // waiting_input→LISTENING，running→PROCESSING，避免确认窗口内到达的 running 导致 mode 滞留；
                // 终态（succeeded/failed/cancelled/aborted）恢复 COMPLETED，避免无条件自动上传
                // 把已置 COMPLETED 的 mode 滞留在 PROCESSING）
                when (_uiState.value.runStatus) {
                    "waiting_input" -> _uiState.update { it.copy(interactionMode = InteractionMode.LISTENING) }
                    "running" -> _uiState.update { it.copy(interactionMode = InteractionMode.PROCESSING) }
                    "succeeded", "failed", "cancelled", "aborted" -> _uiState.update { it.copy(interactionMode = InteractionMode.COMPLETED) }
                }
                // 上传结束（无论成败）重建常驻 P2P 连接（三级收敛，为下一次硬件拍照做准备）
                releaseHardwarePhotoRound()
                rebuildPersistentConnection()
            } catch (e: Exception) {
                // 协程被 viewModelScope 取消（如离开页面）：直接抛出，无需更新状态；
                // 其余任何异常（含上传总时限超时，已在 doUploadTerminalFileWithRetry 内
                // 转为 RuntimeException）都兜底落 failed 终态，堵住"永远转圈"缺口
                if (e is CancellationException) throw e
                Log.e("PSOP_DEBUG", ">>> confirmPhoto coroutine FAILED: ${e.message}", e)
                pendingStatusMsgId = null
                _uiState.update { state ->
                    val updatedMessages = state.messages.map {
                        if (it.id == msgId) it.copy(
                            content = "❌ 图片上传失败: ${e.message ?: "unknown"}",
                            uploadStatus = "failed"
                        )
                        else it
                    }
                    state.copy(messages = updatedMessages)
                }
                when (_uiState.value.runStatus) {
                    "waiting_input" -> _uiState.update { it.copy(interactionMode = InteractionMode.LISTENING) }
                    "running" -> _uiState.update { it.copy(interactionMode = InteractionMode.PROCESSING) }
                    "succeeded", "failed", "cancelled", "aborted" -> _uiState.update { it.copy(interactionMode = InteractionMode.COMPLETED) }
                }
                releaseHardwarePhotoRound()
                rebuildPersistentConnection()
            }
        }
    }

    /**
     * 超时或手动取消拍照确认
     */
    private fun cancelPhotoConfirm() {
        Log.d(TAG, "Photo confirm cancelled (timeout or manual)")
        val msgId = pendingPhotoMsgId
        val statusMsgId = pendingStatusMsgId
        pendingPhotoMsgId = null
        pendingStatusMsgId = null
        clearPreparedPhoto()
        pendingPhotoFile = null
        closePhotoConfirmView()

        // 移除临时状态消息（超时/取消不需要留在聊天列表中）
        if (msgId != null || statusMsgId != null) {
            _uiState.update { state ->
                val removeIds = setOfNotNull(msgId, statusMsgId)
                state.copy(messages = state.messages.filter { it.id !in removeIds })
            }
        }

        // 恢复 interactionMode（按 runStatus 派生，与 updateUiForRunStatus 映射一致：
        // waiting_input→LISTENING，running→PROCESSING，避免确认窗口内到达的 running 导致 mode 滞留）
        when (_uiState.value.runStatus) {
            "waiting_input" -> _uiState.update { it.copy(interactionMode = InteractionMode.LISTENING) }
            "running" -> _uiState.update { it.copy(interactionMode = InteractionMode.PROCESSING) }
        }
        // 既有缺口修复②：取消上传即管线终态——状态不能滞留 TORN_DOWN_FOR_UPLOAD（否则照片管线窗口
        // 永远开着、排队消息永不补发），触发三级重建收敛；排队消息随之 flush（重建在进行中会重新入队）
        releaseHardwarePhotoRound()
        rebuildPersistentConnection()
        flushQueuedPhotoMessages()
    }

    // ===== 硬件拍照 WiFi P2P 同步流程 =====

    /**
     * 管线终态出口统一出队：排队标志为真且管线已关闭时，自动开启新一轮拍照回传
     *（与硬件键相同入口：mode=PHOTO_CAPTURE → stopTtsForPhotoCapture → delay(3000) → syncHardwarePhoto）。
     * 与 flushQueuedPhotoMessages 严格并列挂接；若出队时恰为 ESTABLISHED + cachedP2pIp 有效，
     * 新轮直接快路径 startSync2，连拍第二张回传仅需 ~1s。
     * 上限守卫：连续自动轮达 HW_PHOTO_AUTO_ROUND_LIMIT 停止（防空轮无限链），手动按键重置。
     */
    private fun tryStartQueuedPhotoRound() {
        // 调用方可能在 binder 线程（SyncStatusCallback/P2PListener 回调链），状态读写统一切 Main
        if (Looper.myLooper() != Looper.getMainLooper()) {
            viewModelScope.launch(Dispatchers.Main) { tryStartQueuedPhotoRound() }
            return
        }
        if (!hwPhotoRoundPending) return
        if (isPhotoPipelineActive()) return  // 窗口重开（如活性判败重来）：留到真正终态再出队
        hwPhotoRoundPending = false
        hwPhotoQueuedCount = 0
        if (hwPhotoAutoRoundCount >= HW_PHOTO_AUTO_ROUND_LIMIT) {
            Log.w(TAG, "auto round limit reached, paused ($HW_PHOTO_AUTO_ROUND_LIMIT)")
            _uiState.update { state -> state.copy(messages = state.messages + TerminalMessage(
                id = "hwphoto-autopause-${System.currentTimeMillis()}",
                direction = "output",
                content = "自动回传已暂停（连续 $HW_PHOTO_AUTO_ROUND_LIMIT 轮），请按眼镜拍照键继续",
                timestamp = ""
            )) }
            return
        }
        hwPhotoAutoRoundCount++
        Log.i(TAG, "starting queued photo round (auto=#$hwPhotoAutoRoundCount)")
        startHardwarePhotoRound()
    }

    /**
     * 进入对话页时预建 P2P 连接（后台静默执行，拍照时直接用缓存 IP，省去发现+连接耗时）
     */
    private fun preConnectP2P() {
        preConnectP2PInternal(rearmPeriodicOnFail = true)
    }

    /**
     * 预热建连内部实现。
     * @param rearmPeriodicOnFail 失败/看门狗到期是否 re-arm 周期预热循环：
     *   true=首次预热语义（startSkill/失败路径）；false=重建 Stage 2 语义（失败收敛到 Stage 3，不挂周期循环）。
     */
    private fun preConnectP2PInternal(rearmPeriodicOnFail: Boolean) {
        if (isP2pPreConnected || isP2pConnecting) return
        isP2pConnecting = true
        p2pPreConnectActive = true
        p2pPersistState = P2pPersistState.ESTABLISHING
        Log.d(TAG, "Pre-connecting P2P... (rearmPeriodicOnFail=$rearmPeriodicOnFail)")
        // 整体看门狗：覆盖 init 响应+发现+建连全阶段，防眼镜 BLE 静默导致预热卡死停摆；
        // 到期按 onDiscoveryFailed 同款处理：复位标志 +（按 rearm 语义）周期预热 re-arm
        preConnectWatchdogJob?.cancel()
        preConnectWatchdogJob = viewModelScope.launch {
            delay(PRE_CONNECT_WATCHDOG_MS)
            if (!p2pPreConnectActive) return@launch  // 已终态/已被硬件轮接管，不碰共享状态
            Log.w(TAG, "Pre-connect watchdog timeout (${PRE_CONNECT_WATCHDOG_MS}ms), resetting")
            p2pPreConnectActive = false
            isP2pConnecting = false
            cachedP2pIp = null
            isP2pPreConnected = false
            if (rearmPeriodicOnFail) {
                schedulePeriodicPreConnect()
            } else {
                // 重建 Stage 2 失败收敛：回 IDLE，不挂任何周期循环（无挂起态）
                rebuildConvergeToIdle("stage 2 watchdog timeout")
            }
        }
        val context = getApplication<Application>()
        initPreConnectP2PWithRetry(retryLeft = 2, context, rearmPeriodicOnFail)
    }

    /** 取消预热看门狗（幂等）：预热终态回调/硬件轮接管/teardown 共用 */
    private fun cancelPreConnectWatchdog() {
        preConnectWatchdogJob?.cancel()
        preConnectWatchdogJob = null
    }

    /** 预热路径 initWifiP2P2（onFailed 时延时重试，最多 2 次）；rearmPeriodicOnFail 语义透传到失败出口 */
    private fun initPreConnectP2PWithRetry(retryLeft: Int, context: Application, rearmPeriodicOnFail: Boolean = true) {
        Log.d(TAG, "pre-connect initWifiP2P2 issued (retryLeft=$retryLeft)")
        CxrApi.getInstance().initWifiP2P2(false, object : WifiP2PStatusCallback {
            override fun onConnected() {}
            override fun onDisconnected() {
                Log.w(TAG, "P2P pre-connect: disconnected")
                if (!p2pPreConnectActive) return  // 已被慢速路径接管，不碰共享状态
                p2pPreConnectActive = false
                isP2pConnecting = false
                cachedP2pIp = null
                isP2pPreConnected = false
            }
            override fun onFailed(p0: ValueUtil.CxrWifiErrorCode?) {
                Log.w(TAG, "P2P pre-connect failed: $p0, retryLeft=$retryLeft")
                if (!p2pPreConnectActive) return
                if (retryLeft > 0) {
                    viewModelScope.launch {
                        delay(P2P_CMD_RETRY_DELAY_MS)
                        if (p2pPreConnectActive) initPreConnectP2PWithRetry(retryLeft - 1, context, rearmPeriodicOnFail)
                    }
                    return
                }
                p2pPreConnectActive = false
                isP2pConnecting = false
                isP2pPreConnected = false
                if (rearmPeriodicOnFail) {
                    schedulePeriodicPreConnect()
                } else {
                    rebuildConvergeToIdle("stage 2 init failed")
                }
            }
            override fun onP2pDeviceAvailable(name: String?, macAddress: String?, deviceType: String?) {
                Log.d(TAG, "P2P pre-connect: device available name=$name, mac=$macAddress")
                // 已被硬件同步慢速路径接管（其监听器已挂上），不得覆盖
                if (!p2pPreConnectActive) {
                    Log.w(TAG, "P2P pre-connect superseded by hw photo sync, skip listener setup")
                    return
                }
                // 设备快照：重建 Stage 1 按缓存 name/MAC 直接 discover+connect（不 init 避免固件 MAC 轮换）
                lastKnownP2pName = name
                lastKnownP2pMac = macAddress
                P2PUtils.Instance.initP2P(context)
                P2PUtils.Instance.setTargetDevice(name, macAddress)
                // 预热场景：发现期连续轮询无果提前判败（避免挂满等待，静默失败不影响用户）
                P2PUtils.Instance.setFailFastOnEmptyDiscovery(true)
                P2PUtils.Instance.setListener(object : P2PListener {
                    override fun onDeviceFound(device: android.net.wifi.p2p.WifiP2pDevice) {
                        Log.d(TAG, "P2P pre-connect: device found ${device.deviceName}")
                    }
                    override fun onConnected(ipAddress: String) {
                        Log.d(TAG, "P2P pre-connected! IP: $ipAddress")
                        if (!p2pPreConnectActive) return
                        p2pPreConnectActive = false
                        isP2pConnecting = false
                        // 快路径恢复：眼镜固件每轮 initWifiP2P2 都轮换全新随机 MAC，MAC 精确匹配
                        // 几乎不可能命中，故建连成功即缓存 IP；快路径连到陈旧/错误组的风险由
                        // 既有 5.5s 活性探测兜底（startHwPhotoSync 无回调判败重来）
                        cachedP2pIp = ipAddress
                        isP2pPreConnected = true
                        p2pPersistState = P2pPersistState.ESTABLISHED
                        Log.i(TAG, "P2P persist: ESTABLISHED (name=$lastKnownP2pName mac=$lastKnownP2pMac)")
                        // 预热成功：停止周期预热循环与看门狗
                        periodicPreConnectJob?.cancel()
                        periodicPreConnectJob = null
                        cancelPreConnectWatchdog()
                        // 管线终态（重建 Stage 2 也经此成功）：统一 flush 排队消息（幂等，非管线场景队列为空）
                        flushQueuedPhotoMessages()
                        // 出队点③：重建 Stage 2 成功（经预热路径）——排队照片此时可快路径回传
                        tryStartQueuedPhotoRound()
                        Log.d(TAG, "P2P pre-connect cached IP for fast path (macMatched=${P2PUtils.Instance.lastMacMatchedConnect})")
                    }
                    override fun onConnectionFailed(reason: String) {
                        Log.w(TAG, "P2P pre-connect connection failed: $reason")
                        if (!p2pPreConnectActive) return
                        p2pPreConnectActive = false
                        isP2pConnecting = false
                        cachedP2pIp = null
                        isP2pPreConnected = false
                        cancelPreConnectWatchdog()
                        if (rearmPeriodicOnFail) {
                            // 固件“广播窗口”：窗外判败属预期，隔段再试捕获下一个窗口（不再永久停摆）
                            schedulePeriodicPreConnect()
                        } else {
                            // 重建 Stage 2 失败收敛：回 IDLE，不挂周期循环
                            rebuildConvergeToIdle("stage 2 connection failed")
                        }
                    }
                    override fun onDiscoveryStarted() {}
                    override fun onDiscoveryFailed(reason: Int) {
                        Log.w(TAG, "P2P pre-connect discovery failed: $reason")
                        if (!p2pPreConnectActive) return
                        p2pPreConnectActive = false
                        isP2pConnecting = false
                        isP2pPreConnected = false
                        cancelPreConnectWatchdog()
                        if (rearmPeriodicOnFail) {
                            // 与 onConnectionFailed 对齐：判败后周期化再试，捕获固件广播窗口（入口幂等守卫已有）
                            schedulePeriodicPreConnect()
                        } else {
                            rebuildConvergeToIdle("stage 2 discovery failed")
                        }
                    }
                })
                P2PUtils.Instance.startDiscoverP2P(context)
            }
        })
    }

    /** 完全拆除 P2P 连接（退出对话页时调用） */
    private fun teardownP2P() {
        // 终止在途硬件拍照流程：复位同步标志并取消建连等待/TTL 终判超时 Job，
        // 避免退出页面后超时 Job 到期触发 onHwPhotoSyncFailed 改 interactionMode / 重新预热
        isHwPhotoSyncing = false
        hwPhotoConnectTimeoutJob?.cancel()
        hwPhotoConnectTimeoutJob = null
        hwPhotoDiscoveryTtlJob?.cancel()
        hwPhotoDiscoveryTtlJob = null
        // 周期预热必须显式取消：ViewModel 不随退页销毁，否则后台残留持续 init/discover
        periodicPreConnectJob?.cancel()
        periodicPreConnectJob = null
        hwDiscoveryBusyRetryJob?.cancel()
        hwDiscoveryBusyRetryJob = null
        cancelPreConnectWatchdog()
        hwPhotoSyncTimeoutJob?.cancel()
        hwPhotoSyncTimeoutJob = null
        hwPhotoSyncLivenessJob?.cancel()
        hwPhotoSyncLivenessJob = null
        hwSyncActiveSeen = false
        cachedP2pIp = null
        isP2pPreConnected = false
        isP2pConnecting = false
        p2pPreConnectActive = false
        // 常驻状态机复位（快照 lastKnownP2pName/Mac 不清除，供重建 Stage 1 复用）；
        // 重建 Stage 限时 Job 必须显式取消，避免退页后到期触发陈旧重建
        p2pPersistState = P2pPersistState.IDLE
        rebuildStageJob?.cancel()
        rebuildStageJob = null
        try {
            P2PUtils.Instance.stopDiscoverP2P()
            // 取消 P2PUtils 在途延时重试 Runnable（与 cleanup/resetForHandover 对齐），
            // 避免挂起的 performConnection/重连 Runnable 到期发起陈旧 connect
            P2PUtils.Instance.cancelPendingRetryRunnables()
            // 清除目标设备信息，避免旧 MAC 跨轮残留参与下轮匹配（下轮会重新 setTargetDevice）
            P2PUtils.Instance.clearTargetDevice()
            P2PUtils.Instance.setListener(null)
            CxrApi.getInstance().deinitWifiP2P()
            Log.d(TAG, "P2P torn down")
        } catch (e: Exception) {
            Log.w(TAG, "P2P teardown error", e)
        }
        // 双保险：流程异常未走终态出口时兜底补发排队消息（幂等：队列空直接返回）。
        // 注意 onCleared 触发的 teardown 时 viewModelScope 已取消，协程不会执行，可接受
        flushQueuedPhotoMessages()
        // 出队点⑥：teardown 兜底（管线已关时消费排队标志开新轮；管线仍开时内部守卫跳过，
        // 留给后续真正终态出口；onCleared 场景协程不执行且 onCleared 会显式清标志）
        tryStartQueuedPhotoRound()
    }
    
    /**
     * 照片管线窗口判定（拍照键按下→同步→自动确认→上传→重建完成全程）：
     * 五条件派生——PHOTO_CAPTURE（拍照键按下含 3s 写盘等待）/ isHwPhotoSyncing（P2P 同步中）/
     * PHOTO_CONFIRM（自动确认等待窗口）/ TORN_DOWN_FOR_UPLOAD（同步成功拆组后的上传段）/
     * RECONNECTING（上传后三级重建段）。窗口内 WS output 消息整条排队，
     * 避免其 BLE 副作用（眼镜显示/TTS）与 P2P BLE 命令/重建握手（含 Stage 2 initWifiP2P2）交错争抢通道
     */
    private fun isPhotoPipelineActive(): Boolean {
        val mode = _uiState.value.interactionMode
        return mode == InteractionMode.PHOTO_CAPTURE ||
            mode == InteractionMode.PHOTO_CONFIRM ||
            isHwPhotoSyncing ||
            p2pPersistState == P2pPersistState.TORN_DOWN_FOR_UPLOAD ||
            p2pPersistState == P2pPersistState.RECONNECTING
    }
    
    /** 照片管线窗口内整条入队（含消息对象本身）；容量上限防泄漏，超限丢最旧并记日志 */
    private fun enqueuePhotoPipelineMessage(msg: TerminalMessage) {
        if (pendingMessagesDuringPhoto.size >= PHOTO_PIPELINE_QUEUE_CAPACITY) {
            val dropped = pendingMessagesDuringPhoto.removeAt(0)
            Log.w(TAG, "Photo pipeline queue full ($PHOTO_PIPELINE_QUEUE_CAPACITY), dropping oldest message id=${dropped.id}")
        }
        pendingMessagesDuringPhoto.add(msg)
        Log.w(TAG, "photo pipeline active, message queued (${pendingMessagesDuringPhoto.size} pending)")
    }
    
    /**
     * 照片管线终态后统一补发排队消息：按到达顺序逐条完成（入 UI 列表→眼镜显示→TTS），
     * 复用实时 output 路径的处理节奏（displayGlassesText + delay 800 + enqueueTtsForMessage，含图片轮播）。
     * 幂等：队列空直接返回，可在多个终态出口/teardown 重复调用不重复发送。
     * 线程：调用方可能在 binder 线程（P2PListener/SyncStatusCallback 回调），非主线程先切 Main。
     */
    private fun flushQueuedPhotoMessages() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            viewModelScope.launch(Dispatchers.Main) { flushQueuedPhotoMessages() }
            return
        }
        if (pendingMessagesDuringPhoto.isEmpty()) return
        val msgs = pendingMessagesDuringPhoto.toList()
        pendingMessagesDuringPhoto.clear()
        Log.i(TAG, "flushing ${msgs.size} queued messages after photo pipeline")
        viewModelScope.launch {
            // 派发前窗口重开（如活性判败重来/新拍照）：回退入队留到真正终态再补发，
            // 避免与新一轮 P2P BLE 命令再争通道
            if (isPhotoPipelineActive()) {
                Log.w(TAG, "Photo pipeline reopened before flush dispatch, re-queuing ${msgs.size} messages")
                msgs.reversed().forEach { enqueuePhotoPipelineMessageAtHead(it) }
                return@launch
            }
            msgs.forEach { msg ->
                // 入 UI 列表（与实时路径同款去重）
                _uiState.update { state ->
                    if (state.messages.any { it.id == msg.id }) state
                    else state.copy(messages = state.messages + msg)
                }
                // 与实时路径一致：PROCESSING 收到 output 即回 LISTENING
                if (_uiState.value.interactionMode == InteractionMode.PROCESSING) {
                    _uiState.update { it.copy(interactionMode = InteractionMode.LISTENING) }
                }
                val cleanedContent = msg.content.replace("[图片]", "").replace("[音频]", "").replace("[视频]", "").replace("[文件]", "").trim()
                if (cleanedContent.isNotBlank()) {
                    if (_uiState.value.operatingMode == PsopOperatingMode.GLASSES) {
                        displayGlassesText(cleanedContent)
                        delay(800L)
                        enqueueTtsForMessage(cleanedContent)
                    } else {
                        speakOnPhone(cleanedContent)
                    }
                }
                // 图片 parts 与实时路径一致：仅 CustomView 模式发眼镜端轮播
                val imgParts = msg.parts.filter { p -> p.kind == "image" }
                if (_uiState.value.operatingMode == PsopOperatingMode.GLASSES && imgParts.isNotEmpty() && _uiState.value.glassesDisplayMode == GlassesDisplayMode.CUSTOM_VIEW) {
                    sendImagesToTeleprompter(imgParts)
                }
            }
        }
    }
    
    /** 回退入队专用：插到队首保持到达顺序（超限仍丢全局最旧，即队尾方向的历史消息） */
    private fun enqueuePhotoPipelineMessageAtHead(msg: TerminalMessage) {
        if (pendingMessagesDuringPhoto.size >= PHOTO_PIPELINE_QUEUE_CAPACITY) {
            val dropped = pendingMessagesDuringPhoto.removeAt(pendingMessagesDuringPhoto.size - 1)
            Log.w(TAG, "Photo pipeline queue full ($PHOTO_PIPELINE_QUEUE_CAPACITY), dropping newest re-queued message id=${dropped.id}")
            return
        }
        pendingMessagesDuringPhoto.add(0, msg)
    }

    /**
     * 硬件拍照后，通过 WiFi P2P 同步眼镜中的照片到手机
     * 已预连接：直接 startSync2（跳过发现+连接）
     * 未预连接：回退到完整流程 initWifiP2P2 → 发现 → 连接 → startSync2
     */
    private fun syncHardwarePhoto() {
        // 防御性兜底：即使未来有其它入口绕过了入口占位，也绝不覆盖当前轮的
        // message ID、round token、P2P listener 与超时 Job；改为排队等本轮终态。
        if (isHwPhotoSyncing || hwPhotoSyncMsgId != null) {
            Log.w(TAG, "syncHardwarePhoto re-entry blocked; queue for next round")
            queueHardwarePhotoRound()
            return
        }
        val msgId = "hwphoto-${System.currentTimeMillis()}"
        hwPhotoSyncMsgId = msgId
        isHwPhotoSyncing = true
        hwSyncFullRetryUsed = false
        hwPhotoSyncRound++  // 新一轮同步：旧轮次的 startSync2 回调将被 round 守卫忽略

        val msg = TerminalMessage(
            id = msgId,
            direction = "input",
            content = "",
            timestamp = "",
            uploadStatus = "uploading"
        )
        _uiState.update { it.copy(messages = it.messages + msg) }

        // 快速路径：已有预连接 IP，直接同步（同步阶段超时由 startHwPhotoSync 启动）
        val ip = cachedP2pIp
        if (ip != null && isP2pPreConnected) {
            Log.d(TAG, "P2P pre-connected, fast sync with IP: $ip")
            startHwPhotoSync(ip)
            return
        }

        // 慢速路径接管：挂起周期预热当前轮（避免与硬件拍照流程并发争抢 Wi-Fi/BLE）；
        // 预热自带 isP2pPreConnected/isP2pConnecting 幂等守卫，不会重复 init；
        // 硬件流程结束后由 schedulePeriodicPreConnect（失败/同步成功路径）重新 arm
        periodicPreConnectJob?.cancel()
        periodicPreConnectJob = null

        // 慢速路径：等待态与终判解耦——
        // 1) 15s（建连预算）到点不判败：仅更新消息气泡文案为“等待眼镜就绪…”，
        //    mode 保持 PHOTO_CAPTURE，不 flush BLE 缓冲、不退 mode、不 teardown，后台 discovery/连接继续；
        // 2) TTL 终判：从拍照触发起 90s 仍未连成才走既有 onHwPhotoSyncFailed 失败出口
        hwPhotoConnectTimeoutJob?.cancel()
        hwPhotoConnectTimeoutJob = viewModelScope.launch {
            delay(HW_PHOTO_CONNECT_TIMEOUT_MS)
            if (isHwPhotoSyncing) {
                Log.w(TAG, "HW photo P2P connect budget exhausted (${HW_PHOTO_CONNECT_TIMEOUT_MS}ms), keep waiting for glasses broadcast window")
                val waitingMsgId = hwPhotoSyncMsgId
                if (waitingMsgId != null) {
                    _uiState.update { state ->
                        state.copy(messages = state.messages.map {
                            if (it.id == waitingMsgId) it.copy(content = "⏳ 等待眼镜就绪…") else it
                        })
                    }
                }
            }
        }
        hwPhotoDiscoveryTtlJob?.cancel()
        hwPhotoDiscoveryTtlJob = viewModelScope.launch {
            delay(HW_PHOTO_DISCOVERY_TTL_MS)
            if (isHwPhotoSyncing) {
                Log.e(TAG, "HW photo P2P discovery TTL expired (${HW_PHOTO_DISCOVERY_TTL_MS}ms), final fail")
                onHwPhotoSyncFailed("P2P 建连超时")
            }
        }

        // 慢速路径：完整 P2P 建连流程
        Log.d(TAG, "No cached P2P IP, starting full P2P flow...")
        // 若预热建连进行中：按在途状态选择接管方式（periodicPreConnectJob 已在慢路径入口取消）。
        // 注：原 supersede 收敛分支（RECONNECTING/ESTABLISHING 被硬件轮接管时 cancel rebuildStageJob
        // 并收敛 IDLE）已删除——实证它杀掉带超时自救的重建协程后软接管死等被眼镜拒绝的
        // connect 结果导致卡死 90s TTL；RECONNECTING 场景改由入口排队守卫覆盖（管线窗口内新拍照键
        // 排队不打断），此处只保留预热在途（ESTABLISHING）一条接管路径
        if (isP2pConnecting) {
            cancelPreConnectWatchdog()  // 接管后预热看门狗作废（硬件轮有自己的等待/TTL 机制）
            p2pPreConnectActive = false  // 失效预热轮回调（@2120-2131 同款双标志衔接模式）
            if (P2PUtils.Instance.isFlowInFlight()) {
                // 软接管（优选）：预热轮已进入发现/建连且可能随时成功（MAC 命中/WPS 已启动）——
                // 不 resetForHandover（避免杀掉即将成功的在途 connect）、不重新 initWifiP2P2
                // （避免触发固件 MAC 再轮换），只换 listener 承接硬件轮语义，
                // 等在途 connect 的 onConnected/onConnectionFailed（发现 BUSY 则走 onDiscoveryFailed 等待态）
                Log.w(TAG, "P2P pre-connect in flight, soft handover: swap listener only, await in-progress connect result")
                P2PUtils.Instance.setFailFastOnEmptyDiscovery(false)  // 硬件轮不做 4.5s 提前判败（复位预热轮置位的开关）
                P2PUtils.Instance.setListener(createHwP2pListener(getApplication<Application>()))
                return
            }
            // 硬接管（兜底）：预热轮尚未进入发现（死在 init 阶段），按传统流程完整复位后重走；
            // resetForHandover 内含 cancelConnect 兜底，系统栈不滞留 BUSY
            Log.w(TAG, "P2P pre-connect not in flight, hard handover: reset and restart full flow")
            try {
                P2PUtils.Instance.resetForHandover()
                P2PUtils.Instance.setListener(null)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to reset P2PUtils for handover", e)
            }
        }
        isP2pConnecting = true
        val context = getApplication<Application>()

        initHwP2pWithRetry(retryLeft = 2, context)
    }

    /** 慢速路径 initWifiP2P2（onFailed 时延时重试，最多 2 次，耗尽后才判败） */
    private fun initHwP2pWithRetry(retryLeft: Int, context: Application) {
        Log.d(TAG, "hw initWifiP2P2 issued (retryLeft=$retryLeft)")
        CxrApi.getInstance().initWifiP2P2(false, object : WifiP2PStatusCallback {
            override fun onConnected() { /* 方案二不会回调 */ }
            override fun onDisconnected() { /* 方案二不会回调 */ }
            override fun onFailed(p0: ValueUtil.CxrWifiErrorCode?) {
                Log.e(TAG, "initWifiP2P2 failed: $p0, retryLeft=$retryLeft")
                if (!isHwPhotoSyncing) return
                if (retryLeft > 0) {
                    viewModelScope.launch {
                        delay(P2P_CMD_RETRY_DELAY_MS)
                        if (isHwPhotoSyncing) initHwP2pWithRetry(retryLeft - 1, context)
                    }
                    return
                }
                isP2pConnecting = false
                onHwPhotoSyncFailed("P2P 初始化失败")
            }

            override fun onP2pDeviceAvailable(name: String?, macAddress: String?, deviceType: String?) {
                Log.d(TAG, "P2P device available: name=$name, mac=$macAddress")
                if (!isHwPhotoSyncing) return

                // 设备快照：重建 Stage 1 按缓存 name/MAC 直接 discover+connect（teardown 不清除）
                lastKnownP2pName = name
                lastKnownP2pMac = macAddress
                P2PUtils.Instance.initP2P(context)
                P2PUtils.Instance.setTargetDevice(name, macAddress)
                // 硬件拍照轮不开启 fail-fast 提前判败：实机存在广播延迟 4.5~8s 后才成功建连的
                // 场景，4.5s 判败窗口会误杀该场景导致成功率下降；恢复“愿意等满建连预算”的
                // 旧行为，等待预算由 hwPhotoConnectTimeoutJob（15s 仅提示）+ TTL 终判（90s）接管。
                // 1.5s 主动轮询（P2PUtils 内部）保留，属纯增益
                // 硬件轮 connect 发起时间戳（诊断：onConnected 处算本次建连耗时）
                hwConnectStartMs = System.currentTimeMillis()
                Log.d(TAG, "HW P2P connect issued at $hwConnectStartMs")
                P2PUtils.Instance.setListener(createHwP2pListener(context))
                P2PUtils.Instance.startDiscoverP2P(context)
            }
        })
    }

    /**
     * 硬件拍照轮 P2PListener（慢速路径与软接管共用）。
     * onDiscoveryFailed 对 BUSY(reason=2) 不即时判败：delay 后重新发起发现，循环直至成功
     * 或 TTL 终判（hwPhotoDiscoveryTtlJob 90s）；reason=0/1（ERROR/P2P_UNSUPPORTED）维持即时判败。
     */
    private fun createHwP2pListener(context: Application): P2PListener {
        return object : P2PListener {
            override fun onDeviceFound(device: android.net.wifi.p2p.WifiP2pDevice) {
                Log.d(TAG, "P2P target device found: ${device.deviceName}")
            }

            override fun onConnected(ipAddress: String) {
                val elapsed = if (hwConnectStartMs > 0) System.currentTimeMillis() - hwConnectStartMs else -1L
                Log.d(TAG, "P2P connected, IP: $ipAddress, 本次建连耗时 ${elapsed}ms, starting photo sync...")
                if (!isHwPhotoSyncing) return
                isP2pConnecting = false
                hwDiscoveryBusyRetryJob?.cancel()
                hwDiscoveryBusyRetryJob = null
                // 快路径恢复：固件 MAC 轮换下 MAC 精确匹配几乎不可能，建连成功即缓存 IP，
                // 供下次快路径使用；陈旧/错误组风险由 5.5s 活性探测兜底
                cachedP2pIp = ipAddress
                isP2pPreConnected = true
                p2pPersistState = P2pPersistState.ESTABLISHED
                Log.i(TAG, "P2P persist: ESTABLISHED (name=$lastKnownP2pName mac=$lastKnownP2pMac)")
                Log.d(TAG, "HW P2P cached IP for fast path (macMatched=${P2PUtils.Instance.lastMacMatchedConnect})")
                // 建连阶段结束：切换到文件同步阶段超时（等待提示 Job 与 TTL 终判 Job 一并取消）
                hwPhotoConnectTimeoutJob?.cancel()
                hwPhotoConnectTimeoutJob = null
                hwPhotoDiscoveryTtlJob?.cancel()
                hwPhotoDiscoveryTtlJob = null
                startHwPhotoSync(ipAddress)
            }

            override fun onConnectionFailed(reason: String) {
                Log.e(TAG, "P2P connection failed: $reason")
                isP2pConnecting = false
                cancelPreConnectWatchdog()  // 软接管承接的预热看门狗在此作废
                onHwPhotoSyncFailed("P2P 连接失败: $reason")
            }

            override fun onDiscoveryStarted() {
                Log.d(TAG, "P2P discovery started")
            }

            override fun onDiscoveryFailed(reason: Int) {
                // BUSY(reason=2)：系统 P2P 栈滞留（半程 connect 残留等）——不即时判败，
                // delay 后重新发起发现，循环直至成功或 TTL 终判（不新建终判机制）；
                // reason=0/1（ERROR/P2P_UNSUPPORTED）维持即时判败现状
                if (reason == 2 && isHwPhotoSyncing) {
                    Log.w(TAG, "HW photo discovery BUSY, retry in ${HW_DISCOVERY_BUSY_RETRY_MS}ms (TTL guard)")
                    hwDiscoveryBusyRetryJob?.cancel()
                    hwDiscoveryBusyRetryJob = viewModelScope.launch {
                        delay(HW_DISCOVERY_BUSY_RETRY_MS)
                        // isHwPhotoSyncing 守卫隔离陈旧回调；isP2pConnecting 防连接已成功/已判败后再触发发现
                        if (isHwPhotoSyncing && isP2pConnecting) {
                            P2PUtils.Instance.startDiscoverP2P(context)
                        }
                    }
                    return
                }
                Log.e(TAG, "P2P discovery failed: $reason")
                isP2pConnecting = false
                onHwPhotoSyncFailed("P2P 发现设备失败")
            }
        }
    }

    /**
     * getUnsyncNum 预检回调（仅减法不做加法）：
     * 仅 RESPONSE_SUCCEED 且 pictureNum==0 时提前判败（眼镜端已无可同步照片，startSync2 必空转）；
     * 其他状态/超时/非零一律静默继续原 startSync2 流程
     */
    private val hwUnsyncNumPreCheckCallback =
        UnsyncNumResultCallback { status, _, pictureNum, _ ->
            if (status == ValueUtil.CxrStatus.RESPONSE_SUCCEED && pictureNum == 0 && isHwPhotoSyncing) {
                Log.w(TAG, "HW photo unsync pre-check: pictureNum=0, failing fast without startSync2")
                onHwPhotoSyncFailed(HW_PHOTO_EMPTY_SYNC_MSG)
            } else {
                Log.d(TAG, "HW photo unsync pre-check: status=$status, pictureNum=$pictureNum, continue startSync2")
            }
        }

    /** P2P 连接成功后，开始同步图片文件 */
    private fun startHwPhotoSync(ipAddress: String) {
        // 文件同步阶段超时保护（建连阶段超时已在进入本函数前取消/未启动）
        hwPhotoConnectTimeoutJob?.cancel()
        hwPhotoConnectTimeoutJob = null
        hwPhotoDiscoveryTtlJob?.cancel()
        hwPhotoDiscoveryTtlJob = null
        hwDiscoveryBusyRetryJob?.cancel()
        hwDiscoveryBusyRetryJob = null
        hwPhotoSyncTimeoutJob?.cancel()
        hwPhotoSyncTimeoutJob = viewModelScope.launch {
            delay(HW_PHOTO_SYNC_TIMEOUT_MS)
            if (isHwPhotoSyncing) {
                Log.e(TAG, "HW photo file sync timeout (${HW_PHOTO_SYNC_TIMEOUT_MS}ms)")
                onHwPhotoSyncFailed("照片同步超时")
            }
        }

        // 活性探测：若窗口内没有收到任何同步回调（典型场景：连上幽灵 peer 后 startSync2 静默空转），
        // 主动判败并整体重来一次；收到回调/成功/失败/15s超时/teardown 时该 Job 均会被取消
        hwSyncActiveSeen = false
        hwSyncFileSeen = false
        hwPhotoSyncLivenessJob?.cancel()
        hwPhotoSyncLivenessJob = viewModelScope.launch {
            delay(HW_PHOTO_SYNC_LIVENESS_MS)
            if (isHwPhotoSyncing && !hwSyncActiveSeen) {
                Log.e(TAG, "HW photo sync liveness check failed: no sync callback in ${HW_PHOTO_SYNC_LIVENESS_MS}ms")
                onHwPhotoSyncLivenessTimeout()
            }
        }

        // 清空并确保同步目录存在（避免残留旧照片干扰）
        val dir = File(HW_PHOTO_SYNC_DIR)
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
        } else {
            dir.mkdirs()
        }

        // 捕获当前轮次 token：活性判败重来后，SDK 内残留的上一轮 syncCallback 若延迟补发终态回调，
        // round 不一致则全部忽略，避免误拆第二轮连接/误判失败/误取消第二轮活性 Job
        val round = hwPhotoSyncRound

        val syncCallback = object : SyncStatusCallback {
            override fun onSyncStart() {
                if (round != hwPhotoSyncRound) {
                    Log.w(TAG, "HW photo sync started callback from stale round $round (current=$hwPhotoSyncRound), ignoring")
                    return
                }
                Log.d(TAG, "HW photo sync started")
                // 活性信号：收到同步开始即认为传输通道存活
                hwSyncActiveSeen = true
                hwPhotoSyncLivenessJob?.cancel()
                hwPhotoSyncLivenessJob = null
            }

            override fun onSingleFileSynced(filename: String?) {
                if (round != hwPhotoSyncRound) {
                    Log.w(TAG, "HW photo onSingleFileSynced from stale round $round (current=$hwPhotoSyncRound), ignoring")
                    return
                }
                Log.d(TAG, "HW photo file synced: $filename")
                // 活性信号（兼容 SDK 不回调 onSyncStart 的情况）
                hwSyncActiveSeen = true
                hwPhotoSyncLivenessJob?.cancel()
                hwPhotoSyncLivenessJob = null
                // 只记录"已收到文件回调"信号，不启动逐文件消费链：统一由 onSyncFinished
                // 扫描目录取最新一张消费，消除双回调竞态与 teardown 抢跑（SDK 回调的
                // filename 是绝对路径，旧版 File(dir, filename) 拼接必出畸形路径，消费链必白耗重试）
                hwSyncFileSeen = true
            }

            override fun onSyncFailed() {
                if (round != hwPhotoSyncRound) {
                    Log.w(TAG, "HW photo onSyncFailed from stale round $round (current=$hwPhotoSyncRound), ignoring")
                    return
                }
                Log.e(TAG, "HW photo sync failed")
                onHwPhotoSyncFailed("照片同步失败")
            }

            override fun onSyncFinished() {
                if (round != hwPhotoSyncRound) {
                    Log.w(TAG, "HW photo onSyncFinished from stale round $round (current=$hwPhotoSyncRound), ignoring")
                    return
                }
                // 活性信号：收到终态回调即证明传输通道存活（兼容 SDK 不经
                // onSyncStart/onSingleFileSynced 直接回 onSyncFinished 的极端场景，
                // 否则 5.5s 活性 Job 到期会误触发一次不必要的整轮重来）
                hwSyncActiveSeen = true
                hwPhotoSyncLivenessJob?.cancel()
                hwPhotoSyncLivenessJob = null
                Log.d(TAG, "HW photo sync finished, fileSeen=$hwSyncFileSeen")
                // 空同步分流：眼镜回 0 文件且全程无文件回调 → 跳过 5×200ms 目录扫描直接判败（省 1s），
                // 文案区别于建连类失败；fileSeen=true 路径维持原 dir scan 行为不动
                if (!hwSyncFileSeen) {
                    Log.e(TAG, "HW sync finished with zero files (fileSeen=false), fail fast with empty-sync text (msgId=$hwPhotoSyncMsgId)")
                    onHwPhotoSyncFailed(HW_PHOTO_EMPTY_SYNC_MSG)
                    return
                }
                // 统一消费入口：先扫目录取最新一张消费（先消费后拆，此时尚未 teardown，
                // isHwPhotoSyncing 仍为 true，刷盘重试不会被守卫杀死）；
                // 15s 超时 Job 不在此取消（由消费/失败/teardown 各自负责，极端不回时兜底仍生效）；
                // 5×200ms 刷盘扫描耗尽后，若有文件回调信号再追加 3×300ms 重试
                consumeHwPhotoFromDir(round, extraRetryLeft = 3)
            }
        }

        // getUnsyncNum 预检（仅减法不做加法）：眼镜端待同步照片为 0 时提前判败（空同步文案分流），
        // 不阻塞流程——查询失败/REQUEST_WAITING/异常/非零均由回调容错，继续走原 startSync2 流程
        try {
            val preCheckStatus = CxrApi.getInstance().getUnsyncNum(hwUnsyncNumPreCheckCallback)
            Log.d(TAG, "HW photo unsync pre-check issued: requestStatus=$preCheckStatus")
        } catch (e: Exception) {
            Log.w(TAG, "HW photo unsync pre-check exception, continue startSync2", e)
        }

        startSync2WithRetry(ipAddress, syncCallback, retryLeft = 2)
    }

    /**
     * 传输阶段活性判败：startSync2 启动后窗口内无任何同步回调（典型为连上幽灵 peer 后静默空转）。
     * 取消当前同步、teardown，然后整体重来一次（重新 init+发现+按 MAC 匹配，复用 initHwP2pWithRetry）；
     * 重来的第二轮因 cachedP2pIp 已被 teardown 清空，必然走慢速路径；最多重来 1 次避免无限循环。
     */
    private fun onHwPhotoSyncLivenessTimeout() {
        if (!isHwPhotoSyncing) return

        // 二次判败放弃：必须在 cleanupP2P 之前判断——cleanupP2P→teardownP2P 会复位 isHwPhotoSyncing=false，
        // 之后再调 onHwPhotoSyncFailed 会进防御性早退分支，消息将永久卡在 uploading；
        // 放弃分支直接交给 onHwPhotoSyncFailed（内部含完整失败处理：消息落 ❌/清 msgId/重新预热）
        if (hwSyncFullRetryUsed) {
            Log.e(TAG, "HW photo sync liveness failed twice, giving up")
            try { CxrApi.getInstance().stopSync() } catch (e: Exception) {
                Log.w(TAG, "stopSync before give-up failed", e)
            }
            onHwPhotoSyncFailed("照片同步无响应")
            return
        }

        // 重来分支：先停止 SDK 内残留的上一轮同步，再取消计时器、拆除连接
        // （teardown 会清 cachedP2pIp，下轮强制慢速路径）
        try { CxrApi.getInstance().stopSync() } catch (e: Exception) {
            Log.w(TAG, "stopSync before liveness retry failed", e)
        }
        hwPhotoSyncTimeoutJob?.cancel()
        hwPhotoSyncTimeoutJob = null
        hwPhotoSyncLivenessJob?.cancel()
        hwPhotoSyncLivenessJob = null
        hwSyncActiveSeen = false
        cleanupP2P()

        hwSyncFullRetryUsed = true
        hwPhotoSyncRound++  // 递增轮次 token，使第一轮残留的 syncCallback 全部失效
        Log.w(TAG, "HW photo sync liveness failed, restarting full P2P flow (slow path, 1 retry, round=$hwPhotoSyncRound)")
        isP2pConnecting = true
        isHwPhotoSyncing = true
        // 建连阶段等待提示（与首轮慢速路径一致：到点仅更新文案不判败；TTL 终判 Job 由首轮启动仍在跑，不重复创建）
        hwPhotoConnectTimeoutJob?.cancel()
        hwPhotoConnectTimeoutJob = viewModelScope.launch {
            delay(HW_PHOTO_CONNECT_TIMEOUT_MS)
            if (isHwPhotoSyncing) {
                Log.w(TAG, "HW photo P2P connect budget exhausted after liveness retry (${HW_PHOTO_CONNECT_TIMEOUT_MS}ms), keep waiting")
                val waitingMsgId = hwPhotoSyncMsgId
                if (waitingMsgId != null) {
                    _uiState.update { state ->
                        state.copy(messages = state.messages.map {
                            if (it.id == waitingMsgId) it.copy(content = "⏳ 等待眼镜就绪…") else it
                        })
                    }
                }
            }
        }
        initHwP2pWithRetry(retryLeft = 2, getApplication<Application>())
    }

    /** startSync2 返回 false 时延时重试（最多 2 次），耗尽后才判败 */
    private fun startSync2WithRetry(ipAddress: String, callback: SyncStatusCallback, retryLeft: Int) {
        if (!isHwPhotoSyncing) return
        Log.d(TAG, "startSync2 issued (ip=$ipAddress, retryLeft=$retryLeft)")
        val result = CxrApi.getInstance().startSync2(
            HW_PHOTO_SYNC_DIR,
            arrayOf(ValueUtil.CxrMediaType.PICTURE),
            ipAddress,
            callback
        )
        if (!result) {
            Log.e(TAG, "startSync2 returned false, retryLeft=$retryLeft")
            if (retryLeft > 0) {
                viewModelScope.launch {
                    delay(P2P_CMD_RETRY_DELAY_MS)
                    startSync2WithRetry(ipAddress, callback, retryLeft - 1)
                }
            } else if (!hwSyncFullRetryUsed) {
                // 重试耗尽且整轮重来尚未用过：连接通道异常，升级整轮重建（复用活性判败重来骨架，
                // 其内部 hwSyncFullRetryUsed 守卫保证最多重来 1 次，耗尽后走原判败）
                Log.w(TAG, "startSync2 retries exhausted, escalate to full-round rebuild")
                onHwPhotoSyncLivenessTimeout()
            } else {
                onHwPhotoSyncFailed("启动同步失败")
            }
        }
    }

    /**
     * 统一消费入口（onSyncFinished 调用）：扫描同步目录取最新一张照片消费。
     * 顺序硬约束："先消费后拆"——onHwPhotoSynced/onHwPhotoSyncFailed 内部各自完成
     * pending 设置、1s 自动确认 arm、Job 取消与 teardown；本函数绝不在消费链发起前 teardown，
     * 否则 isHwPhotoSyncing 守卫会静默杀死消费导致消息永久卡 uploading。
     * 刷盘等待：最多 5×200ms 轮询"目录里出现文件"；若有文件回调信号而目录仍空，
     * 追加 3×300ms 重试；全程轮次守卫防陈旧回调误消费。
     */
    private fun consumeHwPhotoFromDir(round: Int, scanRetryLeft: Int = 5, extraRetryLeft: Int = 0) {
        if (round != hwPhotoSyncRound) {
            Log.w(TAG, "consumeHwPhotoFromDir: stale round $round (current=$hwPhotoSyncRound), abort (msgId=$hwPhotoSyncMsgId)")
            return
        }
        if (!isHwPhotoSyncing) {
            // 已被 15s 超时/活性判败等路径 teardown：不重复消费；最终判败由那些路径负责
            Log.w(TAG, "consumeHwPhotoFromDir: sync already torn down, skip consume (msgId=$hwPhotoSyncMsgId)")
            return
        }
        val latest = findLatestSyncedPhoto()
        if (latest != null) {
            val count = File(HW_PHOTO_SYNC_DIR).listFiles()
                ?.count { it.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp") } ?: 0
            // 找到后再等一拍（200ms）确保文件写完，然后消费（onHwPhotoSynced 内部完成 teardown）
            Log.d(TAG, "HW sync dir scan: found=$count files, latest=${latest.name}, waiting 200ms for flush before consume")
            viewModelScope.launch {
                delay(200L)
                if (round != hwPhotoSyncRound) {
                    Log.w(TAG, "consumeHwPhotoFromDir: stale round $round after flush wait, abort consume (msgId=$hwPhotoSyncMsgId)")
                    return@launch
                }
                onHwPhotoSynced(latest)
            }
        } else if (scanRetryLeft > 0) {
            // 刷盘等待：目录里尚未出现文件（文件回调与落盘存在窗口），200ms 后重扫
            Log.d(TAG, "HW sync dir scan: no photo yet, retrying... ($scanRetryLeft left, fileSeen=$hwSyncFileSeen)")
            viewModelScope.launch {
                delay(200L)
                consumeHwPhotoFromDir(round, scanRetryLeft - 1, extraRetryLeft)
            }
        } else if (hwSyncFileSeen && extraRetryLeft > 0) {
            // 收到过文件回调但目录仍空：再给一轮短重试（3×300ms）
            Log.w(TAG, "HW sync dir scan: no photo but file callback seen, extra retry ($extraRetryLeft left, msgId=$hwPhotoSyncMsgId)")
            viewModelScope.launch {
                delay(300L)
                consumeHwPhotoFromDir(round, 0, extraRetryLeft - 1)
            }
        } else if (hwSyncFileSeen) {
            // 有文件回调信号但多轮重试后仍无文件：落 ❌ 后 teardown（onHwPhotoSyncFailed 内部处理）
            Log.e(TAG, "HW sync finished but photo file never appeared in dir (fileSeen=true), fail (msgId=$hwPhotoSyncMsgId)")
            onHwPhotoSyncFailed("同步完成但未找到照片文件")
        } else {
            // 无任何文件回调信号且目录空：落 ❌ 兜底，避免消息卡 uploading（理论上已被 onSyncFinished
            // fileSeen=false 快路径拦截，此处为直接调用本函数的兜底分支）
            Log.e(TAG, "HW sync finished with empty dir and no file callback, fail (msgId=$hwPhotoSyncMsgId)")
            onHwPhotoSyncFailed(HW_PHOTO_EMPTY_SYNC_MSG)
        }
    }

    /**
     * 在同步目录中查找最新的一张图片文件：文件名时间戳优先。
     * 文件名格式 img-yyyyMMdd-HHmmss-*.jpg，提取中间时间戳段按字典序（=时间序）取最大；
     * 格式不符的文件回退 lastModified，且恒排在格式匹配的文件之后
     * （眼镜端残留旧照片会被全量同步下来，lastModified 不可靠，必按文件名时间戳甄别）
     */
    private fun findLatestSyncedPhoto(): File? {
        val dir = File(HW_PHOTO_SYNC_DIR)
        return dir.listFiles()
            ?.filter { it.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp") }
            ?.maxByOrNull { photoSortKey(it) }
    }

    /** 照片排序 key：格式匹配返回 "1-{时间戳数字}"（字典序=时间序）；
     *  不匹配回退 "0-{lastModified}"，前缀 0 保证恒排在格式匹配文件之后 */
    private fun photoSortKey(f: File): String {
        val ts = Regex("^img-(\\d{8})-(\\d{6})-").find(f.name)?.let { m ->
            (m.groupValues[1] + m.groupValues[2]).filter { c -> c.isDigit() }
        }
        return if (!ts.isNullOrEmpty()) "1-$ts" else "0-${f.lastModified()}"
    }

    /** P2P 同步拿到照片后，进入确认流程（与 BLE 拍照确认流程一致）。
     *  调用时机保证在 teardown 之前（consumeHwPhotoFromDir "先消费后拆"），
     *  守卫仅拦 15s 超时/活性判败已先行 teardown 的极端场景 */
    private fun onHwPhotoSynced(file: File) {
        if (!isHwPhotoSyncing) {
            Log.w(TAG, "onHwPhotoSynced: sync already torn down, skip consume (msgId=$hwPhotoSyncMsgId, file=${file.name})")
            return
        }
        isHwPhotoSyncing = false
        hwPhotoConnectTimeoutJob?.cancel()
        hwPhotoConnectTimeoutJob = null
        hwPhotoDiscoveryTtlJob?.cancel()
        hwPhotoDiscoveryTtlJob = null
        hwDiscoveryBusyRetryJob?.cancel()
        hwDiscoveryBusyRetryJob = null
        hwPhotoSyncTimeoutJob?.cancel()
        hwPhotoSyncTimeoutJob = null
        hwPhotoSyncLivenessJob?.cancel()
        hwPhotoSyncLivenessJob = null
        // 完全断开 P2P（含 removeGroup/deinit）：P2P 组存活会抢占手机 Wi-Fi 路由，
        // 导致后续上传到局域网后端超时；下次拍照前会重新 preConnectP2P 预热
        cleanupP2P()
        // 同步成功：P2P 组已拆（上传期间不允许存活）。状态置“上传感知拆组”，
        // 重建交给上传终态出口（confirmPhoto 两处）的 rebuildPersistentConnection 三级收敛；
        // 原“成功即 re-arm 周期预热”已删除：周期循环会在上传完成前重建连接抢占 Wi-Fi 路由拖垮上传
        p2pPersistState = P2pPersistState.TORN_DOWN_FOR_UPLOAD
        Log.i(TAG, "P2P persist: torn down for upload")
        // 注意：此处不 flush 排队消息——管线未终态（后续还有 PHOTO_CONFIRM/上传/重建段），
        // flush 统一延迟到管线真正终态（重建 ESTABLISHED/收敛 IDLE/失败终态/teardown）

        val msgId = hwPhotoSyncMsgId
        hwPhotoSyncMsgId = null
        if (msgId == null) {
            Log.w(TAG, "onHwPhotoSynced: hwPhotoSyncMsgId is null, photo consumed but no message to update (file=${file.name})")
            // 这是不应出现的状态损坏；仍必须收敛管线，避免入口占位与 P2P 状态永久卡住。
            when (_uiState.value.runStatus) {
                "waiting_input" -> _uiState.update { it.copy(interactionMode = InteractionMode.LISTENING) }
                "running" -> _uiState.update { it.copy(interactionMode = InteractionMode.PROCESSING) }
                "succeeded", "failed", "cancelled", "aborted" -> _uiState.update { it.copy(interactionMode = InteractionMode.COMPLETED) }
            }
            releaseHardwarePhotoRound()
            rebuildPersistentConnection()
            return
        }
        Log.d(TAG, "HW photo synced: ${file.name}, size=${file.length() / 1024}KB")

        // 立即转移到 cacheDir/psop_photos（与 BLE 拍照存放方式统一）：
        // 同步目录下次硬件拍照时会被整体清空，若 UI 继续用 file:// 原路径展示必现破图
        val photoFile = moveHwPhotoToCache(file)

        // 走现有确认流程：暂存文件 → PHOTO_CONFIRM → 缩略图 → 自动上传
        pendingPhotoFile = photoFile
        pendingPhotoMsgId = msgId
        pendingStatusMsgId = msgId

        // 利用确认等待期提前完成压缩（与 1s 自动确认并行，未完成时 confirmPhoto 会 join 等待），确认后直接上传
        preparePhotoAsync { compressImageIfNeeded(photoFile) }

        _uiState.update { it.copy(interactionMode = InteractionMode.PHOTO_CONFIRM) }

        // 向眼镜端投射确认界面（缩略图+确认提示）——受临时开关控制，关闭时跳过显示但仍走 1s 自动确认
        if (ENABLE_GLASSES_PHOTO_CONFIRM_VIEW) {
            // 发送缩略图到眼镜端展示
            val thumbData = photoFile.readBytes()
            sendPhotoThumbnail(thumbData)
            openPhotoConfirmView()
            enqueueTts("照片已同步，长按触摸板确认，否则自动上传")
        } else {
            Log.d(TAG, "Glasses photo confirm view disabled by ENABLE_GLASSES_PHOTO_CONFIRM_VIEW, skip display")
        }

        // 1 秒后自动确认上传：无条件上传——照片同步成功意味着文件已在本地，
        // 上传永远是无副作用的正确动作；即便 interactionMode 在 1s 内被 run 状态事件
        // 改写，只要 pending 尚未被消费也照常 confirmPhoto()，堵住原 no-op 分支
        // 导致消息永久卡"发送中"、长按自救失效的缺口
        photoConfirmTimeoutJob?.cancel()
        photoConfirmTimeoutJob = viewModelScope.launch {
            delay(1_000L)
            if (pendingPhotoFile != null || pendingPhotoMsgId != null) {
                val mode = _uiState.value.interactionMode
                if (mode != InteractionMode.PHOTO_CONFIRM) {
                    Log.w(TAG, "HW auto-confirm: mode changed to $mode within 1s window, still uploading (photo already local)")
                } else {
                    Log.d(TAG, "HW photo auto-upload after 1s")
                }
                confirmPhoto()
            } else {
                // pending 已被 confirmPhoto（长按/提前触发）或 cancelPhotoConfirm 消费：
                // 防重入跳过，绝不二次 confirm（confirmPhoto 早退会把已上传消息改判 failed）
                Log.w(TAG, "HW auto-confirm skipped: pending already consumed (mode=${_uiState.value.interactionMode})")
            }
        }
    }

    /** P2P 同步失败处理：显示错误，恢复状态 */
    private fun onHwPhotoSyncFailed(reason: String) {
        if (!isHwPhotoSyncing) {
            // 防御性重置：即使不在同步中，也确保 interactionMode 不卡在 PHOTO_CAPTURE
            if (_uiState.value.interactionMode == InteractionMode.PHOTO_CAPTURE) {
                Log.w(TAG, "onHwPhotoSyncFailed: isHwPhotoSyncing=false but mode stuck in PHOTO_CAPTURE, force reset")
                _uiState.update { it.copy(interactionMode = InteractionMode.LISTENING) }
            }
            return
        }
        isHwPhotoSyncing = false
        hwPhotoConnectTimeoutJob?.cancel()
        hwPhotoConnectTimeoutJob = null
        hwPhotoDiscoveryTtlJob?.cancel()
        hwPhotoDiscoveryTtlJob = null
        hwDiscoveryBusyRetryJob?.cancel()
        hwDiscoveryBusyRetryJob = null
        hwPhotoSyncTimeoutJob?.cancel()
        hwPhotoSyncTimeoutJob = null
        hwPhotoSyncLivenessJob?.cancel()
        hwPhotoSyncLivenessJob = null
        cleanupP2P()
        // 失败终态补发：管线到此结束（后续重建由 scheduleP2pPreConnect 驱动，不属管线窗口）
        flushQueuedPhotoMessages()

        val msgId = hwPhotoSyncMsgId
        hwPhotoSyncMsgId = null
        Log.e(TAG, "HW photo sync failed: $reason")

        if (msgId != null) {
            _uiState.update { state ->
                val updatedMessages = state.messages.map {
                    if (it.id == msgId) it.copy(content = "❌ $reason", uploadStatus = "failed")
                    else it
                }
                state.copy(messages = updatedMessages)
            }
        }
        _uiState.update { it.copy(interactionMode = InteractionMode.LISTENING) }
        // 同步失败也重新预热 P2P（周期化：失败后隔段再试，捕获固件广播窗口），保证下次硬件拍照仍可快速同步
        scheduleP2pPreConnect()
        schedulePeriodicPreConnect()
        // 出队点⑤：失败终态——排队照片获得重试机会（mode 已回 LISTENING，管线已关）
        releaseHardwarePhotoRound()
        tryStartQueuedPhotoRound()
    }

    /** 同步结束后完全断开 P2P（上传期间不允许 P2P 组存活，避免抢占局域网 Wi-Fi 路由） */
    private fun cleanupP2P() {
        teardownP2P()
    }

    /**
     * 将硬件同步的照片移动到 cacheDir/psop_photos（与 BLE 拍照存放位置统一），并删除同步目录原文件。
     * 同步目录会被下次 startHwPhotoSync 整体清空，留在原路径会导致聊天中的 file:// 图片失效
     */
    private fun moveHwPhotoToCache(file: File): File {
        return try {
            val cacheDir = File(getApplication<Application>().cacheDir, "psop_photos")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val target = File(cacheDir, "hwphoto_${System.currentTimeMillis()}_${file.name}")
            file.copyTo(target, overwrite = true)
            file.delete()
            Log.d(TAG, "HW photo moved to cacheDir: ${target.name}")
            target
        } catch (e: Exception) {
            Log.w(TAG, "Failed to move hw photo to cacheDir, fallback to original file", e)
            file
        }
    }

    /**
     * 确认等待期提前执行照片准备（保存/压缩），与自动确认倒计时并行，确认后直接上传。
     * prepare 在 IO 线程执行，结果存入 prePreparedPhotoFile；异常时置空走 confirmPhoto 回退逻辑。
     */
    private fun preparePhotoAsync(prepare: suspend () -> File?) {
        photoPrepareJob?.cancel()
        prePreparedPhotoFile = null
        photoPrepareJob = viewModelScope.launch(Dispatchers.IO) {
            prePreparedPhotoFile = try {
                prepare()
            } catch (e: Exception) {
                Log.w(TAG, "Photo prepare ahead failed", e)
                null
            }
        }
    }

    /** 清理提前压缩的暂存文件与任务（取消时调用） */
    private fun clearPreparedPhoto() {
        photoPrepareJob?.cancel()
        photoPrepareJob = null
        val prepared = prePreparedPhotoFile
        prePreparedPhotoFile = null
        if (prepared != null && prepared.exists() && prepared != pendingPhotoFile) {
            prepared.delete()
        }
    }

    /**
     * 上传结束后（成功或最终失败）重新预热 P2P，为下一次硬件拍照做准备。
     * 通过 viewModelScope 调度：ViewModel 已清理时协程不会执行，不会重建连接。
     */
    private fun scheduleP2pPreConnect() {
        viewModelScope.launch {
            try {
                preConnectP2P()
            } catch (e: Exception) {
                Log.w(TAG, "Re-preconnect P2P failed", e)
            }
        }
    }

    /**
     * 周期化预热（固件“广播窗口”对策）：眼镜大部分时间不广播 P2P，约 43~52s 开一次窗；
     * 单轮 failFast 判败后不停摆，隔 PRE_CONNECT_PERIOD_INTERVAL_MS 再试一轮，直至预热成功或 Job 被取消。
     * 生命周期：preConnect onConnectionFailed / 硬件同步终态（onHwPhotoSynced/Failed）arm；
     * 预热成功（onConnected 缓存 IP）/ teardownP2P / onCleared（viewModelScope 取消）时取消。
     * 防重复：复用 preConnectP2P 既有幂等守卫（isP2pPreConnected || isP2pConnecting），不新造机制。
     */
    private fun schedulePeriodicPreConnect() {
        if (periodicPreConnectJob?.isActive == true) return
        // 已 ESTABLISHED 不 arm：常驻连接存活时周期循环无意义（循环内 isP2pPreConnected 守卫也会立即 break，此处提前收敛）
        if (p2pPersistState == P2pPersistState.ESTABLISHED) return
        periodicPreConnectJob = viewModelScope.launch {
            while (isActive) {
                if (isP2pPreConnected) {
                    Log.d(TAG, "Periodic pre-connect: already pre-connected, stop loop")
                    break
                }
                preConnectP2P()  // failFast=true 保持现状（约 4.5s 判败），失败后走下方间隔再试
                delay(PRE_CONNECT_PERIOD_INTERVAL_MS)
            }
        }
    }

    /**
     * 上传感知重建（核心改动点 B）：confirmPhoto 两个上传终态出口调用，重建常驻 P2P 连接。
     * 三级收敛（每段限时 Job + 双守卫，任何分支都不挂起、不挂周期循环）：
     *   Stage 1：不 init（避免触发固件 MAC 轮换），按缓存 name/MAC 快照 discover+connect，限时 10s；
     *   Stage 2：失败→完整 initWifiP2P2（复用 preConnectP2PInternal(rearm=false) + 25s 看门狗预算）；
     *   Stage 3：再失败→收敛回 IDLE，不挂任何周期循环；下次拍照键走慢路径（15s 提示+90s TTL 既有兜底）。
     * RECONNECTING 期间置 isP2pConnecting=true：期间拍照键到达能命中 syncHardwarePhoto 软/硬接管分支。
     */
    private fun rebuildPersistentConnection() {
        // 在途同步/建连中：不重复发起（接管/现有流程会驱动到终态）
        if (isHwPhotoSyncing || isP2pConnecting) {
            Log.d(TAG, "P2P persist: rebuild skipped, flow in progress (syncing=$isHwPhotoSyncing, connecting=$isP2pConnecting)")
            return
        }
        // 已是常驻连接：无需重建；此时即管线终态，排队消息就地 flush
        if (p2pPersistState == P2pPersistState.ESTABLISHED && isP2pPreConnected && cachedP2pIp != null) {
            Log.d(TAG, "P2P persist: rebuild skipped, already ESTABLISHED")
            flushQueuedPhotoMessages()
            // 出队点④：无需重建即终态，排队照片可直接快路径回传
            tryStartQueuedPhotoRound()
            return
        }
        Log.i(TAG, "P2P persist: rebuild start (state=$p2pPersistState)")
        p2pPersistState = P2pPersistState.RECONNECTING
        isP2pConnecting = true  // 接管兼容：期间拍照键命中既有软/硬接管分支
        rebuildStage1()
    }

    /** 重建 Stage 1：不 init，按快照 name/MAC 直接 discover+connect，限时 P2P_REBUILD_STAGE1_MS */
    private fun rebuildStage1() {
        val name = lastKnownP2pName
        val mac = lastKnownP2pMac
        if (name == null && mac == null) {
            // 无快照（首次建连前就拆过等极端场景）：直接走完整 init
            Log.d(TAG, "P2P persist: rebuild stage 1 skipped, no device snapshot")
            rebuildStage2()
            return
        }
        Log.i(TAG, "P2P persist: rebuild stage 1: reuse MAC discover (name=$name mac=$mac)")
        rebuildStageStartMs = System.currentTimeMillis()
        rebuildStageJob?.cancel()
        rebuildStageJob = viewModelScope.launch {
            delay(P2P_REBUILD_STAGE1_MS)
            // 双守卫：仍在重建中且未被硬件轮接管（接管会把状态收敛回 IDLE）
            if (p2pPersistState == P2pPersistState.RECONNECTING && isP2pConnecting) {
                Log.w(TAG, "P2P persist: rebuild stage 1 timeout (${P2P_REBUILD_STAGE1_MS}ms), escalate to stage 2")
                rebuildEscalateToStage2("stage 1 timeout")
            }
        }
        val context = getApplication<Application>()
        P2PUtils.Instance.initP2P(context)
        P2PUtils.Instance.setTargetDevice(name, mac)
        // Stage 1 开 failFast（~4.5s 无目标提前判败）：重建是后台行为，快失败进 Stage 2 比挂满 10s 更优
        P2PUtils.Instance.setFailFastOnEmptyDiscovery(true)
        P2PUtils.Instance.setListener(rebuildP2pListener)
        P2PUtils.Instance.startDiscoverP2P(context)
    }

    /** 重建 Stage 1 专用监听器：成功→ESTABLISHED；任何失败→升级 Stage 2（P2PUtils 回调在主 looper） */
    private val rebuildP2pListener = object : P2PListener {
        override fun onDeviceFound(device: android.net.wifi.p2p.WifiP2pDevice) {
            Log.d(TAG, "P2P persist: rebuild stage 1 device found ${device.deviceName}")
        }

        override fun onConnected(ipAddress: String) {
            if (p2pPersistState != P2pPersistState.RECONNECTING) return  // 已终态/被接管，陈旧回调不碰共享状态
            rebuildStageJob?.cancel()
            rebuildStageJob = null
            isP2pConnecting = false
            cachedP2pIp = ipAddress
            isP2pPreConnected = true
            p2pPersistState = P2pPersistState.ESTABLISHED
            val elapsed = System.currentTimeMillis() - rebuildStageStartMs
            Log.i(TAG, "P2P persist: rebuild stage 1 ok in ${elapsed}ms (ESTABLISHED, ip=$ipAddress)")
            // 管线终态（重建完成）：统一 flush 排队消息
            flushQueuedPhotoMessages()
            // 出队点②：重建 Stage 1 成功（ESTABLISHED + cachedP2pIp 有效，排队照片快路径回传）
            tryStartQueuedPhotoRound()
        }

        override fun onConnectionFailed(reason: String) {
            if (p2pPersistState != P2pPersistState.RECONNECTING) return
            rebuildEscalateToStage2("stage 1 connection failed: $reason")
        }

        override fun onDiscoveryStarted() {}

        override fun onDiscoveryFailed(reason: Int) {
            if (p2pPersistState != P2pPersistState.RECONNECTING) return
            rebuildEscalateToStage2("stage 1 discovery failed: $reason")
        }
    }

    /** Stage 1 → Stage 2 升级：先停掉 Stage 1 发现/在途重试，再走完整 init 流程 */
    private fun rebuildEscalateToStage2(reason: String) {
        if (p2pPersistState != P2pPersistState.RECONNECTING) return
        Log.w(TAG, "P2P persist: rebuild escalating to stage 2 ($reason)")
        rebuildStageJob?.cancel()
        rebuildStageJob = null
        try {
            P2PUtils.Instance.cancelPendingRetryRunnables()
            P2PUtils.Instance.stopDiscoverP2P()
            P2PUtils.Instance.setListener(null)
        } catch (e: Exception) {
            Log.w(TAG, "P2P persist: stage 1 cleanup error", e)
        }
        rebuildStage2()
    }

    /** 重建 Stage 2：完整 initWifiP2P2 流程（复用 preConnectP2PInternal(rearm=false) + 25s 看门狗预算） */
    private fun rebuildStage2() {
        if (p2pPersistState != P2pPersistState.RECONNECTING) return
        Log.i(TAG, "P2P persist: rebuild stage 2: full init")
        rebuildStageStartMs = System.currentTimeMillis()
        // preConnectP2PInternal 幂等守卫要求空闲：重建语义下先复位，其内部会重新置位
        isP2pConnecting = false
        preConnectP2PInternal(rearmPeriodicOnFail = false)
        // preConnectP2PInternal 内部置 ESTABLISHING（通用预热语义）；本轮属重建，恢复 RECONNECTING，
        // 成功时其 onConnected 会置 ESTABLISHED，失败出口（rearm=false）走 rebuildConvergeToIdle
        p2pPersistState = P2pPersistState.RECONNECTING
    }

    /**
     * 重建 Stage 3 收敛口：回 IDLE，不挂任何周期循环（无人值守不挂起）；
     * 下次拍照键自然走慢路径（15s 提示+90s TTL 既有兜底）。
     */
    private fun rebuildConvergeToIdle(reason: String) {
        rebuildStageJob?.cancel()
        rebuildStageJob = null
        isP2pConnecting = false
        p2pPreConnectActive = false
        cachedP2pIp = null
        isP2pPreConnected = false
        p2pPersistState = P2pPersistState.IDLE
        Log.i(TAG, "P2P persist: rebuild converged to IDLE ($reason), no periodic loop armed")
        // 管线终态（重建失败收敛）：统一 flush 排队消息
        flushQueuedPhotoMessages()
        // 出队点①：重建收敛 IDLE——排队照片重试机会（下轮走慢路径既有兜底）
        tryStartQueuedPhotoRound()
    }

    /** 上传图片大小上限：4MB（画质优先：仅压缩超过 4MB 的图，压缩参数保持原有） */
    private val MAX_UPLOAD_SIZE = 4 * 1024 * 1024L

    /**
     * 压缩图片文件至 ≤4MB（生成独立压缩副本，不覆盖原图，保留手机端查看原图的质量）：
     * 1. 文件已 ≤4MB → 直接返回原文件
     * 2. 超过 → 降低 JPEG 质量（90→80→...→50）
     * 3. 质量降到底仍超 → 缩小分辨率（每次缩一半）再重试
     */
    private fun compressImageIfNeeded(file: File): File {
        if (file.length() <= MAX_UPLOAD_SIZE) return file
        if (!file.extension.lowercase().let { it in listOf("jpg", "jpeg", "png", "webp") }) return file

        Log.i(TAG, "Image too large (${file.length() / 1024}KB), compressing to ≤4MB...")
        var bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return file
        val cacheDir = File(getApplication<Application>().cacheDir, "psop_photos")
        if (!cacheDir.exists()) cacheDir.mkdirs()

        var quality = 90
        while (true) {
            val compressed = File.createTempFile("compressed_", ".jpg", cacheDir)
            compressed.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
            if (compressed.length() <= MAX_UPLOAD_SIZE || quality <= 50) {
                // 质量降到底仍超 → 缩小分辨率重试
                if (compressed.length() > MAX_UPLOAD_SIZE && bitmap.width > 800) {
                    compressed.delete()
                    val scaled = Bitmap.createScaledBitmap(bitmap, bitmap.width / 2, bitmap.height / 2, true)
                    bitmap.recycle()
                    bitmap = scaled
                    quality = 90
                    continue
                }
                val finalWidth = bitmap.width
                val finalHeight = bitmap.height
                bitmap.recycle()
                Log.i(TAG, "Compressed: ${file.length() / 1024}KB → ${compressed.length() / 1024}KB (quality=$quality, ${finalWidth}x${finalHeight})")
                return compressed
            }
            compressed.delete()
            quality -= 10
        }
    }

    /**
     * 统一的文件上传工具方法：构造 event JSON、filePart、caption 并调用 Repository
     * caption 参数当前未发送给服务端（文档未定义），保留以备将来使用
     * idempotencyKey 可由调用方传入（重试时复用同一 key）；为 null 时新生成
     */
    private suspend fun doUploadTerminalFile(runId: String, file: File, caption: String?, idempotencyKey: String? = null) {
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
        val filePart = MultipartBody.Part.createFormData("files", file.name, requestFile)

        // 生成统一的幂等键（与 Repository 共用同一生成逻辑；重试场景由调用方传入复用）
        val key = idempotencyKey ?: repository.generateIdempotencyKey()

        // 构造 event JSON，external_event_id 放在 event JSON 内部
        val eventJson = JSONObject().apply {
            put("direction", "input")
            put("event_kind", "terminal.multimodal.input.v1")
            put("mime_type", "multipart/mixed")
            put("external_event_id", key)
            put("source", JSONObject().apply { put("kind", "external_terminal") })
        }.toString()
        val eventBody = eventJson.toRequestBody("application/json".toMediaType())

        repository.uploadTerminalFile(
            runId = runId,
            idempotencyKey = key,
            event = eventBody,
            files = listOf(filePart)
        )
    }

    /**
     * 带重试的上传包装：首次失败后按指数退避（1s/2s）重试，最多重试 1 次；
     * 整体时限 40s，超时转为 RuntimeException 抛出（由调用方 catch 落 failed 终态）。
     * 幂等键全程复用，服务端可据此去重，重试不会产生重复事件。
     * 全部失败后抛出最后一次异常。
     * 4xx 客户端错误（408/429 除外）不重试，避免无意义等待。
     */
    private suspend fun doUploadTerminalFileWithRetry(
        runId: String,
        file: File,
        caption: String?,
        maxRetries: Int = 1
    ) {
        val key = repository.generateIdempotencyKey()
        var lastError: Exception? = null
        var uploaded = false
        try {
            withTimeout(40_000L) {
                for (attempt in 0..maxRetries) {
                    if (attempt > 0) {
                        val backoffMs = 1_000L * (1L shl (attempt - 1)) // 1s, 2s
                        Log.w(TAG, "Upload retry #$attempt in ${backoffMs}ms, file=${file.name}")
                        delay(backoffMs)
                    }
                    try {
                        doUploadTerminalFile(runId, file, caption, key)
                        uploaded = true
                        break
                    } catch (e: Exception) {
                        val clientError = e is retrofit2.HttpException &&
                            e.code() in 400..499 && e.code() != 408 && e.code() != 429
                        if (clientError) {
                            Log.e(TAG, "Upload failed with client error ${e.code()}, no retry", e)
                            throw e
                        }
                        lastError = e
                        Log.w(TAG, "Upload attempt ${attempt + 1} failed: ${e.message}")
                    }
                }
            }
        } catch (t: TimeoutCancellationException) {
            Log.e(TAG, "Upload exceeded total time limit (40s), giving up", t)
            throw RuntimeException("上传总时限(40s)已到", t)
        }
        if (!uploaded) throw lastError!!
    }

    /**
     * 补齐断线期间遗漏的终端事件
     */
    private suspend fun recoverMissedEvents(runId: String, fromSeq: Int) {
        try {
            Log.d(TAG, "Recovering missed events from seq=$fromSeq")
            val events = repository.recoverMissedEvents(runId, fromSeq)
            Log.d("PSOP_DEBUG", "Recover: got ${events.size} events from API")
            events.forEach { e ->
                Log.d("PSOP_DEBUG", "Recover: id=${e.id.take(8)}... dir=${e.direction} parts=${e.parts.size} eventKind=${e.eventKind}")
            }
            if (events.isEmpty()) return

            val existingIds = _uiState.value.messages.map { it.id }.toSet()

            val newMessages = events
                .filter { it.direction == "output" } // 只恢复 output 事件（input 消息本地已有，服务端 ID 与本地 ID 不同会导致重复）
                .filter { it.id !in existingIds } // 去重
                .map { event ->
                    val content: String = if (event.parts.isNotEmpty()) {
                        val textParts = event.parts.filter { it.kind == "text" || it.mimeType.startsWith("text/") }.map { it.text }
                        val mediaParts = event.parts.filter { it.mimeType.startsWith("image/") || it.mimeType.startsWith("audio/") || it.mimeType.startsWith("video/") }.map { part ->
                            when {
                                part.mimeType.startsWith("image/") -> "[图片]"
                                part.mimeType.startsWith("audio/") -> "[音频]"
                                part.mimeType.startsWith("video/") -> "[视频]"
                                else -> "[文件]"
                            }
                        }
                        (textParts + mediaParts).joinToString(" ")
                    } else {
                        ""
                    }
                    Log.d("PSOP_DEBUG", "Recover content: parts=${event.parts.size}, contentLen=${content.length}, content=|${content.take(100)}|")
                    // 为所有方向的媒体 parts 构建 contentUrl
                    val imageParts = event.parts
                        .filter { it.mimeType.startsWith("image/") || it.mimeType.startsWith("video/") || it.mimeType.startsWith("audio/") }
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
                        content = content,
                        timestamp = event.occurredAt,
                        seqNo = event.seqNo,
                        eventKind = event.eventKind,
                        mimeType = event.mimeType,
                        artifactObjectId = event.artifactObjectId,
                        parts = imageParts
                    )
                }

            if (newMessages.isNotEmpty()) {
                // 照片管线窗口内（断线恢复也可能落在窗口内：P2P 建连抢占 Wi-Fi 路由导致 WS 重连）：
                // 与实时路径同语义——output 消息整条排队不入列表，管线终态后统一补发
                val pipelineActive = isPhotoPipelineActive()
                val (queued, added) = if (pipelineActive) {
                    newMessages.partition { it.direction == "output" }
                } else Pair(emptyList(), newMessages)
                queued.forEach { enqueuePhotoPipelineMessage(it) }
                _uiState.update { state ->
                    state.copy(messages = state.messages + added)
                }
                // 更新本地 seq
                val maxSeq = events.maxOf { it.seqNo }
                lastSeqNo = maxOf(lastSeqNo, maxSeq)
                Log.d(TAG, "Recovered ${newMessages.size} events, lastSeqNo=$lastSeqNo")

                // 只对有实际文字内容（去掉媒体占位符后）的消息 TTS 播报 + 眼镜端显示
                if (pipelineActive) {
                    // output 已整条排队（含眼镜显示/TTS），此处无需再处理
                    Log.w(TAG, "Recovered events inside photo pipeline window: ${queued.size} output queued")
                } else if (isPhotoConfirmViewOpen) {
                    // 照片确认窗口内：不重开提词器、不播报 TTS，避免覆盖确认界面与 BLE 争用（消息已入聊天列表）
                    Log.w(TAG, "Photo confirm view open: skip teleprompter/TTS for recovered events")
                } else {
                    newMessages
                        .filter { it.direction == "output" }
                        .forEach {
                            val cleaned = it.content.replace("[图片]", "").replace("[音频]", "").replace("[视频]", "").replace("[文件]", "").trim()
                            if (cleaned.isNotBlank()) {
                                if (_uiState.value.operatingMode == PsopOperatingMode.GLASSES) {
                                    displayGlassesText(cleaned)
                                    // 延迟 800ms 再播 TTS，等待 CustomView 蓝牙指令发送完毕
                                    // 按段入队（与轮播段对齐），新消息抢占打断旧内容
                                    viewModelScope.launch {
                                        delay(800L)
                                        enqueueTtsForMessage(cleaned)
                                    }
                                } else {
                                    speakOnPhone(cleaned)
                                }
                            }
                            // 如果有图片 parts，同时发送图片到眼镜端轮播（仅 CustomView 模式：
                            // 提词器模式下图片轮播需开 CustomView，会覆盖 WORD_TIPS 场景，故跳过）
                            val imgParts = it.parts.filter { p -> p.kind == "image" }
                            if (_uiState.value.operatingMode == PsopOperatingMode.GLASSES && imgParts.isNotEmpty() && _uiState.value.glassesDisplayMode == GlassesDisplayMode.CUSTOM_VIEW) {
                                sendImagesToTeleprompter(imgParts)
                            }
                        }
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
     * 加载任务进度状态（进入对话桖时调用）
     */
    private fun loadTaskStatus(runId: String) {
        viewModelScope.launch {
            try {
                val status = repository.getTaskStatus(runId)
                Log.d(TAG, "TaskStatus loaded: progress=${status.progress?.completed}/${status.progress?.total}, stages=${status.stages.size}")
                _uiState.update { it.copy(taskStatus = status) }
                updateGlassesStageDisplay(status)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load task status", e)
            }
        }
    }

    /**
     * 处理 WebSocket 推送的 run.task_status.updated 事件
     */
    private fun handleTaskStatusUpdated(payload: Map<String, Any?>) {
        try {
            val gson = com.google.gson.Gson()
            val json = gson.toJson(payload)
            val status = gson.fromJson(json, TaskStatusResponse::class.java)
            val current = _uiState.value.taskStatus
            // 只接受更新的 snapshot_seq
            if (current == null || status.snapshotSeq >= current.snapshotSeq) {
                Log.d(TAG, "TaskStatus updated: seq=${status.snapshotSeq}, progress=${status.progress?.completed}/${status.progress?.total}")
                _uiState.update { it.copy(taskStatus = status) }
                updateGlassesStageDisplay(status)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse task_status.updated", e)
        }
    }

    /**
     * 更新手机端任务进度面板（眼镜端不再显示步骤信息，避免覆盖对话框消息）
     */
    private fun updateGlassesStageDisplay(status: TaskStatusResponse) {
        // 仅更新 UI 状态，不发送到眼镜端 CustomView
        // 任务进度通过手机端 TaskProgressPanel 展示，眼镜端专用于显示对话框消息
    }

    /**
     * 根据 Run 状态更新 UI
     */
    private fun updateUiForRunStatus(status: String) {
        val oldMode = _uiState.value.interactionMode
        _uiState.update { state ->
            when (status) {
                "waiting_input" -> {
                    // 保护条件从 isPhotoConfirmViewOpen 扩为 mode==PHOTO_CONFIRM：
                    // ENABLE_GLASSES_PHOTO_CONFIRM_VIEW=false 后 isPhotoConfirmViewOpen 恒 false，
                    // 原保护永不生效；无界面场景下 1s 自动确认窗口内 run 状态到达也会改写 mode，
                    // 保住长按 TouchPad 自救通道。副作用评估：保护窗口仅持续至 1s Job 到期，
                    // confirmPhoto 末尾按 runStatus 恢复 mode，不影响其他正常流转
                    if (state.interactionMode == InteractionMode.PHOTO_CONFIRM) {
                        // 照片确认窗口内：不改写 interactionMode、不关提词器/重开界面，仅更新运行状态；
                        // 确认/取消流程结束后会自行恢复到 LISTENING
                        Log.w(TAG, "waiting_input received while photo confirm: keep interactionMode=$oldMode")
                        state.copy(
                            isRunning = true,
                            isCompleted = false,
                            runStatus = status
                        )
                    } else {
                        // 后端等待用户输入 → 立即关闭眼镜 CustomView，释放 ASR 触摸板交互
                        closeTeleprompter()
                        state.copy(
                            isRunning = true,
                            isCompleted = false,
                            runStatus = status,
                            interactionMode = InteractionMode.LISTENING
                        )
                    }
                }
                "running" -> {
                    if (state.interactionMode == InteractionMode.PHOTO_CONFIRM) {
                        // 照片确认窗口内：保持 PHOTO_CONFIRM，确保长按 TouchPad 仍能触发 confirmPhoto
                        Log.w(TAG, "running received while photo confirm: keep interactionMode=$oldMode")
                        state.copy(isRunning = true, isCompleted = false, runStatus = status)
                    } else {
                        state.copy(
                            isRunning = true,
                            isCompleted = false,
                            runStatus = status,
                            interactionMode = InteractionMode.PROCESSING
                        )
                    }
                }
                "succeeded" -> {
                    repository.disconnectWebSocket()
                    stopAsrListening()
                    CxrApi.getInstance().setAiEventListener(null)
                    closeTeleprompter()
                    closePhotoConfirmView()
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
                    closePhotoConfirmView()
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
                    closePhotoConfirmView()
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
                    closePhotoConfirmView()
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
        // 诊断日志：run 状态改写 interactionMode 的全量追踪（update 的 lambda 可能因竞争重试，
        // 日志放 update 外避免重复打印）
        val newMode = _uiState.value.interactionMode
        if (oldMode != newMode) {
            Log.d(TAG, "mode $oldMode -> $newMode by runStatus=$status")
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
                Log.d("PSOP_DEBUG", "WS event: partsRaw=${partsRaw?.size ?: "null"}, payloadKeys=${payload.keys}")
                val eventId = payload["id"]?.toString() ?: "ws-${System.currentTimeMillis()}"
                val currentRunId = _uiState.value.runId ?: ""
                val content: String
                val parsedParts: List<MessagePart> = mutableListOf()

                if (!partsRaw.isNullOrEmpty()) {
                    val textParts = mutableListOf<String>()
                    val mediaPlaceholders = mutableListOf<String>()

                    partsRaw.filterIsInstance<Map<*, *>>().forEach { part ->
                        val partId = part["part_id"]?.toString() ?: ""
                        val kind = part["kind"]?.toString() ?: "text"
                        val partMimeType = part["mime_type"]?.toString() ?: ""
                        val text = part["text"]?.toString() ?: ""
                        val metadata = part["metadata"] as? Map<*, *>

                        when {
                            kind == "text" || partMimeType.startsWith("text/") -> {
                                textParts.add(text)
                            }
                            partMimeType.startsWith("image/") || partMimeType.startsWith("audio/") || partMimeType.startsWith("video/") -> {
                                // 媒体 parts 构建 content URL
                                val contentUrl = if (partId.isNotEmpty() && currentRunId.isNotEmpty()) {
                                    "${PsopConfig.baseUrl}terminal/sessions/${currentRunId}/events/${eventId}/parts/${partId}/content"
                                } else ""
                                val resolvedMimeType = if (partMimeType.isNotEmpty()) partMimeType else when {
                                    partMimeType.startsWith("image/") -> "image/jpeg"
                                    partMimeType.startsWith("video/") -> "video/mp4"
                                    partMimeType.startsWith("audio/") -> "audio/mpeg"
                                    else -> "application/octet-stream"
                                }
                                (parsedParts as MutableList<MessagePart>).add(MessagePart(
                                    partId = partId,
                                    kind = kind,
                                    mimeType = resolvedMimeType,
                                    contentUrl = contentUrl
                                ))
                                mediaPlaceholders.add(when {
                                    partMimeType.startsWith("image/") -> "[图片]"
                                    partMimeType.startsWith("audio/") -> "[音频]"
                                    partMimeType.startsWith("video/") -> "[视频]"
                                    else -> "[文件]"
                                })
                            }
                        }
                    }
                    content = (textParts + mediaPlaceholders).joinToString(" ")
                    Log.d("PSOP_DEBUG", "WS content built: textParts=$textParts, mediaPlaceholders=$mediaPlaceholders, parsedParts=${parsedParts.size}, contentLen=${content.length}")
                } else {
                    content = ""
                }

                val msg = TerminalMessage(
                    id = eventId,
                    direction = direction,
                    content = content,
                    timestamp = event.occurredAt ?: "",
                    seqNo = seqNo,
                    eventKind = eventKind,
                    mimeType = mimeType,
                    artifactObjectId = artifactObjectId,
                    parts = parsedParts
                )
                // 只添加 output 事件（input 已本地显示），同时去重
                if (direction == "output") {
                    if (isPhotoPipelineActive()) {
                        // 照片管线窗口内（拍照键按下→同步→自动确认→上传→重建完成全程）：
                        // 消息整条排队（不入列表/不眼镜显示/不 TTS），管线终态后按到达顺序逐条完成；
                        // 避免 BLE 副作用与 P2P BLE 命令/重建握手（含 Stage 2 initWifiP2P2）交错争抢通道
                        enqueuePhotoPipelineMessage(msg)
                        return
                    }
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
                    // 只对有实际文字内容（去掉媒体占位符后）的消息 TTS 播报 + 眼镜端显示
                    if (isPhotoConfirmViewOpen) {
                        // 照片确认窗口内：不重开提词器、不播报 TTS，避免覆盖确认界面与 BLE 争用（消息已入聊天列表）
                        Log.w(TAG, "Photo confirm view open: skip teleprompter/TTS for real-time event")
                    } else {
                        val cleanedContent = content.replace("[图片]", "").replace("[音频]", "").replace("[视频]", "").replace("[文件]", "").trim()
                        if (cleanedContent.isNotBlank()) {
                            if (_uiState.value.operatingMode == PsopOperatingMode.GLASSES) {
                                displayGlassesText(cleanedContent)
                                // 延迟 800ms 再播 TTS，等待 CustomView 蓝牙指令发送完毕
                                // 按段入队（与轮播段对齐），新消息抢占打断旧内容
                                viewModelScope.launch {
                                    delay(800L)
                                    enqueueTtsForMessage(cleanedContent)
                                }
                            } else {
                                speakOnPhone(cleanedContent)
                            }
                        }
                        // 如果有图片 parts，同时发送图片到眼镜端轮播（仅 CustomView 模式：
                        // 提词器模式下图片轮播需开 CustomView，会覆盖 WORD_TIPS 场景，故跳过）
                        val imgParts = msg.parts.filter { p -> p.kind == "image" }
                        if (_uiState.value.operatingMode == PsopOperatingMode.GLASSES && imgParts.isNotEmpty() && _uiState.value.glassesDisplayMode == GlassesDisplayMode.CUSTOM_VIEW) {
                            sendImagesToTeleprompter(imgParts)
                        }
                    }
                }
            }
            "run.completed" -> {
                updateUiForRunStatus("succeeded")
            }
            "run.failed" -> {
                updateUiForRunStatus("failed")
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
            "run.task_status.updated" -> {
                // 任务进度更新
                val payload = event.payload
                if (payload != null) {
                    handleTaskStatusUpdated(payload)
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
        startIncrementalAsr()
        Log.d(TAG, "ASR listening started")
    }

    private fun stopAsrListening() {
        if (!isAsrActive) return
        isAsrActive = false

        CxrApi.getInstance().setAudioStreamListener(null)
        CxrApi.getInstance().notifyAsrEnd()
        CxrApi.getInstance().sendExitEvent()   // 关闭眼镜端AI场景弹窗（官方流程：notifyAsrEnd 后必须 sendExitEvent 才能退出AI场景）
        stopHeartbeat()
        stopIncrementalAsr()

        // 如果音频流回调尚未触发 handleAsrComplete，手动触发
        if (audioBuffer.size() > 0) {
            handleAsrComplete()
        }

        Log.d(TAG, "ASR listening stopped")
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch {
            CxrApi.getInstance().sendAi_Heartbeat()  // 立即发送首条心跳
            while (true) {
                delay(1000)                           // 1秒间隔，3-5秒窗口有3-5次机会，丢一两次不影响
                CxrApi.getInstance().sendAi_Heartbeat()
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * 增量ASR推送：录音期间定期识别已缓冲的音频，把部分识别结果通过 sendAsrContent 推给眼镜。
     * 一方面让眼镜语音弹窗实时显示说话内容（对齐官方APP体验）；
     * 另一方面让眼镜端持续收到ASR内容，避免约3秒收不到内容而超时停止录音。
     */
    private fun startIncrementalAsr() {
        incrementalAsrJob?.cancel()
        // VAD 状态重置
        asrStartTime = System.currentTimeMillis()
        lastSpeechTime = asrStartTime
        hasDetectedSpeech = false
        incrementalAsrJob = viewModelScope.launch {
            var asrTickCount = 0
            while (isAsrActive) {
                delay(500)  // 每500ms检查一次（VAD静音检测需要细粒度）
                val now = System.currentTimeMillis()
                // VAD-1：检测到说过话后，连续安静4秒 → 说完了 → 自动收尾
                // （onAiKeyUp 不可靠，松手检测不到，用“停止说话”作为主要结束信号）
                if (hasDetectedSpeech && now - lastSpeechTime > 4000) {
                    Log.d(TAG, "VAD: silence ${now - lastSpeechTime}ms after speech, finalizing ASR")
                    stopAsrListening()
                    break
                }
                // VAD-2：8秒都没检测到说话 → 可能是误触 → 自动收尾，避免弹窗永远挂着
                if (!hasDetectedSpeech && now - asrStartTime > 8000) {
                    Log.d(TAG, "VAD: no speech for 8s (accidental touch?), finalizing ASR")
                    stopAsrListening()
                    break
                }
                // 每2秒推送一次增量ASR（tick 3,7,11... 即首次1.5秒推送，之后每2秒）
                asrTickCount++
                if (asrTickCount % 4 == 3) {
                    val partial = audioBuffer.toByteArray()  // 读取当前音频快照，不清空缓冲区
                    if (partial.size >= 25600) {  // 至少0.8秒音频才值得识别
                        val text = transcribeAudio(partial)
                        if (text.isNotBlank() && isAsrActive) {
                            CxrApi.getInstance().sendAsrContent(text)
                            Log.d(TAG, "Incremental ASR pushed to glasses: $text")
                        }
                    }
                }
            }
        }
    }

    private fun stopIncrementalAsr() {
        incrementalAsrJob?.cancel()
        incrementalAsrJob = null
    }

    /**
     * VAD 语音活动检测：计算 PCM16 音频帧的 RMS 能量。
     * 说话帧能量高，静音/噪声帧能量低。
     */
    private fun computeFrameRms(data: ByteArray, offset: Int, size: Int): Double {
        var sum = 0.0
        var count = 0
        var i = offset
        while (i + 1 < offset + size) {
            val sample = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)  // 小端 16位 PCM
            sum += sample.toDouble() * sample
            count++
            i += 2
        }
        if (count == 0) return 0.0
        return Math.sqrt(sum / count)
    }

    /**
     * 音频流结束，将累积的 PCM 数据发送到 ASR 服务进行识别
     */
    private fun handleAsrComplete() {
        val pcmData = audioBuffer.toByteArray()
        audioBuffer.reset()

        // 误触过滤：音频时长 < 0.8 秒则丢弃（16kHz × 2字节 × 0.8s = 25600 字节）
        if (pcmData.size < 25600) {
            Log.w(TAG, "ASR discarded: audio too short (${pcmData.size} bytes < 25600), likely accidental touch")
            if (_uiState.value.runStatus == "waiting_input") {
                _uiState.update { it.copy(interactionMode = InteractionMode.LISTENING) }
            }
            return
        }

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

        // 拍照确认模式下忽略全部语音输入（等待 TouchPad 长按确认）
        if (_uiState.value.interactionMode == InteractionMode.PHOTO_CONFIRM) {
            Log.d(TAG, "Ignoring voice in PHOTO_CONFIRM mode: $text")
            return
        }

        // 检测停止录像关键词（录像中优先响应停止）
        val stopVideoKeywords = listOf("停止", "结束录像", "停止录像", "停止录制", "结束录制")
        if (stopVideoKeywords.any { text.contains(it) } && (_uiState.value.interactionMode == InteractionMode.VIDEO_RECORDING || isVideoStreamOpen)) {
            stopVideoRecording()
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
        CxrApi.getInstance().setMediaFilesUpdateListener(null)
        CxrApi.getInstance().setBatteryLevelUpdateListener(null)
        CxrApi.getInstance().setGlassStatusUpdateListener(null)
        CxrApi.getInstance().setSceneStatusUpdateListener(null)
        stopAsrListening()
        CxrApi.getInstance().setAiEventListener(null)
        closeTeleprompter()
        closeWordTipsScene()
        closePhotoConfirmView()
        // 清理 TTS 队列
        ttsQueue.clear()
        isTtsPlaying = false
        ttsEstimatedEndJob?.cancel()
        ttsEstimatedEndJob = null
        ttsWatchdogJob?.cancel()
        ttsWatchdogJob = null
        ttsRetryJob?.cancel()
        ttsRetryJob = null
        ttsPreemptFlagResetJob?.cancel()
        ttsPreemptFlagResetJob = null
        phoneOfflineTts?.release()
        phoneOfflineTts = null
        // 清理 P2P 同步资源
        isHwPhotoSyncing = false
        isHwPhotoRoundClaimed = false
        hwPhotoConnectTimeoutJob?.cancel()
        hwPhotoConnectTimeoutJob = null
        hwPhotoSyncTimeoutJob?.cancel()
        hwPhotoSyncTimeoutJob = null
        photoPrepareJob?.cancel()
        photoPrepareJob = null
        // 删除 cacheDir 内的暂存照片文件（压缩副本/同步副本），避免残留；非 cacheDir 文件不动
        val cacheRoot = getApplication<Application>().cacheDir.absolutePath
        prePreparedPhotoFile?.let { if (it.exists() && it.absolutePath.startsWith(cacheRoot)) it.delete() }
        prePreparedPhotoFile = null
        pendingPhotoFile?.let { if (it.exists() && it.absolutePath.startsWith(cacheRoot)) it.delete() }
        pendingPhotoFile = null
        cleanupP2P()
        // 照片轮排队状态清理（页面销毁）：悬挂的排队标志随退页作废，避免跨会话残留触发陈旧自动轮
        hwPhotoRoundPending = false
        hwPhotoQueuedCount = 0
        hwPhotoAutoRoundCount = 0
        repository.disconnectWebSocket()
        super.onCleared()
    }
}
