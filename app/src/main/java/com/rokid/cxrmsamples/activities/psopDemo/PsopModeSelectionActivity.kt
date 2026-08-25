package com.rokid.cxrmsamples.activities.psopDemo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.rokid.cxrmsamples.activities.bluetoothConnection.BluetoothInitActivity
import com.rokid.cxrmsamples.ui.theme.PsopTheme

/** App 启动入口：先选择本次 PSOP 的工作方式，再进入对应流程。 */
class PsopModeSelectionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PsopTheme {
                PsopModeSelectionScreen(
                    onGlassesMode = {
                        startActivity(Intent(this, BluetoothInitActivity::class.java).apply {
                            putExtra(PsopDemoActivity.EXTRA_OPERATING_MODE, PsopOperatingMode.GLASSES.name)
                        })
                    },
                    onMobileMode = {
                        startActivity(Intent(this, PsopDemoActivity::class.java).apply {
                            putExtra(PsopDemoActivity.EXTRA_OPERATING_MODE, PsopOperatingMode.MOBILE.name)
                        })
                    }
                )
            }
        }
    }
}
