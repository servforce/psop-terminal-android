package com.rokid.cxrmsamples.activities.arRecording

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.cxr.client.utils.ValueUtil
import com.rokid.cxrmsamples.R
import com.rokid.cxrmsamples.ui.theme.CXRMSamplesTheme

class ARRecordingActivity : ComponentActivity() {

    private val syncViewModel: ARRecordSyncViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CXRMSamplesTheme {
                ARRecordingScreen(
                    syncViewModel = syncViewModel,
                    onStartSync = { syncViewModel.startSync(applicationContext) }
                )
            }
        }
        requestWifiP2pPermissionIfNeeded()
    }

    /** P2P 发现/建连需要 NEARBY_WIFI_DEVICES（Android 13+），与 mediaFile 页同款 */
    private fun requestWifiP2pPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES), 2048)
            }
        }
    }
}

@Composable
fun ARRecordingScreen(
    syncViewModel: ARRecordSyncViewModel,
    onStartSync: () -> Unit = {}
) {
    var isRecording by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("未开始") }
    var statusColor by remember { mutableStateOf(Color(0xFF999999)) }

    // 同步录屏视频状态
    val syncRunning by syncViewModel.running.collectAsState()
    val syncStatusText by syncViewModel.statusText.collectAsState()
    val syncStatusLevel by syncViewModel.statusLevel.collectAsState()
    val syncedFiles by syncViewModel.syncedFiles.collectAsState()
    val syncStatusColor = when (syncStatusLevel) {
        1 -> Color(0xFF4CAF50)
        2 -> Color(0xFFFF9800)
        3 -> Color(0xFFE53935)
        else -> Color(0xFF999999)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.glasses_bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            alpha = 0.3f
        )

        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .align(Alignment.Center)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "AR 录屏",
                fontSize = 22.sp,
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "录制眼镜端合成画面（摄像头 + AR 叠加内容）",
                fontSize = 13.sp,
                color = Color(0xFF666666),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // 状态指示
            Text(
                text = if (isRecording) "● 正在录屏..." else "○ $statusText",
                fontSize = 16.sp,
                color = if (isRecording) Color(0xFFE53935) else statusColor,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // 开始/停止按钮
            Button(
                onClick = {
                    if (isRecording) {
                        val result = ARRecordingUtils.stopRecording()
                        when (result) {
                            ValueUtil.CxrStatus.REQUEST_SUCCEED -> {
                                isRecording = false
                                statusText = "已停止"
                                statusColor = Color(0xFF4CAF50)
                            }
                            ValueUtil.CxrStatus.REQUEST_WAITING -> {
                                statusText = "设备未就绪，停止失败"
                                statusColor = Color(0xFFFF9800)
                            }
                            else -> {
                                statusText = "停止失败"
                                statusColor = Color(0xFFE53935)
                            }
                        }
                    } else {
                        val result = ARRecordingUtils.startRecording()
                        when (result) {
                            ValueUtil.CxrStatus.REQUEST_SUCCEED -> {
                                isRecording = true
                                statusText = "正在录屏"
                                statusColor = Color(0xFF4CAF50)
                            }
                            ValueUtil.CxrStatus.REQUEST_WAITING -> {
                                statusText = "设备未就绪，请稍后重试"
                                statusColor = Color(0xFFFF9800)
                            }
                            else -> {
                                statusText = "启动失败，请检查眼镜连接"
                                statusColor = Color(0xFFE53935)
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) Color(0xFFE53935) else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = if (isRecording) "停止录屏" else "开始录屏",
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 同步录屏视频按钮（真机验证：眼镜端 /sdcard/ScreenRecorder/ 录屏文件能否取回）
            Button(
                onClick = onStartSync,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = !syncRunning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1976D2)
                )
            ) {
                Text(
                    text = if (syncRunning) "同步中..." else "同步录屏视频",
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 同步状态区
            Text(
                text = "同步状态：$syncStatusText",
                fontSize = 13.sp,
                color = syncStatusColor,
                textAlign = TextAlign.Center
            )

            if (syncedFiles.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "已同步文件：\n" + syncedFiles.joinToString("\n") { "• $it" },
                    fontSize = 12.sp,
                    color = Color(0xFFBBBBBB),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 提示信息
            Text(
                text = "提示：录制文件保存在眼镜端 /sdcard/ScreenRecorder/ 目录，\n同步后在手机 /sdcard/Download/Rokid/ScreenRecorderSync/ 查看。",
                fontSize = 12.sp,
                color = Color(0xFF999999),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ARRecordingScreenPreview() {
    CXRMSamplesTheme {
        ARRecordingScreen(syncViewModel = viewModel { ARRecordSyncViewModel() })
    }
}
