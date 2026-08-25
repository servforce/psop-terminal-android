package com.rokid.cxrmsamples.activities.psopDemo

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.rokid.cxrmsamples.ui.theme.PsopTheme
import com.rokid.cxrmsamples.activities.bluetoothConnection.BluetoothInitActivity
import com.rokid.cxrmsamples.activities.usageSelection.UsageSelectionActivity

class PsopDemoActivity : ComponentActivity() {
    private val viewModel: PsopDemoViewModel by viewModels()

    companion object {
        const val EXTRA_OPERATING_MODE = "psop_operating_mode"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requestedMode = intent.getStringExtra(EXTRA_OPERATING_MODE)
            ?.let { runCatching { PsopOperatingMode.valueOf(it) }.getOrNull() }
        requestedMode?.let(viewModel::selectOperatingMode)

        if (requestedMode != PsopOperatingMode.MOBILE) {
            // Android 13+ 需动态申请 NEARBY_WIFI_DEVICES 权限（Wi-Fi P2P 设备发现必需）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(android.Manifest.permission.NEARBY_WIFI_DEVICES), 1024)
            }
            // 初始化离线 ASR 引擎（首次会从 assets 解压模型，约 2-3 秒）
            viewModel.initAsrEngine()
        }

        setContent {
            // 使用 PSOP 专用固定浅色主题，不跟随系统深色模式与壁纸动态取色
            PsopTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PsopDemoScreen(
                        viewModel = viewModel,
                        onOpenDeviceConnection = {
                            startActivity(Intent(this@PsopDemoActivity, BluetoothInitActivity::class.java))
                        },
                        onOpenSdkDebug = {
                            startActivity(Intent(this@PsopDemoActivity, UsageSelectionActivity::class.java))
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (viewModel.uiState.value.operatingMode == PsopOperatingMode.GLASSES) {
            viewModel.refreshGlassConnection()
        }
    }
}
