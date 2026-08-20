package com.rokid.cxrmsamples.activities.psopDemo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokid.cxrmsamples.network.models.RunResponse

private val PsopBlue = Color(0xFF2E66E9)
private val PsopSoftBlue = Color(0xFFEAF0FF)
private val PsopSoftGreen = Color(0xFFE4F7F0)
private val PsopSoftOrange = Color(0xFFFFF3DF)
private val PsopSoftRed = Color(0xFFFFEBED)
private val PsopOutline = Color(0xFFE3EAF1)
private val PsopPage = Color(0xFFF5F7FA)
private val PsopSecondary = Color(0xFF6B7688)

@Composable
fun PsopHomeScreen(
    uiState: PsopDemoUiState,
    onOpenSkills: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDeviceConnection: () -> Unit,
    onOpenSdkDebug: () -> Unit,
    onResumeRun: (RunResponse) -> Unit
) {
    val activeRun = uiState.homeRuns.firstOrNull()
    Scaffold(
        containerColor = PsopPage,
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(selected = true, onClick = {}, icon = { Icon(Icons.Default.Home, null) }, label = { Text("首页") })
                NavigationBarItem(selected = false, onClick = onOpenSkills, icon = { Icon(Icons.Default.List, null) }, label = { Text("任务") })
                NavigationBarItem(selected = false, onClick = onOpenHistory, icon = { Icon(Icons.Default.History, null) }, label = { Text("历史") })
                NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Default.Person, null) }, label = { Text("我的") })
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("PSOP 智能巡检", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onOpenSdkDebug) { Icon(Icons.Default.Settings, contentDescription = "SDK 调试", tint = PsopSecondary) }
                }
            }
            item {
                Text("上午好", color = PsopSecondary, style = MaterialTheme.typography.titleMedium)
                Text("准备开始今天的巡检", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 4.dp))
            }
            item {
                DeviceSummaryCard(uiState, onOpenDeviceConnection)
            }
            item {
                Button(
                    onClick = { activeRun?.let(onResumeRun) ?: onOpenSkills() },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PsopBlue)
                ) {
                    Icon(if (activeRun == null) Icons.Default.PlayArrow else Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (activeRun == null) "开始巡检" else "继续巡检", fontSize = 22.sp)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HomeShortcut("历史记录", Icons.Default.History, Modifier.weight(1f), onOpenHistory)
                    HomeShortcut("巡检技能", Icons.Default.List, Modifier.weight(1f), onOpenSkills)
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("最近巡检", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    Text("查看全部", color = PsopBlue, modifier = Modifier.clickable(onClick = onOpenHistory))
                }
            }
            if (activeRun != null) {
                item { HomeRunCard(activeRun, uiState, onResumeRun) }
            } else if (!uiState.isLoadingHomeRuns) {
                item {
                    Text("暂无运行中的巡检", color = PsopSecondary, modifier = Modifier.padding(vertical = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun DeviceSummaryCard(uiState: PsopDemoUiState, onClick: () -> Unit) {
    val context = LocalContext.current
    val savedDevice = remember(context) {
        context.getSharedPreferences("record", android.content.Context.MODE_PRIVATE)
            .getString("record_name", null) ?: "已连接眼镜"
    }
    val savedMac = remember(context) {
        context.getSharedPreferences("record", android.content.Context.MODE_PRIVATE)
            .getString("record_mac_address", null).orEmpty()
    }
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = Color.White,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                val isConnected = uiState.isGlassesConnected
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isConnected) PsopSoftGreen else PsopSoftOrange
                ) {
                    Text(
                        if (isConnected) "●  已连接" else "未连接",
                        color = if (isConnected) Color(0xFF10B981) else Color(0xFFB76A00),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.weight(1f))
                uiState.glassBatteryLevel?.takeIf { isConnected }?.let { level ->
                    Text(
                        text = "电量 $level%",
                        color = PsopSecondary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(18.dp), color = PsopSoftBlue, modifier = Modifier.size(88.dp)) {
                    Icon(Icons.Default.Settings, null, tint = PsopBlue, modifier = Modifier.padding(25.dp))
                }
                Spacer(Modifier.width(20.dp))
                Column {
                    Text(savedDevice, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    if (savedMac.isNotBlank()) {
                        Text(savedMac, color = PsopSecondary, modifier = Modifier.padding(top = 6.dp), letterSpacing = 1.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeShortcut(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = Color.White, modifier = modifier.clickable(onClick = onClick)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 22.dp)) {
            Icon(icon, null, tint = PsopBlue, modifier = Modifier.size(32.dp))
            Text(label, modifier = Modifier.padding(top = 12.dp), color = Color(0xFF3B4555))
        }
    }
}

@Composable
private fun HomeRunCard(run: RunResponse, uiState: PsopDemoUiState, onResumeRun: (RunResponse) -> Unit) {
    val progress = uiState.taskStatus?.progress
    Surface(shape = RoundedCornerShape(24.dp), color = Color.White, modifier = Modifier.fillMaxWidth().clickable { onResumeRun(run) }) {
        Column(Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(skillName(run, uiState), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                StatusChip("运行中", "running")
            }
            if (progress != null && progress.total > 0) {
                LinearProgressIndicator(
                    progress = { progress.percent / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp).height(12.dp),
                    color = PsopBlue,
                    trackColor = Color(0xFFE9EDF2)
                )
                Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Text("完成 ${progress.completed} / ${progress.total} 个步骤", color = PsopSecondary, modifier = Modifier.weight(1f))
                    Text("${progress.percent}%", color = PsopBlue, fontWeight = FontWeight.Bold)
                }
            } else {
                Text("点击继续巡检", color = PsopSecondary, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

@Composable
fun PsopHistoryScreen(
    uiState: PsopDemoUiState,
    onStatusChanged: (String) -> Unit,
    onRunClicked: (RunResponse) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val statuses = listOf("running" to "运行中", "succeeded" to "已完成", "aborted" to "已中止", "cancelled" to "已取消")
    Scaffold(containerColor = PsopPage) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 32.dp, vertical = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("历史记录", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onBack) { Icon(Icons.Default.Home, "返回首页", tint = PsopSecondary) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                statuses.forEach { (value, label) ->
                    val selected = value == uiState.runStatusFilter
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (selected) PsopBlue else Color.White,
                        modifier = Modifier.weight(1f).clickable { onStatusChanged(value) }
                    ) {
                        Text(label, color = if (selected) Color.White else Color(0xFF485467), modifier = Modifier.padding(vertical = 14.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
            if (uiState.isLoadingInvocations) {
                Text("正在加载…", color = PsopSecondary, modifier = Modifier.padding(top = 36.dp))
            } else if (uiState.invocations.isEmpty()) {
                Text("暂无${statuses.first { it.first == uiState.runStatusFilter }.second}记录", color = PsopSecondary, modifier = Modifier.padding(top = 36.dp))
            } else {
                LazyColumn(contentPadding = PaddingValues(top = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(uiState.invocations, key = { it.id }) { run -> HistoryRunCard(run, uiState, onRunClicked) }
                }
            }
        }
    }
}

/** 单个技能的运行记录。结构和“历史记录”一致，避免从技能页跳回旧版样式。 */
@Composable
fun PsopSkillRunsScreen(
    skillName: String,
    uiState: PsopDemoUiState,
    onStatusChanged: (String) -> Unit,
    onStartInspection: () -> Unit,
    onRunClicked: (RunResponse) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val statuses = listOf("running" to "运行中", "succeeded" to "已完成", "aborted" to "已中止", "cancelled" to "已取消")
    Scaffold(
        containerColor = PsopPage,
        bottomBar = {
            Surface(color = Color.White) {
                Button(
                    onClick = onStartInspection,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 18.dp).height(60.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PsopBlue)
                ) {
                    Text("开始巡检", fontSize = 20.sp)
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 32.dp, vertical = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text(skillName, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 8.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                statuses.forEach { (value, label) ->
                    val selected = value == uiState.runStatusFilter
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (selected) PsopBlue else Color.White,
                        modifier = Modifier.weight(1f).clickable { onStatusChanged(value) }
                    ) {
                        Text(
                            label,
                            color = if (selected) Color.White else Color(0xFF485467),
                            modifier = Modifier.padding(vertical = 14.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
            when {
                uiState.isLoadingInvocations -> Text("正在加载…", color = PsopSecondary, modifier = Modifier.padding(top = 36.dp))
                uiState.invocations.isEmpty() -> {
                    val label = statuses.first { it.first == uiState.runStatusFilter }.second
                    Text("暂无${label}记录", color = PsopSecondary, modifier = Modifier.padding(top = 36.dp))
                }
                else -> LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(top = 28.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(uiState.invocations, key = { it.id }) { run ->
                        HistoryRunCard(run, uiState, onRunClicked)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRunCard(run: RunResponse, uiState: PsopDemoUiState, onRunClicked: (RunResponse) -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = Color.White, modifier = Modifier.fillMaxWidth().clickable { onRunClicked(run) }) {
        Column(Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(skillName(run, uiState), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                StatusChip(historyStatusLabel(run.status), run.status)
            }
            Text(
                formatRunDate(run.createdAt),
                color = PsopSecondary,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End
            )
            if (run.status == "running" || run.status == "waiting_input") {
                Text("继续", color = PsopBlue, fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.End).padding(top = 12.dp))
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, status: String) {
    val (background, text) = when (status) {
        "succeeded" -> PsopSoftGreen to Color(0xFF10B981)
        "aborted", "cancelled", "failed" -> PsopSoftRed to Color(0xFFEF4444)
        "running", "waiting_input" -> PsopSoftOrange to Color(0xFFF59E0B)
        else -> PsopSoftBlue to PsopBlue
    }
    Surface(shape = RoundedCornerShape(18.dp), color = background) {
        Text(label, color = text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), fontWeight = FontWeight.Medium)
    }
}

private fun skillName(run: RunResponse, state: PsopDemoUiState): String =
    state.skills.find { it.id == run.skillDefinitionId }?.name ?: run.currentStep ?: "巡检任务"

private fun formatRunDate(value: String): String {
    val normalized = value.replace("T", " ").substringBefore(".")
    val date = normalized.substringBefore(" ")
    val time = normalized.substringAfter(" ", "")
    return if (date.length >= 10 && time.length >= 8) {
        "$date ${time.take(8)}"
    } else {
        normalized
    }
}

private fun historyStatusLabel(status: String): String = when (status) {
    "running", "waiting_input" -> "运行中"
    "succeeded" -> "已完成"
    "aborted" -> "已中止"
    "cancelled" -> "已取消"
    "failed" -> "失败"
    else -> status
}
