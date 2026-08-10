package com.rokid.cxrmsamples.activities.arRecording

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import android.util.Log
import androidx.lifecycle.ViewModel
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.SyncStatusCallback
import com.rokid.cxr.client.extend.callbacks.WifiP2PStatusCallback
import com.rokid.cxr.client.utils.ValueUtil
import com.rokid.cxrmsamples.activities.mediaFile.P2PListener
import com.rokid.cxrmsamples.activities.mediaFile.P2PUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * AR 录屏文件取回验证：
 * 复用 mediaFile 页的 P2P 连接 + startSync2 全量同步模式，
 * 以 VIDEO 类型全量同步眼镜端媒体文件到独立本地目录，
 * 用于验证眼镜端 /sdcard/ScreenRecorder/ 的 mix_record 录屏产物能否被取回。
 *
 * 注意：P2PUtils 是单例且被 mediaFile 页与 PSOP 页共用，
 * 本页用完必须清理 listener 与目标设备，避免污染其他页面。
 */
class ARRecordSyncViewModel : ViewModel() {

    companion object {
        private const val TAG = "AR_RECORD_SYNC"

        /** 独立本地保存目录，先清空再同步，避免与照片目录混淆 */
        private const val LOCAL_DIR = "/sdcard/Download/Rokid/ScreenRecorderSync/"
    }

    /** 整个流程（连接中/同步中）是否进行中，用于禁用按钮防重入 */
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()

    /** 当前状态行：连接中/已连接IP/同步中/同步完成(N个文件)/失败原因 */
    private val _statusText = MutableStateFlow("未开始")
    val statusText = _statusText.asStateFlow()

    /** 状态行颜色标记：0=灰 1=绿 2=橙 3=红 */
    private val _statusLevel = MutableStateFlow(0)
    val statusLevel = _statusLevel.asStateFlow()

    /** 已同步文件名列表（onSingleFileSynced 逐条追加） */
    private val _syncedFiles = MutableStateFlow<List<String>>(emptyList())
    val syncedFiles = _syncedFiles.asStateFlow()

    private var ipAddress: String? = null
    private var appContext: Context? = null
    private var syncedCount = 0

    /**
     * 启动同步流程：initWifiP2P2 → 设备发现 → P2PUtils 建连 → startSync2(VIDEO)
     */
    fun startSync(context: Context) {
        if (_running.value) {
            Log.w(TAG, "startSync ignored: already running")
            return
        }
        appContext = context.applicationContext
        _running.value = true
        syncedCount = 0
        ipAddress = null
        _syncedFiles.value = emptyList()
        setStatus("正在连接...", 2)
        Log.i(TAG, "startSync: begin, localDir=$LOCAL_DIR, mediaType=VIDEO")

        // 与 mediaFile 页同款：设置手机机型标识（眼镜端 P2P 广播前置条件）
        val settingsStatus = P2PUtils.Instance.setToZuoyitong()
        Log.i(TAG, "setToZuoyitong: status=$settingsStatus")

        // 文档 06 方案二：initWifiP2P2，onP2pDeviceAvailable 后由 P2PUtils 建连拿 IP，再 startSync2
        CxrApi.getInstance().initWifiP2P2(false, object : WifiP2PStatusCallback {
            override fun onConnected() { /* 方案二不会回调 */ }
            override fun onDisconnected() { /* 方案二不会回调 */ }
            override fun onFailed(p0: ValueUtil.CxrWifiErrorCode?) { /* 方案二不会回调 */ }

            override fun onP2pDeviceAvailable(
                name: String?,
                macAddress: String?,
                deviceType: String?
            ) {
                Log.i(TAG, "P2P device available: name=$name, mac=$macAddress, type=$deviceType")
                setStatus("已发现设备，正在建立连接...", 2)

                val ctx = appContext ?: return
                // 初始化 P2P（复用 P2PUtils 单例的名字匹配建连逻辑）
                P2PUtils.Instance.initP2P(ctx)
                // 目标设备：照搬 mediaFile 页，直接用 SDK 回调返回的设备名与 MAC
                P2PUtils.Instance.setTargetDevice(name, macAddress)
                P2PUtils.Instance.setListener(object : P2PListener {
                    override fun onDeviceFound(device: WifiP2pDevice) {
                        Log.i(TAG, "Target device found: ${device.deviceName}, ${device.deviceAddress}")
                    }

                    override fun onConnected(ip: String) {
                        Log.i(TAG, "P2P connected, ip=$ip")
                        ipAddress = ip
                        setStatus("已连接 IP：$ip，正在启动同步...", 1)
                        startVideoSync(ip)
                    }

                    override fun onConnectionFailed(reason: String) {
                        Log.e(TAG, "P2P connection failed: $reason")
                        fail("P2P 连接失败：$reason")
                    }

                    override fun onDiscoveryStarted() {
                        Log.i(TAG, "P2P discovery started")
                    }

                    override fun onDiscoveryFailed(reason: Int) {
                        Log.e(TAG, "P2P discovery failed: $reason")
                        fail("设备发现失败：$reason")
                    }
                })
                P2PUtils.Instance.startDiscoverP2P(ctx)
            }
        })
    }

    /**
     * 建连成功后发起全量 VIDEO 同步；本地目录先清空再同步
     */
    @SuppressLint("SdCardPath")
    private fun startVideoSync(ip: String) {
        // 先清空独立目录，避免与旧文件/照片目录混淆
        val dir = File(LOCAL_DIR)
        try {
            if (dir.exists()) {
                dir.listFiles()?.forEach { f ->
                    val deleted = f.delete()
                    Log.d(TAG, "pre-clean: delete ${f.name} -> $deleted")
                }
            }
            dir.mkdirs()
        } catch (e: Exception) {
            Log.w(TAG, "pre-clean dir failed: ${e.message}")
        }

        val ok = CxrApi.getInstance().startSync2(
            LOCAL_DIR,
            arrayOf(ValueUtil.CxrMediaType.VIDEO),
            ip,
            syncCallback
        )
        Log.i(TAG, "startSync2: result=$ok, dir=$LOCAL_DIR, ip=$ip")
        if (!ok) {
            fail("启动同步请求失败（startSync2 返回 false）")
        }
    }

    private val syncCallback = object : SyncStatusCallback {
        override fun onSyncStart() {
            Log.i(TAG, "sync start")
            setStatus("同步中...", 2)
        }

        override fun onSingleFileSynced(filename: String?) {
            syncedCount++
            val name = filename ?: "(unknown)"
            Log.i(TAG, "single file synced #$syncedCount: $name")
            _syncedFiles.update { it + name }
            setStatus("同步中... 已收到 $syncedCount 个文件", 2)
        }

        override fun onSyncFailed() {
            Log.e(TAG, "sync failed")
            fail("同步失败（onSyncFailed）")
        }

        override fun onSyncFinished() {
            Log.i(TAG, "sync finished, total=$syncedCount")
            if (syncedCount > 0) {
                setStatus("同步完成，共 $syncedCount 个视频", 1)
            } else {
                setStatus("同步完成，但未收到任何视频文件（ScreenRecorder 目录可能未被覆盖）", 2)
            }
            teardown()
        }
    }

    private fun setStatus(text: String, level: Int) {
        _statusText.value = text
        _statusLevel.value = level
    }

    /**
     * 失败终态：展示原因 + 清理 P2P + 放开按钮
     */
    private fun fail(reason: String) {
        setStatus("失败：$reason", 3)
        teardown()
    }

    /**
     * 断开 P2P 并清理 P2PUtils 单例状态（listener + 目标设备），
     * 避免污染 mediaFile 页与 PSOP 页
     */
    private fun teardown() {
        appContext?.let {
            P2PUtils.Instance.cleanup(it)
        } ?: run {
            P2PUtils.Instance.stopDiscoverP2P()
            P2PUtils.Instance.setListener(null)
        }
        CxrApi.getInstance().deinitWifiP2P()
        Log.i(TAG, "teardown: P2P cleaned up")
        _running.value = false
    }

    /**
     * 页面销毁兜底清理：即使流程未完成也要释放 P2PUtils 单例的 listener 与发现轮询
     */
    override fun onCleared() {
        appContext?.let {
            P2PUtils.Instance.cleanup(it)
        } ?: run {
            P2PUtils.Instance.stopDiscoverP2P()
            P2PUtils.Instance.setListener(null)
        }
        CxrApi.getInstance().deinitWifiP2P()
        Log.i(TAG, "onCleared: P2P cleaned up")
        super.onCleared()
    }
}
