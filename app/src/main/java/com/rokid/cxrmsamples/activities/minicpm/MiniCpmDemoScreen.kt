package com.rokid.cxrmsamples.activities.minicpm

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniCpmDemoScreen(
    viewModel: MiniCpmDemoViewModel,
    onBack: () -> Unit,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MiniCPM-V 4.6 本地演示") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::clearConversation,
                        enabled = state.ready || state.generating,
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = "清空会话")
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 16.dp),
        ) {
            StatusCard(state = state, onRetry = viewModel::initialize)

            if (state.selectedImageUri != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .padding(vertical = 8.dp),
                ) {
                    AsyncImage(
                        model = state.selectedImageUri,
                        contentDescription = "当前图片",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    IconButton(
                        onClick = viewModel::removeImage,
                        enabled = !state.generating,
                        modifier = Modifier.align(Alignment.TopEnd),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "移除图片")
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.messages.isEmpty()) {
                    item {
                        Text(
                            text = "可直接输入文字，也可以选择或拍摄一张图片后提问。推理完全在手机本地完成。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
                items(state.messages, key = { it.id }) { message ->
                    MessageCard(message)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onPickImage,
                    enabled = state.ready && !state.imagePreparing,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Photo, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("相册")
                }
                OutlinedButton(
                    onClick = onTakePhoto,
                    enabled = state.ready && !state.imagePreparing,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("拍照")
                }
            }

            OutlinedTextField(
                value = state.inputText,
                onValueChange = viewModel::updateInput,
                enabled = state.ready,
                label = { Text("输入问题") },
                placeholder = { Text("例如：图中有什么？") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                minLines = 1,
                maxLines = 4,
            )
            Button(
                onClick = if (state.generating) viewModel::stopGeneration else viewModel::send,
                enabled = state.generating ||
                    (state.ready && !state.imagePreparing &&
                        (state.inputText.isNotBlank() || state.selectedImageUri != null)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            ) {
                Text(if (state.generating) "停止生成" else "发送")
            }
        }
    }
}

@Composable
private fun StatusCard(state: MiniCpmDemoUiState, onRetry: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.phase in listOf(
                        MiniCpmPhase.CHECKING,
                        MiniCpmPhase.COPYING,
                        MiniCpmPhase.LOADING,
                    )
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                Text(state.statusText, style = MaterialTheme.typography.bodyMedium)
            }
            state.progress?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
            if (state.deviceSummary.isNotEmpty()) {
                Text(
                    state.deviceSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (state.phase == MiniCpmPhase.ERROR) {
                OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) {
                    Text("重试")
                }
            }
        }
    }
}

@Composable
private fun MessageCard(message: MiniCpmMessage) {
    val isUser = message.role == MiniCpmRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(modifier = Modifier.fillMaxWidth(0.88f)) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    text = if (isUser) "你" else "MiniCPM-V",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                message.imageUri?.let { uri ->
                    AsyncImage(
                        model = uri,
                        contentDescription = "本轮图片",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .size(96.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                }
                Text(
                    text = message.text.ifEmpty { "…" },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (message.stopped) {
                    Text(
                        "已停止",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
