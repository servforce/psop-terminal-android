package com.rokid.cxrmsamples.activities.settings

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rokid.cxrmsamples.network.PsopConfig
import com.rokid.cxrmsamples.network.api.RetrofitClient
import com.rokid.cxrmsamples.ui.theme.PsopTheme

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PsopTheme {
                SettingsScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var hostInput by remember { mutableStateOf(PsopConfig.getSavedHost(context)) }
    var saved by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("服务器设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                text = "服务器地址",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "输入 IP 或 IP:端口，例如：\n• 192.168.1.100\n• 192.168.1.100:9000",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = hostInput,
                onValueChange = { hostInput = it; saved = false },
                label = { Text("服务器地址") },
                placeholder = { Text("192.168.1.100:8001") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            // 实时预览解析后的地址
            if (hostInput.isNotBlank()) {
                val preview = buildPreview(hostInput)
                Text(
                    text = "API: ${preview.first}\nWS:  ${preview.second}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = {
                    if (hostInput.isNotBlank()) {
                        PsopConfig.save(context, hostInput)
                        RetrofitClient.resetApiService()
                        saved = true
                        Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (saved) "✓ 已保存" else "保存")
            }
            Spacer(modifier = Modifier.height(16.dp))
            // 当前生效地址（只读展示）
            Text(
                text = "当前生效地址",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "API: ${PsopConfig.baseUrl}\nWS:  ${PsopConfig.wsUrl}\nASR: ${PsopConfig.asrUrl}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 根据用户输入预览解析后的 baseUrl 和 wsUrl
 */
private fun buildPreview(input: String): Pair<String, String> {
    val cleaned = input
        .removePrefix("http://")
        .removePrefix("https://")
        .removeSuffix("/")
    val parts = cleaned.split(":")
    val host = parts[0].ifBlank { "..." }
    val port = parts.getOrNull(1)?.ifBlank { "8001" } ?: "8001"
    return "http://$host:$port/api/v1/" to "ws://$host:$port/ws"
}
