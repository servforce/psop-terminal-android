package com.rokid.cxrmsamples.activities.mediaFile

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.cxr.client.utils.ValueUtil
import com.rokid.cxrmsamples.R
import com.rokid.cxrmsamples.ui.theme.CXRMSamplesTheme

class MediaFileActivity : ComponentActivity() {

    private val viewModel: MediaFileViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CXRMSamplesTheme {
                MediaFileScreen(
                    viewModel = viewModel,
                    connect = { viewModel.connect(this) },
                    disconnect = { viewModel.disconnect(this) }
                )
            }
        }

        requestPermissionsIfNeeded()
    }

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.NEARBY_WIFI_DEVICES), 1024)
        }
    }
}

@Composable
fun MediaFileScreen(
    viewModel: MediaFileViewModel,
    connect: () -> Unit = {},
    disconnect: () -> Unit = {}
) {
    val connectStatus by viewModel.connected.collectAsState()
    val audioNumber by viewModel.audioNumber.collectAsState()
    val pictureNumber by viewModel.pictureNumber.collectAsState()
    val videoNumber by viewModel.videoNumber.collectAsState()
    val syncStatus by viewModel.syncing.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    val connectStatusText = when (connectStatus) {
        ConnectionStatus.CONNECTED -> stringResource(R.string.media_file_status_connected)
        ConnectionStatus.CONNECTING -> stringResource(R.string.media_file_status_connecting)
        ConnectionStatus.DISCONNECTED -> stringResource(R.string.media_file_status_disconnected)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.glasses_bg),
            modifier = Modifier.fillMaxSize(),
            contentDescription = null,
            alpha = 0.3f
        )
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "", modifier = Modifier.height(32.dp))

            Button(onClick = { viewModel.getUnsyncNum() }) {
                Text(text = stringResource(R.string.media_file_get_unsync_numbers))
            }

            Row(modifier = Modifier.fillMaxWidth(0.85f)) {
                Text(
                    text = stringResource(R.string.media_file_audio_count, audioNumber),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.media_file_picture_count, pictureNumber),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.media_file_video_count, videoNumber),
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                text = stringResource(R.string.media_file_connect_status, connectStatusText),
                modifier = Modifier.padding(vertical = 8.dp)
            )
            if (statusMessage.isNotEmpty()) {
                Text(
                    text = statusMessage,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Button(
                    onClick = connect,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    enabled = connectStatus == ConnectionStatus.DISCONNECTED
                ) {
                    Text(text = stringResource(R.string.media_file_connect_wifi))
                }

                Button(
                    onClick = disconnect,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    enabled = connectStatus == ConnectionStatus.CONNECTED
                ) {
                    Text(text = stringResource(R.string.media_file_disconnect_wifi))
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Button(
                    onClick = { viewModel.startSync(arrayOf(ValueUtil.CxrMediaType.VIDEO)) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    enabled = !syncStatus
                ) {
                    Text(text = stringResource(R.string.media_file_sync_videos))
                }
                Button(
                    onClick = { viewModel.startSync(arrayOf(ValueUtil.CxrMediaType.PICTURE)) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    enabled = !syncStatus
                ) {
                    Text(text = stringResource(R.string.media_file_sync_pictures))
                }
                Button(
                    onClick = { viewModel.startSync(arrayOf(ValueUtil.CxrMediaType.AUDIO)) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    enabled = !syncStatus
                ) {
                    Text(text = stringResource(R.string.media_file_sync_audios))
                }
            }

            Button(
                onClick = { viewModel.stopSync() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                enabled = syncStatus
            ) {
                Text(text = stringResource(R.string.media_file_stop_sync))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    CXRMSamplesTheme {
        MediaFileScreen(viewModel = viewModel { MediaFileViewModel() })
    }
}
