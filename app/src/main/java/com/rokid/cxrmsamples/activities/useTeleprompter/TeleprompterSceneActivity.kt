package com.rokid.cxrmsamples.activities.useTeleprompter

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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.cxrmsamples.R
import com.rokid.cxrmsamples.ui.theme.CXRMSamplesTheme

class TeleprompterSceneActivity : ComponentActivity() {

    private val viewModel: TeleprompterViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CXRMSamplesTheme {
                TeleprompterScreen(
                    viewModel = viewModel,
                    startSend = { viewModel.sendStream(this@TeleprompterSceneActivity) }
                )
            }
        }
    }
}

@Composable
fun TeleprompterScreen(viewModel: TeleprompterViewModel, startSend: () -> Unit) {
    val isReady by viewModel.isReady.collectAsState()
    val isUploading by viewModel.isUploading.collectAsState()
    val textSize by viewModel.textSize.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val lineSpace by viewModel.lineSpace.collectAsState()
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
            if (!isReady) {
                Button(onClick = { startSend() }, enabled = !isUploading) {
                    Text(text = "上传提词器文件")
                }
            } else {
                Button(onClick = {
                    isOpen = !isOpen
                    viewModel.controlSceneTeleprompter(isOpen)
                }) {
                    Text(text = if (isOpen) "关闭提词器" else "打开提词器")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "场景模式：")
                    Checkbox(
                        checked = mode == TeleprompterMode.AI,
                        onCheckedChange = {
                            viewModel.setMode(if (it) TeleprompterMode.AI else TeleprompterMode.NORMAL)
                        }
                    )
                    Text(text = if (mode == TeleprompterMode.AI) "AI" else "普通")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "文字大小：${textSize.toInt()}",
                        modifier = Modifier
                            .weight(0.4f)
                            .padding(end = 8.dp),
                        textAlign = TextAlign.End
                    )
                    Slider(
                        modifier = Modifier.weight(0.6f),
                        value = textSize,
                        onValueChange = { newValue -> viewModel.setTextSize(newValue) },
                        valueRange = 8f..32f,
                        steps = 23
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "行间距：${lineSpace.toInt()}",
                        modifier = Modifier
                            .weight(0.4f)
                            .padding(end = 8.dp),
                        textAlign = TextAlign.End
                    )
                    Slider(
                        modifier = Modifier.weight(0.6f),
                        value = lineSpace,
                        onValueChange = { newValue -> viewModel.setLineSpace(newValue) },
                        valueRange = 0f..12f,
                        steps = 11
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
                        onValueChange = { newValue -> viewModel.setPointX(newValue) },
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
                        onValueChange = { newValue -> viewModel.setPointY(newValue) },
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
                        onValueChange = { newValue -> viewModel.setWidth(newValue) },
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
                        onValueChange = { newValue -> viewModel.setHeight(newValue) },
                        valueRange = startPointY.toFloat()..640f,
                        steps = 640 - startPointY
                    )
                }

                Text(text = "模拟 ASR 结果：")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    var toSend by remember { mutableStateOf("") }
                    TextField(
                        value = toSend,
                        onValueChange = { toSend = it },
                        modifier = Modifier
                            .weight(0.8f)
                            .padding(end = 8.dp),
                        placeholder = { Text("请输入 ASR 结果") }
                    )
                    Button(
                        onClick = { viewModel.sendASRToStartAutoScroll(toSend) },
                        enabled = toSend.isNotEmpty()
                    ) {
                        Text(text = "发送")
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
        TeleprompterScreen(viewModel = viewModel { TeleprompterViewModel() }, startSend = {})
    }
}
