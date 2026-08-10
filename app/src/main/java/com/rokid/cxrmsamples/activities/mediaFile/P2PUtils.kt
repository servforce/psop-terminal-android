package com.rokid.cxrmsamples.activities.mediaFile

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import com.rokid.cxr.Caps
import com.rokid.cxr.client.controllers.CxrController
import com.rokid.cxr.client.utils.ValueUtil
import org.json.JSONArray
import org.json.JSONObject

interface P2PListener{
    /**
     * 发现目标设备
     * @param device 发现的设备
     */
    fun onDeviceFound(device: WifiP2pDevice)
    
    /**
     * 连接成功，返回 IP 地址
     * @param ipAddress 连接成功后的 IP 地址
     */
    fun onConnected(ipAddress: String)
    
    /**
     * 连接失败
     * @param reason 失败原因
     */
    fun onConnectionFailed(reason: String)
    
    /**
     * 开始发现设备
     */
    fun onDiscoveryStarted()
    
    /**
     * 发现失败
     * @param reason 失败原因代码
     */
    fun onDiscoveryFailed(reason: Int)
}

class P2PUtils {
    companion object{
        val Instance by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            P2PUtils()
        }
    }

    private val tag = "P2PUtils"
    
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var context: Context? = null
    
    // 目标设备信息
    private var targetDeviceName: String? = null
    private var targetDeviceMac: String? = null
    
    // 回调监听器
    private var listener: P2PListener? = null
    
    // 状态标志
    private var isDiscovering = false
    private var isConnecting = false
    // 当前 Wi-Fi P2P 是否已启用（由 WIFI_P2P_STATE_CHANGED_ACTION 驱动）
    private var isP2pEnabled = false
    private var targetDeviceFound = false
    private var lastThisDeviceChangedTime = 0L
    private val THIS_DEVICE_CHANGED_THROTTLE_MS = 2000L // 防抖时间2秒
    // 当 P2P 还未 enable 时，是否需要在 enable 后自动开始扫描
    private var pendingStartDiscovery = false
    private var pendingDiscoveryContext: Context? = null
    
    // 重试次数限制
    private val MAX_DISCOVERY_RETRY_COUNT = 5 // Discovery 最大重试次数（非 BUSY 场景）
    private val MAX_CONNECTION_RETRY_COUNT = 5 // 连接最大重试次数
    // BUSY(reason=2) 专用放宽参数：系统 P2P 栈滞留 BUSY（如半程 connect 残留）需更长间隔与更多轮次
    // （单轮 ≈ 8×2s = 16s）；其他 reason 维持 5×800ms 原行为
    private val DISCOVERY_BUSY_RETRY_COUNT = 8
    private val DISCOVERY_BUSY_RETRY_DELAY_MS = 2000L
    private val DISCOVERY_RETRY_DELAY_MS = 800L
    
    // 当前重试次数
    private var currentDiscoveryRetryCount = 0
    private var currentConnectionRetryCount = 0
    // 建连期间瞬时断连（Connection lost）的允许重连次数
    private var connectionLostRetryCount = 0
    private val MAX_CONNECTION_LOST_RETRY = 2

    // 最近一次成功建连是否为 MAC 精确匹配（供调用方判断快路径缓存 IP 可信度）
    @Volatile
    var lastMacMatchedConnect = false
        private set

    // MAC 命中但 status=UNAVAILABLE 时的重扫等待预算（避免连上同名幽灵 peer）
    private var unavailableMacRescanCount = 0
    private val MAX_UNAVAILABLE_MAC_RESCAN = 3
    private val UNAVAILABLE_MAC_RESCAN_DELAY_MS = 1200L

    // 发现阶段主动轮询：眼镜固件 ready 后可能长时间不广播 PEERS_CHANGED，
    // 发现期间每 1.5s 主动 requestPeers 一次；开启 failFastOnEmptyDiscovery 时，
    // 距发现起始时间超过 ~4.5s 仍无目标设备则提前判败（"眼镜未就绪，请重试"）。
    // 提前判败按时间戳而非轮询计数：THIS_DEVICE_CHANGED 抖动会经
    // resetAndRestartDiscovery → startDiscoverP2P(isRetry=false) 重置计数，
    // 计数方案会让判败窗口被不断刷新拉长；时间戳只在首次启动发现时设置、重启不重置。
    private var discoveryPollRunnable: Runnable? = null
    private var discoveryPollCount = 0
    private var discoveryStartTimeMs = 0L
    private val DISCOVERY_POLL_INTERVAL_MS = 1_500L
    private val MAX_EMPTY_DISCOVERY_POLLS = 3
    private val EMPTY_DISCOVERY_FAIL_FAST_MS = MAX_EMPTY_DISCOVERY_POLLS * DISCOVERY_POLL_INTERVAL_MS // ≈4.5s 提前判败
    private var failFastOnEmptyDiscovery = false

    /** 开关：发现期间连续轮询无果是否提前判败。
     *  仅硬件拍照/预热场景置 true；mediaFile 等共用方默认 false 保持原行为。 */
    fun setFailFastOnEmptyDiscovery(enabled: Boolean) {
        failFastOnEmptyDiscovery = enabled
    }

    // 延时重试 Runnable 句柄（支持 removeCallbacks 取消在途重试）
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var discoveryRetryRunnable: Runnable? = null
    private var connectionRetryRunnable: Runnable? = null
    private var connectionInfoRetryRunnable: Runnable? = null
    private var restartDiscoveryRunnable: Runnable? = null
    private var unavailableRescanRunnable: Runnable? = null

    /** 取消所有在途延时重试 Runnable（公开：供 teardown 等外部清理路径复用） */
    fun cancelPendingRetryRunnables() {
        discoveryRetryRunnable?.let { handler.removeCallbacks(it) }
        discoveryRetryRunnable = null
        connectionRetryRunnable?.let { handler.removeCallbacks(it) }
        connectionRetryRunnable = null
        connectionInfoRetryRunnable?.let { handler.removeCallbacks(it) }
        connectionInfoRetryRunnable = null
        restartDiscoveryRunnable?.let { handler.removeCallbacks(it) }
        restartDiscoveryRunnable = null
        unavailableRescanRunnable?.let { handler.removeCallbacks(it) }
        unavailableRescanRunnable = null
        discoveryPollRunnable?.let { handler.removeCallbacks(it) }
        discoveryPollRunnable = null
        discoveryPollCount = 0
    }

    /**
     * 供外部接管前完整复位：停止发现、复位连接/发现状态、取消所有在途延时重试 Runnable。
     * 不影响已注册的 receiver/listener（由调用方自行 setListener）。
     */
    fun resetForHandover() {
        Log.d(tag, "resetForHandover: resetting discovery/connect state and cancelling pending retry runnables")
        // 兜底：取消系统栈内半程 connect（P2PUtils 其余路径均无 cancelConnect），
        // 否则残留 connect 会让后续 discoverPeers 持续 BUSY；无在途 connect 时失败属预期，忽略
        try {
            manager?.let { m ->
                channel?.let { c ->
                    m.cancelConnect(c, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            Log.d(tag, "cancelConnect succeed (handover)")
                        }
                        override fun onFailure(r: Int) {
                            Log.d(tag, "cancelConnect no-op failure: $r (handover, ignored)")
                        }
                    })
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "cancelConnect error during handover (ignored)", e)
        }
        cancelPendingRetryRunnables()
        stopDiscoverP2P()
        pendingStartDiscovery = false
        pendingDiscoveryContext = null
        currentDiscoveryRetryCount = 0
        currentConnectionRetryCount = 0
        connectionLostRetryCount = 0
        unavailableMacRescanCount = 0
        failFastOnEmptyDiscovery = false
        discoveryStartTimeMs = 0L
    }

    /**
     * 当前是否有在途流程（发现中/建连中/已发现目标）：
     * 供接管方判断能否软接管（只换 listener 等在途 connect 回调）而非硬复位
     */
    fun isFlowInFlight(): Boolean = isDiscovering || isConnecting || targetDeviceFound

    /**
     * 清除目标设备信息（供 teardown 调用，避免旧 MAC 跨轮残留参与下轮匹配）。
     * 不影响后续 setTargetDevice 的正常重新设置。
     */
    fun clearTargetDevice() {
        Log.d(tag, "clearTargetDevice: clearing name=$targetDeviceName, mac=$targetDeviceMac")
        targetDeviceName = null
        targetDeviceMac = null
        targetDeviceFound = false
        unavailableMacRescanCount = 0
        lastMacMatchedConnect = false
        discoveryPollCount = 0
        failFastOnEmptyDiscovery = false
        discoveryStartTimeMs = 0L
    }

    val receiver = object : BroadcastReceiver(){
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(tag, "onReceive: action=${intent?.action}, context=${context != null}")
            intent?.action?.let{ action ->
                Log.d(tag, "Broadcast received: action=$action")
                when(action){
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {// Wi-Fi P2P 状态改变
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        Log.d(tag, "WIFI_P2P_STATE_CHANGED_ACTION: state=$state (${if(state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) "ENABLED" else if(state == WifiP2pManager.WIFI_P2P_STATE_DISABLED) "DISABLED" else "UNKNOWN"})")
                        when(state){
                            WifiP2pManager.WIFI_P2P_STATE_ENABLED -> {
                                Log.d(tag, "Wi-Fi P2P is enabled")
                                isP2pEnabled = true
                                Log.d(tag, "Current state: isDiscovering=$isDiscovering, isConnecting=$isConnecting, targetDeviceFound=$targetDeviceFound, pendingStartDiscovery=$pendingStartDiscovery")

                                // 如果之前因为未 enable 而延迟了扫描，这里在 enable 后再开始
                                if (pendingStartDiscovery) {
                                    val ctx = pendingDiscoveryContext
                                    Log.d(tag, "Pending discovery found, ctx is null = ${ctx == null}")
                                    if (ctx != null) {
                                        Log.d(tag, "Starting pending discovery after P2P enabled")
                                        pendingStartDiscovery = false
                                        pendingDiscoveryContext = null
                                        startDiscoverP2P(ctx)
                                    } else {
                                        Log.w(tag, "pendingDiscoveryContext is null, cannot start pending discovery")
                                        pendingStartDiscovery = false
                                    }
                                }
                            }
                            WifiP2pManager.WIFI_P2P_STATE_DISABLED -> {
                                Log.d(tag, "Wi-Fi P2P is disabled")
                                isP2pEnabled = false
                                pendingStartDiscovery = false
                                pendingDiscoveryContext = null
                                Log.d(tag, "Resetting states: isDiscovering=$isDiscovering -> false, isConnecting=$isConnecting -> false, targetDeviceFound=$targetDeviceFound -> false")
                                isDiscovering = false
                                isConnecting = false
                                targetDeviceFound = false
                                Log.d(tag, "States reset complete")
                            }
                            else -> {
                                Log.w(tag, "Unknown Wi-Fi P2P state: $state")
                            }
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {// Wi-Fi P2P 连接状态改变
                        Log.d(tag, "WIFI_P2P_CONNECTION_CHANGED_ACTION received")
                        val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(
                            WifiP2pManager.EXTRA_NETWORK_INFO
                        )
                        Log.d(tag, "NetworkInfo: ${if(networkInfo != null) "not null, isConnected=${networkInfo.isConnected}, state=${networkInfo.state}, detailedState=${networkInfo.detailedState}" else "null"}")
                        networkInfo?.let { info ->
                            if (info.isConnected) {
                                Log.d(tag, "Connection established! isConnecting=$isConnecting, targetDeviceFound=$targetDeviceFound")
                                // 连接成功，获取连接信息
                                requestConnectionInfo()
                            } else {
                                Log.d(tag, "Connection disconnected. isConnecting=$isConnecting, state=${info.state}, detailedState=${info.detailedState}")
                                // 连接断开
                                if (isConnecting) {
                                    // 建连期间的瞬时断开（如对方组拆除重建）：给重连机会，重新发起对目标设备的 connect，
                                    // 耗尽重连次数后才判败；isConnecting 保持 true，避免直接 onConnectionFailed("Connection lost")
                                    if (connectionLostRetryCount < MAX_CONNECTION_LOST_RETRY) {
                                        connectionLostRetryCount++
                                        Log.w(tag, "Connection lost during connect, scheduling reconnect ($connectionLostRetryCount/$MAX_CONNECTION_LOST_RETRY)")
                                        currentConnectionRetryCount = 0
                                        val runnable = Runnable {
                                            Log.d(tag, "Connection-lost retry delay elapsed, reconnecting to target device")
                                            reconnectToDevice()
                                        }
                                        connectionRetryRunnable = runnable
                                        handler.postDelayed(runnable, 800)
                                    } else {
                                        Log.d(tag, "Connection lost retries exhausted ($connectionLostRetryCount). Resetting retry count: $currentConnectionRetryCount -> 0")
                                        isConnecting = false
                                        connectionLostRetryCount = 0
                                        // 连接断开时重置重试次数
                                        currentConnectionRetryCount = 0
                                        Log.d(tag, "Calling listener.onConnectionFailed")
                                        listener?.onConnectionFailed("Connection lost")
                                    }
                                } else {
                                    Log.d(tag, "Connection disconnected but was not in connecting state, ignoring")
                                }
                            }
                        } ?: run {
                            Log.w(tag, "NetworkInfo is null in WIFI_P2P_CONNECTION_CHANGED_ACTION")
                        }
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {// Wi-Fi P2P 设备列表改变
                        Log.d(tag, "WIFI_P2P_PEERS_CHANGED_ACTION: isDiscovering=$isDiscovering, targetDeviceFound=$targetDeviceFound")
                        if (isDiscovering && !targetDeviceFound) {
                            Log.d(tag, "Requesting peers list...")
                            requestPeers()
                        } else {
                            Log.d(tag, "Skipping requestPeers: isDiscovering=$isDiscovering, targetDeviceFound=$targetDeviceFound")
                        }
                    }
                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {// Wi-Fi P2P 本地设备信息改变
                        val currentTime = System.currentTimeMillis()
                        val timeSinceLastChange = currentTime - lastThisDeviceChangedTime
                        Log.d(tag, "WIFI_P2P_THIS_DEVICE_CHANGED_ACTION: timeSinceLastChange=${timeSinceLastChange}ms, throttle=${THIS_DEVICE_CHANGED_THROTTLE_MS}ms")
                        // 防抖处理
                        if (timeSinceLastChange < THIS_DEVICE_CHANGED_THROTTLE_MS) {
                            Log.d(tag, "Throttling THIS_DEVICE_CHANGED_ACTION (${timeSinceLastChange}ms < ${THIS_DEVICE_CHANGED_THROTTLE_MS}ms)")
                            return
                        }
                        lastThisDeviceChangedTime = currentTime
                        Log.d(tag, "THIS_DEVICE_CHANGED processed: isDiscovering=$isDiscovering, targetDeviceFound=$targetDeviceFound")
                        
                        // 如果未找到目标设备且正在 discovery，重新开始 discovery
                        if (isDiscovering && !targetDeviceFound) {
                            Log.d(tag, "THIS_DEVICE_CHANGED: restarting discovery (isDiscovering=$isDiscovering, targetDeviceFound=$targetDeviceFound)")
                            context?.let { ctx ->
                                Log.d(tag, "Calling resetAndRestartDiscovery")
                                // 可能需要重置 P2P 配置
                                resetAndRestartDiscovery(ctx)
                            } ?: run {
                                Log.w(tag, "Context is null, cannot restart discovery")
                            }
                        } else {
                            Log.d(tag, "Skipping restart: isDiscovering=$isDiscovering, targetDeviceFound=$targetDeviceFound")
                        }
                    }
                    else -> {
                        Log.d(tag, "Unknown action: $action")
                    }
                }

            } ?: run {
                Log.w(tag, "onReceive: intent or action is null")
            }
        }

    }

    /**
     * 初始化 P2P
     */
    fun initP2P(context: Context){
        Log.d(tag, "initP2P: called with context=${context != null}")
        // 如果已经注册过，先注销
        try {
            Log.d(tag, "Unregistering existing receiver...")
            context.unregisterReceiver(receiver)
            Log.d(tag, "Receiver unregistered successfully")
        } catch (e: Exception) {
            // 如果未注册，忽略异常
            Log.d(tag, "Receiver was not registered, ignoring: ${e.message}")
        }
        
        this.context = context
        Log.d(tag, "Registering broadcast receiver with actions...")
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        })
        Log.d(tag, "Broadcast receiver registered")

        manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        Log.d(tag, "WifiP2pManager obtained: ${manager != null}")

        channel = manager?.initialize(context, context.mainLooper, null)
        Log.d(tag, "Channel initialized: ${channel != null}")
        Log.d(tag, "initP2P: completed")
    }
    
    /**
     * 清理资源
     */
    fun cleanup(context: Context){
        Log.d(tag, "cleanup: called")
        Log.d(tag, "Current state before cleanup: isDiscovering=$isDiscovering, isConnecting=$isConnecting, targetDeviceFound=$targetDeviceFound")
        Log.d(tag, "Retry counts: discovery=$currentDiscoveryRetryCount, connection=$currentConnectionRetryCount")
        try {
            Log.d(tag, "Unregistering receiver...")
            context.unregisterReceiver(receiver)
            Log.d(tag, "Receiver unregistered")
        } catch (e: Exception) {
            // 忽略异常
            Log.w(tag, "Error unregistering receiver: ${e.message}")
        }
        Log.d(tag, "Stopping discovery...")
        stopDiscoverP2P()
        Log.d(tag, "Clearing listener and target device info...")
        listener = null
        targetDeviceName = null
        targetDeviceMac = null
        lastMacMatchedConnect = false
        unavailableMacRescanCount = 0
        isDiscovering = false
        isConnecting = false
        isP2pEnabled = false
        pendingStartDiscovery = false
        pendingDiscoveryContext = null
        targetDeviceFound = false
        // 重置重试次数并取消在途延时重试
        cancelPendingRetryRunnables()
        currentDiscoveryRetryCount = 0
        currentConnectionRetryCount = 0
        connectionLostRetryCount = 0
        failFastOnEmptyDiscovery = false
        discoveryStartTimeMs = 0L
        Log.d(tag, "cleanup: completed, all states reset")
    }

    /**
     * 设置目标设备信息
     */
    fun setTargetDevice(name: String?, macAddress: String?){
        Log.d(tag, "setTargetDevice: called with name=$name, macAddress=$macAddress")
        Log.d(tag, "Previous target: name=$targetDeviceName, mac=$targetDeviceMac")
        targetDeviceName = name
        targetDeviceMac = macAddress
        targetDeviceFound = false
        // 重置重试次数
        Log.d(tag, "Resetting retry counts: discovery=$currentDiscoveryRetryCount -> 0, connection=$currentConnectionRetryCount -> 0")
        currentDiscoveryRetryCount = 0
        currentConnectionRetryCount = 0
        // 同步复位断连重连预算，避免上次会话残留消耗导致本会话重连机会减少
        connectionLostRetryCount = 0
        unavailableMacRescanCount = 0
        lastMacMatchedConnect = false
        // 复位提前判败开关，避免预热/硬件拍照场景置 true 后跨场景泄漏
        // （psop 两处调用点均在 setTargetDevice 之后紧接 setFailFastOnEmptyDiscovery(true)，顺序安全）
        failFastOnEmptyDiscovery = false
        discoveryStartTimeMs = 0L
        Log.d(tag, "Set target device: name=$name, mac=$macAddress, targetDeviceFound reset to false")
    }

    /**
     * 设置监听器
     */
    fun setListener(listener: P2PListener?){
        Log.d(tag, "setListener: called, listener=${if(listener != null) "set" else "null"}")
        this.listener = listener
        Log.d(tag, "Listener ${if(listener != null) "set" else "cleared"}")
    }

    /**
     * 开始发现 P2P 设备
     * @param context 上下文
     * @param isRetry 是否是重试（内部使用，外部调用时默认为 false）
     */
    @SuppressLint("MissingPermission")
    fun startDiscoverP2P(context: Context, isRetry: Boolean = false){
        Log.d(tag, "startDiscoverP2P: called, isRetry=$isRetry, isP2pEnabled=$isP2pEnabled")
        Log.d(tag, "Target device: name=$targetDeviceName, mac=$targetDeviceMac")
        if (targetDeviceName == null && targetDeviceMac == null) {
            Log.e(tag, "Target device not set, cannot start discovery")
            return
        }

        // 注意：一定要在 Wi-Fi P2P ENABLED 之后再开始扫描
        if (!isP2pEnabled && !isRetry) {
            // 首次调用且当前 P2P 未 enable，则记录为 pending，等待 WIFI_P2P_STATE_CHANGED_ACTION -> ENABLED 后再真正开始
            Log.w(tag, "Wi-Fi P2P is not enabled yet, delay discovery until enabled")
            pendingStartDiscovery = true
            pendingDiscoveryContext = context
            return
        }
        
        this.context = context
        Log.d(tag, "Setting isDiscovering: false -> true, targetDeviceFound: $targetDeviceFound -> false")
        isDiscovering = true
        targetDeviceFound = false
        // 只有在开始新的 discovery（不是重试）时才重置重试次数；
        // 提前判败窗口（discoveryStartTimeMs）不在此重置：THIS_DEVICE_CHANGED 触发的
        // resetAndRestartDiscovery 会以 isRetry=false 走到这里，重置会让判败窗口被反复刷新拉长
        if (!isRetry) {
            Log.d(tag, "New discovery, resetting retry count: $currentDiscoveryRetryCount -> 0")
            currentDiscoveryRetryCount = 0
            discoveryPollCount = 0
        } else {
            Log.d(tag, "Retry discovery, keeping retry count: $currentDiscoveryRetryCount")
        }
        // 记录发现起始时间戳：仅首次启动发现时设置，重启/重试发现均保留，
        // 保证 fail-fast 判败窗口固定 ~4.5s 不随 THIS_DEVICE_CHANGED 重启刷新
        if (discoveryStartTimeMs == 0L) {
            discoveryStartTimeMs = System.currentTimeMillis()
            Log.d(tag, "Discovery window started at $discoveryStartTimeMs")
        }
        // 启动发现期主动轮询（不依赖 PEERS_CHANGED 广播被动驱动）
        startDiscoveryPoll()
        Log.d(tag, "Manager: ${manager != null}, Channel: ${channel != null}")
        
        manager?.let { m ->
            Log.d(tag, "Manager is not null, checking channel...")
            channel?.let { c ->
                Log.d(tag, "Channel is not null, calling discoverPeers...")
                m.discoverPeers(c, object : WifiP2pManager.ActionListener{
                    override fun onSuccess() {
                        Log.d(tag, "Discovery started successfully")
                        Log.d(tag, "Resetting retry count: $currentDiscoveryRetryCount -> 0")
                        // 成功时重置重试次数
                        currentDiscoveryRetryCount = 0
                        Log.d(tag, "Calling listener.onDiscoveryStarted()")
                        listener?.onDiscoveryStarted()
                    }

                    override fun onFailure(reason: Int) {
                        // BUSY(reason=2) 专用放宽参数（间隔 2s×8 轮），其他 reason 维持 800ms×5 轮原行为
                        val isBusy = reason == WifiP2pManager.BUSY
                        val maxRetry = if (isBusy) DISCOVERY_BUSY_RETRY_COUNT else MAX_DISCOVERY_RETRY_COUNT
                        val retryDelayMs = if (isBusy) DISCOVERY_BUSY_RETRY_DELAY_MS else DISCOVERY_RETRY_DELAY_MS
                        Log.e(tag, "Discovery failed: reason=$reason (busy=$isBusy), current retry count: $currentDiscoveryRetryCount/$maxRetry")
                        currentDiscoveryRetryCount++
                        Log.d(tag, "Retry count incremented to: $currentDiscoveryRetryCount")
                        
                        if (currentDiscoveryRetryCount <= maxRetry) {
                            // 未超过重试次数，继续重试
                            Log.d(tag, "Retrying discovery... ($currentDiscoveryRetryCount/$maxRetry)")
                            Log.d(tag, "Setting isDiscovering: true -> false")
                            isDiscovering = false
                            // 重新初始化 channel 并重试
                            Log.d(tag, "Reinitializing channel...")
                            channel = null
                            channel = m.initialize(context, context.mainLooper, null)
                            Log.d(tag, "Channel reinitialized: ${channel != null}, scheduling retry in ${retryDelayMs}ms...")
                            // 延迟后重试，避免立即重试（保存句柄支持接管/清理时 removeCallbacks）
                            val runnable = Runnable {
                                Log.d(tag, "Retry delay elapsed, calling startDiscoverP2P with isRetry=true")
                                startDiscoverP2P(context, isRetry = true)
                            }
                            discoveryRetryRunnable = runnable
                            handler.postDelayed(runnable, retryDelayMs)
                        } else {
                            // 超过重试次数，停止并通知失败
                            Log.e(tag, "Discovery failed after $maxRetry retries (busy=$isBusy), stopping")
                            Log.d(tag, "Setting isDiscovering: true -> false")
                            isDiscovering = false
                            Log.d(tag, "Resetting retry count: $currentDiscoveryRetryCount -> 0")
                            currentDiscoveryRetryCount = 0
                            Log.d(tag, "Calling listener.onDiscoveryFailed($reason)")
                            listener?.onDiscoveryFailed(reason)
                        }
                    }
                })
            }?: run {
                // channel is null, 重新初始化
                Log.w(tag, "Channel is null, attempting to reinitialize...")
                currentDiscoveryRetryCount++
                Log.d(tag, "Retry count incremented to: $currentDiscoveryRetryCount")
                if (currentDiscoveryRetryCount <= MAX_DISCOVERY_RETRY_COUNT) {
                    Log.d(tag, "Channel is null, reinitializing... ($currentDiscoveryRetryCount/$MAX_DISCOVERY_RETRY_COUNT)")
                    channel = m.initialize(context, context.mainLooper, null)
                    Log.d(tag, "Channel initialized: ${channel != null}, scheduling retry in 500ms...")
                    val runnable = Runnable {
                        Log.d(tag, "Retry delay elapsed, calling startDiscoverP2P with isRetry=true")
                        startDiscoverP2P(context, isRetry = true)
                    }
                    discoveryRetryRunnable = runnable
                    handler.postDelayed(runnable, 500)
                } else {
                    Log.e(tag, "Failed to initialize channel after $MAX_DISCOVERY_RETRY_COUNT retries")
                    Log.d(tag, "Setting isDiscovering: true -> false")
                    isDiscovering = false
                    Log.d(tag, "Resetting retry count: $currentDiscoveryRetryCount -> 0")
                    currentDiscoveryRetryCount = 0
                    Log.d(tag, "Calling listener.onDiscoveryFailed(${WifiP2pManager.ERROR})")
                    listener?.onDiscoveryFailed(WifiP2pManager.ERROR)
                }
            }
        } ?: run {
            Log.e(tag, "Manager is null, cannot start discovery")
        }
        Log.d(tag, "startDiscoverP2P: completed")
    }

    /**
     * 停止发现 P2P 设备
     */
    fun stopDiscoverP2P(){
        Log.d(tag, "stopDiscoverP2P: called, isDiscovering=$isDiscovering, isConnecting=$isConnecting, targetDeviceFound=$targetDeviceFound")
        isDiscovering = false
        isConnecting = false
        targetDeviceFound = false
        cancelDiscoveryPoll()
        Log.d(tag, "All state flags reset: isDiscovering=false, isConnecting=false, targetDeviceFound=false")
        manager?.let { m ->
            channel?.let { c ->
                Log.d(tag, "Calling stopPeerDiscovery...")
                m.stopPeerDiscovery(c, object : WifiP2pManager.ActionListener{
                    override fun onSuccess() {
                        Log.d(tag, "Discovery stopped successfully")
                    }

                    override fun onFailure(reason: Int) {
                        Log.e(tag, "Stop discovery failed: reason=$reason")
                    }
                })
            } ?: run {
                Log.w(tag, "Channel is null, cannot stop discovery")
            }
        } ?: run {
            Log.w(tag, "Manager is null, cannot stop discovery")
        }
        Log.d(tag, "stopDiscoverP2P: completed")
    }

    /**
     * 发现期主动轮询：每 1.5s 主动 requestPeers 一次（固件 ready 后可能长时间不发
     * PEERS_CHANGED 广播，纯被动等待会挂满建连超时）。isDiscovering && !targetDeviceFound
     * 期间自动续排；停止/接管/清理路径经 cancelDiscoveryPoll/cancelPendingRetryRunnables 取消。
     * 提前判败：开启 failFastOnEmptyDiscovery 时，距发现起始时间超过
     * EMPTY_DISCOVERY_FAIL_FAST_MS（≈4.5s）仍无目标设备 → 停止发现并经
     * onConnectionFailed 报"眼镜未就绪，请重试"。
     */
    private fun startDiscoveryPoll() {
        discoveryPollRunnable?.let { handler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                if (!isDiscovering || targetDeviceFound) {
                    Log.d(tag, "Discovery poll stopped: isDiscovering=$isDiscovering, targetDeviceFound=$targetDeviceFound")
                    return
                }
                discoveryPollCount++
                Log.d(tag, "Discovery poll #$discoveryPollCount: actively requesting peers")
                requestPeers()
                // 按时间戳判败（非计数）：THIS_DEVICE_CHANGED 重启发现会重置计数，
                // 计数方案会让判败窗口被反复刷新拉长；时间戳只在首次启动发现时设置
                val elapsedMs = if (discoveryStartTimeMs > 0L) System.currentTimeMillis() - discoveryStartTimeMs else 0L
                if (failFastOnEmptyDiscovery && elapsedMs >= EMPTY_DISCOVERY_FAIL_FAST_MS) {
                    Log.e(tag, "Target still not found after ${elapsedMs}ms (~${EMPTY_DISCOVERY_FAIL_FAST_MS}ms window, polls=$discoveryPollCount), fail fast")
                    isDiscovering = false
                    cancelDiscoveryPoll()
                    listener?.onConnectionFailed("眼镜未就绪，请重试")
                    return
                }
                handler.postDelayed(this, DISCOVERY_POLL_INTERVAL_MS)
            }
        }
        discoveryPollRunnable = runnable
        handler.postDelayed(runnable, DISCOVERY_POLL_INTERVAL_MS)
    }

    /** 取消发现期主动轮询并复位计数（时间戳保留，由 startDiscoverP2P 首次启动时设置） */
    private fun cancelDiscoveryPoll() {
        discoveryPollRunnable?.let { handler.removeCallbacks(it) }
        discoveryPollRunnable = null
        discoveryPollCount = 0
    }

    /**
     * 请求设备列表
     */
    @SuppressLint("MissingPermission")
    private fun requestPeers(){
        Log.d(tag, "requestPeers: called")
        Log.d(tag, "Manager: ${manager != null}, Channel: ${channel != null}")
        Log.d(tag, "Target device: name=$targetDeviceName, mac=$targetDeviceMac")
        manager?.let { m ->
            channel?.let { c ->
                Log.d(tag, "Calling requestPeers...")
                m.requestPeers(c) { peers ->
                    Log.d(tag, "requestPeers callback received, peers=${peers != null}")
                    // 在途回调守卫：判败/停止后发现后迟到的 requestPeers 回调不得再发起建连。
                    // 预热路径判败不 teardown（target/listener 仍在），若无守卫，眼镜恰在判败
                    // 瞬间进入列表时迟到回调会 connectToDevice 建立无人管理的游离 P2P 组。
                    // 正常流程不受影响：连接阶段 isDiscovering 恒 true，targetDeviceFound 在
                    // 回调内同步置位后才进入连接。
                    if (!isDiscovering || targetDeviceFound) {
                        Log.w(tag, "Stale requestPeers callback ignored: isDiscovering=$isDiscovering, targetDeviceFound=$targetDeviceFound")
                        return@requestPeers
                    }
                    peers?.deviceList?.let { deviceList ->
                        Log.d(tag, "Found ${deviceList.size} peers")
                        if (deviceList.isEmpty()) {
                            Log.d(tag, "Peer list is empty")
                        } else {
                            Log.d(tag, "Peer list:")
                            deviceList.forEachIndexed { index, device ->
                                Log.d(tag, "  [$index] name=${device.deviceName}, address=${device.deviceAddress}, status=${device.status}")
                            }
                        }
                        // 查找目标设备：MAC 优先两轮扫描（BLE 上报的 MAC 为权威，
                        // 消除旧版"名字或 MAC 任一命中且按列表顺序命中即连"导致连上幽灵 peer 的问题）
                        Log.d(tag, "Searching for target device (MAC-priority two-pass)...")
                        val macMatch = targetDeviceMac?.let { mac ->
                            deviceList.firstOrNull { it.deviceAddress.equals(mac, ignoreCase = true) }
                        }

                        if (macMatch != null) {
                            // AOSP 常量：CONNECTED=0, INVITED=1, FAILED=2, AVAILABLE=3, UNAVAILABLE=4。
                            // FAILED/UNAVAILABLE 视为"暂不可连"进入限时重扫；status=3（AVAILABLE）直接建连
                            // （实机日志中真实 MAC peer 的 status=3 按 AOSP 语义即 AVAILABLE，属可连状态）
                            if (macMatch.status == android.net.wifi.p2p.WifiP2pDevice.FAILED ||
                                macMatch.status == android.net.wifi.p2p.WifiP2pDevice.UNAVAILABLE) {
                                // MAC 精确命中但暂不可连（status 为 FAILED=2 或 UNAVAILABLE=4）：
                                // 列表里的同名异 MAC peer 是幽灵残留记录，绝不建连；
                                // 等待真实 MAC peer 转可用，限时重扫
                                val ghostPeer = targetDeviceName?.let { n ->
                                    deviceList.firstOrNull {
                                        it.deviceName.equals(n, ignoreCase = true) &&
                                            !it.deviceAddress.equals(macMatch.deviceAddress, ignoreCase = true)
                                    }
                                }
                                Log.w(tag, "MAC-matched peer not connectable (status=${macMatch.status}), ghost same-name peer present=${ghostPeer != null}, will NOT connect ghost")
                                // 进入重扫前先停掉发现轮询：重扫 Runnable 自带 1.2s 节奏，
                                // 否则 failFast 开启时轮询判败可能抢跑，失败文案会错报"眼镜未就绪"
                                cancelDiscoveryPoll()
                                scheduleMacUnavailableRescan()
                                return@requestPeers
                            }
                            // MAC 精确命中且可用：直接连接
                            unavailableMacRescanCount = 0
                            cancelDiscoveryPoll()
                            Log.d(tag, "Target device found by MAC exact match! name=${macMatch.deviceName}, address=${macMatch.deviceAddress}, status=${macMatch.status}")
                            targetDeviceFound = true
                            listener?.onDeviceFound(macMatch)
                            connectToDevice(macMatch, macMatched = true)
                            return@requestPeers
                        }

                        // 第二轮：MAC 未命中才退回名字匹配（降级匹配；MAC 轮换固件下的唯一可行路径）。
                        // 多个同名 peer 中优先选 AVAILABLE（status=3）：上轮实机幽灵恰是 status=0
                        // （CONNECTED 残留），按列表顺序 firstOrNull 会直接命中它复刻"连上空转组"；
                        // 实机真实 peer 为 AVAILABLE 正好命中优先选择
                        val nameMatch = targetDeviceName?.let { n ->
                            deviceList.firstOrNull { it.deviceName.equals(n, ignoreCase = true) && it.status == WifiP2pDevice.AVAILABLE }
                                ?: deviceList.firstOrNull { it.deviceName.equals(n, ignoreCase = true) }
                        }
                        if (nameMatch != null) {
                            val macMismatch = targetDeviceMac != null &&
                                !nameMatch.deviceAddress.equals(targetDeviceMac, ignoreCase = true)
                            if (macMismatch &&
                                (nameMatch.status == android.net.wifi.p2p.WifiP2pDevice.FAILED ||
                                 nameMatch.status == android.net.wifi.p2p.WifiP2pDevice.UNAVAILABLE)) {
                                // 名字命中但 MAC 不一致且不可连：MAC 轮换场景下的同名幽灵残留，
                                // 比照 MAC 分支的不可连处理：限时重扫而非直接连接
                                Log.w(tag, "NAME fallback hit with MAC mismatch and not connectable (status=${nameMatch.status}), treat as ghost, rescan instead of connect")
                                // 同 MAC 幽灵分支：进入重扫前先停掉发现轮询，避免 failFast 轮询判败抢跑
                                cancelDiscoveryPoll()
                                scheduleMacUnavailableRescan()
                                return@requestPeers
                            }
                            if (macMismatch) {
                                Log.w(tag, "MAC '$targetDeviceMac' not in peer list, degraded to NAME fallback with MAC MISMATCH (firmware MAC rotation): name=${nameMatch.deviceName}, address=${nameMatch.deviceAddress}, status=${nameMatch.status}")
                            } else {
                                Log.w(tag, "MAC '$targetDeviceMac' not in peer list, degraded to NAME fallback match: name=${nameMatch.deviceName}, address=${nameMatch.deviceAddress}, status=${nameMatch.status}")
                            }
                            unavailableMacRescanCount = 0
                            cancelDiscoveryPoll()
                            targetDeviceFound = true
                            listener?.onDeviceFound(nameMatch)
                            connectToDevice(nameMatch, macMatched = false)
                            return@requestPeers
                        }
                        // 单行拼 peer 列表摘要（name+后4位MAC+status），避免详情行被日志工具过滤丢失
                        val peerSummary = if (deviceList.isEmpty()) "<empty>" else deviceList.joinToString(", ") { d ->
                            "${d.deviceName}(${d.deviceAddress.takeLast(4)},s=${d.status})"
                        }
                        Log.d(tag, "Target device not found in peer list of ${deviceList.size} devices, peers=[$peerSummary]")
                    } ?: run {
                        Log.w(tag, "Peer list is null")
                    }
                }
            } ?: run {
                Log.e(tag, "Channel is null, cannot request peers")
            }
        } ?: run {
            Log.e(tag, "Manager is null, cannot request peers")
        }
        Log.d(tag, "requestPeers: completed")
    }

    /**
     * MAC 精确命中的 peer 处于不可连状态（FAILED/UNAVAILABLE）时的限时重扫：
     * 间隔 1.2s 重新拉取 peer 列表（最多 3 次，不占用 discoveryRetry 预算，也不拆组重启扫描）；
     * 耗尽后报"目标设备暂不可用"快速失败，由调用方走 onConnectionFailed 通道。
     * 计数语义说明：预算按"观测到不可连状态的次数"消耗——重扫等待期 PEERS_CHANGED 广播
     * 触发的额外 requestPeers 若仍观测到不可连也会消耗一次预算（属可接受：更早判败）；
     * 重复建连风险由 connectToDevice 的 isConnecting 守卫兜底。
     */
    private fun scheduleMacUnavailableRescan() {
        if (!isDiscovering) {
            Log.w(tag, "MAC-unavailable rescan skipped: discovery already stopped")
            return
        }
        unavailableMacRescanCount++
        if (unavailableMacRescanCount > MAX_UNAVAILABLE_MAC_RESCAN) {
            unavailableMacRescanCount = 0
            Log.e(tag, "MAC-matched peer still not connectable after $MAX_UNAVAILABLE_MAC_RESCAN rescans, fail fast")
            isDiscovering = false
            listener?.onConnectionFailed("目标设备暂不可用")
            return
        }
        Log.w(tag, "MAC-unavailable rescan #$unavailableMacRescanCount/$MAX_UNAVAILABLE_MAC_RESCAN in ${UNAVAILABLE_MAC_RESCAN_DELAY_MS}ms")
        // 覆盖句柄前先回收旧 Runnable，避免多个延时重扫叠加触发
        unavailableRescanRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            Log.d(tag, "MAC-unavailable rescan delay elapsed, re-requesting peers")
            if (isDiscovering && !targetDeviceFound) {
                requestPeers()
            } else {
                Log.d(tag, "MAC-unavailable rescan skipped: isDiscovering=$isDiscovering, targetDeviceFound=$targetDeviceFound")
            }
        }
        unavailableRescanRunnable = runnable
        handler.postDelayed(runnable, UNAVAILABLE_MAC_RESCAN_DELAY_MS)
    }

    /**
     * 连接到指定设备
     * @param macMatched 本次匹配是否为 MAC 精确匹配（记录到 lastMacMatchedConnect，
     *                   供调用方判断快路径缓存 IP 可信度；名字降级匹配不信任缓存 IP）
     */
    private fun connectToDevice(device: WifiP2pDevice, macMatched: Boolean){
        Log.d(tag, "connectToDevice: called, macMatched=$macMatched")
        lastMacMatchedConnect = macMatched
        Log.d(tag, "Device: name=${device.deviceName}, address=${device.deviceAddress}, status=${device.status}")
        Log.d(tag, "Current state: isConnecting=$isConnecting")
        if (isConnecting) {
            Log.d(tag, "Already connecting, skip")
            return
        }
        
        // 重置连接重试次数（开始新的连接时）
        Log.d(tag, "Resetting connection retry count: $currentConnectionRetryCount -> 0")
        currentConnectionRetryCount = 0
        Log.d(tag, "Setting isConnecting: false -> true")
        isConnecting = true
        Log.d(tag, "Calling performConnection()")
        performConnection(device)
    }
    
    /**
     * 执行连接操作（支持重试）
     */
    @SuppressLint("MissingPermission")
    private fun performConnection(device: WifiP2pDevice){
        Log.d(tag, "performConnection: called")
        Log.d(tag, "Device: name=${device.deviceName}, address=${device.deviceAddress}")
        Log.d(tag, "Manager: ${manager != null}, Channel: ${channel != null}")
        Log.d(tag, "Current retry count: $currentConnectionRetryCount/$MAX_CONNECTION_RETRY_COUNT")
        manager?.let { m ->
            channel?.let { c ->
                val config = android.net.wifi.p2p.WifiP2pConfig().apply {
                    deviceAddress = device.deviceAddress
                }
                Log.d(tag, "WifiP2pConfig created: deviceAddress=${config.deviceAddress}")
                Log.d(tag, "Calling connect()...")
                m.connect(c, config, object : WifiP2pManager.ActionListener{
                    override fun onSuccess() {
                        Log.d(tag, "Connection request sent successfully")
                        Log.d(tag, "Resetting retry count: $currentConnectionRetryCount -> 0")
                        // 仅重置 connect 请求失败重试；断连重连预算（connectionLostRetryCount）
                        // 留待真正连接成功（拿到 IP）后重置，避免瞬断后失去重连机会
                        currentConnectionRetryCount = 0
                        Log.d(tag, "Waiting for connection state change...")
                    }

                    override fun onFailure(reason: Int) {
                        Log.e(tag, "Connection request failed: reason=$reason, current retry count: $currentConnectionRetryCount/$MAX_CONNECTION_RETRY_COUNT")
                        currentConnectionRetryCount++
                        Log.d(tag, "Retry count incremented to: $currentConnectionRetryCount")
                        
                        if (currentConnectionRetryCount <= MAX_CONNECTION_RETRY_COUNT) {
                            // 未超过重试次数，继续重试
                            Log.d(tag, "Retrying connection... ($currentConnectionRetryCount/$MAX_CONNECTION_RETRY_COUNT)")
                            Log.d(tag, "Scheduling retry in 800ms...")
                            // 延迟后重试，避免立即重试（保存句柄支持接管/清理时 removeCallbacks）
                            val runnable = Runnable {
                                Log.d(tag, "Retry delay elapsed, calling performConnection() again")
                                performConnection(device)
                            }
                            connectionRetryRunnable = runnable
                            handler.postDelayed(runnable, 800)
                        } else {
                            // 超过重试次数，停止并通知失败
                            Log.e(tag, "Connection failed after $MAX_CONNECTION_RETRY_COUNT retries, stopping")
                            Log.d(tag, "Setting isConnecting: true -> false")
                            isConnecting = false
                            Log.d(tag, "Resetting retry count: $currentConnectionRetryCount -> 0")
                            currentConnectionRetryCount = 0
                            val errorMsg = "Connection request failed after $MAX_CONNECTION_RETRY_COUNT retries: $reason"
                            Log.d(tag, "Calling listener.onConnectionFailed('$errorMsg')")
                            listener?.onConnectionFailed(errorMsg)
                        }
                    }
                })
            } ?: run {
                // channel 为 null，无法连接
                Log.e(tag, "Channel is null, cannot connect")
                Log.d(tag, "Setting isConnecting: true -> false")
                isConnecting = false
                Log.d(tag, "Resetting retry count: $currentConnectionRetryCount -> 0")
                currentConnectionRetryCount = 0
                Log.d(tag, "Calling listener.onConnectionFailed('Channel is null')")
                listener?.onConnectionFailed("Channel is null")
            }
        } ?: run {
            // manager 为 null，无法连接
            Log.e(tag, "Manager is null, cannot connect")
            Log.d(tag, "Setting isConnecting: true -> false")
            isConnecting = false
            Log.d(tag, "Resetting retry count: $currentConnectionRetryCount -> 0")
            currentConnectionRetryCount = 0
            Log.d(tag, "Calling listener.onConnectionFailed('Manager is null')")
            listener?.onConnectionFailed("Manager is null")
        }
        Log.d(tag, "performConnection: completed")
    }

    /**
     * 建连期间瞬时断连后的重连：用已保存的目标设备信息直接重新发起 connect。
     * isConnecting 保持 true（由广播分支调起），performConnection 内部重试机制继续生效
     */
    @SuppressLint("MissingPermission")
    private fun reconnectToDevice() {
        val mac = targetDeviceMac
        if (!isConnecting || mac == null) {
            Log.w(tag, "reconnectToDevice: skip, isConnecting=$isConnecting, mac=$mac")
            return
        }
        Log.d(tag, "reconnectToDevice: reconnecting to $mac")
        val device = WifiP2pDevice().apply {
            deviceAddress = mac
            deviceName = targetDeviceName ?: ""
        }
        performConnection(device)
    }

    /**
     * 请求连接信息（获取 IP 地址）
     */
    private fun requestConnectionInfo(){
        Log.d(tag, "requestConnectionInfo: called")
        Log.d(tag, "Manager: ${manager != null}, Channel: ${channel != null}")
        Log.d(tag, "Current state: isConnecting=$isConnecting")
        manager?.let { m ->
            channel?.let { c ->
                Log.d(tag, "Calling requestConnectionInfo()...")
                m.requestConnectionInfo(c) { info ->
                    Log.d(tag, "requestConnectionInfo callback received, info=${info != null}")
                    info?.let {
                        Log.d(tag, "Connection info: groupFormed=${it.groupFormed}, isGroupOwner=${it.isGroupOwner}")
                        Log.d(tag, "Group owner address: ${it.groupOwnerAddress}")
                        if (it.groupFormed) {
                            if (it.isGroupOwner) {
                                // 作为组所有者，获取自己的 IP
                                Log.d(tag, "We are group owner, getting local IP address...")
                                val ipAddress = getLocalIpAddress()
                                if (ipAddress != null) {
                                    Log.d(tag, "Connected as group owner, IP: $ipAddress")
                                    Log.d(tag, "Setting isConnecting: $isConnecting -> false")
                                    isConnecting = false
                                    // 连接成功，重置重试次数（含断连重连预算）
                                    Log.d(tag, "Resetting retry count: $currentConnectionRetryCount -> 0")
                                    currentConnectionRetryCount = 0
                                    connectionLostRetryCount = 0
                                    // 取消在途的 IP 重试，防止与成功回调交叠导致 onConnected 二次触发
                                    connectionInfoRetryRunnable?.let { handler.removeCallbacks(it) }
                                    connectionInfoRetryRunnable = null
                                    Log.d(tag, "Calling listener.onConnected('$ipAddress')")
                                    listener?.onConnected(ipAddress)
                                } else {
                                    Log.w(tag, "Group owner but IP not available yet, retrying in 500ms...")
                                    // 重试获取 IP（保存句柄支持接管/清理时 removeCallbacks）
                                    val runnable = Runnable { 
                                        Log.d(tag, "Retry delay elapsed, calling requestConnectionInfo() again")
                                        requestConnectionInfo() 
                                    }
                                    connectionInfoRetryRunnable = runnable
                                    handler.postDelayed(runnable, 500)
                                }
                            } else {
                                // 作为客户端，获取组所有者的 IP
                                Log.d(tag, "We are client, getting group owner IP address...")
                                val groupOwnerAddress = it.groupOwnerAddress
                                if (groupOwnerAddress != null) {
                                    Log.d(tag, "Group owner address: $groupOwnerAddress")
                                    val ipAddress = groupOwnerAddress.hostAddress
                                    if (ipAddress != null) {
                                        Log.d(tag, "Connected as client, Group Owner IP: $ipAddress")
                                        Log.d(tag, "Setting isConnecting: $isConnecting -> false")
                                        isConnecting = false
                                        // 连接成功，重置重试次数（含断连重连预算）
                                        Log.d(tag, "Resetting retry count: $currentConnectionRetryCount -> 0")
                                        currentConnectionRetryCount = 0
                                        connectionLostRetryCount = 0
                                        // 取消在途的 IP 重试，防止与成功回调交叠导致 onConnected 二次触发
                                        connectionInfoRetryRunnable?.let { handler.removeCallbacks(it) }
                                        connectionInfoRetryRunnable = null
                                        Log.d(tag, "Calling listener.onConnected('$ipAddress')")
                                        listener?.onConnected(ipAddress)
                                    } else {
                                        Log.w(tag, "Group owner address hostAddress is null, retrying in 500ms...")
                                        // 重试获取 IP（保存句柄支持接管/清理时 removeCallbacks）
                                        val runnable = Runnable { 
                                            Log.d(tag, "Retry delay elapsed, calling requestConnectionInfo() again")
                                            requestConnectionInfo() 
                                        }
                                        connectionInfoRetryRunnable = runnable
                                        handler.postDelayed(runnable, 500)
                                    }
                                } else {
                                    Log.w(tag, "Group owner address not available yet, retrying in 500ms...")
                                    // 重试获取 IP（保存句柄支持接管/清理时 removeCallbacks）
                                    val runnable = Runnable { 
                                        Log.d(tag, "Retry delay elapsed, calling requestConnectionInfo() again")
                                        requestConnectionInfo() 
                                    }
                                    connectionInfoRetryRunnable = runnable
                                    handler.postDelayed(runnable, 500)
                                }
                            }
                        } else {
                            Log.d(tag, "Group not formed yet, waiting...")
                        }
                    } ?: run {
                        Log.w(tag, "Connection info is null")
                    }
                }
            } ?: run {
                Log.e(tag, "Channel is null, cannot request connection info")
            }
        } ?: run {
            Log.e(tag, "Manager is null, cannot request connection info")
        }
        Log.d(tag, "requestConnectionInfo: completed")
    }

    /**
     * 获取本地 IP 地址
     */
    private fun getLocalIpAddress(): String? {
        Log.d(tag, "getLocalIpAddress: called")
        context?.let { ctx ->
            try {
                Log.d(tag, "Enumerating network interfaces...")
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                var interfaceCount = 0
                while (interfaces.hasMoreElements()) {
                    interfaceCount++
                    val networkInterface = interfaces.nextElement()
                    Log.d(tag, "  Interface [$interfaceCount]: name=${networkInterface.name}, isUp=${networkInterface.isUp}, isLoopback=${networkInterface.isLoopback}")
                    val addresses = networkInterface.inetAddresses
                    var addressCount = 0
                    while (addresses.hasMoreElements()) {
                        addressCount++
                        val address = addresses.nextElement()
                        Log.d(tag, "    Address [$addressCount]: $address, isLoopback=${address.isLoopbackAddress}, isIPv4=${address is java.net.Inet4Address}")
                        if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                            val ip = address.hostAddress
                            Log.d(tag, "Found valid IPv4 address: $ip")
                            return ip
                        }
                    }
                }
                Log.d(tag, "No valid IPv4 address found in $interfaceCount interfaces")
            } catch (e: Exception) {
                Log.e(tag, "Error getting IP address", e)
            }
        } ?: run {
            Log.w(tag, "Context is null, cannot get IP address")
        }
        Log.d(tag, "getLocalIpAddress: returning null")
        return null
    }

    /**
     * 重置并重新开始 discovery
     */
    private fun resetAndRestartDiscovery(context: Context){
        Log.d(tag, "resetAndRestartDiscovery: called")
        Log.d(tag, "Manager: ${manager != null}, Channel: ${channel != null}")
        Log.d(tag, "Current state: isDiscovering=$isDiscovering, targetDeviceFound=$targetDeviceFound")
        if (isConnecting) {
            // 正在建连：跳过 removeGroup（自己拆组会触发断开广播打断当前建连），
            // 也不重启 discovery，等连接回调驱动后续流程
            Log.d(tag, "resetAndRestartDiscovery: skip removeGroup/restart while connecting")
            return
        }
        manager?.let { m ->
            channel?.let { c ->
                // 先移除现有组
                Log.d(tag, "Removing existing group...")
                m.removeGroup(c, object : WifiP2pManager.ActionListener{
                    override fun onSuccess() {
                        Log.d(tag, "Group removed successfully, restarting discovery in 500ms...")
                        // 等待一小段时间后重新开始 discovery（保存句柄支持接管/清理时 removeCallbacks）
                        val runnable = Runnable {
                            Log.d(tag, "Delay elapsed, calling startDiscoverP2P()")
                            startDiscoverP2P(context)
                        }
                        restartDiscoveryRunnable = runnable
                        handler.postDelayed(runnable, 500)
                    }

                    override fun onFailure(reason: Int) {
                        Log.e(tag, "Remove group failed: reason=$reason, restarting discovery anyway in 500ms...")
                        // 即使移除失败也重新开始 discovery（保存句柄支持接管/清理时 removeCallbacks）
                        val runnable = Runnable {
                            Log.d(tag, "Delay elapsed, calling startDiscoverP2P()")
                            startDiscoverP2P(context)
                        }
                        restartDiscoveryRunnable = runnable
                        handler.postDelayed(runnable, 500)
                    }
                })
            } ?: run {
                // channel 为 null，直接重新开始 discovery
                Log.w(tag, "Channel is null, reinitializing and starting discovery...")
                channel = m.initialize(context, context.mainLooper, null)
                Log.d(tag, "Channel reinitialized: ${channel != null}")
                startDiscoverP2P(context)
            }
        } ?: run {
            Log.e(tag, "Manager is null, cannot reset and restart discovery")
        }
        Log.d(tag, "resetAndRestartDiscovery: completed")
    }


    fun setToZuoyitong(): ValueUtil.CxrStatus{
        val jsonArray = JSONArray()
        val jsonObject = JSONObject()

        jsonObject.put("key", "settings_phone_model")
        jsonObject.put("value", "zhuoyitong")

        jsonArray.put(jsonObject)
        val paramsJson = jsonArray.toString()
        val caps = Caps().apply {
            write("Settings_Update")
            write(paramsJson)
        }
        return CxrController.getInstance().request(66, "Settings", caps, null)
    }
}