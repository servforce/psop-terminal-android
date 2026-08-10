package com.rokid.cxrmsamples.activities.p2pStabilityTest

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.SyncStatusCallback
import com.rokid.cxr.client.extend.callbacks.WifiP2PStatusCallback
import com.rokid.cxr.client.extend.listeners.MediaFilesUpdateListener
import com.rokid.cxr.client.utils.ValueUtil
import com.rokid.cxrmsamples.activities.mediaFile.P2PListener
import com.rokid.cxrmsamples.activities.mediaFile.P2PUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P2P 拍照回传稳定性测试页 ViewModel。
 *
 * 定位：干净的独立基线测试——专测"眼镜硬件键拍照 → P2P 回传到手机落盘"。
 * 严禁带入巡检页业务逻辑：无 WebSocket、无 TTS、无后端上传、无消息流、无缓冲补发、无场景切换。
 *
 * 每轮固定流程（不做预热/快路径/软接管等复杂机制）：
 *   硬件拍照键 → t0 → delay(3s) 等眼镜写盘 →
 *   （常驻且已有连接：直接 startSync2；否则 initWifiP2P2 → 发现 → 建连 → startSync2）→
 *   onSyncFinished 后扫目录确认落盘（5×200ms）→ 记录成败与耗时；
 *   非常驻模式每轮结束 teardown（deinitWifiP2P），常驻模式保持连接到退页。
 */

/** 当前轮阶段（状态行展示用） */
enum class P2pPhase { IDLE, WAIT_WRITE, CONNECTING, SYNCING }

/** 单轮记录（列表展示，最新在上） */
data class RoundRecord(
    val round: Int,
    val photoTime: String,      // 拍照时间 HH:mm:ss
    val connectMs: Long?,       // 建连耗时；null = 常驻模式复用连接
    val syncMs: Long?,
    val totalMs: Long?,         // 按键 → 文件落盘
    val success: Boolean,
    val reason: String? = null  // 失败原因分类
)

data class P2pStabilityUiState(
    val persistentMode: Boolean = false,   // 连接常驻模式（官方 demo 式）
    val phase: P2pPhase = P2pPhase.IDLE,
    val statusText: String = "空闲",
    val totalRounds: Int = 0,
    val successCount: Int = 0,
    val failCount: Int = 0,
    val avgSuccessMs: Long = 0,
    val fastestMs: Long = 0,
    val slowestMs: Long = 0,
    val records: List<RoundRecord> = emptyList()
)

class P2pStabilityTestViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val TAG = "P2PStability"
    }

    // ===== 固定参数（干净基线：无预热/无快路径/无软接管） =====
    private val WRITE_WAIT_MS = 3_000L        // 硬件键后等眼镜写盘
    private val CONNECT_BUDGET_MS = 15_000L   // 建连预算（init+发现+连接）
    private val SYNC_BUDGET_MS = 15_000L      // 同步无响应超时（startSync2 → onSyncFinished）
    private val SYNC_DIR = "/sdcard/Download/Rokid/P2PStabilityTest/"  // 专属同步目录（进页清空）
    private val MAX_RECORDS = 50              // 列表最多保留条数

    private val _uiState = MutableStateFlow(P2pStabilityUiState())
    val uiState = _uiState.asStateFlow()

    // ===== 轮次状态（读写均在 Main 线程） =====
    private var roundInProgress = false       // 在途轮守卫：轮进行中忽略新的硬件按键
    private var currentRound = 0
    private var t0 = 0L                       // 本轮硬件键时间戳
    private var connectStartMs = 0L           // 本轮建连发起时间戳
    private var syncStartMs = 0L              // 本轮 startSync2 发起时间戳
    private var connectTimeoutJob: Job? = null
    private var syncTimeoutJob: Job? = null
    private var persistentIp: String? = null  // 常驻模式下复用的连接 IP（null = 需建连）
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /** 眼镜硬件拍照键事件（MediaFilesUpdateListener，与巡检页/官方示例页同款回调） */
    private val mediaFilesUpdateListener = MediaFilesUpdateListener {
        viewModelScope.launch(Dispatchers.Main) { onHwPhotoButton() }
    }

    init {
        // 进入页面即注册硬件键监听并清空专属同步目录（避免旧照片干扰落盘判定）
        CxrApi.getInstance().setMediaFilesUpdateListener(mediaFilesUpdateListener)
        clearSyncDir()
        // P2P 目标设备标识（幂等，失败仅日志；参考 mediaFile 示例页既有调用）
        try {
            P2PUtils.Instance.setToZuoyitong()
        } catch (e: Exception) {
            Log.w(TAG, "setToZuoyitong error (ignored)", e)
        }
        Log.i(TAG, "page opened, sync dir=$SYNC_DIR cleared")
    }

    // ================= 每轮流程 =================

    /** 硬件拍照键：记录 t0，等眼镜写盘后进入本轮流程 */
    private fun onHwPhotoButton() {
        if (roundInProgress) {
            Log.w(TAG, "hw button ignored: round $currentRound still in progress")
            return
        }
        roundInProgress = true
        currentRound++
        t0 = System.currentTimeMillis()
        Log.i(TAG, "round $currentRound: hw button at t0")
        _uiState.update {
            it.copy(
                phase = P2pPhase.WAIT_WRITE,
                statusText = "轮 $currentRound：等待眼镜写盘（3s）…"
            )
        }
        viewModelScope.launch(Dispatchers.Main) {
            delay(WRITE_WAIT_MS)
            if (roundInProgress) runRoundFlow()
        }
    }

    /** 写盘等待结束后分流：常驻且已有连接 → 直接同步；否则完整建连 */
    private fun runRoundFlow() {
        val ip = persistentIp
        if (_uiState.value.persistentMode && ip != null) {
            Log.i(TAG, "round $currentRound: persistent mode, reuse connection ip=$ip, startSync2 directly")
            startSync(ip)
        } else {
            startConnect()
        }
    }

    /** 完整建连：initWifiP2P2 → 发现 → 连接（15s 建连预算） */
    private fun startConnect() {
        connectStartMs = System.currentTimeMillis()
        _uiState.update { it.copy(phase = P2pPhase.CONNECTING, statusText = "轮 $currentRound：建连中…") }
        // 建连预算：到期仍未连上判败（原因分类：建连超时）
        connectTimeoutJob?.cancel()
        connectTimeoutJob = viewModelScope.launch(Dispatchers.Main) {
            delay(CONNECT_BUDGET_MS)
            if (roundInProgress) failRound("建连超时")
        }
        val app = getApplication<Application>()
        Log.i(TAG, "round $currentRound: initWifiP2P2 issued")
        CxrApi.getInstance().initWifiP2P2(false, object : WifiP2PStatusCallback {
            override fun onConnected() { /* 方案二不会回调 */ }
            override fun onDisconnected() { /* 方案二不会回调 */ }
            override fun onFailed(errorCode: ValueUtil.CxrWifiErrorCode?) {
                viewModelScope.launch(Dispatchers.Main) {
                    if (roundInProgress) failRound("建连失败(init:$errorCode)")
                }
            }

            override fun onP2pDeviceAvailable(name: String?, macAddress: String?, deviceType: String?) {
                viewModelScope.launch(Dispatchers.Main) {
                    if (!roundInProgress) return@launch
                    Log.d(TAG, "round $currentRound: P2P device available name=$name mac=$macAddress")
                    // 复用 P2PUtils 既有能力：MAC 优先匹配发现/建连
                    P2PUtils.Instance.initP2P(app)
                    P2PUtils.Instance.setTargetDevice(name, macAddress)
                    P2PUtils.Instance.setListener(p2pListener)
                    P2PUtils.Instance.startDiscoverP2P(app)
                }
            }
        })
    }

    /** P2PUtils 发现/建连回调（SDK/系统线程 → 统一投递 Main） */
    private val p2pListener = object : P2PListener {
        override fun onDeviceFound(device: android.net.wifi.p2p.WifiP2pDevice) {
            Log.d(TAG, "round $currentRound: device found ${device.deviceName}/${device.deviceAddress}")
        }

        override fun onConnected(ipAddress: String) {
            viewModelScope.launch(Dispatchers.Main) {
                if (!roundInProgress) return@launch
                connectTimeoutJob?.cancel()
                connectTimeoutJob = null
                val connectMs = System.currentTimeMillis() - connectStartMs
                Log.i(TAG, "round $currentRound: connect ok in ${connectMs}ms, ip=$ipAddress")
                currentConnectMs = connectMs
                if (_uiState.value.persistentMode) {
                    // 常驻模式：缓存 IP，后续轮直接 startSync2 复用（退页才拆）
                    persistentIp = ipAddress
                }
                startSync(ipAddress)
            }
        }

        override fun onConnectionFailed(reason: String) {
            viewModelScope.launch(Dispatchers.Main) {
                if (roundInProgress) failRound("建连失败($reason)")
            }
        }

        override fun onDiscoveryStarted() {
            viewModelScope.launch(Dispatchers.Main) {
                if (roundInProgress) {
                    _uiState.update { it.copy(statusText = "轮 $currentRound：发现设备中…") }
                }
            }
        }

        override fun onDiscoveryFailed(reason: Int) {
            viewModelScope.launch(Dispatchers.Main) {
                if (roundInProgress) failRound("发现失败(reason=$reason)")
            }
        }
    }

    /** 本轮建连耗时（常驻复用轮为 null） */
    private var currentConnectMs: Long? = null

    /** 文件同步：startSync2 + 15s 同步无响应超时 */
    private fun startSync(ipAddress: String) {
        syncStartMs = System.currentTimeMillis()
        _uiState.update { it.copy(phase = P2pPhase.SYNCING, statusText = "轮 $currentRound：同步中…") }
        syncTimeoutJob?.cancel()
        syncTimeoutJob = viewModelScope.launch(Dispatchers.Main) {
            delay(SYNC_BUDGET_MS)
            if (roundInProgress) failRound("同步无响应")
        }
        File(SYNC_DIR).let { if (!it.exists()) it.mkdirs() }
        Log.i(TAG, "round $currentRound: startSync2 issued (ip=$ipAddress)")
        val issued = CxrApi.getInstance().startSync2(
            SYNC_DIR,
            arrayOf(ValueUtil.CxrMediaType.PICTURE),
            ipAddress,
            syncCallback
        )
        if (!issued) {
            failRound("同步启动失败(startSync2=false)")
        }
    }

    /** 同步状态回调（SDK 线程 → Main） */
    private val syncCallback = object : SyncStatusCallback {
        override fun onSyncStart() {
            Log.d(TAG, "round $currentRound: onSyncStart")
        }

        override fun onSingleFileSynced(filename: String?) {
            Log.d(TAG, "round $currentRound: onSingleFileSynced $filename")
        }

        override fun onSyncFailed() {
            viewModelScope.launch(Dispatchers.Main) {
                if (roundInProgress) failRound("同步失败(SDK onSyncFailed)")
            }
        }

        override fun onSyncFinished() {
            viewModelScope.launch(Dispatchers.Main) {
                if (!roundInProgress) return@launch
                syncTimeoutJob?.cancel()
                syncTimeoutJob = null
                // 扫描目录确认文件落盘（5×200ms，参考巡检页 consumeHwPhotoFromDir 的简化版）
                scanSyncDir(scanRetryLeft = 5)
            }
        }
    }

    /** 落盘确认：目录有文件 → 成功收尾；5×200ms 耗尽仍空 → 判败"落盘为空" */
    private fun scanSyncDir(scanRetryLeft: Int) {
        if (!roundInProgress) return
        val latest = File(SYNC_DIR).listFiles()?.maxByOrNull { it.lastModified() }
        if (latest != null) {
            viewModelScope.launch(Dispatchers.Main) {
                // 再等一拍（200ms）确保文件写完
                delay(200L)
                if (roundInProgress) finishRoundSuccess(latest)
            }
        } else if (scanRetryLeft > 0) {
            viewModelScope.launch(Dispatchers.Main) {
                delay(200L)
                scanSyncDir(scanRetryLeft - 1)
            }
        } else {
            failRound("落盘为空")
        }
    }

    // ================= 终态收尾 =================

    /** 成功收尾：记录耗时/统计，非常驻 teardown，常驻保持连接 */
    private fun finishRoundSuccess(file: File) {
        val now = System.currentTimeMillis()
        val syncMs = now - syncStartMs
        val totalMs = now - t0
        Log.i(TAG, "round $currentRound sync finished, file landed (${file.name}), total ${totalMs}ms")
        val record = RoundRecord(
            round = currentRound,
            photoTime = timeFmt.format(Date(t0)),
            connectMs = currentConnectMs,
            syncMs = syncMs,
            totalMs = totalMs,
            success = true
        )
        appendRecord(record)
        roundInProgress = false
        currentConnectMs = null
        if (_uiState.value.persistentMode) {
            // 常驻模式：不 teardown，保持连接等待下一轮
            _uiState.update { it.copy(phase = P2pPhase.IDLE, statusText = "轮 $currentRound ✅ 总耗时 ${totalMs}ms（连接保持）") }
        } else {
            teardownConnection()
            _uiState.update { it.copy(phase = P2pPhase.IDLE, statusText = "轮 $currentRound ✅ 总耗时 ${totalMs}ms") }
        }
    }

    /** 失败收尾：记录原因分类，并按模式 teardown */
    private fun failRound(reason: String) {
        if (!roundInProgress) return
        val totalMs = System.currentTimeMillis() - t0
        Log.e(TAG, "round $currentRound failed: $reason (elapsed ${totalMs}ms)")
        val record = RoundRecord(
            round = currentRound,
            photoTime = timeFmt.format(Date(t0)),
            connectMs = currentConnectMs,
            syncMs = null,
            totalMs = totalMs,
            success = false,
            reason = reason
        )
        appendRecord(record)
        roundInProgress = false
        currentConnectMs = null
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        syncTimeoutJob?.cancel()
        syncTimeoutJob = null
        if (!_uiState.value.persistentMode) {
            // 非常驻：每轮结束必 teardown
            teardownConnection()
        } else if (reason.startsWith("建连") || reason.startsWith("发现")) {
            // 常驻模式下建连/发现类失败：本轮未建立可用连接，复位 IP 下轮重新建连
            persistentIp = null
            teardownConnection()
        }
        // 常驻模式下同步类失败：连接仍存活，保持复用
        _uiState.update { it.copy(phase = P2pPhase.IDLE, statusText = "轮 $currentRound ❌ $reason") }
    }

    /** 追加记录并更新统计（最新在上，最多保留 MAX_RECORDS 条） */
    private fun appendRecord(record: RoundRecord) {
        _uiState.update { state ->
            val records = listOf(record) + state.records
            val totalRounds = state.totalRounds + 1
            if (record.success) {
                val successCount = state.successCount + 1
                val totals = (records.filter { it.success }.mapNotNull { it.totalMs })
                val avg = if (totals.isNotEmpty()) totals.average().toLong() else 0L
                state.copy(
                    totalRounds = totalRounds,
                    successCount = successCount,
                    avgSuccessMs = avg,
                    fastestMs = if (state.fastestMs == 0L) record.totalMs ?: 0L else minOf(state.fastestMs, record.totalMs ?: 0L),
                    slowestMs = maxOf(state.slowestMs, record.totalMs ?: 0L),
                    records = records.take(MAX_RECORDS)
                )
            } else {
                state.copy(
                    totalRounds = totalRounds,
                    failCount = state.failCount + 1,
                    records = records.take(MAX_RECORDS)
                )
            }
        }
    }

    // ================= 连接拆除 / 开关 / 统计 =================

    /** 拆连接并恢复 P2PUtils 干净状态（进程级单例，退页/每轮 teardown 共用） */
    private fun teardownConnection() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        syncTimeoutJob?.cancel()
        syncTimeoutJob = null
        try {
            P2PUtils.Instance.cancelPendingRetryRunnables()
            P2PUtils.Instance.stopDiscoverP2P()
            P2PUtils.Instance.setListener(null)
            P2PUtils.Instance.clearTargetDevice()
        } catch (e: Exception) {
            Log.w(TAG, "P2PUtils teardown error (ignored)", e)
        }
        try {
            CxrApi.getInstance().deinitWifiP2P()
        } catch (e: Exception) {
            Log.w(TAG, "deinitWifiP2P error (ignored)", e)
        }
        persistentIp = null
        Log.d(TAG, "connection torn down")
    }

    /** 连接常驻模式开关（运行中禁止切换，避免在途轮语义混乱） */
    fun togglePersistentMode() {
        if (roundInProgress) {
            Log.w(TAG, "persistent mode toggle ignored: round in progress")
            return
        }
        val next = !_uiState.value.persistentMode
        if (!next) {
            // 关掉常驻：立即拆掉保持的连接，恢复"每轮完整建连"语义
            teardownConnection()
        }
        _uiState.update {
            it.copy(persistentMode = next, statusText = if (next) "连接常驻模式：开" else "连接常驻模式：关（每轮完整建连）")
        }
        Log.i(TAG, "persistent mode -> $next")
    }

    /** 清空统计与记录 */
    fun clearStats() {
        if (roundInProgress) return
        _uiState.update {
            it.copy(
                totalRounds = 0, successCount = 0, failCount = 0,
                avgSuccessMs = 0, fastestMs = 0, slowestMs = 0,
                records = emptyList(), statusText = "统计已清空"
            )
        }
        Log.i(TAG, "stats cleared")
    }

    /** 清空专属同步目录（进页时调用，避免旧照片干扰） */
    private fun clearSyncDir() {
        try {
            val dir = File(SYNC_DIR)
            if (dir.exists()) {
                dir.listFiles()?.forEach { it.delete() }
            } else {
                dir.mkdirs()
            }
        } catch (e: Exception) {
            Log.w(TAG, "clear sync dir error (ignored)", e)
        }
    }

    override fun onCleared() {
        // 退页拆除：注销按键监听 + 拆连接 + 恢复 P2PUtils 干净状态（不污染其他页面）
        CxrApi.getInstance().setMediaFilesUpdateListener(null)
        teardownConnection()
        Log.i(TAG, "page cleared")
        super.onCleared()
    }
}
