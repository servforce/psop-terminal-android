package com.rokid.cxrmsamples.activities.arRecording

import android.util.Log
import com.rokid.cxr.Caps
import com.rokid.cxr.client.controllers.CxrController
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.utils.ValueUtil
import org.json.JSONObject

/**
 * AR 录屏工具类（mix_record）。
 *
 * 通过蓝牙 RFCOMM 向眼镜发送 Scene_Control 指令，控制眼镜端混合录制
 * （摄像头画面 + AR 叠加内容的合成画面）。
 *
 * 录制文件保存在眼镜端 /sdcard/ScreenRecorder/ 目录，
 * 需通过 WiFi P2P 媒体同步回手机。
 *
 * 注意：此为非公开 API，SDK 未正式封装，后续版本可能变动。
 */
object ARRecordingUtils {

    private const val TAG = "ARRecordingUtils"
    private const val SCENE_NAME = "mix_record"

    /**
     * 控制 AR 混合录屏。
     * @param open true=开始录屏，false=停止录屏
     * @return CxrStatus 请求结果
     */
    fun controlMixRecord(open: Boolean): ValueUtil.CxrStatus {
        return try {
            val json = JSONObject().apply {
                put("name", SCENE_NAME)
                put("open", open)
            }
            val caps = Caps().apply {
                write("Scene_Control")
                write(json.toString())
            }
            val status = CxrController.getInstance().request(
                CxrApi.getInstance().B, "Sys", caps, null
            )
            Log.i(TAG, "controlMixRecord open=$open, status=$status")
            status
        } catch (e: Exception) {
            Log.e(TAG, "controlMixRecord error: ${e.message}", e)
            ValueUtil.CxrStatus.REQUEST_FAILED
        }
    }

    /**
     * 开始 AR 录屏
     */
    fun startRecording(): ValueUtil.CxrStatus = controlMixRecord(true)

    /**
     * 停止 AR 录屏
     */
    fun stopRecording(): ValueUtil.CxrStatus = controlMixRecord(false)
}
