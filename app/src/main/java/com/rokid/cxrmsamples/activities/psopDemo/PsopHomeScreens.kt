package com.rokid.cxrmsamples.activities.psopDemo

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokid.cxrmsamples.network.models.RunResponse
import java.util.Calendar
import kotlinx.coroutines.flow.distinctUntilChanged

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
    val activeRun = uiState.homeResumeRun
    val recentRun = uiState.homeRecentRun
    val greeting = homeGreeting()
    val actionHint = homeActionHint(activeRun, recentRun)
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
                    Text("PSOP 智能作业", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onOpenSdkDebug) { Icon(Icons.Default.Settings, contentDescription = "SDK 调试", tint = PsopSecondary) }
                }
            }
            item {
                Text(greeting, color = PsopSecondary, style = MaterialTheme.typography.titleMedium)
                Text(actionHint, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 4.dp))
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
            if (recentRun != null) {
                item { HomeRecentRunCard(recentRun, uiState, onResumeRun) }
            } else if (!uiState.isLoadingHomeRuns) {
                item {
                    Text("暂无巡检记录", color = PsopSecondary, modifier = Modifier.padding(vertical = 16.dp))
                }
            }
        }
    }
}

fun homeGreeting(hourOfDay: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): String = when (hourOfDay) {
    in 5..11 -> "上午好"
    in 12..17 -> "下午好"
    else -> "晚上好"
}

fun homeActionHint(activeRun: RunResponse?, recentRun: RunResponse?): String = when {
    activeRun != null -> "你有一项巡检正在进行，继续完成它吧"
    recentRun != null -> "上次巡检已完成，开始新的巡检吧"
    else -> "准备开始今天的巡检吧"
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
fun HomeShortcut(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = Color.White, modifier = modifier.clickable(onClick = onClick)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 22.dp)) {
            Icon(icon, null, tint = PsopBlue, modifier = Modifier.size(32.dp))
            Text(label, modifier = Modifier.padding(top = 12.dp), color = Color(0xFF3B4555))
        }
    }
}

@Composable
fun HomeRecentRunCard(run: RunResponse, uiState: PsopDemoUiState, onResumeRun: (RunResponse) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copyInteraction = remember { MutableInteractionSource() }
    val progress = uiState.historyProgressByRunId[run.id]
    Surface(shape = RoundedCornerShape(24.dp), color = Color.White, modifier = Modifier.fillMaxWidth().clickable { onResumeRun(run) }) {
        Column(Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(skillName(run, uiState), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            Text(
                historyCardTitle(run, progress),
                color = PsopSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
            progress?.let { currentProgress ->
                if (currentProgress.total > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("作业进度", color = PsopSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(
                            "${currentProgress.completed} / ${currentProgress.total} · ${currentProgress.percent}%",
                            color = PsopBlue,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    LinearProgressIndicator(
                        progress = { currentProgress.percent.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 7.dp).height(6.dp),
                        color = PsopBlue,
                        trackColor = PsopSoftBlue
                    )
                }
            }
            Text(
                "${historyTimeLabel(run)} ${formatRunDate(if (isActiveHistoryRun(run)) run.createdAt else run.updatedAt)}",
                color = PsopSecondary,
                modifier = Modifier.padding(top = if ((progress?.total ?: 0) > 0) 12.dp else 18.dp)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 4.dp)
                    .clickable(
                        interactionSource = copyInteraction,
                        indication = null
                    ) {
                        clipboard.setText(AnnotatedString(run.id))
                        Toast.makeText(context, "已复制编号：…${run.id.takeLast(8)}", Toast.LENGTH_SHORT).show()
                    }
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "复制编号", tint = PsopBlue, modifier = Modifier.size(16.dp))
                Text("复制编号", color = PsopBlue, fontSize = 13.sp, modifier = Modifier.padding(start = 5.dp))
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PsopHistoryScreen(
    uiState: PsopDemoUiState,
    onStatusChanged: (String) -> Unit,
    onRefresh: () -> Unit,
    onLoadNextPage: () -> Unit,
    onRunClicked: (RunResponse) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val statuses = listOf("running" to "运行中", "succeeded" to "已完成", "aborted" to "已中止", "cancelled" to "已取消")
    val listState = rememberLazyListState()
    HistoryAutoLoadMore(
        listState = listState,
        canLoadMore = uiState.historyCanLoadMore,
        isLoading = uiState.isLoadingMoreHistory,
        onLoadNextPage = onLoadNextPage
    )
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
            if (uiState.isRefreshingHistory) {
                HistoryLoadingIndicator(color = PsopBlue)
            }
            PullToRefreshBox(
                isRefreshing = uiState.isRefreshingHistory,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxWidth().weight(1f),
                indicator = {}
            ) {
                when {
                    uiState.isLoadingInvocations -> Text("正在加载…", color = PsopSecondary, modifier = Modifier.fillMaxWidth().padding(top = 36.dp))
                    uiState.invocations.isEmpty() -> Text("暂无${statuses.first { it.first == uiState.runStatusFilter }.second}记录", color = PsopSecondary, modifier = Modifier.fillMaxWidth().padding(top = 36.dp))
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(top = 28.dp, bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(uiState.invocations, key = { it.id }) { run -> HistoryRunCard(run, uiState, onRunClicked) }
                        if (uiState.isLoadingMoreHistory) {
                            item { HistoryLoadingIndicator(color = PsopBlue) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun HistoryAutoLoadMore(
    listState: LazyListState,
    canLoadMore: Boolean,
    isLoading: Boolean,
    onLoadNextPage: () -> Unit
) {
    LaunchedEffect(listState, canLoadMore, isLoading) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible to layout.totalItemsCount
        }
            .distinctUntilChanged()
            .collect { (lastVisible, totalItems) ->
                if (canLoadMore && !isLoading && totalItems > 0 && lastVisible >= totalItems - 1) {
                    onLoadNextPage()
                }
            }
    }
}

@Composable
internal fun HistoryLoadingIndicator(color: Color) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = color, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
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
internal fun HistoryRunCard(run: RunResponse, uiState: PsopDemoUiState, onRunClicked: (RunResponse) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copyInteraction = remember { MutableInteractionSource() }
    val progress = uiState.historyProgressByRunId[run.id]
    Surface(shape = RoundedCornerShape(24.dp), color = Color.White, modifier = Modifier.fillMaxWidth().clickable { onRunClicked(run) }) {
        Column(Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(historyCardTitle(run, progress), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            progress?.let { progress ->
                if (progress.total > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("作业进度", color = PsopSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(
                            "${progress.completed} / ${progress.total} · ${progress.percent}%",
                            color = PsopBlue,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (progress.percent.coerceIn(0, 100)) / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 7.dp).height(6.dp),
                        color = PsopBlue,
                        trackColor = PsopSoftBlue
                    )
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = if ((progress?.total ?: 0) > 0) 12.dp else 18.dp)
            ) {
                Text(
                    "${historyTimeLabel(run)} ${formatRunDate(if (isActiveHistoryRun(run)) run.createdAt else run.updatedAt)}",
                    color = PsopSecondary
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 10.dp, bottom = 4.dp)
                        .clickable(
                            interactionSource = copyInteraction,
                            indication = null
                        ) {
                            clipboard.setText(AnnotatedString(run.id))
                            Toast.makeText(
                                context,
                                "已复制编号：…${run.id.takeLast(8)}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "复制编号", tint = PsopBlue, modifier = Modifier.size(16.dp))
                    Text("复制编号", color = PsopBlue, fontSize = 13.sp, modifier = Modifier.padding(start = 5.dp))
                }
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

private fun isActiveHistoryRun(run: RunResponse): Boolean = run.status in setOf(
    "queued", "waiting_runtime", "accepted", "running", "waiting_input", "finalizing"
)

private fun historyCardTitle(run: RunResponse, progress: HistoryRunProgress?): String = when {
    isActiveHistoryRun(run) -> progress?.currentStageTitle ?: "当前作业"
    run.status == "succeeded" -> "本次作业已完成"
    run.status == "cancelled" -> "任务已取消"
    run.status == "aborted" || run.status == "failed" -> "任务已中止"
    else -> "作业记录"
}

private fun historyTimeLabel(run: RunResponse): String =
    if (isActiveHistoryRun(run)) "开始时间" else if (run.status == "succeeded") "完成时间" else "结束时间"

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
