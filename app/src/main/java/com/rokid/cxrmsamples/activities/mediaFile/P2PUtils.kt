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
    private val MAX_DISCOVERY_RETRY_COUNT = 10 // Discovery 最大重试次数
    private val MAX_CONNECTION_RETRY_COUNT = 10 // 连接最大重试次数
    
    // 当前重试次数
    private var currentDiscoveryRetryCount = 0
    private var currentConnectionRetryCount = 0

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
                                    Log.d(tag, "Connection was in progress, marking as failed. Resetting retry count: $currentConnectionRetryCount -> 0")
                                    isConnecting = false
                                    // 连接断开时重置重试次数
                                    currentConnectionRetryCount = 0
                                    Log.d(tag, "Calling listener.onConnectionFailed")
                                    listener?.onConnectionFailed("Connection lost")
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
        isDiscovering = false
        isConnecting = false
        isP2pEnabled = false
        pendingStartDiscovery = false
        pendingDiscoveryContext = null
        targetDeviceFound = false
        // 重置重试次数
        currentDiscoveryRetryCount = 0
        currentConnectionRetryCount = 0
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
        // 只有在开始新的 discovery（不是重试）时才重置重试次数
        if (!isRetry) {
            Log.d(tag, "New discovery, resetting retry count: $currentDiscoveryRetryCount -> 0")
            currentDiscoveryRetryCount = 0
        } else {
            Log.d(tag, "Retry discovery, keeping retry count: $currentDiscoveryRetryCount")
        }
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
                        Log.e(tag, "Discovery failed: reason=$reason, current retry count: $currentDiscoveryRetryCount/$MAX_DISCOVERY_RETRY_COUNT")
                        currentDiscoveryRetryCount++
                        Log.d(tag, "Retry count incremented to: $currentDiscoveryRetryCount")
                        
                        if (currentDiscoveryRetryCount <= MAX_DISCOVERY_RETRY_COUNT) {
                            // 未超过重试次数，继续重试
                            Log.d(tag, "Retrying discovery... ($currentDiscoveryRetryCount/$MAX_DISCOVERY_RETRY_COUNT)")
                            Log.d(tag, "Setting isDiscovering: true -> false")
                            isDiscovering = false
                            // 重新初始化 channel 并重试
                            Log.d(tag, "Reinitializing channel...")
                            channel = null
                            channel = m.initialize(context, context.mainLooper, null)
                            Log.d(tag, "Channel reinitialized: ${channel != null}, scheduling retry in 1000ms...")
                            // 延迟后重试，避免立即重试
                            android.os.Handler(context.mainLooper).postDelayed({
                                Log.d(tag, "Retry delay elapsed, calling startDiscoverP2P with isRetry=true")
                                startDiscoverP2P(context, isRetry = true)
                            }, 1000)
                        } else {
                            // 超过重试次数，停止并通知失败
                            Log.e(tag, "Discovery failed after $MAX_DISCOVERY_RETRY_COUNT retries, stopping")
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
                    android.os.Handler(context.mainLooper).postDelayed({
                        Log.d(tag, "Retry delay elapsed, calling startDiscoverP2P with isRetry=true")
                        startDiscoverP2P(context, isRetry = true)
                    }, 500)
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
        Log.d(tag, "stopDiscoverP2P: called, isDiscovering=$isDiscovering")
        isDiscovering = false
        Log.d(tag, "isDiscovering set to false")
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
                        // 查找目标设备
                        Log.d(tag, "Searching for target device...")
                        for (device in deviceList) {
                            val matchesName = targetDeviceName?.let { 
                                val match = device.deviceName.equals(it, ignoreCase = true)
                                Log.d(tag, "  Comparing name: '${device.deviceName}' == '$it' (ignoreCase) = $match")
                                match
                            } ?: false
                            val matchesMac = targetDeviceMac?.let { 
                                val match = device.deviceAddress.equals(it, ignoreCase = true)
                                Log.d(tag, "  Comparing mac: '${device.deviceAddress}' == '$it' (ignoreCase) = $match")
                                match
                            } ?: false
                            
                            Log.d(tag, "  Device '${device.deviceName}' (${device.deviceAddress}): matchesName=$matchesName, matchesMac=$matchesMac")
                            
                            if (matchesName || matchesMac) {
                                Log.d(tag, "Target device found! name=${device.deviceName}, address=${device.deviceAddress}, status=${device.status}")
                                Log.d(tag, "Setting targetDeviceFound: $targetDeviceFound -> true")
                                targetDeviceFound = true
                                Log.d(tag, "Calling listener.onDeviceFound()")
                                listener?.onDeviceFound(device)
                                // 连接到目标设备
                                Log.d(tag, "Calling connectToDevice()")
                                connectToDevice(device)
                                return@requestPeers
                            }
                        }
                        Log.d(tag, "Target device not found in peer list of ${deviceList.size} devices")
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
     * 连接到指定设备
     */
    private fun connectToDevice(device: WifiP2pDevice){
        Log.d(tag, "connectToDevice: called")
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
                        // 成功时重置重试次数
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
                            Log.d(tag, "Scheduling retry in 1000ms...")
                            // 延迟后重试，避免立即重试
                            android.os.Handler(context?.mainLooper ?: android.os.Looper.getMainLooper())
                                .postDelayed({
                                    Log.d(tag, "Retry delay elapsed, calling performConnection() again")
                                    performConnection(device)
                                }, 1000)
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
                                    // 连接成功，重置重试次数
                                    Log.d(tag, "Resetting retry count: $currentConnectionRetryCount -> 0")
                                    currentConnectionRetryCount = 0
                                    Log.d(tag, "Calling listener.onConnected('$ipAddress')")
                                    listener?.onConnected(ipAddress)
                                } else {
                                    Log.w(tag, "Group owner but IP not available yet, retrying in 500ms...")
                                    // 重试获取 IP
                                    android.os.Handler(context?.mainLooper ?: android.os.Looper.getMainLooper())
                                        .postDelayed({ 
                                            Log.d(tag, "Retry delay elapsed, calling requestConnectionInfo() again")
                                            requestConnectionInfo() 
                                        }, 500)
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
                                        // 连接成功，重置重试次数
                                        Log.d(tag, "Resetting retry count: $currentConnectionRetryCount -> 0")
                                        currentConnectionRetryCount = 0
                                        Log.d(tag, "Calling listener.onConnected('$ipAddress')")
                                        listener?.onConnected(ipAddress)
                                    } else {
                                        Log.w(tag, "Group owner address hostAddress is null, retrying in 500ms...")
                                        // 重试获取 IP
                                        android.os.Handler(context?.mainLooper ?: android.os.Looper.getMainLooper())
                                            .postDelayed({ 
                                                Log.d(tag, "Retry delay elapsed, calling requestConnectionInfo() again")
                                                requestConnectionInfo() 
                                            }, 500)
                                    }
                                } else {
                                    Log.w(tag, "Group owner address not available yet, retrying in 500ms...")
                                    // 重试获取 IP
                                    android.os.Handler(context?.mainLooper ?: android.os.Looper.getMainLooper())
                                        .postDelayed({ 
                                            Log.d(tag, "Retry delay elapsed, calling requestConnectionInfo() again")
                                            requestConnectionInfo() 
                                        }, 500)
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
        manager?.let { m ->
            channel?.let { c ->
                // 先移除现有组
                Log.d(tag, "Removing existing group...")
                m.removeGroup(c, object : WifiP2pManager.ActionListener{
                    override fun onSuccess() {
                        Log.d(tag, "Group removed successfully, restarting discovery in 500ms...")
                        // 等待一小段时间后重新开始 discovery
                        android.os.Handler(context.mainLooper).postDelayed({
                            Log.d(tag, "Delay elapsed, calling startDiscoverP2P()")
                            startDiscoverP2P(context)
                        }, 500)
                    }

                    override fun onFailure(reason: Int) {
                        Log.e(tag, "Remove group failed: reason=$reason, restarting discovery anyway in 500ms...")
                        // 即使移除失败也重新开始 discovery
                        android.os.Handler(context.mainLooper).postDelayed({
                            Log.d(tag, "Delay elapsed, calling startDiscoverP2P()")
                            startDiscoverP2P(context)
                        }, 500)
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