package com.rokid.cxrmsamples.activities.mediaFile

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import com.rokid.cxr.Caps
import com.rokid.cxr.client.controllers.CxrController
import com.rokid.cxr.client.extend.Constants
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.SyncStatusCallback
import com.rokid.cxr.client.extend.callbacks.UnsyncNumResultCallback
import com.rokid.cxr.client.extend.callbacks.WifiP2PStatusCallback
import com.rokid.cxr.client.extend.listeners.MediaFilesUpdateListener
import com.rokid.cxr.client.utils.ValueUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Enum representing the connection status states
 * CONNECTED: Device is connected
 * CONNECTING: Connection attempt is in progress
 * DISCONNECTED: Device is not connected
 */
enum class ConnectionStatus{
    CONNECTED,
    CONNECTING,
    DISCONNECTED
}

/**
 * 文档参考：06媒体文件同步。本示例采用方案二：initWifiP2P2 + onP2pDeviceAvailable + startSync2(ipAddress)。
 * 方案一为 initWifiP2P + startSync，见下方注释掉的代码块。
 */
class MediaFileViewModel: ViewModel() {

    private val tag = "MediaFileViewModel"
    
    /** State flow representing the current connection status */
    private val _connected: MutableStateFlow<ConnectionStatus> = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    /** Public state flow for observing connection status */
    val connected = _connected.asStateFlow()

    /** State flow for the number of unsynchronized audio files */
    private val _audioNumber: MutableStateFlow<Int> = MutableStateFlow(0)
    /** Public state flow for observing audio file count */
    val audioNumber = _audioNumber.asStateFlow()
    
    /** State flow for the number of unsynchronized picture files */
    private val _pictureNumber: MutableStateFlow<Int> = MutableStateFlow(0)
    /** Public state flow for observing picture file count */
    val pictureNumber = _pictureNumber.asStateFlow()
    
    /** State flow for the number of unsynchronized video files */
    private val _videoNumber: MutableStateFlow<Int> = MutableStateFlow(0)
    /** Public state flow for observing video file count */
    val videoNumber = _videoNumber.asStateFlow()

    /** State flow indicating if synchronization is in progress */
    private val _syncing: MutableStateFlow<Boolean> = MutableStateFlow(false)
    /** Public state flow for observing synchronization status */
    val syncing = _syncing.asStateFlow()

    /** State flow for lightweight UI messages (info/warn) */
    private val _statusMessage: MutableStateFlow<String> = MutableStateFlow("")
    /** Public state flow for observing status messages */
    val statusMessage = _statusMessage.asStateFlow()

    /** Listener for media file updates */
    private val mediaFilesUpdateListener = MediaFilesUpdateListener { getUnsyncNum() }

    private var ipAddress: String? = null

    init {
        // Optional: preset video parameters
        CxrApi.getInstance().setVideoParams(60, 30, 1920, 1080, 0)
        setMediaFilesUpdateListener()
        P2PUtils.Instance.setToZuoyitong()
    }

    /**
     * Called when the ViewModel is cleared to clean up resources
     */
    override fun onCleared() {
        CxrApi.getInstance().setMediaFilesUpdateListener(null)
        super.onCleared()
    }

    /**
     * Sets the media files update listener
     */
    fun setMediaFilesUpdateListener(){
        CxrApi.getInstance().setMediaFilesUpdateListener(mediaFilesUpdateListener)
    }

    /**
     * Callback for handling the result of getting unsynchronized media file counts
     */
    private val unsyncNumResultCallback =
        UnsyncNumResultCallback { status, audioNum, pictureNum, videoNum ->

            when(status){
                ValueUtil.CxrStatus.RESPONSE_SUCCEED -> {
                    Log.i(tag, "get unsync num succeed")
                    _audioNumber.value = audioNum
                    _pictureNumber.value = pictureNum
                    _videoNumber.value = videoNum
                }
                ValueUtil.CxrStatus.RESPONSE_INVALID -> {
                    Log.e(tag, "get unsync num failed: RESPONSE_INVALID")
                }
                ValueUtil.CxrStatus.RESPONSE_TIMEOUT -> {
                    Log.e(tag, "get unsync num failed: RESPONSE_TIMEOUT")
                }
                else -> {
                    Log.e(tag, "get unsync num failed: unknown error")
                }
            }

        }

    /**
     * Callback for monitoring synchronization status
     */
    private val syncStatus = object : SyncStatusCallback{
        /**
         * Called when synchronization starts
         */
        override fun onSyncStart() {
            // Todo when sync start
        }

        /**
         * Called when a single file has been synchronized
         * @param filename The name of the synchronized file
         */
        override fun onSingleFileSynced(filename: String?) {
            // Todo when sync single file
            Log.i(tag, "sync single file, name = $filename")
            getUnsyncNum()
        }

        /**
         * Called when synchronization fails
         */
        override fun onSyncFailed() {
            _syncing.value = false
            Log.e(tag, "sync failed")
        }

        /**
         * Called when synchronization finishes successfully
         */
        override fun onSyncFinished() {
            _syncing.value = false
            Log.i(tag, "sync finished")
            getUnsyncNum()
        }

    }



    /**
     * Initiates connection to the device via WiFi P2P
     */
    fun connect(context: Context){
        _connected.value = ConnectionStatus.CONNECTING
        _statusMessage.value = "正在连接..."

        // 文档 06方案二：initWifiP2P2；onP2pDeviceAvailable 中拿到设备信息后由 P2PUtils 建立连接并得到 ipAddress，再 startSync2
        CxrApi.getInstance().initWifiP2P2(false, object : WifiP2PStatusCallback {
            override fun onConnected() {
                // if false this will not be called
            }

            override fun onDisconnected() {
                // if false this will not be called
            }

            override fun onFailed(p0: ValueUtil.CxrWifiErrorCode?) {
                // if false this will not be called
            }

            override fun onP2pDeviceAvailable(
                name: String?,
                macAddress: String?,
                deviceType: String?
            ) {
                // to start p2p connection
                Log.i(tag, "P2P device available: name=$name, mac=$macAddress, type=$deviceType")

                // 初始化 P2P
                P2PUtils.Instance.initP2P(context)

                // 设置目标设备信息
                P2PUtils.Instance.setTargetDevice(name, macAddress)

                // 设置监听器
                P2PUtils.Instance.setListener(object : P2PListener {
                    override fun onDeviceFound(device: android.net.wifi.p2p.WifiP2pDevice) {
                        Log.i(tag, "Target device found: ${device.deviceName}, ${device.deviceAddress}")
                        _statusMessage.value = "已发现设备，正在建立连接..."
                    }

                    override fun onConnected(ipAddress: String) {
                        Log.i(tag, "P2P connected successfully, IP: $ipAddress")
                        _connected.value = ConnectionStatus.CONNECTED
                        _statusMessage.value = "Wi-Fi P2P 已连接：$ipAddress"
                        this@MediaFileViewModel.ipAddress = ipAddress
                    }

                    override fun onConnectionFailed(reason: String) {
                        Log.e(tag, "P2P connection failed: $reason")
                        _connected.value = ConnectionStatus.DISCONNECTED
                        _statusMessage.value = "连接失败：$reason"
                    }

                    override fun onDiscoveryStarted() {
                        Log.i(tag, "P2P discovery started")
                        _statusMessage.value = "正在发现设备..."
                    }

                    override fun onDiscoveryFailed(reason: Int) {
                        Log.e(tag, "P2P discovery failed: $reason")
                        _connected.value = ConnectionStatus.DISCONNECTED
                        _statusMessage.value = "发现设备失败：$reason"
                    }
                })

                // 开始发现设备
                P2PUtils.Instance.startDiscoverP2P(context)
            }

        })


// 文档 06方案一：initWifiP2P(callback)，连接成功后用 startSync(path, mediaTypes, syncStatus)
//        when (CxrApi.getInstance().initWifiP2P( object : WifiP2PStatusCallback {
//
//            /**
//             * Called when connection is established
//             */
//            override fun onConnected() {
//                _connected.value = ConnectionStatus.CONNECTED
//                _statusMessage.value = "Wi-Fi P2P connected"
//            }
//
//            /**
//             * Called when device is disconnected
//             */
//            override fun onDisconnected() {
//                disconnect()
////                _connected.value = ConnectionStatus.DISCONNECTED
//            }
//
//            /**
//             * Called when connection attempt fails
//             * @param errorCode The error code indicating failure reason
//             */
//            override fun onFailed(errorCode: ValueUtil.CxrWifiErrorCode?) {
////                _connected.value = ConnectionStatus.DISCONNECTED
//                disconnect()
//            }
//        })) {
//            ValueUtil.CxrStatus.REQUEST_SUCCEED -> {
//                _statusMessage.value = "Connecting..."
//            }
//            ValueUtil.CxrStatus.REQUEST_FAILED -> {
//                _connected.value = ConnectionStatus.DISCONNECTED
//                _statusMessage.value = "Connect request failed"
//            }
//            ValueUtil.CxrStatus.REQUEST_WAITING -> {
//                // Glasses busy, inform user and restore UI
//                _connected.value = ConnectionStatus.DISCONNECTED
//                _statusMessage.value = "Device busy, do not need try agin"
//            }
//            else -> {
//                _connected.value = ConnectionStatus.DISCONNECTED
//                _statusMessage.value = "Connect request unknown status"
//            }
//        }
    }

    /**
     * Disconnects from the device and cleans up the connection
     */
    fun disconnect(context: Context? = null){
//        // 清理 P2P 资源
        context?.let {
            P2PUtils.Instance.cleanup(it)
        } ?: run {
            // 如果没有 context，至少停止 discovery 和清除监听器
            P2PUtils.Instance.stopDiscoverP2P()
            P2PUtils.Instance.setListener(null)
        }
        // 断开连接
        CxrApi.getInstance().deinitWifiP2P()
//        CxrApi.getInstance().deinitWifiHot()
        context?.let { WifiHostUtils3.Instance.cleanup(it) }
        _connected.value = ConnectionStatus.DISCONNECTED
        _statusMessage.value = "已断开连接"
    }

    /**
     * Retrieves the number of unsynchronized media files
     */
    fun getUnsyncNum(){
        when(CxrApi.getInstance().getUnsyncNum(unsyncNumResultCallback)){
            ValueUtil.CxrStatus.REQUEST_SUCCEED -> {// request succeed
            }
            ValueUtil.CxrStatus.REQUEST_FAILED -> {
                Log.e(tag, "get unsync num failed")
            }
            ValueUtil.CxrStatus.REQUEST_WAITING -> {
                // Glasses busy, inform user and restore UI
                Log.e(tag, "get unsync num failed: REQUEST_WAITING")
            }
            else -> {
                Log.e(tag, "get unsync num unknown status")
            }
        }
    }

    /**
     * Starts synchronization of media files
     * @param mediaType Array of media types to synchronize
     */
    @SuppressLint("SdCardPath")
    fun startSync(mediaType: Array<ValueUtil.CxrMediaType>){

        // Ensure local target directory exists before sync, avoid startSync失败
        val file = File("/sdcard/Download/Rokid/Media/")
        if (!file.exists()){
            file.mkdirs()
        }


        // 文档 06方案二：startSync2(path, mediaTypes, ipAddress, syncStatus)，ipAddress 来自 P2P 连接
        this@MediaFileViewModel.ipAddress?.let {
            when(CxrApi.getInstance().startSync2("/sdcard/Download/Rokid/Media/", mediaType,  it, syncStatus)){
                true -> {
                    Log.i(tag, "start sync succeed")
                    _syncing.value = true
                }
                false -> {
                    Log.e(tag, "start sync failed")
                }
            }
        }


// 文档 06方案一：startSync(path, mediaTypes, syncStatus)
//        when(CxrApi.getInstance().startSync("/sdcard/Download/Rokid/Media/", mediaType, syncStatus)){
//            true -> {
//                Log.i(tag, "start sync succeed")
//                _syncing.value = true
//            }
//            false -> {
//                Log.e(tag, "start sync failed")
//            }
//        }

    }

    /**
     * Starts synchronization of a single media file
     * @param filePath Path to the file to synchronize
     * @param mediaType Media type of the file to synchronize
     */
    fun startSyncSingle(filePath: String, mediaType: ValueUtil.CxrMediaType){
        CxrApi.getInstance().syncSingleFile("/sdcard/Download/Rokid/Media/", mediaType,  "/sdcard/Download/Rokid/Media/test.jpg", syncStatus)
    }

    /**
     * Stops the ongoing synchronization process
     */
    fun stopSync(){
        CxrApi.getInstance().stopSync()
        _syncing.value = false
    }

}
