package com.rokid.cxrmsamples.activities.usageSelection

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import com.rokid.cxrmsamples.activities.arRecording.ARRecordingActivity
import com.rokid.cxrmsamples.activities.audio.AudioUsageActivity
import com.rokid.cxrmsamples.activities.customProtocol.CustomProtocolActivity
import com.rokid.cxrmsamples.activities.customView.CustomViewActivity
import com.rokid.cxrmsamples.activities.deviceInformation.DeviceInformationActivity
import com.rokid.cxrmsamples.activities.mediaFile.MediaFileActivity
import com.rokid.cxrmsamples.activities.p2pStabilityTest.P2pStabilityTestActivity
import com.rokid.cxrmsamples.activities.picture.PictureActivity
import com.rokid.cxrmsamples.activities.useAIScene.AISceneActivity
import com.rokid.cxrmsamples.activities.useTeleprompter.TeleprompterSceneActivity
import com.rokid.cxrmsamples.activities.useTranslation.TranslationSceneActivity
import com.rokid.cxrmsamples.activities.liveVideo.LiveVideoActivity
import com.rokid.cxrmsamples.activities.psopDemo.PsopDemoActivity
import com.rokid.cxrmsamples.activities.ttsAndNotification.TTSAndNotificationActivity
import com.rokid.cxrmsamples.activities.video.VideoActivity
import com.rokid.cxrmsamples.dataBeans.UsageType

class UsageSelectionViewModel: ViewModel() {
    fun toUsage(context: Context, type: UsageType) {
        when(type){
            UsageType.USAGE_TYPE_AUDIO -> {
                context.startActivity(Intent(context, AudioUsageActivity::class.java))
            }
            UsageType.USAGE_TYPE_VIDEO -> {
                context.startActivity(Intent(context, VideoActivity::class.java))
            }
            UsageType.USAGE_TYPE_PHOTO -> {
                context.startActivity(Intent(context, PictureActivity::class.java))
            }
            UsageType.USAGE_TYPE_FILE -> {
                context.startActivity(Intent(context, MediaFileActivity::class.java))
            }
            UsageType.USAGE_TYPE_AI -> {
                context.startActivity(Intent(context, AISceneActivity::class.java))
            }
            UsageType.USAGE_CUSTOM_VIEW -> {
                context.startActivity(Intent(context, CustomViewActivity::class.java))
            }
            UsageType.USAGE_TYPE_CUSTOM_PROTOCOL -> {
                context.startActivity(Intent(context, CustomProtocolActivity::class.java))
            }
            UsageType.USAGE_TYPE_TELEPROMPTER -> {
                context.startActivity(Intent(context, TeleprompterSceneActivity::class.java))
            }
            UsageType.USAGE_TYPE_TRANSLATION -> {
                context.startActivity(Intent(context, TranslationSceneActivity::class.java))
            }
            UsageType.USAGE_TYPE_DEVICE_INFORMATION -> {
                context.startActivity(Intent(context, DeviceInformationActivity::class.java))
            }
            UsageType.USAGE_TYPE_TTS_NOTIFICATION -> {
                context.startActivity(Intent(context, TTSAndNotificationActivity::class.java))
            }
            UsageType.USAGE_TYPE_LIVE_VIDEO -> {
                context.startActivity(Intent(context, LiveVideoActivity::class.java))
            }
            UsageType.USAGE_TYPE_AR_RECORDING -> {
                context.startActivity(Intent(context, ARRecordingActivity::class.java))
            }
            UsageType.USAGE_TYPE_PSOP_DEMO -> {
                context.startActivity(Intent(context, PsopDemoActivity::class.java))
            }
            UsageType.USAGE_TYPE_P2P_STABILITY_TEST -> {
                context.startActivity(Intent(context, P2pStabilityTestActivity::class.java))
            }
        }
    }

}