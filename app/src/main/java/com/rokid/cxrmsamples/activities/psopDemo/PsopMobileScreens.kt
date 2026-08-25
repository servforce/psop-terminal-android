package com.rokid.cxrmsamples.activities.psopDemo

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Surface
import android.view.TextureView
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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.io.File
import kotlinx.coroutines.delay

private val MobileBlue = Color(0xFF2E66E9)
private val MobileModeAccent = Color(0xFFFF6900)
internal val MobileActiveRunStatuses = setOf("accepted", "running", "waiting_input")

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
    val recentRun = uiState.homeRecentRun
    val greeting = homeGreeting()
    val actionHint = homeActionHint(activeRun, recentRun)
    Scaffold(
        containerColor = Color(0xFFF5F7FA),
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
                    IconButton(onClick = onChangeMode) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = "切换模式", tint = Color(0xFF6B7688))
                    }
                }
            }
            item {
                Text(greeting, color = Color(0xFF6B7688), style = MaterialTheme.typography.titleMedium)
                Text(actionHint, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 4.dp))
            }
            item {
                Button(
                    onClick = { activeRun?.let(onResumeRun) ?: onOpenSkills() },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MobileBlue)
                ) {
                    Icon(Icons.Default.PlayArrow, null)
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
                    Text("查看全部", color = MobileBlue, modifier = Modifier.clickable(onClick = onOpenHistory))
                }
            }
            if (recentRun != null) {
                item { HomeRecentRunCard(recentRun, uiState, onResumeRun) }
            } else if (!uiState.isLoadingHomeRuns) {
                item { Text("暂无巡检记录", color = Color(0xFF6B7688), modifier = Modifier.padding(vertical = 16.dp)) }
            }
        }
    }
}

@Composable
fun MobileArTaskScreen(viewModel: PsopDemoViewModel, uiState: PsopDemoUiState) {
    var showMenu by remember { mutableStateOf(false) }
    var showChat by rememberSaveable { mutableStateOf(false) }
    var isCaptureMode by rememberSaveable { mutableStateOf(false) }
    var captureStatus by remember { mutableStateOf<String?>(null) }
    var isAwaitingAiReply by remember { mutableStateOf(false) }
    var aiReplyBaselineCount by remember { mutableStateOf(0) }
    var arAiReply by remember { mutableStateOf<String?>(null) }
    var captureFrame by remember { mutableStateOf<(() -> Bitmap?)?>(null) }
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCameraPermission = it
    }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }
    var hasMicrophonePermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var isListening by rememberSaveable { mutableStateOf(false) }
    var startVoiceAfterPermission by rememberSaveable { mutableStateOf(false) }
    val speechRecognizer = remember(context) {
        if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
    }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasMicrophonePermission = granted
        startVoiceAfterPermission = granted
    }

    fun startVoiceRecognition() {
        if (speechRecognizer == null) {
            captureStatus = "语音识别暂不可用"
            return
        }
        isListening = true
        speechRecognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        })
    }

    fun captureAndUpload() {
        val bitmap = captureFrame?.invoke() ?: return
        val photoDir = File(context.cacheDir, "psop_mobile_photos").apply { mkdirs() }
        val photoFile = File.createTempFile("capture_", ".jpg", photoDir)
        photoFile.outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
        }
        arAiReply = null
        isAwaitingAiReply = true
        aiReplyBaselineCount = uiState.messages.size
        viewModel.uploadFile(photoFile)
        captureStatus = "已拍摄，正在校验"
    }

    LaunchedEffect(captureStatus) {
        if (captureStatus != null) {
            delay(2400)
            captureStatus = null
        }
    }

    LaunchedEffect(uiState.messages, isAwaitingAiReply, aiReplyBaselineCount) {
        if (!isAwaitingAiReply) return@LaunchedEffect
        val nextReply = uiState.messages
            .drop(aiReplyBaselineCount)
            .asReversed()
            .firstOrNull { message ->
                message.direction == "output" && message.content.isNotBlank()
            }
            ?.content
            ?.replace("[图片]", "")
            ?.replace("[音频]", "")
            ?.replace("[视频]", "")
            ?.replace("[文件]", "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (nextReply != null) {
            arAiReply = nextReply
            isAwaitingAiReply = false
        }
    }

    DisposableEffect(speechRecognizer) {
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) { isListening = false }
            override fun onResults(results: Bundle?) {
                isListening = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    arAiReply = null
                    isAwaitingAiReply = true
                    aiReplyBaselineCount = uiState.messages.size
                    viewModel.submitInput(text)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        onDispose {
            speechRecognizer?.destroy()
        }
    }

    LaunchedEffect(startVoiceAfterPermission, hasMicrophonePermission) {
        if (startVoiceAfterPermission && hasMicrophonePermission) {
            startVoiceAfterPermission = false
            startVoiceRecognition()
        }
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
        modifier = Modifier.fillMaxSize().background(Color(0xFF101820))
    ) {
        if (hasCameraPermission) {
            MobileCameraPreview(
                onCaptureFrameAvailable = { captureFrame = it },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            CameraPermissionContent(onRequestPermission = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) })
        }
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
                Text(uiState.selectedSkill?.name ?: "当前巡检任务", color = Color.White, fontSize = 12.sp)
                Text(
                    "任务进行中 · 现场画面",
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
            }
        }
        when {
            isAwaitingAiReply -> {
                ArAiReplyCard(
                    text = "AI 正在分析现场信息…",
                    modifier = Modifier.align(Alignment.BottomCenter).padding(start = 20.dp, end = 20.dp, bottom = 170.dp)
                )
            }
            arAiReply != null -> {
                ArAiReplyCard(
                    text = arAiReply!!,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(start = 20.dp, end = 20.dp, bottom = 170.dp)
                )
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            captureStatus?.let { status ->
                Surface(
                    color = Color(0xD9162A44),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Text(status, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                }
            }
            Row(
                modifier = Modifier.padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                Text(
                    "语音提问",
                    color = if (!isCaptureMode) MobileModeAccent else Color(0xFFB8C4D6),
                    fontSize = 13.sp,
                    fontWeight = if (!isCaptureMode) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clickable { isCaptureMode = false }
                )
                Text(
                    "拍摄",
                    color = if (isCaptureMode) MobileModeAccent else Color(0xFFB8C4D6),
                    fontSize = 13.sp,
                    fontWeight = if (isCaptureMode) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clickable { isCaptureMode = true }
                )
            }
            Surface(
                shape = CircleShape,
                color = if (isCaptureMode) Color.White else if (isListening) Color.White else MobileBlue,
                shadowElevation = 8.dp,
                modifier = Modifier.size(62.dp)
            ) {
                IconButton(
                    onClick = {
                        if (isCaptureMode) {
                            if (hasCameraPermission) captureAndUpload() else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        } else {
                            when {
                                isListening -> speechRecognizer?.stopListening()
                                !hasMicrophonePermission -> {
                                    startVoiceAfterPermission = true
                                    microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                                else -> startVoiceRecognition()
                            }
                        }
                    }
                ) {
                    Icon(
                        imageVector = if (isCaptureMode) Icons.Default.CameraAlt else if (isListening) Icons.Default.Stop else Icons.Default.KeyboardVoice,
                        contentDescription = if (isCaptureMode) "拍摄并校验" else if (isListening) "停止语音" else "语音助手",
                        tint = if (isCaptureMode || isListening) MobileBlue else Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun ArAiReplyCard(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = Color(0xE9162A44),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text("AI 现场提示", color = MobileModeAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(text, color = Color.White, fontSize = 14.sp, maxLines = 4, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

@Composable
private fun CameraPermissionContent(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("需要相机权限", color = Color.White, style = MaterialTheme.typography.titleLarge)
        Text("授权后将显示现场画面并进入任务引导", color = Color(0xFFD7E4FF), modifier = Modifier.padding(top = 8.dp))
        Button(onClick = onRequestPermission, modifier = Modifier.padding(top = 18.dp)) {
            Text("开启相机")
        }
    }
}

@Composable
private fun MobileCameraPreview(
    onCaptureFrameAvailable: ((() -> Bitmap?)?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val textureView = remember { TextureView(context) }

    AndroidView(factory = { textureView }, modifier = modifier)

    DisposableEffect(textureView) {
        onCaptureFrameAvailable { textureView.bitmap }
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraThread = HandlerThread("psop-mobile-camera").apply { start() }
        val cameraHandler = Handler(cameraThread.looper)
        var cameraDevice: CameraDevice? = null
        var captureSession: CameraCaptureSession? = null
        var openingCamera = false

        fun closeCamera() {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            openingCamera = false
        }

        fun startPreview(surfaceTexture: SurfaceTexture) {
            val camera = cameraDevice ?: return
            surfaceTexture.setDefaultBufferSize(textureView.width.coerceAtLeast(1), textureView.height.coerceAtLeast(1))
            val surface = Surface(surfaceTexture)
            camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    }.build()
                    session.setRepeatingRequest(request, null, cameraHandler)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) = Unit
            }, cameraHandler)
        }

        val cameraCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                openingCamera = false
                cameraDevice = camera
                textureView.surfaceTexture?.let(::startPreview)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
                openingCamera = false
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                cameraDevice = null
                openingCamera = false
            }
        }

        fun openBackCamera() {
            if (cameraDevice != null || openingCamera || !textureView.isAvailable) return
            val cameraId = try {
                cameraManager.cameraIdList.firstOrNull { id ->
                    cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                }
            } catch (_: Exception) {
                null
            } ?: return
            openingCamera = true
            try {
                cameraManager.openCamera(cameraId, cameraCallback, cameraHandler)
            } catch (_: SecurityException) {
                openingCamera = false
            }
        }

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = openBackCamera()
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        openBackCamera()

        onDispose {
            onCaptureFrameAvailable(null)
            textureView.surfaceTextureListener = null
            closeCamera()
            cameraThread.quitSafely()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileTaskChatScreen(
    uiState: PsopDemoUiState,
    onBack: () -> Unit,
    onSend: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    val isActiveMobileTask = uiState.runStatus in MobileActiveRunStatuses
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            uiState.taskStatus?.let { taskStatus ->
                TaskProgressPanel(taskStatus = taskStatus)
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        if (isActiveMobileTask) "当前处于 AR 任务中。描述问题后，返回 AR 继续实时识别。"
                        else "当前未进入现场执行，可直接向 AI 提问。",
                        color = Color(0xFF60708A),
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
                items(uiState.messages, key = { it.id }) { message ->
                    MessageBubble(message = message)
                }
            }
        }
    }
}
