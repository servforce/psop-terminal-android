package com.rokid.cxrmsamples.activities.p2pStabilityTest

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokid.cxrmsamples.ui.theme.CXRMSamplesTheme

/**
 * P2P 拍照回传稳定性测试页。
 * 专测"眼镜硬件键拍照 → P2P 回传到手机落盘"，用于压测稳定性并与官方正式版 App 对比。
 * 干净基线：无 WebSocket / TTS / 后端上传 / 消息流 / 场景切换。
 */
class P2pStabilityTestActivity : ComponentActivity() {
    private val viewModel: P2pStabilityTestViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 13+ 需动态申请 NEARBY_WIFI_DEVICES 权限（Wi-Fi P2P 设备发现必需，与巡检页一致）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.NEARBY_WIFI_DEVICES), 1024)
        }

        enableEdgeToEdge()
        setContent {
            CXRMSamplesTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    P2pStabilityTestScreen(viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
fun P2pStabilityTestScreen(viewModel: P2pStabilityTestViewModel) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "P2P 拍照回传稳定性测试",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "前提：眼镜已通过 BLE 连接（与巡检页相同，本页不做重连）。\n用眼镜硬件拍照键触发每轮测试。",
            fontSize = 12.sp,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 连接常驻模式开关
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "连接常驻模式（官方 demo 式）\n关 = 每轮完整建连（巡检页式）",
                fontSize = 13.sp
            )
            Switch(
                checked = state.persistentMode,
                onCheckedChange = { viewModel.togglePersistentMode() },
                enabled = state.phase == P2pPhase.IDLE
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 当前状态行
        Text(
            text = "状态：${state.statusText}",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E2A38), RoundedCornerShape(6.dp))
                .padding(8.dp),
            color = Color(0xFF8AD4FF)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 统计面板
        Text(
            text = "总轮数 ${state.totalRounds}　成功 ${state.successCount}　失败 ${state.failCount}",
            fontSize = 14.sp
        )
        Text(
            text = "成功耗时：平均 ${state.avgSuccessMs}ms　最快 ${state.fastestMs}ms　最慢 ${state.slowestMs}ms",
            fontSize = 13.sp,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = { viewModel.clearStats() }) {
            Text("清空统计")
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "逐轮记录（最新在上，最多 50 条）",
            fontSize = 13.sp,
            color = Color.Gray
        )

        // 逐轮记录列表
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.records, key = { it.round }) { r ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                ) {
                    Text(
                        text = "#${r.round} ${r.photoTime}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = buildString {
                            r.connectMs?.let { append("建连 ${it}ms ") } ?: append("建连 复用 ")
                            r.syncMs?.let { append("同步 ${it}ms ") }
                            r.totalMs?.let { append("总 ${it}ms ") }
                            if (r.success) append("✅") else append("❌ ${r.reason}")
                        },
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (r.success) Color(0xFF6DD66D) else Color(0xFFFF7A6E)
                    )
                }
            }
        }
    }
}
