package com.rokid.cxrmsamples.activities.ttsAndNotification

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.cxrmsamples.R
import com.rokid.cxrmsamples.ui.theme.CXRMSamplesTheme
import java.util.Locale

class TTSAndNotificationActivity : ComponentActivity() {

    private val viewModel: TTSAndNotificationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CXRMSamplesTheme {
                TTSAndNotificationScreen(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TTSAndNotificationScreen(viewModel: TTSAndNotificationViewModel = viewModel()) {
    var notificationContentText by remember { mutableStateOf("") }
    var notificationIconType by remember { mutableStateOf(IconType.NONE) }
    var notificationPlayTTS by remember { mutableStateOf(false) }
    var notificationExpandedIconType by remember { mutableStateOf(false) }

    var toastContentText by remember { mutableStateOf("") }
    var toastIconType by remember { mutableStateOf(IconType.NONE) }
    var toastPlayTTS by remember { mutableStateOf(false) }
    var toastExpandedIconType by remember { mutableStateOf(false) }

    var ttsSpeed by remember { mutableStateOf("1.0") }
    var ttsContent by remember { mutableStateOf("") }
    var selectedVoiceType by remember { mutableStateOf(VoiceType.Girl) }

    Image(
        painter = painterResource(id = R.drawable.glasses_bg),
        modifier = Modifier.fillMaxSize(),
        contentDescription = null,
        alpha = 0.3f
    )

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.tts_notification_section), modifier = Modifier.fillMaxWidth())

        OutlinedTextField(
            value = notificationContentText,
            onValueChange = { notificationContentText = it },
            label = { Text(stringResource(R.string.tts_notification_content)) },
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ExposedDropdownMenuBox(
                modifier = Modifier.weight(1f),
                expanded = notificationExpandedIconType,
                onExpandedChange = { notificationExpandedIconType = !notificationExpandedIconType }
            ) {
                TextField(
                    readOnly = true,
                    value = stringResource(notificationIconType.labelResId),
                    onValueChange = { },
                    label = { Text(stringResource(R.string.tts_icon_label)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(
                            expanded = notificationExpandedIconType
                        )
                    },
                )
                DropdownMenu(
                    expanded = notificationExpandedIconType,
                    onDismissRequest = { notificationExpandedIconType = false }
                ) {
                    IconType.values().forEach { type ->
                        DropdownMenuItem(
                            text = { Text(stringResource(type.labelResId)) },
                            onClick = {
                                notificationIconType = type
                                notificationExpandedIconType = false
                            }
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.tts_toggle_label))
                Switch(
                    checked = notificationPlayTTS,
                    onCheckedChange = { notificationPlayTTS = it }
                )
            }

            Button(
                onClick = {
                    viewModel.sendNotification(
                        notificationIconType,
                        notificationContentText,
                        notificationPlayTTS
                    )
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.tts_send_button))
            }
        }

        Text(stringResource(R.string.tts_toast_section), modifier = Modifier.fillMaxWidth())

        OutlinedTextField(
            value = toastContentText,
            onValueChange = { toastContentText = it },
            label = { Text(stringResource(R.string.tts_toast_content)) },
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ExposedDropdownMenuBox(
                modifier = Modifier.weight(1f),
                expanded = toastExpandedIconType,
                onExpandedChange = { toastExpandedIconType = !toastExpandedIconType }
            ) {
                TextField(
                    readOnly = true,
                    value = stringResource(toastIconType.labelResId),
                    onValueChange = { },
                    label = { Text(stringResource(R.string.tts_icon_label)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = toastExpandedIconType)
                    },
                )
                DropdownMenu(
                    expanded = toastExpandedIconType,
                    onDismissRequest = { toastExpandedIconType = false }
                ) {
                    IconType.values().forEach { type ->
                        DropdownMenuItem(
                            text = { Text(stringResource(type.labelResId)) },
                            onClick = {
                                toastIconType = type
                                toastExpandedIconType = false
                            }
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.tts_toggle_label))
                Switch(
                    checked = toastPlayTTS,
                    onCheckedChange = { toastPlayTTS = it }
                )
            }

            Button(
                onClick = { viewModel.sendToast(toastIconType, toastContentText, toastPlayTTS) },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.tts_send_button))
            }
        }

        Text(stringResource(R.string.tts_settings_section), modifier = Modifier.fillMaxWidth())

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.tts_voice_female))
                RadioButton(
                    selected = selectedVoiceType == VoiceType.Girl,
                    onClick = { selectedVoiceType = VoiceType.Girl }
                )

                Text(stringResource(R.string.tts_voice_male))
                RadioButton(
                    selected = selectedVoiceType == VoiceType.Boy,
                    onClick = { selectedVoiceType = VoiceType.Boy }
                )
            }

            Button(onClick = { viewModel.setTTSLocalParam(selectedVoiceType) }) {
                Text(stringResource(R.string.tts_set_button))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Slider(
                value = ttsSpeed.toFloatOrNull() ?: 1.0f,
                onValueChange = {
                    ttsSpeed = String.format(Locale.US, "%.2f", it)
                },
                valueRange = 0.75f..4.0f,
                steps = 12,
                modifier = Modifier.weight(1f)
            )

            Button(
                onClick = {
                    val speed = ttsSpeed.toFloatOrNull()
                    if (speed != null && speed in 0.75f..4.0f) {
                        viewModel.setTTSSpeed(speed)
                    }
                },
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text(stringResource(R.string.tts_set_button))
            }
        }

        Text(
            stringResource(R.string.tts_speed_current, ttsSpeed),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = ttsContent,
            onValueChange = { ttsContent = it },
            label = { Text(stringResource(R.string.tts_play_content)) },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = { viewModel.tts(ttsContent) },
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            Text(stringResource(R.string.tts_play_button))
        }
    }
}

@Preview(showBackground = true)
@Composable
fun TTSAndNotificationScreenPreview() {
    CXRMSamplesTheme {
        TTSAndNotificationScreen()
    }
}
