package com.rokid.cxrmsamples.activities.deviceInformation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.cxrmsamples.R
import com.rokid.cxrmsamples.ui.theme.CXRMSamplesTheme

class DeviceInformationActivity : ComponentActivity() {

    private val viewModel: DeviceInformationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CXRMSamplesTheme {
                DeviceInformationScreen(
                    viewModel = viewModel,
                    getAllInformation = { viewModel.getDeviceInformation() },
                    listenVolume = { viewModel.toSetSoundVolumeListener() },
                    listenBrightness = { viewModel.toSetBrightnessListener() },
                    listenBattery = { viewModel.toSetBatteryListener() },
                    listenScreen = { viewModel.toSetScreenListener() },
                    screenOff = { viewModel.notifyScreenOff() },
                    volumeUp = { viewModel.volumeUp() },
                    volumeDown = { viewModel.volumeDown() },
                    brightnessUp = { viewModel.brightnessUp() },
                    brightnessDown = { viewModel.brightnessDown() }
                )
            }
        }
    }
}

@Composable
fun DeviceInformationScreen(
    viewModel: DeviceInformationViewModel,
    getAllInformation: () -> Unit,
    listenVolume: () -> Unit,
    listenBrightness: () -> Unit,
    listenBattery: () -> Unit,
    listenScreen: () -> Unit,
    screenOff: () -> Unit,
    volumeUp: () -> Unit,
    volumeDown: () -> Unit,
    brightnessUp: () -> Unit,
    brightnessDown: () -> Unit
) {
    val deviceName = viewModel.deviceName.collectAsState()
    val deviceId = viewModel.deviceId.collectAsState()
    val systemVersion = viewModel.systemVersion.collectAsState()
    val wearingState = viewModel.wearingState.collectAsState()
    val batteryLevel = viewModel.batteryLevel.collectAsState()
    val isCharging = viewModel.isCharging.collectAsState()
    val brightness = viewModel.brightness.collectAsState()
    val soundVolume = viewModel.soundVolume.collectAsState()
    val isScreenOn = viewModel.isScreenOn.collectAsState()

    val isSetScreenOn = viewModel.screenListenerSet.collectAsState()
    val isSetBrightness = viewModel.brightnessListenerSet.collectAsState()
    val isSetVolume = viewModel.volumeListenerSet.collectAsState()
    val isSetBattery = viewModel.batteryListenerSet.collectAsState()

    val setVolume = viewModel.setVolume.collectAsState()
    val setBrightness = viewModel.setBrightness.collectAsState()

    val ttsStatus = viewModel.ttsStatus.collectAsState()
    val templeStatus = viewModel.templeStatus.collectAsState()
    val warningStatus = viewModel.waringStatus.collectAsState()

    val wearingStateText = when (wearingState.value) {
        "1" -> stringResource(R.string.device_info_state_wearing)
        "0" -> stringResource(R.string.device_info_state_not_wearing)
        else -> stringResource(R.string.device_info_state_unknown)
    }
    val screenStateText = when (isScreenOn.value) {
        true -> stringResource(R.string.device_info_state_screen_on)
        false -> stringResource(R.string.device_info_state_screen_off)
        null -> stringResource(R.string.device_info_state_unknown)
    }
    val chargingText = when (isCharging.value) {
        true -> stringResource(R.string.device_info_state_charging)
        false -> stringResource(R.string.device_info_state_not_charging)
        null -> stringResource(R.string.device_info_state_unknown)
    }
    val templeStatusText = when (templeStatus.value) {
        "1" -> stringResource(R.string.device_info_state_temple_open)
        "0" -> stringResource(R.string.device_info_state_temple_close)
        null -> ""
        else -> stringResource(R.string.device_info_state_unknown)
    }
    val ttsStatusText = when (ttsStatus.value) {
        "1" -> stringResource(R.string.device_info_state_tts_start)
        "0" -> stringResource(R.string.device_info_state_tts_end)
        null -> ""
        else -> stringResource(R.string.device_info_state_unknown)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.glasses_bg),
            contentDescription = null,
            alpha = 0.3f,
            modifier = Modifier.fillMaxSize()
        )
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .align(Alignment.TopCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "", modifier = Modifier.height(32.dp))
            if (!deviceName.value.isNullOrEmpty()) {
                Text(
                    text = stringResource(R.string.device_info_name, deviceName.value.orEmpty()),
                    fontSize = 18.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
            if (!deviceId.value.isNullOrEmpty()) {
                Text(
                    text = stringResource(R.string.device_info_sn, deviceId.value.orEmpty()),
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
            if (!systemVersion.value.isNullOrEmpty()) {
                Text(
                    text = stringResource(
                        R.string.device_info_system_version,
                        systemVersion.value.orEmpty()
                    ),
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
            if (ttsStatusText.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.device_info_tts_status, ttsStatusText),
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
            if (templeStatusText.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.device_info_temple_status, templeStatusText),
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
            if (!warningStatus.value.isNullOrEmpty()) {
                Text(
                    text = stringResource(
                        R.string.device_info_warning_status,
                        warningStatus.value.orEmpty()
                    ),
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                if (!wearingState.value.isNullOrEmpty()) {
                    Text(
                        text = stringResource(R.string.device_info_wearing_state, wearingStateText),
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (isSetScreenOn.value && isScreenOn.value != null) {
                    Text(
                        text = stringResource(R.string.device_info_screen_state, screenStateText),
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                if (batteryLevel.value != -1) {
                    Text(
                        text = stringResource(
                            R.string.device_info_battery_level,
                            batteryLevel.value
                        ),
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (isCharging.value != null) {
                    Text(
                        text = stringResource(R.string.device_info_is_charging, chargingText),
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                if (soundVolume.value != -1) {
                    Text(
                        text = stringResource(R.string.device_info_volume_level, soundVolume.value),
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (brightness.value != -1) {
                    Text(
                        text = stringResource(R.string.device_info_brightness, brightness.value),
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Button(onClick = getAllInformation) {
                Text(text = stringResource(R.string.device_info_get_all))
            }

            Row(
                modifier = Modifier.fillMaxWidth(0.85f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = listenVolume, modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            if (isSetVolume.value) {
                                R.string.device_info_remove_volume
                            } else {
                                R.string.device_info_listen_volume
                            }
                        )
                    )
                }
                Button(onClick = listenBrightness, modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            if (isSetBrightness.value) {
                                R.string.device_info_remove_brightness
                            } else {
                                R.string.device_info_listen_brightness
                            }
                        )
                    )
                }
            }

            Button(onClick = listenBattery, modifier = Modifier.fillMaxWidth(0.85f)) {
                Text(
                    text = stringResource(
                        if (isSetBattery.value) {
                            R.string.device_info_remove_battery
                        } else {
                            R.string.device_info_listen_battery
                        }
                    )
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(0.85f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = listenScreen, modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            if (isSetScreenOn.value) {
                                R.string.device_info_remove_screen
                            } else {
                                R.string.device_info_listen_screen
                            }
                        )
                    )
                }
                Button(onClick = screenOff, modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.device_info_screen_off_action))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(0.85f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.device_info_sound_volume_label),
                    fontSize = 14.sp,
                    modifier = Modifier.weight(0.4f)
                )
                Row(
                    modifier = Modifier.weight(0.6f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = volumeUp,
                        shape = RoundedCornerShape(0.0f),
                        enabled = setVolume.value != 15
                    ) {
                        Text(text = "+")
                    }
                    Text(
                        text = "${setVolume.value}",
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    Button(
                        onClick = volumeDown,
                        shape = RoundedCornerShape(0.0f),
                        enabled = setVolume.value != 0
                    ) {
                        Text(text = "-")
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(0.85f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.device_info_brightness_label),
                    fontSize = 14.sp,
                    modifier = Modifier.weight(0.4f),
                    textAlign = TextAlign.Center
                )
                Row(
                    modifier = Modifier.weight(0.6f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = brightnessUp,
                        shape = RoundedCornerShape(0.0f),
                        enabled = setBrightness.value != 15
                    ) {
                        Text(text = "+")
                    }
                    Text(
                        text = "${setBrightness.value}",
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    Button(
                        onClick = brightnessDown,
                        shape = RoundedCornerShape(0.0f),
                        enabled = setBrightness.value != 0
                    ) {
                        Text(text = "-")
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    CXRMSamplesTheme {
        DeviceInformationScreen(
            viewModel = viewModel { DeviceInformationViewModel() },
            getAllInformation = {},
            listenVolume = {},
            listenBattery = {},
            listenBrightness = {},
            listenScreen = {},
            screenOff = {},
            volumeUp = {},
            volumeDown = {},
            brightnessUp = {},
            brightnessDown = {}
        )
    }
}
