package com.rokid.cxrmsamples.activities.psopDemo

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import com.rokid.cxrmsamples.network.models.InvocationListResponse
import com.rokid.cxrmsamples.network.models.SkillSummaryResponse

/**
 * 视频缩略图全局缓存（LruCache），key 为视频 URL。
 * 最多缓存 20 张缩略图，避免 LazyColumn 滑动时反复用 MediaMetadataRetriever 提取帧。
 */
private val thumbnailCache = object : android.util.LruCache<String, android.graphics.Bitmap>(20) {}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PsopDemoScreen(viewModel: PsopDemoViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    when (uiState.currentScreen) {
        InspectionScreen.SKILL_LIST -> SkillListScreen(
            skills = uiState.skills,
            isLoading = uiState.isLoadingSkills,
            error = uiState.error,
            onSkillSelected = { viewModel.selectSkill(it) },
            onRetry = { viewModel.loadSkills() }
        )
        InspectionScreen.INVOCATION_LIST -> InvocationListScreen(
            skillName = uiState.selectedSkill?.name ?: "",
            invocations = uiState.invocations,
            isLoading = uiState.isLoadingInvocations,
            onStartInspection = { viewModel.startSkill() },
            onInvocationClicked = { viewModel.resumeInvocation(it) },
            onBack = { viewModel.navigateBack() }
        )
        InspectionScreen.INTERACTION -> InteractionScreen(viewModel = viewModel, uiState = uiState)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillListScreen(
    skills: List<SkillSummaryResponse>,
    isLoading: Boolean,
    error: String?,
    onSkillSelected: (SkillSummaryResponse) -> Unit,
    onRetry: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("选择巡检技能") })
        }
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (error != null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onRetry) { Text("重试") }
                }
            }
        } else if (skills.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无可用技能", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onRetry) { Text("刷新") }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(skills) { skill ->
                    ListItem(
                        headlineContent = { Text(skill.name) },
                        modifier = Modifier.clickable { onSkillSelected(skill) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvocationListScreen(
    skillName: String,
    invocations: List<InvocationListResponse>,
    isLoading: Boolean,
    onStartInspection: () -> Unit,
    onInvocationClicked: (InvocationListResponse) -> Unit,
    onBack: () -> Unit
) {
    BackHandler {
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(skillName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
        } else if (invocations.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("暂无调用记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(invocations) { invocation ->
                    ListItem(
                        headlineContent = { Text(invocation.runId ?: invocation.id) },
                        supportingContent = {
                            Text("状态: ${translateStatus(invocation.status)} | ${formatDateTime(invocation.createdAt)}")
                        },
                        modifier = Modifier.clickable { onInvocationClicked(invocation) }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.selectedSkill?.name ?: "PSOP 设备巡检") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.navigateBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
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
                            Text(
                                "✅ 巡检完成",
                                color = Color(0xFF4CAF50),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.fillMaxWidth()
                            )
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
        // 机器人头像（与 MessageBubble 保持一致）
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(0xFFE8EAF6)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.SmartToy,
                contentDescription = "AI",
                tint = Color(0xFF5C6BC0),
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFF0F0F0))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = "正在思考...",
                color = Color(0xFF9E9E9E),
                fontSize = 14.sp
            )
        }
    }
}

@Composable
fun MessageBubble(
    message: TerminalMessage,
    onImageClick: (String) -> Unit = {},
    onVideoClick: (String) -> Unit = {}
) {
    val isOutput = message.direction == "output"
    val textColor = if (isOutput) Color.Black else Color.White
    val richMedia = resolveRichMediaInfo(message.eventKind)
    // 如果有图片 parts 且 content 是元数据格式，不展示
    val displayContent = if (message.parts.isNotEmpty() && message.content.startsWith("{")) {
        ""
    } else message.content

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isOutput) Alignment.Start else Alignment.End
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isOutput) Arrangement.Start else Arrangement.End,
            verticalAlignment = Alignment.Top
        ) {
            // AI 消息：左侧显示圆形机器人头像
            if (isOutput) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE8EAF6)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SmartToy,
                        contentDescription = "AI",
                        tint = Color(0xFF5C6BC0),
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .then(
                        if (message.parts.isEmpty()) {
                            Modifier.background(
                                if (isOutput) Color(0xFFF0F0F0) else Color(0xFF2196F3)
                            )
                        } else Modifier
                    )
                    .padding(12.dp)
            ) {
                Column {
                    if (richMedia != null) {
                        // 富媒体消息：图标 + 类型标签 + 提示
                        val (icon, label) = richMedia
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = icon,
                                contentDescription = label,
                                tint = if (isOutput) Color(0xFF757575) else Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = label,
                                    color = textColor,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "点击查看",
                                    color = if (isOutput) Color.Gray else Color(0xBBFFFFFF),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    } else if (displayContent.isNotEmpty()) {
                        // 纯文本消息
                        Text(
                            text = displayContent,
                            color = textColor
                        )
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
        // 时间戳
        if (message.timestamp.isNotEmpty()) {
            Text(
                text = formatDateTime(message.timestamp),
                color = Color.Gray,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp)
            )
        }
    }
}

private fun translateStatus(status: String): String {
    return when (status.lowercase()) {
        "accepted" -> "已接受"
        "running" -> "运行中"
        "waiting_input" -> "等待输入"
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
