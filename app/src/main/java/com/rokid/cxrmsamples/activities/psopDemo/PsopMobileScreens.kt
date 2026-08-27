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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import com.rokid.cxrmsamples.network.models.RunResponse
import com.rokid.cxrmsamples.network.models.SkillSummaryResponse
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
internal val MobileActiveRunStatuses = setOf(
    "queued", "waiting_runtime", "accepted", "running", "waiting_input", "finalizing"
)
private val MobileTerminalRunStatuses = setOf("succeeded", "failed", "cancelled", "aborted")

/** 新建任务在首个状态事件到达前 runStatus 为空，此时仍应保持可操作。 */
internal fun isMobileTaskActive(uiState: PsopDemoUiState): Boolean =
    uiState.isRunning && uiState.runStatus !in MobileTerminalRunStatuses

private const val MOBILE_ASR_SAMPLE_RATE = 16_000
private const val MOBILE_ASR_VOICE_RMS_THRESHOLD = 150.0

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

    @Volatile
    private var hasDetectedVoice = false

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
        hasDetectedVoice = false
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
                            if (computePcm16Rms(buffer, size) > MOBILE_ASR_VOICE_RMS_THRESHOLD) {
                                hasDetectedVoice = true
                            }
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
        if (!hasDetectedVoice) {
            return@withContext MobileAsrResult(error = "未检测到语音，请重试")
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
        if (pcm.size < MOBILE_ASR_SAMPLE_RATE || !hasDetectedVoice) return@withContext null
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
        hasDetectedVoice = false
    }

    fun release() {
        cancelRecording()
        scope.cancel()
    }

    private fun computePcm16Rms(data: ByteArray, size: Int): Double {
        var sum = 0.0
        var sampleCount = 0
        var index = 0
        while (index + 1 < size) {
            val sample = (data[index].toInt() and 0xFF) or (data[index + 1].toInt() shl 8)
            sum += sample.toDouble() * sample
            sampleCount++
            index += 2
        }
        return if (sampleCount == 0) 0.0 else kotlin.math.sqrt(sum / sampleCount)
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
            Text("PSOP 智能作业", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
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
    val cardShape = RoundedCornerShape(24.dp)
    Surface(
        shape = cardShape,
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFE0E7F0)),
        // 将点击波纹裁切到卡片圆角内，避免按压时出现矩形底色。
        modifier = Modifier.fillMaxWidth().clip(cardShape).clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tag, color = MobileBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(description, color = Color(0xFF6B7688), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileSkillListScreen(
    skills: List<SkillSummaryResponse>,
    isLoading: Boolean,
    error: String?,
    title: String = "任务",
    onSkillSelected: (SkillSummaryResponse) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = Color(0xFFF5F7FA),
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Medium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新任务")
                    }
                }
            )
        }
    ) { padding ->
        when {
            isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(color = MobileBlue)
            }
            error != null -> MobileSkillEmptyState(
                modifier = Modifier.fillMaxSize().padding(padding),
                message = error,
                actionLabel = "重试",
                onAction = onRetry
            )
            skills.isEmpty() -> MobileSkillEmptyState(
                modifier = Modifier.fillMaxSize().padding(padding),
                message = "暂无可用任务",
                actionLabel = "刷新",
                onAction = onRetry
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Text("选择一项任务，查看作业说明后再启动。", color = Color(0xFF6B7688))
                }
                items(skills, key = { it.id }) { skill ->
                    val introduction = mobileSkillIntroduction(skill)
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, Color(0xFFE0E7F0)),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).clickable {
                            onSkillSelected(skill)
                        }
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("AI 现场作业", color = MobileBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(skill.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(introduction, color = Color(0xFF6B7688), style = MaterialTheme.typography.bodyMedium)
                            Text("查看任务说明", color = MobileBlue, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MobileSkillEmptyState(
    modifier: Modifier,
    message: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(message, color = Color(0xFF6B7688))
        Spacer(Modifier.height(14.dp))
        Button(onClick = onAction, colors = ButtonDefaults.buttonColors(containerColor = MobileBlue)) {
            Text(actionLabel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileSkillDetailScreen(
    skill: SkillSummaryResponse?,
    activeRun: RunResponse?,
    isLoading: Boolean,
    onStartNew: () -> Unit,
    onResume: (RunResponse) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val task = skill ?: return
    val introduction = mobileSkillIntroduction(task)
    Scaffold(
        containerColor = Color(0xFFF5F7FA),
        topBar = {
            TopAppBar(
                title = { Text("任务说明", fontWeight = FontWeight.Medium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        bottomBar = {
            Surface(color = Color.White, shadowElevation = 8.dp) {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 14.dp)) {
                    if (activeRun != null) {
                        Button(
                            onClick = { onResume(activeRun) },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MobileBlue)
                        ) { Text("继续当前任务", fontSize = 18.sp) }
                        Text(
                            "当前任务仍在进行中",
                            color = Color(0xFF6B7688),
                            fontSize = 12.sp,
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)
                        )
                        androidx.compose.material3.TextButton(
                            onClick = onStartNew,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) { Text("启动新任务", color = MobileBlue) }
                    } else {
                        Button(
                            onClick = onStartNew,
                            enabled = !isLoading,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MobileBlue)
                        ) { Text(if (isLoading) "正在检查任务状态…" else "启动新任务", fontSize = 18.sp) }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text("AI 现场作业", color = MobileBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(task.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            }
            item {
                MobileTaskInfoCard(title = "任务介绍", content = introduction)
            }
            item {
                MobileTaskInfoCard(
                    title = "作业方式",
                    content = "启动后进入手机 AR 实时画面，按任务步骤操作；完成后由 AI 对现场结果进行核验。"
                )
            }
            item {
                MobileTaskInfoCard(
                    title = "任务流程",
                    content = "启动任务 → 跟随现场引导 → 提交关键画面 → 获取核验结果"
                )
            }
        }
    }
}

@Composable
private fun MobileTaskInfoCard(title: String, content: String) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color(0xFFE0E7F0)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(content, color = Color(0xFF6B7688), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun mobileSkillIntroduction(skill: SkillSummaryResponse): String = when {
    skill.name.contains("电脑") && (skill.name.contains("安装") || skill.name.contains("装机")) ->
        "按照主机装配顺序完成关键部件安装，并在关键步骤获取现场核验结果。"
    skill.name.contains("维修") -> "根据现场引导完成设备检查、修正与维修结果核验。"
    skill.name.contains("巡检") -> "按照标准巡检路径完成检查，并记录现场结果。"
    else -> "跟随现场引导完成本次作业，并在关键步骤获取 AI 核验结果。"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileHistoryScreen(
    uiState: PsopDemoUiState,
    onSkillSelected: (SkillSummaryResponse) -> Unit,
    onStatusChanged: (String) -> Unit,
    onRefresh: () -> Unit,
    onLoadNextPage: () -> Unit,
    onRunClicked: (RunResponse) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val statuses = listOf("running" to "运行中", "succeeded" to "已完成", "aborted" to "已中止", "cancelled" to "已取消")
    var showSkillMenu by remember { mutableStateOf(false) }
    val selectedSkill = uiState.selectedSkill
    val listState = rememberLazyListState()
    HistoryAutoLoadMore(
        listState = listState,
        canLoadMore = uiState.historyCanLoadMore,
        isLoading = uiState.isLoadingMoreHistory,
        onLoadNextPage = onLoadNextPage
    )
    Scaffold(containerColor = Color(0xFFF5F7FA)) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 24.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("历史记录", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.Home, contentDescription = "返回首页", tint = Color(0xFF6B7688))
                }
            }
            Text("当前作业", color = Color(0xFF6B7688), fontSize = 12.sp, modifier = Modifier.padding(top = 20.dp, bottom = 7.dp))
            Surface(
                color = Color(0xFFEAF1FF),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { showSkillMenu = true }
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("作业 · ${selectedSkill?.name ?: "正在加载…"}", color = MobileBlue, fontWeight = FontWeight.SemiBold)
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "切换作业技能",
                        tint = MobileBlue,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                statuses.forEach { (value, label) ->
                    val selected = value == uiState.runStatusFilter
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) MobileBlue else Color.White,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable {
                            if (!selected && selectedSkill != null) onStatusChanged(value)
                        }
                    ) {
                        Text(
                            label,
                            color = if (selected) Color.White else Color(0xFF485467),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 12.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
            if (uiState.isRefreshingHistory) {
                HistoryLoadingIndicator(color = MobileBlue)
            }
            PullToRefreshBox(
                isRefreshing = uiState.isRefreshingHistory,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxWidth().weight(1f),
                indicator = {}
            ) {
                when {
                    uiState.isLoadingInvocations -> Text("正在加载…", color = Color(0xFF6B7688), modifier = Modifier.fillMaxWidth().padding(top = 36.dp))
                    selectedSkill == null -> Text("暂无可用作业技能", color = Color(0xFF6B7688), modifier = Modifier.fillMaxWidth().padding(top = 36.dp))
                    uiState.invocations.isEmpty() -> {
                        val label = statuses.firstOrNull { it.first == uiState.runStatusFilter }?.second ?: "当前"
                        Text("暂无${selectedSkill.name}的${label}记录", color = Color(0xFF6B7688), modifier = Modifier.fillMaxWidth().padding(top = 36.dp))
                    }
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(top = 22.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(uiState.invocations, key = { it.id }) { run ->
                            HistoryRunCard(run, uiState, onRunClicked)
                        }
                        if (uiState.isLoadingMoreHistory) {
                            item { HistoryLoadingIndicator(color = MobileBlue) }
                        }
                    }
                }
            }
        }
    }
    if (showSkillMenu) {
        ModalBottomSheet(
            onDismissRequest = { showSkillMenu = false },
            containerColor = Color.White
        ) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
                Text("选择作业技能", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("历史记录只显示所选作业", color = Color(0xFF6B7688), fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp, bottom = 16.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.skills, key = { it.id }) { skill ->
                        val isSelected = skill.id == selectedSkill?.id
                        Surface(
                            color = if (isSelected) Color(0xFFEAF1FF) else Color(0xFFF7F9FC),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable {
                                showSkillMenu = false
                                if (!isSelected) onSkillSelected(skill)
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(skill.name, modifier = Modifier.weight(1f), fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
                                if (isSelected) Text("当前", color = MobileBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
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
                    Text("PSOP 智能作业", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
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
                    HomeShortcut("任务技能", Icons.Default.List, Modifier.weight(1f), onOpenSkills)
                    HomeShortcut("历史记录", Icons.Default.History, Modifier.weight(1f), onOpenHistory)
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("最近任务", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
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
    var showChat by rememberSaveable { mutableStateOf(false) }
    var isCaptureMode by rememberSaveable { mutableStateOf(false) }
    var isConversationMode by rememberSaveable { mutableStateOf(false) }
    var modeSwipeDistance by remember { mutableFloatStateOf(0f) }
    val selectedModeOffset by animateDpAsState(
        targetValue = when {
            isCaptureMode -> 52.dp
            isConversationMode -> (-52).dp
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
    val conversationModeInteraction = remember { MutableInteractionSource() }
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
                isConversationMode = false
            }
            1 -> {
                isCaptureMode = false
                isConversationMode = false
            }
            else -> {
                isCaptureMode = false
                isConversationMode = true
            }
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
                        .pointerInput(isCaptureMode, isConversationMode) {
                            detectHorizontalDragGestures(
                                onDragStart = { modeSwipeDistance = 0f },
                                onHorizontalDrag = { _, dragAmount ->
                                    modeSwipeDistance += dragAmount
                                },
                                onDragEnd = {
                                    val currentIndex = when {
                                        isCaptureMode -> 0
                                        isConversationMode -> 2
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
                        color = if (isCaptureMode) MobileModeAccent else Color(0xFFB8C4D6),
                        fontSize = 13.sp,
                        fontWeight = if (isCaptureMode) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.clickable(interactionSource = captureModeInteraction, indication = null) { selectControlMode(0) }
                    )
                    Text(
                        "语音",
                        color = if (!isCaptureMode && !isConversationMode) MobileModeAccent else Color(0xFFB8C4D6),
                        fontSize = 13.sp,
                        fontWeight = if (!isCaptureMode && !isConversationMode) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.clickable(interactionSource = voiceModeInteraction, indication = null) { selectControlMode(1) }
                    )
                    Text(
                        "会话",
                        color = if (isConversationMode) MobileModeAccent else Color(0xFFB8C4D6),
                        fontSize = 13.sp,
                        fontWeight = if (isConversationMode) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.clickable(interactionSource = conversationModeInteraction, indication = null) { selectControlMode(2) }
                    )
                }
            Box(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    shape = CircleShape,
                    color = when {
                        isCaptureMode -> Color.White
                        isListening -> Color.White
                        else -> MobileBlue
                    },
                    shadowElevation = 8.dp,
                    modifier = Modifier.size(62.dp).align(Alignment.Center)
                ) {
                    if (isConversationMode) {
                        IconButton(onClick = { showChat = true }) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "进入会话",
                                tint = Color.White
                            )
                        }
                    } else if (isCaptureMode) {
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
    // 接管系统侧滑返回，终态历史会话应回到历史列表，而不是退出到模式选择页。
    BackHandler(onBack = onBack)
    var input by remember { mutableStateOf("") }
    var fullscreenImageUrl by remember { mutableStateOf<String?>(null) }
    val isActiveMobileTask = isMobileTaskActive(uiState)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.selectedSkill?.name ?: "PSOP 任务助手") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回历史记录")
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
