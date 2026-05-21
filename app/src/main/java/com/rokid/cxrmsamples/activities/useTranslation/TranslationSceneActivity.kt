package com.rokid.cxrmsamples.activities.useTranslation

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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.cxrmsamples.R
import com.rokid.cxrmsamples.ui.theme.CXRMSamplesTheme

class TranslationSceneActivity : ComponentActivity() {
    private val viewModel: TranslationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CXRMSamplesTheme {
                TranslationScreen(viewModel)
            }
        }
    }
}

@Composable
fun TranslationScreen(viewModel: TranslationViewModel) {
    val isReady by viewModel.isReady.collectAsState()
    val textSize by viewModel.textSize.collectAsState()
    val startPointX by viewModel.startPointX.collectAsState()
    val startPointY by viewModel.startPointY.collectAsState()
    val width by viewModel.width.collectAsState()
    val height by viewModel.height.collectAsState()
    var isOpen by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.glasses_bg),
            modifier = Modifier.fillMaxSize(),
            contentDescription = null,
            alpha = 0.3f
        )
        Column(
            modifier = Modifier
                .padding(top = 64.dp)
                .padding(horizontal = 12.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(onClick = {
                isOpen = !isOpen
                viewModel.controlTranslationScene(isOpen)
            }, enabled = isReady) {
                Text(text = if (isOpen) "关闭翻译场景" else "打开翻译场景")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "文字大小：$textSize",
                    modifier = Modifier
                        .weight(0.4f)
                        .padding(end = 8.dp),
                    textAlign = TextAlign.End
                )
                Slider(
                    modifier = Modifier.weight(0.6f),
                    value = textSize.toFloat(),
                    onValueChange = { newValue -> viewModel.setTextSize(newValue.toInt()) },
                    valueRange = 8f..32f,
                    steps = 23
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "起点 X：$startPointX",
                    modifier = Modifier
                        .weight(0.4f)
                        .padding(end = 8.dp),
                    textAlign = TextAlign.End
                )
                Slider(
                    modifier = Modifier.weight(0.6f),
                    value = startPointX.toFloat(),
                    onValueChange = { newValue -> viewModel.setPointX(newValue.toInt()) },
                    valueRange = 0f..480f,
                    steps = 479
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "起点 Y：$startPointY",
                    modifier = Modifier
                        .weight(0.4f)
                        .padding(end = 8.dp),
                    textAlign = TextAlign.End
                )
                Slider(
                    modifier = Modifier.weight(0.6f),
                    value = startPointY.toFloat(),
                    onValueChange = { newValue -> viewModel.setPointY(newValue.toInt()) },
                    valueRange = 0f..640f,
                    steps = 639
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "宽度：$width",
                    modifier = Modifier
                        .weight(0.4f)
                        .padding(end = 8.dp),
                    textAlign = TextAlign.End
                )
                Slider(
                    modifier = Modifier.weight(0.6f),
                    value = width.toFloat(),
                    onValueChange = { newValue -> viewModel.setWidth(newValue.toInt()) },
                    valueRange = startPointX.toFloat()..480f,
                    steps = 480 - startPointX
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "高度：$height",
                    modifier = Modifier
                        .weight(0.4f)
                        .padding(end = 8.dp),
                    textAlign = TextAlign.End
                )
                Slider(
                    modifier = Modifier.weight(0.6f),
                    value = height.toFloat(),
                    onValueChange = { newValue -> viewModel.setHeight(newValue.toInt()) },
                    valueRange = startPointY.toFloat()..640f,
                    steps = 640 - startPointY
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    CXRMSamplesTheme {
        TranslationScreen(viewModel = viewModel { TranslationViewModel() })
    }
}
