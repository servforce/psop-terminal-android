package com.rokid.cxrmsamples.activities.useTranslation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.listeners.TranslationListener
import com.rokid.cxr.client.utils.ValueUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// 场景示例，文档不展开介绍，详见 Demo。
class TranslationViewModel: ViewModel() {

    private val _textSize = MutableStateFlow(12)
    val textSize = _textSize.asStateFlow()
    private val _startPointX = MutableStateFlow(0)
    val startPointX = _startPointX.asStateFlow()
    private val _startPointY = MutableStateFlow(80)
    val startPointY = _startPointY.asStateFlow()
    private val _width = MutableStateFlow(480)
    val width = _width.asStateFlow()
    private val _height = MutableStateFlow(400)
    val height = _height.asStateFlow()

    private val _isReady = MutableStateFlow(true)
    val isReady = _isReady.asStateFlow()

    private var translationJob: Job? = null

    private val translationListener by lazy {
        object : TranslationListener {
            override fun onTranslationStart() {
                _isReady.value = true
                // 开始模拟翻译 -- 每3秒发送一句翻译结果
                translationJob?.cancel()
                translationJob = viewModelScope.launch {
                    var count = 0
                    while (true) {
                        CxrApi.getInstance().sendTranslationContent(count,1,false,true, "翻译结果:${count++}")
                        delay(3000)
                    }
                }
            }

            override fun onTranslationStop() {
                _isReady.value = true
            }
        }
    }

    init {
        CxrApi.getInstance().setTranslationListener(translationListener)
    }

    override
    fun onCleared() {
        CxrApi.getInstance().setTranslationListener(null)
    }

    private fun controlTranslationParams() {
        CxrApi.getInstance().configTranslationText(
            _textSize.value,//text Size
            _startPointX.value,// start point x
            _startPointY.value,// start point y
            _width.value,// width
            _height.value// height
        )
    }

    fun controlTranslationScene(toOpen: Boolean) {
        _isReady.value = false
        if (!toOpen){
            translationJob?.cancel()
            translationJob = null
        }
        CxrApi.getInstance().controlScene(ValueUtil.CxrSceneType.TRANSLATE, toOpen, null)
    }

    fun setTextSize(value: Int) {
        _textSize.value = value
        controlTranslationParams()
    }

    fun setPointX(newValue: Int) {
        _startPointX.value = newValue
        if (_width.value + _startPointX.value > 480){
            _width.value = 480 - _startPointX.value
        }
        controlTranslationParams()
    }

    fun setPointY(newValue: Int) {
        _startPointY.value = newValue
        if (_height.value + _startPointY.value > 640){
            _height.value = 640 - _startPointY.value
        }
        controlTranslationParams()
    }

    fun setWidth(newValue: Int) {
        _width.value = newValue
        controlTranslationParams()
    }

    fun setHeight(newValue: Int) {
        _height.value = newValue
        controlTranslationParams()
    }

}