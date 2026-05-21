package com.rokid.cxrmsamples.activities.liveVideo

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.cxrmsamples.R
import com.rokid.cxrmsamples.ui.theme.CXRMSamplesTheme

class LiveVideoActivity : ComponentActivity() {
    private val viewModel: LiveVideoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CXRMSamplesTheme {
                LiveVideoScreen(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveVideoScreen(viewModel: LiveVideoViewModel) {
    val selectedResolution by viewModel.selectedResolution.collectAsState()
    val selectedCodec by viewModel.selectedCodec.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val cameraOpened by viewModel.cameraOpened.collectAsState()
    val cameraError by viewModel.cameraError.collectAsState()
    val frameCount by viewModel.frameCount.collectAsState()
    val cameraInUse by viewModel.cameraInUse.collectAsState()
    val recordToFile by viewModel.recordToFile.collectAsState()
    val isRecordingToFile by viewModel.isRecordingToFile.collectAsState()
    val currentRecordPath by viewModel.currentRecordPath.collectAsState()

    var resolutionExpanded by remember { mutableStateOf(false) }
    var codecExpanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.glasses_bg),
            modifier = Modifier.fillMaxSize(),
            contentDescription = null,
            alpha = 0.3f
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.live_video_screen_title),
                modifier = Modifier.padding(top = 32.dp, bottom = 16.dp)
            )

            if (isStreaming) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .padding(vertical = 8.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            SurfaceView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        viewModel.setPreviewSurface(holder.surface)
                                    }

                                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                                        viewModel.setPreviewSurface(null)
                                    }

                                    override fun surfaceChanged(
                                        holder: SurfaceHolder,
                                        format: Int,
                                        width: Int,
                                        height: Int
                                    ) = Unit
                                })
                            }
                        }
                    )
                }
            }

            ExposedDropdownMenuBox(
                expanded = resolutionExpanded,
                onExpandedChange = { resolutionExpanded = !resolutionExpanded }
            ) {
                TextField(
                    value = "${selectedResolution.width}x${selectedResolution.height}",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.live_video_resolution_label)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = resolutionExpanded)
                    },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                        .fillMaxWidth()
                )
                DropdownMenu(
                    expanded = resolutionExpanded,
                    onDismissRequest = { resolutionExpanded = false },
                    modifier = Modifier.exposedDropdownSize(true)
                ) {
                    viewModel.resolutions.forEach { size ->
                        DropdownMenuItem(
                            text = { Text("${size.width}x${size.height}") },
                            onClick = {
                                viewModel.selectResolution(size)
                                resolutionExpanded = false
                            }
                        )
                    }
                }
            }

            ExposedDropdownMenuBox(
                expanded = codecExpanded,
                onExpandedChange = { codecExpanded = !codecExpanded }
            ) {
                TextField(
                    value = selectedCodec.label,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.live_video_codec_label)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = codecExpanded)
                    },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                )
                DropdownMenu(
                    expanded = codecExpanded,
                    onDismissRequest = { codecExpanded = false },
                    modifier = Modifier.exposedDropdownSize(true)
                ) {
                    VideoEncoderMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label) },
                            onClick = {
                                viewModel.selectCodec(mode)
                                codecExpanded = false
                            }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Checkbox(
                    checked = recordToFile,
                    onCheckedChange = { viewModel.setRecordToFile(it) }
                )
                Text(text = stringResource(R.string.live_video_record_mp4))
            }
            if (isRecordingToFile) {
                val path = currentRecordPath.orEmpty()
                val fileName = if (path.isNotEmpty()) path.substringAfterLast('/') else "*.mp4"
                Text(
                    text = stringResource(R.string.live_video_recording, fileName),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (cameraInUse) {
                Text(
                    text = stringResource(R.string.live_video_camera_in_use),
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
            if (cameraError) {
                Text(
                    text = stringResource(R.string.live_video_camera_error),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            if (cameraOpened) {
                Text(
                    text = stringResource(R.string.live_video_frame_count, frameCount),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Button(
                onClick = {
                    viewModel.refreshCameraInUse()
                    viewModel.toggleStreaming()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                enabled = !cameraInUse
            ) {
                Text(
                    stringResource(
                        if (isStreaming) {
                            R.string.live_video_stop
                        } else {
                            R.string.live_video_start
                        }
                    )
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun LiveVideoScreenPreview() {
    CXRMSamplesTheme {
        LiveVideoScreen(viewModel = viewModel())
    }
}
