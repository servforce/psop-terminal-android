package com.rokid.cxrmsamples.activities.psopDemo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokid.cxrmsamples.R
import com.rokid.cxrmsamples.network.ConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PsopDemoScreen(viewModel: PsopDemoViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var inputText by remember { mutableStateOf("") }
    var showTextInput by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // 自动滚动到底部
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PSOP 设备巡检") },
                actions = {
                    // 连接状态指示器
                    ConnectionIndicator(state = uiState.connectionState)
                    Spacer(modifier = Modifier.width(16.dp))
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 消息列表
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(uiState.messages, key = { it.id }) { message ->
                    MessageBubble(message = message)
                }
            }

            // 错误提示
            if (uiState.error != null) {
                Text(
                    text = uiState.error!!,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    maxLines = 5,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // 底部操作区
            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.padding(16.dp)) {
                    when {
                        !uiState.isRunning && !uiState.isCompleted -> {
                            Button(
                                onClick = { viewModel.startSkill() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("启动巡检")
                            }
                        }
                        uiState.isCompleted -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "✅ 巡检完成",
                                    color = Color(0xFF4CAF50),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(onClick = { viewModel.startSkill() }) {
                                    Text("重新开始")
                                }
                            }
                        }
                        else -> {
                            // 语音优先交互模式
                            Column {
                                if (showTextInput) {
                                    // 文本输入模式（备用）
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedTextField(
                                            value = inputText,
                                            onValueChange = { inputText = it },
                                            modifier = Modifier.weight(1f),
                                            placeholder = { Text("输入文字...") },
                                            singleLine = true
                                        )
                                        IconButton(onClick = {
                                            if (inputText.isNotBlank()) {
                                                viewModel.submitInput(inputText.trim())
                                                inputText = ""
                                                showTextInput = false
                                            }
                                        }) {
                                            Icon(
                                                imageVector = Icons.Default.Send,
                                                contentDescription = "发送",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        IconButton(onClick = { showTextInput = false }) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "关闭",
                                                tint = Color.Gray
                                            )
                                        }
                                    }
                                } else {
                                    // 语音模式默认显示：状态指示 + 操作按钮
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 0.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // 状态指示文字（占据主要空间）
                                        Text(
                                            text = when (uiState.interactionMode) {
                                                InteractionMode.IDLE -> "点击启动巡检"
                                                InteractionMode.LISTENING -> {
                                                    if (uiState.asrText.isNotBlank()) "\uD83C\uDF99\uFE0F ${uiState.asrText}"
                                                    else "\uD83C\uDF99\uFE0F 长按触摸板说话..."
                                                }
                                                InteractionMode.PROCESSING -> "⏳ 执行中..."
                                                InteractionMode.PHOTO_CAPTURE -> "\uD83D\uDCF7 拍照中..."
                                                InteractionMode.VIDEO_RECORDING -> "\uD83D\uDD34 录像中..."
                                                InteractionMode.COMPLETED -> "✅ 巡检完成"
                                            },
                                            modifier = Modifier.weight(1f),
                                            fontSize = 14.sp,
                                            color = when (uiState.interactionMode) {
                                                InteractionMode.LISTENING -> Color(0xFF4CAF50)
                                                InteractionMode.PROCESSING -> Color(0xFFFF9800)
                                                InteractionMode.VIDEO_RECORDING -> Color.Red
                                                else -> Color.Gray
                                            }
                                        )

                                        // 拍照按钮
                                        IconButton(
                                            onClick = { viewModel.takePictureWithGlass() }
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_photo_camera),
                                                contentDescription = "拍照",
                                                tint = Color(0xFF2196F3)
                                            )
                                        }

                                        // 键盘按钮（备用文本输入）
                                        IconButton(onClick = { showTextInput = true }) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = "文字输入",
                                                tint = Color.Gray
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectionIndicator(state: ConnectionState) {
    val color = when (state) {
        ConnectionState.CONNECTED -> Color(0xFF4CAF50)
        ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> Color(0xFFFFC107)
        ConnectionState.DISCONNECTED -> Color(0xFFF44336)
    }
    val label = when (state) {
        ConnectionState.CONNECTED -> "已连接"
        ConnectionState.CONNECTING -> "连接中"
        ConnectionState.RECONNECTING -> "重连中"
        ConnectionState.DISCONNECTED -> "未连接"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun MessageBubble(message: TerminalMessage) {
    val isOutput = message.direction == "output"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutput) Arrangement.Start else Arrangement.End
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (isOutput) Color(0xFFF0F0F0) else Color(0xFF2196F3)
                )
                .padding(12.dp)
        ) {
            Text(
                text = message.content,
                color = if (isOutput) Color.Black else Color.White
            )
        }
    }
}
