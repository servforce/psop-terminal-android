package com.rokid.cxrmsamples.activities.ttsAndNotification

import androidx.annotation.FloatRange
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.utils.ValueUtil
import com.rokid.cxrmsamples.R

enum class VoiceType(val voiceType: String) {
    Girl("{\"voice_id\": 1}"),
    Boy("{\"voice_id\": 2}"),
}

enum class IconType(val iconType: Int, @StringRes val labelResId: Int) {
    NONE(0, R.string.tts_icon_none),
    InfoOrError(1, R.string.tts_icon_info_error),
    Success(2, R.string.tts_icon_success),
    During(3, R.string.tts_icon_during),
    Unknown(4, R.string.tts_icon_unknown),
}

class TTSAndNotificationViewModel : ViewModel() {

    fun sendNotification(iconType: IconType, content: String, playTTS: Boolean) {
        when (CxrApi.getInstance().sendGlobalMsgContent(iconType.iconType, content, playTTS)) {
            ValueUtil.CxrStatus.REQUEST_SUCCEED -> Unit
            ValueUtil.CxrStatus.REQUEST_FAILED -> Unit
            ValueUtil.CxrStatus.REQUEST_WAITING -> Unit
            else -> Unit
        }
    }

    fun sendToast(iconType: IconType, content: String, playTTS: Boolean) {
        when (CxrApi.getInstance().sendGlobalToastContent(iconType.iconType, content, playTTS)) {
            ValueUtil.CxrStatus.REQUEST_SUCCEED -> Unit
            ValueUtil.CxrStatus.REQUEST_FAILED -> Unit
            ValueUtil.CxrStatus.REQUEST_WAITING -> Unit
            else -> Unit
        }
    }

    fun setTTSLocalParam(voiceType: VoiceType) {
        when (CxrApi.getInstance().setLocalTtsParam(voiceType.voiceType)) {
            ValueUtil.CxrStatus.REQUEST_SUCCEED -> Unit
            ValueUtil.CxrStatus.REQUEST_FAILED -> Unit
            ValueUtil.CxrStatus.REQUEST_WAITING -> Unit
            else -> Unit
        }
    }

    fun setTTSSpeed(@FloatRange(from = 0.75, to = 4.0) speed: Float) {
        when (CxrApi.getInstance().setLocalTtsSpeed(speed)) {
            ValueUtil.CxrStatus.REQUEST_SUCCEED -> Unit
            ValueUtil.CxrStatus.REQUEST_FAILED -> Unit
            ValueUtil.CxrStatus.REQUEST_WAITING -> Unit
            else -> Unit
        }
    }

    fun tts(content: String) {
        when (CxrApi.getInstance().sendGlobalTtsContent(content)) {
            ValueUtil.CxrStatus.REQUEST_SUCCEED -> Unit
            ValueUtil.CxrStatus.REQUEST_FAILED -> Unit
            ValueUtil.CxrStatus.REQUEST_WAITING -> Unit
            else -> Unit
        }
    }
}
