package com.rokid.cxrmsamples.activities.psopDemo

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Videocam
import com.rokid.cxrmsamples.activities.arRecording.ARRecordingUtils
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.rokid.cxrmsamples.R
import com.rokid.cxrmsamples.network.ConnectionState
import com.rokid.cxrmsamples.network.models.RunResponse
import com.rokid.cxrmsamples.network.models.SkillSummaryResponse
import com.rokid.cxrmsamples.network.models.TaskStatusResponse
import com.mikepenz.markdown.m3.Markdown

/**
 * 视频缩略图全局缓存（LruCache），key 为视频 URL。
 * 最多缓存 20 张缩略图，避免 LazyColumn 滑动时反复用 MediaMetadataRetriever 提取帧。
 */
private val thumbnailCache = object : android.util.LruCache<String, android.graphics.Bitmap>(20) {}

/** PSOP 页面语义色：统一状态与提示色，避免同义颜色多版本硬编码 */
private val PsopSuccess = Color(0xFF4CAF50)      // 成功 / 已连接 / 运行中
private val PsopWarning = Color(0xFFFF9800)      // 警告 / 进行中 / 等待输入
private val PsopError = Color(0xFFE53935)        // 错误 / 失败 / 断开
private val PsopInfo = Color(0xFF1976D2)         // 信息 / 主操作蓝
private val PsopTextSecondary = Color(0xFF666666) // 次级正文
private val PsopTextHint = Color(0xFF999999)      // 辅助提示

/**
 * AI 助手徽标：蓝→靛渐变圆形 + 白色 "AI" 字样，
 * 替代机器人图标，作为消息头像与技能列表标识。
 */
@Composable
private fun AiAvatar(modifier: Modifier = Modifier, textSize: androidx.compose.ui.unit.TextUnit = 13.sp) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF42A5F5), Color(0xFF5C6BC0))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "AI",
            color = Color.White,
            fontSize = textSize,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
    }
}

/** 巡检对话专用标识：按已确认稿使用蓝色叠层图标，不沿用旧版 AI 圆形头像。 */
@Composable
private fun AssistantMessageMark(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Default.Layers,
        contentDescription = "巡检助手",
        tint = Color(0xFF2E66E9),
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PsopDemoScreen(
    viewModel: PsopDemoViewModel,
    onOpenDeviceConnection: () -> Unit,
    onOpenSdkDebug: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    android.util.Log.d("PSOP_DEBUG", "PsopDemoScreen: currentScreen=${uiState.currentScreen}")
    when (uiState.currentScreen) {
        InspectionScreen.MODE_SELECTION -> PsopModeSelectionScreen(
            onGlassesMode = { viewModel.selectOperatingMode(PsopOperatingMode.GLASSES) },
            onMobileMode = { viewModel.selectOperatingMode(PsopOperatingMode.MOBILE) }
        )
        InspectionScreen.HOME -> {
            if (uiState.operatingMode == PsopOperatingMode.MOBILE) {
                PsopMobileHomeScreen(
                    uiState = uiState,
                    onOpenSkills = { viewModel.openSkillList() },
                    onOpenHistory = { viewModel.openHistory() },
                    onResumeRun = { viewModel.resumeInvocation(it) },
                    onChangeMode = { viewModel.openModeSelection() }
                )
            } else {
                PsopHomeScreen(
                    uiState = uiState,
                    onOpenSkills = { viewModel.openSkillList() },
                    onOpenHistory = { viewModel.openHistory() },
                    onOpenDeviceConnection = onOpenDeviceConnection,
                    onOpenSdkDebug = onOpenSdkDebug,
                    onResumeRun = { viewModel.resumeInvocation(it) }
                )
            }
        }
        InspectionScreen.SKILL_LIST -> {
            if (uiState.operatingMode == PsopOperatingMode.MOBILE) {
                MobileSkillListScreen(
                    skills = uiState.skills,
                    isLoading = uiState.isLoadingSkills,
                    error = uiState.error,
                    onSkillSelected = viewModel::selectSkill,
                    onRetry = viewModel::loadSkills,
                    onBack = viewModel::navigateBack
                )
            } else {
                SkillListScreen(
                    skills = uiState.skills,
                    isLoading = uiState.isLoadingSkills,
                    error = uiState.error,
                    onSkillSelected = { viewModel.selectSkill(it) },
                    onRetry = { viewModel.loadSkills() },
                    onBack = { viewModel.navigateBack() }
                )
            }
        }
        InspectionScreen.MOBILE_SKILL_DETAIL -> MobileSkillDetailScreen(
            skill = uiState.selectedSkill,
            activeRun = uiState.invocations.firstOrNull {
                it.skillDefinitionId == uiState.selectedSkill?.id && it.status in MobileActiveRunStatuses
            },
            isLoading = uiState.isLoadingInvocations,
            onStartNew = viewModel::startSkill,
            onResume = viewModel::resumeInvocation,
            onBack = viewModel::navigateBack
        )
        InspectionScreen.INVOCATION_LIST -> PsopSkillRunsScreen(
            skillName = uiState.selectedSkill?.name ?: "巡检技能",
            uiState = uiState,
            onStatusChanged = { newStatus ->
                uiState.selectedSkill?.id?.let { skillId ->
                    viewModel.loadRuns(skillId, newStatus)
                }
            },
            onStartInspection = { viewModel.startSkill() },
            onRunClicked = { viewModel.resumeInvocation(it) },
            onBack = { viewModel.navigateBack() }
        )
        InspectionScreen.HISTORY -> PsopHistoryScreen(
            uiState = uiState,
            onStatusChanged = viewModel::openHistory,
            onRefresh = viewModel::refreshHistory,
            onLoadNextPage = viewModel::loadNextHistoryPage,
            onRunClicked = { viewModel.resumeInvocation(it) },
            onBack = { viewModel.navigateBack() }
        )
        InspectionScreen.INTERACTION -> {
            if (uiState.operatingMode == PsopOperatingMode.MOBILE) {
                if (isMobileTaskActive(uiState)) {
                    MobileArTaskScreen(viewModel = viewModel, uiState = uiState)
                } else {
                    MobileTaskChatScreen(
                        uiState = uiState,
                        onBack = viewModel::navigateBack,
                        onSend = viewModel::submitInput
                    )
                }
            } else {
                InteractionScreen(viewModel = viewModel, uiState = uiState)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillListScreen(
    skills: List<SkillSummaryResponse>,
    isLoading: Boolean,
    error: String?,
    onSkillSelected: (SkillSummaryResponse) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    var searchQuery by remember { mutableStateOf("") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选择巡检技能") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { padding ->
        android.util.Log.d("PSOP_DEBUG", "SkillListScreen: isLoading=$isLoading, error=$error, skills.size=${skills.size}")
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (error != null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = null,
                        tint = PsopError,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(error, color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onRetry) { Text("重试") }
                }
            }
        } else if (skills.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Inbox,
                        contentDescription = null,
                        tint = PsopTextHint,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("暂无可用技能", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onRetry) { Text("刷新") }
                }
            }
        } else {
            val filteredSkills = skills.filter { it.name.contains(searchQuery, ignoreCase = true) }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    placeholder = { Text("搜索巡检技能") },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp)
                )
                if (filteredSkills.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Inbox, null, tint = PsopTextHint, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("未找到“$searchQuery”相关技能", style = MaterialTheme.typography.titleMedium)
                        Text("换个关键词试试", color = PsopTextHint, modifier = Modifier.padding(top = 6.dp))
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(filteredSkills, key = { it.id }) { skill ->
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.fillMaxWidth().clickable { onSkillSelected(skill) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFFF0F5FF), modifier = Modifier.size(56.dp)) {
                                        AiAvatar(modifier = Modifier.padding(10.dp), textSize = 12.sp)
                                    }
                                    Spacer(Modifier.width(16.dp))
                                    Text(skill.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                                    Icon(Icons.Default.ChevronRight, contentDescription = "选择", tint = PsopTextHint)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val RUN_STATUS_OPTIONS = listOf(
    "running" to "运行中",
    "succeeded" to "已完成",
    "aborted" to "已中止",
    "cancelled" to "已取消"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvocationListScreen(
    skillName: String,
    runs: List<RunResponse>,
    isLoading: Boolean,
    currentStatusFilter: String,
    onStatusFilterChanged: (String) -> Unit,
    onStartInspection: () -> Unit,
    onRunClicked: (RunResponse) -> Unit,
    onBack: () -> Unit
) {
    BackHandler {
        onBack()
    }
    var showStatusDropdown by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(skillName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 状态筛选按钮
                    Box {
                        TextButton(onClick = { showStatusDropdown = true }) {
                            Text(
                                text = translateStatus(currentStatusFilter),
                                color = PsopInfo
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "展开筛选",
                                tint = PsopInfo
                            )
                        }
                        DropdownMenu(
                            expanded = showStatusDropdown,
                            onDismissRequest = { showStatusDropdown = false }
                        ) {
                            RUN_STATUS_OPTIONS.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            label,
                                            color = if (value == currentStatusFilter) PsopInfo else Color.Unspecified
                                        )
                                    },
                                    onClick = {
                                        showStatusDropdown = false
                                        if (value != currentStatusFilter) {
                                            onStatusFilterChanged(value)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Button(
                    onClick = onStartInspection,
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) {
                    Text("启动巡检")
                }
            }
        }
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (runs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Inbox,
                        contentDescription = null,
                        tint = PsopTextHint,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("暂无${translateStatus(currentStatusFilter)}的记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "可切换右上角状态筛选",
                        style = MaterialTheme.typography.bodySmall,
                        color = PsopTextHint
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(runs) { run ->
                    ListItem(
                        headlineContent = {
                            Text(run.id.take(12), fontWeight = FontWeight.Medium)
                        },
                        supportingContent = {
                            // 右上角已显示当前筛选状态，列表项只保留时间
                            Text(
                                text = formatDateTime(run.createdAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier.clickable { onRunClicked(run) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InteractionScreen(viewModel: PsopDemoViewModel, uiState: PsopDemoUiState) {
    BackHandler {
        viewModel.navigateBack()
    }

    var inputText by remember { mutableStateOf("") }
    var showTextInput by remember { mutableStateOf(false) }
    var fullScreenImageUrl by remember { mutableStateOf<String?>(null) }
    var fullScreenVideoUrl by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    // 自动滚动到底部
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    val context = LocalContext.current

    // 相册图片多选器（最多 4 张，合并为一条消息发送）
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 4)
    ) { uris ->
        if (uris.isNotEmpty()) {
            val files = uris.mapNotNull { uri ->
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    if (inputStream != null) {
                        val cacheDir = java.io.File(context.cacheDir, "psop_photos").apply { if (!exists()) mkdirs() }
                        val file = java.io.File.createTempFile("gallery_", ".jpg", cacheDir)
                        file.outputStream().use { out -> inputStream.copyTo(out) }
                        inputStream.close()
                        file
                    } else null
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            if (files.isNotEmpty()) {
                viewModel.uploadFiles(files)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(uiState.selectedSkill?.name ?: "PSOP 设备巡检")
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.navigateBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (uiState.isRunning) {
                        Text(
                            text = "运行中 · ${if (uiState.isGlassesConnected) "已连接" else "未连接"}",
                            color = if (uiState.isGlassesConnected) PsopSuccess else PsopTextSecondary,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(end = 16.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 任务进度面板（可折叠）
            uiState.taskStatus?.let { taskStatus ->
                TaskProgressPanel(taskStatus = taskStatus)
            }

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
                    MessageBubble(
                        message = message,
                        onImageClick = { url -> fullScreenImageUrl = url },
                        onVideoClick = { url -> fullScreenVideoUrl = url }
                    )
                }
                // AI 正在思考提示（PROCESSING 状态时显示）
                if (uiState.interactionMode == InteractionMode.PROCESSING) {
                    item(key = "thinking") {
                        ThinkingIndicator()
                    }
                }
            }

            // 错误提示（5 秒后自动消失）
            if (uiState.error != null) {
                LaunchedEffect(uiState.error) {
                    delay(5000)
                    viewModel.clearError()
                }
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
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                            ) {
                                Text("启动巡检", fontWeight = FontWeight.Medium)
                            }
                        }
                        uiState.isCompleted -> {
                            Text(
                                "✅ 巡检完成",
                                color = PsopSuccess,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        else -> {
                            // 语音优先交互模式
                            Column {
                                // 眼镜端长文本轮播控制区（多段轮播进行中显示，结束后自动隐藏）
                                if (uiState.carouselTotalCount > 1) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        // 上一段（首段时禁用）
                                        IconButton(
                                            onClick = { viewModel.showPrevTextSegment() },
                                            enabled = uiState.carouselIndex > 0
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ChevronLeft,
                                                contentDescription = "上一段",
                                                tint = if (uiState.carouselIndex > 0) MaterialTheme.colorScheme.primary else Color.Gray
                                            )
                                        }

                                        // 页码指示（如 2/5）
                                        Text(
                                            text = "眼镜显示 ${uiState.carouselIndex + 1}/${uiState.carouselTotalCount}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 8.dp)
                                        )

                                        // 下一段（末段时禁用）
                                        IconButton(
                                            onClick = { viewModel.showNextTextSegment() },
                                            enabled = uiState.carouselIndex < uiState.carouselTotalCount - 1
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ChevronRight,
                                                contentDescription = "下一段",
                                                tint = if (uiState.carouselIndex < uiState.carouselTotalCount - 1) MaterialTheme.colorScheme.primary else Color.Gray
                                            )
                                        }

                                        // 暂停/继续轮播
                                        IconButton(
                                            onClick = {
                                                if (uiState.isCarouselPaused) viewModel.resumeTextCarousel()
                                                else viewModel.pauseTextCarousel()
                                            }
                                        ) {
                                            Icon(
                                                imageVector = if (uiState.isCarouselPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                                contentDescription = if (uiState.isCarouselPaused) "继续轮播" else "暂停轮播",
                                                tint = if (uiState.isCarouselPaused) PsopSuccess else PsopWarning
                                            )
                                        }
                                    }
                                }

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
                                    // 已确认稿中的固定输入条：语音由眼镜触摸板触发，手机端仅展示入口与补充输入。
                                    Surface(
                                        shape = RoundedCornerShape(30.dp),
                                        color = Color(0xFFF3F5F8),
                                        modifier = Modifier.fillMaxWidth().height(64.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = when (uiState.interactionMode) {
                                                    InteractionMode.LISTENING -> uiState.asrText.ifBlank { "正在聆听…" }
                                                    InteractionMode.PROCESSING -> "正在处理…"
                                                    InteractionMode.PHOTO_CAPTURE -> "正在接收眼镜照片…"
                                                    else -> "长按触摸板说话，或输入文字…"
                                                },
                                                modifier = Modifier.weight(1f).padding(start = 12.dp),
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = PsopTextHint,
                                                maxLines = 1
                                            )
                                            if (uiState.isTtsPlaying) {
                                                IconButton(onClick = { viewModel.stopTtsPlayback() }) {
                                                    Icon(Icons.Default.Close, "停止播报", tint = PsopTextSecondary)
                                                }
                                            }
                                            IconButton(onClick = {
                                                galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                            }) {
                                                Icon(Icons.Default.Image, "相册选图", tint = PsopTextSecondary)
                                            }
                                            IconButton(onClick = { showTextInput = true }) {
                                                Icon(Icons.Default.Edit, "文字输入", tint = PsopTextSecondary)
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

    // 全屏图片查看器
    fullScreenImageUrl?.let { url ->
        FullScreenImageViewer(imageUrl = url, onDismiss = { fullScreenImageUrl = null })
    }
    // 全屏视频播放器
    fullScreenVideoUrl?.let { url ->
        FullScreenVideoPlayer(videoUrl = url, onDismiss = { fullScreenVideoUrl = null })
    }
}

@Composable
fun ConnectionIndicator(state: ConnectionState) {
    val color = when (state) {
        ConnectionState.CONNECTED -> PsopSuccess
        ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> PsopWarning
        ConnectionState.DISCONNECTED -> PsopError
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

/**
 * 根据 eventKind 判断消息的媒体类型，返回对应的图标和标签。
 * 如果是纯文本类型则返回 null。
 */
private fun resolveRichMediaInfo(eventKind: String): Pair<ImageVector, String>? {
    return when {
        eventKind.startsWith("terminal.image.") -> Icons.Default.Image to "[图片]"
        eventKind.startsWith("terminal.audio.") -> Icons.Default.MusicNote to "[音频]"
        eventKind.startsWith("terminal.video.") -> Icons.Default.Videocam to "[视频]"
        eventKind.startsWith("terminal.file.")  -> Icons.Default.InsertDriveFile to "[文件]"
        else -> null // 文本类型，走默认展示
    }
}

@Composable
fun VideoThumbnail(videoUrl: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // 使用静态 LruCache 缓存视频缩略图，避免 LazyColumn 滑动时重复提取帧
    val cache = remember { thumbnailCache }
    var thumbnailBitmap by remember(videoUrl) { mutableStateOf(cache.get(videoUrl)) }

    LaunchedEffect(videoUrl) {
        // 缓存命中则直接使用，无需重复提取
        val cached = cache.get(videoUrl)
        if (cached != null) {
            thumbnailBitmap = cached
            return@LaunchedEffect
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val retriever = android.media.MediaMetadataRetriever()
                val uri = android.net.Uri.parse(videoUrl)
                if (uri.scheme == "file") {
                    retriever.setDataSource(uri.path)
                } else {
                    retriever.setDataSource(videoUrl, HashMap())
                }
                val frame = retriever.getFrameAtTime(
                    1_000_000,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
                frame?.let { cache.put(videoUrl, it) }
                thumbnailBitmap = frame
                retriever.release()
            } catch (_: Exception) { /* fallback to placeholder */ }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.DarkGray)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        thumbnailBitmap?.let { bmp ->
            androidx.compose.foundation.Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "视频缩略图",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            // 半透明遮罩，让播放按钮更清晰
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.25f))
            )
        }
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = "播放视频",
            tint = Color.White,
            modifier = Modifier.size(48.dp)
        )
    }
}

@Composable
fun FullScreenImageViewer(imageUrl: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { visible = true }

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(200))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { onDismiss() }
            ) {
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }

            AsyncImage(
                model = imageUrl,
                contentDescription = "全屏图片",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            if (scale > 1f) {
                                offset = Offset(offset.x + pan.x, offset.y + pan.y)
                            } else {
                                offset = Offset.Zero
                            }
                        }
                    },
                contentScale = ContentScale.Fit
            )

            // 右上角关闭按钮
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "关闭",
                    tint = Color.White
                )
            }
        }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun FullScreenVideoPlayer(videoUrl: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUrl))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    Dialog(
        onDismissRequest = {
            exoPlayer.release()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // 右上角关闭按钮
            IconButton(
                onClick = {
                    exoPlayer.release()
                    onDismiss()
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "关闭",
                    tint = Color.White
                )
            }
        }
    }
}

/**
 * AI 正在思考指示器：左侧机器人头像 + 跳动三点动画气泡
 */
@Composable
fun ThinkingIndicator() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        AssistantMessageMark(modifier = Modifier.size(32.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = "正在思考...",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun MessageBubble(
    message: TerminalMessage,
    onImageClick: (String) -> Unit = {},
    onVideoClick: (String) -> Unit = {},
    enableOutputTextSelection: Boolean = false
) {
    val isOutput = message.direction == "output"
    val textColor = if (isOutput) Color.Black else Color.White
    val richMedia = resolveRichMediaInfo(message.eventKind)
    // 去掉媒体占位符和 JSON 元数据，只保留实际文字
    val rawContent = if (message.parts.isNotEmpty() && message.content.startsWith("{")) {
        ""
    } else message.content
    val displayContent = rawContent.replace("[图片]", "").replace("[音频]", "").replace("[视频]", "").replace("[文件]", "").trim()

    // DEBUG: 查看消息实际内容
    android.util.Log.d("PSOP_DEBUG", "MessageBubble: contentLen=${message.content.length} content=|${message.content}| displayLen=${displayContent.length} parts=${message.parts.size} eventKind=${message.eventKind}")

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isOutput) Alignment.Start else Alignment.End
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            // 气泡最大宽度按可用宽度 78% 计算（扣除头像占位），适配不同屏幕尺寸
            val bubbleMaxWidth = (maxWidth - 40.dp) * 0.78f
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isOutput) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.Top
            ) {
            // 助手消息：左侧使用确认稿中的蓝色叠层标识
            if (isOutput) {
                AssistantMessageMark(modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.width(8.dp))
            }
            // 纯图片消息（有 parts 但无文字）：不加蓝色背景和 padding
            val isPureMedia = displayContent.isEmpty() && message.parts.isNotEmpty()
            // 对话式不对称圆角：AI 气泡左上角小圆角，用户气泡右上角小圆角
            val bubbleShape = if (isOutput) {
                RoundedCornerShape(topStart = 4.dp, topEnd = 12.dp, bottomStart = 12.dp, bottomEnd = 12.dp)
            } else {
                RoundedCornerShape(topStart = 12.dp, topEnd = 4.dp, bottomStart = 12.dp, bottomEnd = 12.dp)
            }
            Box(
                modifier = Modifier
                    .widthIn(max = bubbleMaxWidth)
                    .clip(bubbleShape)
                    .then(
                        if (isPureMedia) {
                            Modifier  // 无背景、无 padding
                        } else {
                            Modifier.background(
                                if (isOutput) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary
                            ).padding(12.dp)
                        }
                    )
            ) {
                Column {
                    // 上传状态指示器
                    if (message.uploadStatus == "uploading") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = if (isOutput) Color(0xFF757575) else Color.White
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "发送中...",
                                color = if (isOutput) PsopTextSecondary else Color(0xBBFFFFFF),
                                fontSize = 12.sp
                            )
                        }
                    } else if (message.uploadStatus == "failed") {
                        // 红色感叹号（类似微信发送失败）
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = "发送失败",
                            tint = PsopError,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    if (displayContent.isNotEmpty()) {
                        if (isOutput) {
                            if (enableOutputTextSelection) {
                                SelectionContainer {
                                    Markdown(content = displayContent)
                                }
                            } else {
                                Markdown(content = displayContent)
                            }
                        } else {
                            SelectionContainer {
                                Text(
                                    text = displayContent,
                                    color = textColor
                                )
                            }
                        }
                    } else if (richMedia != null && message.parts.isNotEmpty()) {
                        // 纯媒体消息（无文字）：显示图标 + 标签
                        val (icon, label) = richMedia
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = icon,
                                contentDescription = label,
                                tint = if (isOutput) PsopTextSecondary else Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = label,
                                color = textColor,
                                fontSize = 14.sp
                            )
                        }
                    }
                    // 渲染媒体 parts
                    if (message.parts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        message.parts.forEach { part ->
                            when (part.kind) {
                                "video" -> {
                                    VideoThumbnail(
                                        videoUrl = part.contentUrl,
                                        onClick = { onVideoClick(part.contentUrl) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                else -> {
                                    AsyncImage(
                                        model = part.contentUrl,
                                        contentDescription = "图片",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 200.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { onImageClick(part.contentUrl) },
                                        contentScale = ContentScale.FillWidth
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }
            }
        }
        // 时间戳（output 消息需偏移 40dp = 32dp头像 + 8dp间距，与消息框对齐）
        if (message.timestamp.isNotEmpty()) {
            Text(
                text = formatDateTime(message.timestamp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                modifier = Modifier.padding(
                    top = 2.dp,
                    start = if (isOutput) 40.dp else 4.dp,
                    end = 4.dp
                )
            )
        }
    }
}

/**
 * 可折叠的任务进度面板，显示在消息列表上方
 */
@Composable
internal fun TaskProgressPanel(taskStatus: TaskStatusResponse) {
    val task = taskStatus.task
    val progress = taskStatus.progress
    val currentStage = taskStatus.stages.find { it.id == taskStatus.currentStageId }
    val stageTitle = currentStage?.title?.takeIf { it.isNotBlank() } ?: task?.skillName ?: "任务进行中"
    val stageGoal = currentStage?.goal?.takeIf { it.isNotBlank() }
    var isExpanded by rememberSaveable { mutableStateOf(true) }
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp)
    ) {
        Column(modifier = Modifier.padding(28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stageTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { isExpanded = !isExpanded }) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "收起步骤记录" else "展开步骤记录",
                        tint = PsopTextSecondary
                    )
                }
            }
            stageGoal?.let { goal ->
                Text(
                    text = goal,
                    color = PsopTextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = tween(180)) + fadeIn(animationSpec = tween(180)),
                exit = shrinkVertically(animationSpec = tween(180))
            ) {
                Column {
                    if (progress != null && progress.total > 0) {
                        LinearProgressIndicator(
                            progress = { progress.percent / 100f },
                            modifier = Modifier.fillMaxWidth().padding(top = 22.dp).height(12.dp),
                            color = Color(0xFF2E66E9),
                            trackColor = Color(0xFFE9EDF2)
                        )
                        Row(Modifier.fillMaxWidth().padding(top = 22.dp)) {
                            Text("${progress.completed} / ${progress.total} 完成", color = PsopTextSecondary, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Text("${progress.percent}%", color = Color(0xFF2E66E9), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(taskStatus.stages, key = { it.id }) { stage ->
                            StageRow(stage = stage, currentStageId = taskStatus.currentStageId)
                        }
                    }
                }
            }
        }
    }
}

private data class StagePresentation(val color: Color, val label: String)

@Composable
private fun StageRow(stage: com.rokid.cxrmsamples.network.models.TaskStage, currentStageId: String?) {
    val stagePresentation = when {
        stage.status == "completed" -> StagePresentation(
            color = Color(0xFF10B981),
            label = "已完成"
        )
        stage.id == currentStageId || stage.status in setOf("in_progress", "waiting_input") -> StagePresentation(
            color = Color(0xFF2E66E9),
            label = "进行中"
        )
        else -> StagePresentation(
            color = Color(0xFFA0AABB),
            label = "未完成"
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Circle, null, tint = stagePresentation.color, modifier = Modifier.size(16.dp))
        Text(
            stage.title,
            color = stagePresentation.color,
            modifier = Modifier.weight(1f).padding(start = 14.dp),
            style = MaterialTheme.typography.titleMedium
        )
        Text(stagePresentation.label, color = stagePresentation.color, style = MaterialTheme.typography.titleMedium)
    }
}

private fun translateStageStatus(status: String): String {
    return when (status.lowercase()) {
        "pending" -> "待开始"
        "in_progress" -> "进行中"
        "waiting_input" -> "运行中"
        "completed" -> "已完成"
        "failed" -> "失败"
        "aborted" -> "已中止"
        "cancelled" -> "已取消"
        else -> status
    }
}

private fun translateStatus(status: String): String {
    return when (status.lowercase()) {
        "queued" -> "排队中"
        "waiting_runtime" -> "准备中"
        "accepted" -> "已接受"
        "running" -> "运行中"
        "waiting_input" -> "运行中"
        "finalizing" -> "正在完成核验"
        "succeeded" -> "已完成"
        "failed" -> "失败"
        "cancelled" -> "已取消"
        "aborted" -> "已中止"
        else -> status
    }
}

private fun formatDateTime(isoString: String): String {
    return try {
        val cleaned = isoString.replace("T", " ").substringBefore(".")
        val parts = cleaned.split(" ")
        if (parts.size == 2) {
            val dateParts = parts[0].split("-")
            val timeParts = parts[1].split(":")
            if (dateParts.size == 3 && timeParts.size == 3) {
                "${dateParts[0]}年${dateParts[1]}月${dateParts[2]}日 ${timeParts[0]}:${timeParts[1]}:${timeParts[2]}"
            } else cleaned
        } else cleaned
    } catch (e: Exception) {
        isoString
    }
}
