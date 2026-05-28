package com.rokid.cxrmsamples.activities.psopDemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.rokid.cxrmsamples.ui.theme.CXRMSamplesTheme

class PsopDemoActivity : ComponentActivity() {
    private val viewModel: PsopDemoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CXRMSamplesTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PsopDemoScreen(viewModel = viewModel)
                }
            }
        }
    }
}
