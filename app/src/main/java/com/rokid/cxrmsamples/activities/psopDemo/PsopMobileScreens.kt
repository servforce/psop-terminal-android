package com.rokid.cxrmsamples.activities.psopDemo

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val MobileBlue = Color(0xFF2E66E9)
private val ArGreen = Color(0xFF47F081)
private val ArGreenSoft = Color(0x6647F081)

@Composable
fun PsopModeSelectionScreen(
    onGlassesMode: () -> Unit,
    onMobileMode: () -> Unit
) {
    Surface(color = Color(0xFFF5F7FA), modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 42.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("PSOP 智能巡检", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("选择本次工作方式", color = Color(0xFF6B7688), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(18.dp))
            ModeCard(
                title = "眼镜模式",
                description = "使用已连接眼镜进行巡检、语音提示与眼镜端显示。",
                tag = "现有完整流程",
                onClick = onGlassesMode
            )
            ModeCard(
                title = "手机模式",
                description = "使用手机进行实时视觉引导、AR 标注与任务核验。",
                tag = "独立手机流程",
                onClick = onMobileMode
            )
        }
    }
}

@Composable
private fun ModeCard(title: String, description: String, tag: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFE0E7F0)),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tag, color = MobileBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(description, color = Color(0xFF6B7688), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun PsopMobileHomeScreen(
    uiState: PsopDemoUiState,
    onOpenSkills: () -> Unit,
    onOpenHistory: () -> Unit,
    onResumeRun: (com.rokid.cxrmsamples.network.models.RunResponse) -> Unit,
    onChangeMode: () -> Unit
) {
    val activeRun = uiState.homeResumeRun
    Scaffold(
        containerColor = Color(0xFFF5F7FA),
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(selected = true, onClick = {}, icon = { Text("⌂") }, label = { Text("首页") })
                NavigationBarItem(selected = false, onClick = onOpenSkills, icon = { Text("☑") }, label = { Text("任务") })
                NavigationBarItem(selected = false, onClick = onOpenHistory, icon = { Text("◷") }, label = { Text("历史") })
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("PSOP 智能巡检", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("手机模式 · AI 视觉辅助", color = MobileBlue, fontSize = 13.sp)
                }
                Text("切换模式", color = MobileBlue, modifier = Modifier.clickable(onClick = onChangeMode))
            }
            Text("准备开始今天的任务", style = MaterialTheme.typography.headlineSmall)
            Surface(shape = RoundedCornerShape(24.dp), color = Color.White, border = BorderStroke(1.dp, Color(0xFFE0E7F0))) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(if (activeRun == null) "待开始任务" else "已暂存任务", color = MobileBlue, fontSize = 12.sp)
                    Text("电脑主机安装", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(if (activeRun == null) "共 7 个步骤 · 从第一步开始学习" else "可继续当前任务", color = Color(0xFF6B7688))
                }
            }
            Button(
                onClick = { activeRun?.let(onResumeRun) ?: onOpenSkills() },
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MobileBlue)
            ) {
                Text(if (activeRun == null) "开始任务" else "继续任务", fontSize = 17.sp)
            }
        }
    }
}

private enum class MobileArState { SCANNING, READY_TO_VERIFY, EXCEPTION, COMPLETE }

@Composable
fun MobileArTaskScreen(viewModel: PsopDemoViewModel, uiState: PsopDemoUiState) {
    var showMenu by remember { mutableStateOf(false) }
    var showChat by rememberSaveable { mutableStateOf(false) }
    var arState by rememberSaveable { mutableStateOf(MobileArState.SCANNING) }
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) {
            viewModel.submitInput(text)
            showChat = true
        }
    }

    LaunchedEffect(Unit) {
        delay(1200)
        if (arState == MobileArState.SCANNING) arState = MobileArState.READY_TO_VERIFY
    }

    BackHandler {
        if (showChat) showChat = false else viewModel.navigateBack()
    }

    if (showChat) {
        MobileTaskChatScreen(
            uiState = uiState,
            onBack = { showChat = false },
            onSend = viewModel::submitInput
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(Color(0xFF9DA8B2), Color(0xFF32404D), Color(0xFF18242E))))
    ) {
        MotherboardScene(
            state = arState,
            modifier = Modifier.fillMaxSize()
        )
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(18.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xC9162A44))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(uiState.selectedSkill?.name ?: "电脑主机安装", color = Color.White, fontSize = 12.sp)
                Text(
                    if (arState == MobileArState.COMPLETE) "01 / 01 · 已通过" else "01 / 07 · 实时识别中",
                    color = Color(0xFFD7E4FF),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Box(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xD9162A44))
            ) {
                Icon(Icons.Default.MoreVert, contentDescription = "更多功能", tint = Color.White)
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(text = { Text("AI 对话") }, onClick = { showMenu = false; showChat = true })
                DropdownMenuItem(
                    text = { Text(if (arState == MobileArState.EXCEPTION) "已重新压紧，开始复核" else "完成操作并复核") },
                    onClick = { showMenu = false; arState = MobileArState.COMPLETE }
                )
                DropdownMenuItem(
                    text = { Text("识别异常处理") },
                    onClick = { showMenu = false; arState = MobileArState.EXCEPTION }
                )
                DropdownMenuItem(text = { Text("暂存并退出") }, onClick = { showMenu = false; viewModel.navigateBack() })
            }
        }
        Surface(
            shape = CircleShape,
            color = MobileBlue,
            shadowElevation = 8.dp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 34.dp).size(58.dp)
        ) {
            IconButton(
                onClick = {
                    voiceLauncher.launch(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "请说出你的问题")
                        }
                    )
                }
            ) {
                Icon(Icons.Default.KeyboardVoice, contentDescription = "语音助手", tint = Color.White)
            }
        }
    }
}

@Composable
private fun MotherboardScene(state: MobileArState, modifier: Modifier = Modifier) {
    Box(modifier) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 34.dp)
                .size(width = 132.dp, height = 112.dp)
                .background(Color(0xFF17212A), RoundedCornerShape(3.dp))
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("CPU SOCKET", color = Color(0xFFD5DCE4), fontSize = 12.sp)
        }
        listOf("A1", "A2", "B1", "B2").forEachIndexed { index, name ->
            val highlight = name == "A2" || name == "B2"
            val exception = state == MobileArState.EXCEPTION && name == "B2"
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = (38 + (3 - index) * 39).dp)
                    .size(width = 27.dp, height = 230.dp)
                    .background(Color(0xFF192632), RoundedCornerShape(5.dp))
                    .then(
                        if (highlight) Modifier.border(
                            BorderStroke(2.dp, if (exception) Color(0xFFB7FFCE) else ArGreen),
                            RoundedCornerShape(5.dp)
                        ) else Modifier
                    )
            ) {
                Text(
                    text = when {
                        exception -> "B2\n待处理"
                        state == MobileArState.COMPLETE && highlight -> "$name\n通过"
                        else -> name
                    },
                    color = if (highlight) ArGreen else Color(0xFFD9E0E7),
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                )
            }
        }
        Text(
            text = when (state) {
                MobileArState.SCANNING -> "正在识别目标槽位"
                MobileArState.READY_TO_VERIFY -> "已识别 A2、B2 目标位置"
                MobileArState.EXCEPTION -> "B2 卡扣状态待处理"
                MobileArState.COMPLETE -> "AI 已确认当前步骤"
            },
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(18.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xC9162A44))
                .padding(horizontal = 10.dp, vertical = 7.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileTaskChatScreen(
    uiState: PsopDemoUiState,
    onBack: () -> Unit,
    onSend: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.selectedSkill?.name ?: "PSOP 任务助手") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回 AR")
                    }
                }
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), placeholder = { Text("输入问题") })
                IconButton(onClick = { if (input.isNotBlank()) { onSend(input); input = "" } }) {
                    Icon(Icons.Default.Send, contentDescription = "发送", tint = MobileBlue)
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text("当前处于 AR 任务中。描述问题后，返回 AR 继续实时识别。", color = Color(0xFF60708A), modifier = Modifier.padding(vertical = 12.dp))
            }
            items(uiState.messages, key = { it.id }) { message ->
                MessageBubble(message = message)
            }
        }
    }
}
