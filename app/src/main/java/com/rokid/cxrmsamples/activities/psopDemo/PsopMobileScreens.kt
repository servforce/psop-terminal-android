package com.rokid.cxrmsamples.activities.psopDemo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaActionSound
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.rokid.cxrmsamples.asr.SherpaAsrEngine
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val MobileBlue = Color(0xFF2E66E9)
private val MobileModeAccent = Color(0xFFFF6900)
private val ArOverlayGreen = Color(0xFF00E676)
internal val MobileActiveRunStatuses = setOf("accepted", "running", "waiting_input")

private const val MOBILE_ASR_SAMPLE_RATE = 16_000

private data class MobileAsrResult(val text: String? = null, val error: String? = null)

/**
 * 手机端独立录音入口。只采集手机麦克风的 16kHz PCM，识别仍使用项目已有的
 * Sherpa 离线模型，不接入系统 SpeechRecognizer，也不影响眼镜音频流。
 */
private class MobileOfflineAsrRecorder(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outputLock = Any()
    private val recognitionMutex = Mutex()
    private var output = ByteArrayOutputStream()
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    @Volatile
    private var isCapturing = false

    @Suppress("MissingPermission")
    fun start(): String? {
        if (isCapturing) return null
        val minBufferSize = AudioRecord.getMinBufferSize(
            MOBILE_ASR_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) return "当前设备无法打开麦克风"

        val bufferSize = maxOf(minBufferSize, 4096)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MOBILE_ASR_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (error: Exception) {
            Log.w("PsopMobileAsr", "Unable to create AudioRecord", error)
            return "无法启动手机录音"
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return "当前设备无法初始化麦克风"
        }

        synchronized(outputLock) { output = ByteArrayOutputStream() }
        audioRecord = recorder
        return try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                recorder.release()
                audioRecord = null
                "手机录音未能启动"
            } else {
                isCapturing = true
                captureJob = scope.launch {
                    val buffer = ByteArray(bufferSize)
                    while (isCapturing) {
                        val size = recorder.read(buffer, 0, buffer.size)
                        if (size > 0) {
                            synchronized(outputLock) { output.write(buffer, 0, size) }
                        } else if (size != AudioRecord.ERROR_INVALID_OPERATION && size != AudioRecord.ERROR_BAD_VALUE) {
                            Log.w("PsopMobileAsr", "AudioRecord read failed: $size")
                        }
                    }
                }
                null
            }
        } catch (error: Exception) {
            Log.w("PsopMobileAsr", "Unable to start AudioRecord", error)
            recorder.release()
            audioRecord = null
            "无法启动手机录音"
        }
    }

    suspend fun stopAndRecognize(): MobileAsrResult = withContext(Dispatchers.IO) {
        isCapturing = false
        val recorder = audioRecord
        runCatching { recorder?.stop() }
        captureJob?.join()
        captureJob = null
        if (audioRecord === recorder) audioRecord = null
        runCatching { recorder?.release() }

        val pcm = synchronized(outputLock) { output.toByteArray() }
        if (pcm.size < MOBILE_ASR_SAMPLE_RATE) {
            return@withContext MobileAsrResult(error = "说话时间太短，请按住再说一次")
        }
        val text = recognitionMutex.withLock {
            if (!SherpaAsrEngine.initialize(appContext)) return@withLock null
            SherpaAsrEngine.recognize(pcm).trim()
        } ?: return@withContext MobileAsrResult(error = "离线语音模型初始化失败")
        if (text.isBlank()) {
            MobileAsrResult(error = "没有识别到有效语音，请再试一次")
        } else {
            MobileAsrResult(text = text)
        }
    }

    suspend fun recognizeCurrentAudio(): String? = withContext(Dispatchers.IO) {
        val pcm = synchronized(outputLock) { output.toByteArray() }
        if (pcm.size < MOBILE_ASR_SAMPLE_RATE) return@withContext null
        recognitionMutex.withLock {
            if (!SherpaAsrEngine.initialize(appContext)) return@withLock null
            SherpaAsrEngine.recognize(pcm).trim().takeIf { it.isNotBlank() }
        }
    }

    fun cancelRecording() {
        isCapturing = false
        val recorder = audioRecord
        audioRecord = null
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        captureJob?.cancel()
        captureJob = null
        synchronized(outputLock) { output = ByteArrayOutputStream() }
    }

    fun release() {
        cancelRecording()
        scope.cancel()
    }
}

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
    var modeSwipeDistance by remember { mutableFloatStateOf(0f) }
    val selectedModeOffset by animateDpAsState(
        targetValue = when {
            showMenu -> (-52).dp
            isCaptureMode -> 52.dp
            else -> 0.dp
        },
        label = "mobileModeAlignment"
    )
    var captureStatus by remember { mutableStateOf<String?>(null) }
    var isAwaitingAiReply by remember { mutableStateOf(false) }
    var aiReplyBaselineCount by remember { mutableStateOf(0) }
    var arAiReply by remember { mutableStateOf<String?>(null) }
    val voiceModeInteraction = remember { MutableInteractionSource() }
    val captureModeInteraction = remember { MutableInteractionSource() }
    val moreModeInteraction = remember { MutableInteractionSource() }
    var captureFrame by remember { mutableStateOf<(() -> Bitmap?)?>(null) }
    var showCaptureFeedback by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val hapticView = LocalView.current
    val modeSwipeThresholdPx = with(LocalDensity.current) { 48.dp.toPx() }
    val shutterSound = remember { MediaActionSound() }
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCameraPermission = it
    }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        shutterSound.load(MediaActionSound.SHUTTER_CLICK)
    }
    var hasMicrophonePermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var isListening by rememberSaveable { mutableStateOf(false) }
    var isRecognizingVoice by rememberSaveable { mutableStateOf(false) }
    var isVoiceCancelArmed by rememberSaveable { mutableStateOf(false) }
    var liveVoiceText by remember { mutableStateOf("") }
    var showVoiceTranscript by remember { mutableStateOf(false) }
    var voiceTouchStartY by remember { mutableFloatStateOf(0f) }
    val coroutineScope = rememberCoroutineScope()
    val offlineAsrRecorder = remember(context) { MobileOfflineAsrRecorder(context) }
    var partialAsrJob by remember { mutableStateOf<Job?>(null) }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasMicrophonePermission = granted
        if (!granted) captureStatus = "需要麦克风权限才能语音提问"
    }

    fun startVoiceRecognition() {
        val error = offlineAsrRecorder.start()
        if (error != null) {
            captureStatus = error
        } else {
            isListening = true
            isVoiceCancelArmed = false
            liveVoiceText = ""
            showVoiceTranscript = true
            captureStatus = "正在聆听 · 松开发送"
            hapticView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            partialAsrJob?.cancel()
            partialAsrJob = coroutineScope.launch {
                delay(800)
                while (isListening) {
                    offlineAsrRecorder.recognizeCurrentAudio()?.let { partial ->
                        liveVoiceText = partial
                    }
                    delay(800)
                }
            }
        }
    }

    fun stopVoiceRecognition() {
        if (!isListening) return
        isListening = false
        isVoiceCancelArmed = false
        isRecognizingVoice = true
        captureStatus = "语音已提交"
        hapticView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        partialAsrJob?.cancel()
        partialAsrJob = null
        coroutineScope.launch {
            delay(2_000)
            showVoiceTranscript = false
            liveVoiceText = ""
        }
        coroutineScope.launch {
            val result = offlineAsrRecorder.stopAndRecognize()
            isRecognizingVoice = false
            result.text?.let { text ->
                liveVoiceText = text
                arAiReply = null
                isAwaitingAiReply = true
                aiReplyBaselineCount = uiState.messages.size
                viewModel.submitInput(text)
                captureStatus = null
            } ?: run {
                captureStatus = result.error ?: "语音识别失败，请再试一次"
            }
        }
    }

    fun cancelVoiceRecognition() {
        if (!isListening) return
        offlineAsrRecorder.cancelRecording()
        isListening = false
        isVoiceCancelArmed = false
        partialAsrJob?.cancel()
        partialAsrJob = null
        showVoiceTranscript = false
        liveVoiceText = ""
        captureStatus = "已取消语音输入"
    }

    fun captureAndUpload() {
        val bitmap = captureFrame?.invoke() ?: return
        val photoDir = File(context.cacheDir, "psop_mobile_photos").apply { mkdirs() }
        val photoFile = File.createTempFile("capture_", ".jpg", photoDir)
        photoFile.outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
        }
        arAiReply = null
        showCaptureFeedback = true
        shutterSound.play(MediaActionSound.SHUTTER_CLICK)
        isAwaitingAiReply = true
        aiReplyBaselineCount = uiState.messages.size
        viewModel.uploadFile(photoFile)
        captureStatus = "已拍摄，正在校验"
    }

    LaunchedEffect(captureStatus) {
        if (captureStatus != null && !isRecognizingVoice && !isListening) {
            delay(2400)
            captureStatus = null
        }
    }

    LaunchedEffect(showCaptureFeedback) {
        if (showCaptureFeedback) {
            delay(1100)
            showCaptureFeedback = false
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

    LaunchedEffect(uiState.phoneTtsCompletionSequence) {
        if (uiState.phoneTtsCompletionSequence > 0 && arAiReply != null) {
            delay(10_000)
            arAiReply = null
        }
    }

    DisposableEffect(offlineAsrRecorder, shutterSound) {
        onDispose {
            offlineAsrRecorder.release()
            shutterSound.release()
        }
    }

    val currentStartVoice by rememberUpdatedState(newValue = { startVoiceRecognition() })
    val currentStopVoice by rememberUpdatedState(newValue = { stopVoiceRecognition() })
    val currentCancelVoice by rememberUpdatedState(newValue = { cancelVoiceRecognition() })
    val currentIsListening by rememberUpdatedState(newValue = isListening)
    val currentIsVoiceCancelArmed by rememberUpdatedState(newValue = isVoiceCancelArmed)
    val currentUpdateVoiceCancelHint by rememberUpdatedState(newValue = { cancelArmed: Boolean ->
        isVoiceCancelArmed = cancelArmed
        captureStatus = if (cancelArmed) "松开取消" else "正在聆听 · 松开发送"
    })
    val currentRequestMicrophonePermission by rememberUpdatedState(newValue = {
        microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    })
    fun selectControlMode(index: Int) {
        when (index) {
            0 -> {
                isCaptureMode = true
                showMenu = false
            }
            1 -> {
                isCaptureMode = false
                showMenu = false
            }
            else -> showMenu = true
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
                modifier = if (showMenu) Modifier.fillMaxSize().blur(12.dp) else Modifier.fillMaxSize()
            )
        } else {
            CameraPermissionContent(onRequestPermission = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) })
        }
        if (showCaptureFeedback) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.10f))
            )
            CaptureFeedbackCorners(modifier = Modifier.fillMaxSize().padding(38.dp))
        }
        if (showVoiceTranscript) {
            LiveVoiceTranscriptCard(
                text = liveVoiceText.ifBlank {
                    if (isListening) "正在聆听…" else "正在识别语音…"
                },
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp)
            )
        }
        arAiReply?.let { reply ->
            ArAiReplyCard(
                text = reply,
                modifier = Modifier.align(Alignment.TopCenter).padding(start = 20.dp, end = 20.dp, top = 28.dp)
            )
        }
        if (showMenu) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.42f))
                    .clickable { showMenu = false }
            )
            Surface(
                color = Color(0xF2172A44),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, Color(0xFF37526F)),
                shadowElevation = 10.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 126.dp)
                    .clickable { showMenu = false; showChat = true }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MobileBlue.copy(alpha = 0.26f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, tint = Color(0xFF9EC1FF), modifier = Modifier.size(17.dp))
                    }
                    Column {
                        Text("AI 对话", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("描述现场问题，获取操作建议", color = Color(0xFFAFC2D9), fontSize = 11.sp)
                    }
                }
            }
        }
        captureStatus?.let { status ->
            Surface(
                color = Color(0xD9162A44),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 128.dp)
            ) {
                Text(
                    status,
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black)
                .padding(top = 14.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .offset(x = selectedModeOffset)
                    .padding(bottom = 12.dp)
                    .pointerInput(isCaptureMode, showMenu) {
                        detectHorizontalDragGestures(
                            onDragStart = { modeSwipeDistance = 0f },
                            onHorizontalDrag = { _, dragAmount ->
                                modeSwipeDistance += dragAmount
                            },
                            onDragEnd = {
                                val currentIndex = when {
                                    showMenu -> 2
                                    isCaptureMode -> 0
                                    else -> 1
                                }
                                when {
                                    modeSwipeDistance <= -modeSwipeThresholdPx ->
                                        selectControlMode((currentIndex + 1).coerceAtMost(2))
                                    modeSwipeDistance >= modeSwipeThresholdPx ->
                                        selectControlMode((currentIndex - 1).coerceAtLeast(0))
                                }
                                modeSwipeDistance = 0f
                            },
                            onDragCancel = { modeSwipeDistance = 0f }
                        )
                    },
                horizontalArrangement = Arrangement.spacedBy(26.dp)
            ) {
                Text(
                    "拍摄",
                    color = if (isCaptureMode && !showMenu) MobileModeAccent else Color(0xFFB8C4D6),
                    fontSize = 13.sp,
                    fontWeight = if (isCaptureMode && !showMenu) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clickable(
                        interactionSource = captureModeInteraction,
                        indication = null
                    ) { selectControlMode(0) }
                )
                Text(
                    "语音",
                    color = if (!isCaptureMode && !showMenu) MobileModeAccent else Color(0xFFB8C4D6),
                    fontSize = 13.sp,
                    fontWeight = if (!isCaptureMode && !showMenu) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clickable(
                        interactionSource = voiceModeInteraction,
                        indication = null
                    ) { selectControlMode(1) }
                )
                Text(
                    "更多",
                    color = if (showMenu) MobileModeAccent else Color(0xFFB8C4D6),
                    fontSize = 13.sp,
                    fontWeight = if (showMenu) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clickable(
                        interactionSource = moreModeInteraction,
                        indication = null
                    ) { if (showMenu) selectControlMode(1) else selectControlMode(2) }
                )
            }
            Surface(
                shape = CircleShape,
                color = if (isCaptureMode) Color.White else if (isListening) Color.White else MobileBlue,
                shadowElevation = 8.dp,
                modifier = Modifier.size(62.dp)
            ) {
                if (isCaptureMode) {
                    IconButton(
                        onClick = {
                            if (hasCameraPermission) captureAndUpload() else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "拍摄并校验",
                            tint = MobileBlue
                        )
                    }
                } else {
                    val cancelDistancePx = with(LocalDensity.current) { 72.dp.toPx() }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInteropFilter { event ->
                                when (event.actionMasked) {
                                    MotionEvent.ACTION_DOWN -> {
                                        if (isRecognizingVoice) return@pointerInteropFilter true
                                        if (!hasMicrophonePermission) {
                                            currentRequestMicrophonePermission()
                                        } else {
                                            voiceTouchStartY = event.y
                                            currentStartVoice()
                                        }
                                        true
                                    }
                                    MotionEvent.ACTION_MOVE -> {
                                        if (currentIsListening) {
                                            currentUpdateVoiceCancelHint(event.y < voiceTouchStartY - cancelDistancePx)
                                        }
                                        true
                                    }
                                    MotionEvent.ACTION_UP -> {
                                        if (currentIsListening) {
                                            if (currentIsVoiceCancelArmed) currentCancelVoice() else currentStopVoice()
                                        }
                                        true
                                    }
                                    MotionEvent.ACTION_CANCEL -> {
                                        if (currentIsListening) currentCancelVoice()
                                        true
                                    }
                                    else -> true
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isListening) ListeningVoiceWaves()
                        Icon(
                            imageVector = if (isListening) Icons.Default.Stop else Icons.Default.KeyboardVoice,
                            contentDescription = if (isListening) "松开发送" else "按住语音助手",
                            tint = if (isListening) MobileBlue else Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArAiReplyCard(text: String, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()
    Surface(
        color = Color(0x3000E676),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, ArOverlayGreen),
        modifier = modifier.fillMaxWidth().heightIn(max = 330.dp)
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(scrollState)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text("AI 现场提示", color = ArOverlayGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(text, color = ArOverlayGreen, fontSize = 14.sp, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

@Composable
private fun LiveVoiceTranscriptCard(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = Color(0xD9162A44),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, ArOverlayGreen),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text("语音输入", color = ArOverlayGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(
                text = text,
                color = ArOverlayGreen,
                fontSize = 18.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun ListeningVoiceWaves() {
    val transition = rememberInfiniteTransition(label = "mobileVoiceWave")
    val outerScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.58f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 860, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "outerWaveScale"
    )
    val innerScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 860, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "innerWaveScale"
    )
    Box(
        modifier = Modifier
            .size(46.dp)
            .scale(outerScale)
            .border(1.dp, MobileBlue.copy(alpha = 0.58f), CircleShape)
    )
    Box(
        modifier = Modifier
            .size(46.dp)
            .scale(innerScale)
            .border(1.dp, MobileBlue.copy(alpha = 0.42f), CircleShape)
    )
}

@Composable
private fun CaptureFeedbackCorners(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cornerLength = 40.dp.toPx()
        val strokeWidth = 3.dp.toPx()
        val right = size.width
        val bottom = size.height
        val color = ArOverlayGreen

        drawLine(color, Offset.Zero, Offset(cornerLength, 0f), strokeWidth)
        drawLine(color, Offset.Zero, Offset(0f, cornerLength), strokeWidth)
        drawLine(color, Offset(right, 0f), Offset(right - cornerLength, 0f), strokeWidth)
        drawLine(color, Offset(right, 0f), Offset(right, cornerLength), strokeWidth)
        drawLine(color, Offset(0f, bottom), Offset(cornerLength, bottom), strokeWidth)
        drawLine(color, Offset(0f, bottom), Offset(0f, bottom - cornerLength), strokeWidth)
        drawLine(color, Offset(right, bottom), Offset(right - cornerLength, bottom), strokeWidth)
        drawLine(color, Offset(right, bottom), Offset(right, bottom - cornerLength), strokeWidth)
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
    var fullscreenImageUrl by remember { mutableStateOf<String?>(null) }
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
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (isActiveMobileTask) "输入问题" else "任务已结束，仅可查看记录") },
                    enabled = isActiveMobileTask
                )
                IconButton(
                    onClick = { if (input.isNotBlank()) { onSend(input); input = "" } },
                    enabled = isActiveMobileTask && input.isNotBlank()
                ) {
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
                        else "该任务已结束，仅可查看历史记录。",
                        color = Color(0xFF60708A),
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
                items(uiState.messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        onImageClick = { fullscreenImageUrl = it },
                        enableOutputTextSelection = true
                    )
                }
            }
        }
    }
    fullscreenImageUrl?.let { imageUrl ->
        FullScreenImageViewer(imageUrl = imageUrl, onDismiss = { fullscreenImageUrl = null })
    }
}
