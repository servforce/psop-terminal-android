package com.rokid.cxrmsamples.activities.useTeleprompter

import android.content.Context
import androidx.lifecycle.ViewModel
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.SendStatusCallback
import com.rokid.cxr.client.utils.ValueUtil
import com.rokid.cxrmsamples.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

enum class TeleprompterMode(string: String) {
    AI("ai"), NORMAL("normal")
}

// 场景示例，文档不展开介绍，详见 Demo。
class TeleprompterViewModel : ViewModel() {

    private val _textSize = MutableStateFlow(12f)
    val textSize = _textSize.asStateFlow()
    private val _lineSpace = MutableStateFlow(0f)
    val lineSpace = _lineSpace.asStateFlow()

    // 场景模式--取值"ai"/"normal"
    private val _mode = MutableStateFlow(TeleprompterMode.AI)
    val mode = _mode.asStateFlow()
    private val _startPointX = MutableStateFlow(0)
    val startPointX = _startPointX.asStateFlow()
    private val _startPointY = MutableStateFlow(80)
    val startPointY = _startPointY.asStateFlow()

    // 场景宽高 -- 480 640
    private val _width = MutableStateFlow(480)
    val width = _width.asStateFlow()
    private val _height = MutableStateFlow(400)
    val height = _height.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady = _isReady.asStateFlow()

    private val _isUploading = MutableStateFlow(false)
    val isUploading = _isUploading.asStateFlow()

    private val sendStatusCallback by lazy {
        object : SendStatusCallback{
            override fun onSendSucceed() {
                //发送成功
                _isReady.value = true
                _isUploading.value = false
            }

            override fun onSendFailed(p0: ValueUtil.CxrSendErrorCode?) {
                //发送失败
                _isReady.value = false
                _isUploading.value = false
            }

        }
    }

    fun sendStream(context: Context){
        //发送流数据 -- 读取raw/demo.txt
        _isUploading.value = true
        val inputStream: InputStream = context.resources.openRawResource(R.raw.demo)
        val byteArray: ByteArray = inputStream.readBytes()
        CxrApi.getInstance().sendStream(ValueUtil.CxrStreamType.WORD_TIPS, byteArray, "demo.txt", sendStatusCallback)
    }


    /**
     * 控制场景 打开或者关闭提词器场景
     * @param toOpen true 打开 false 关闭
     * @return
     */
    fun controlSceneTeleprompter(toOpen: Boolean) {
        if (toOpen){
            controlTeleprompterParams()
        }
        CxrApi.getInstance().controlScene(ValueUtil.CxrSceneType.WORD_TIPS, toOpen, null)
    }

    /**
     * 配置提词器场景参数
     * @return
     */
    private fun controlTeleprompterParams() {
        CxrApi.getInstance().configWordTipsText(
            _textSize.value,//text Size
            _lineSpace.value,//line space
            _mode.value.name,// mode
            _startPointX.value,// start point x
            _startPointY.value,// start point y
            _width.value,// width
            _height.value// height
        )
    }


    /**
     * 模拟将ASR 结果文本发送给提词器，高亮显示，同时如果模式是"ai"，则自动上滑
     * @param text
     */
    fun sendASRToStartAutoScroll(text: String) {
        CxrApi.getInstance().sendWordTipsAsrContent(text)
    }

    fun setMode(mode: TeleprompterMode) {
        _mode.value = mode
        controlTeleprompterParams()
    }

    fun setTextSize(value: Float) {
        _textSize.value = value
        controlTeleprompterParams()
    }

    fun setLineSpace(newValue: Float) {
        _lineSpace.value = newValue
        controlTeleprompterParams()
    }

    fun setPointX(newValue: Float) {
        _startPointX.value = newValue.toInt()
        if (_width.value + _startPointX.value > 480){
            _width.value = 480 - _startPointX.value
        }
        controlTeleprompterParams()
    }

    fun setPointY(newValue: Float) {
        _startPointY.value = newValue.toInt()
        if (_height.value + _startPointY.value > 640){
            _height.value = 640 - _startPointY.value
        }
        controlTeleprompterParams()
    }

    fun setWidth(newValue: Float) {
        _width.value = newValue.toInt()
        controlTeleprompterParams()
    }

    fun setHeight(newValue: Float) {
        _height.value = newValue.toInt()
        controlTeleprompterParams()
    }

}