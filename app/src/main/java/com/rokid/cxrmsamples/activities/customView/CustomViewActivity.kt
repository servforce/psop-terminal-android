package com.rokid.cxrmsamples.activities.customView

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
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxrmsamples.R
import com.rokid.cxrmsamples.ui.theme.CXRMSamplesTheme

class CustomViewActivity : ComponentActivity() {

    private val viewModel: CustomViewViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CXRMSamplesTheme {
                CustomViewScreen(
                    viewModel = viewModel,
                    uploadIcons = { viewModel.uploadIcon(this) }
                )
            }
        }
        viewModel.setCustomSceneListener(true)
    }

    override fun onDestroy() {
        viewModel.setCustomSceneListener(false)
        super.onDestroy()
    }
}

@Composable
fun CustomViewScreen(viewModel: CustomViewViewModel, uploadIcons: () -> Unit) {
    val isCustomViewOpen by viewModel.isCustomViewRunning.collectAsState()
    val iconSent by viewModel.iconSent.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.glasses_bg),
            contentDescription = null,
            alpha = 0.3f,
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.custom_view_title),
                modifier = Modifier.padding(top = 32.dp, bottom = 8.dp)
            )

            if (!iconSent) {
                Button(
                    onClick = { uploadIcons() },
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    Text(stringResource(R.string.custom_view_upload_assets))
                }
            } else {
                Button(
                    onClick = { viewModel.toggleCustomView() },
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    Text(
                        stringResource(
                            if (isCustomViewOpen) {
                                R.string.custom_view_close
                            } else {
                                R.string.custom_view_open
                            }
                        )
                    )
                }
                if (isCustomViewOpen) {
                    Button(
                        onClick = { viewModel.updateCustomView() },
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        Text(stringResource(R.string.custom_view_update))
                    }
                    Row {
                        Button(
                            onClick = {
                                viewModel.controlLottieAnim(CxrApi.LottieAnimControl.PLAY)
                            },
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp)
                        ) {
                            Text(stringResource(R.string.custom_view_anim_play))
                        }
                        Button(
                            onClick = {
                                viewModel.controlLottieAnim(CxrApi.LottieAnimControl.PAUSE)
                            },
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp)
                        ) {
                            Text(stringResource(R.string.custom_view_anim_pause))
                        }
                    }
                    Row {
                        Button(
                            onClick = {
                                viewModel.controlLottieAnim(CxrApi.LottieAnimControl.RESUME)
                            },
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp)
                        ) {
                            Text(stringResource(R.string.custom_view_anim_resume))
                        }
                        Button(
                            onClick = {
                                viewModel.controlLottieAnim(CxrApi.LottieAnimControl.REVERSESPEED)
                            },
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp)
                        ) {
                            Text(stringResource(R.string.custom_view_anim_reverse))
                        }
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
        CustomViewScreen(viewModel = viewModel { CustomViewViewModel() }, uploadIcons = {})
    }
}
