package com.rokid.cxrmsamples.activities.bluetoothConnection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BluetoothConnectionScreen(
    viewModel: BluetoothIniViewModel,
    reconnect: () -> Unit,
    scan: () -> Unit,
    onItemClicked: (DeviceItem) -> Unit,
    onToast: () -> Unit,
    clear: () -> Unit,
    onCancelConnection: () -> Unit,
    onConnected: () -> Unit,
    onStartInspection: () -> Unit,
    onBack: () -> Unit
) {
    val recordState = viewModel.recordState.collectAsState()
    val scanning = viewModel.isScanningState.collectAsState()
    val devices = viewModel.devicesList.collectAsState()
    val recordName = viewModel.recordName.collectAsState()
    val recordMacAddress = viewModel.recordMacAddress.collectAsState()
    val connecting = viewModel.connecting.collectAsState()
    val connected = viewModel.connected.collectAsState()

    LaunchedEffect(connected.value) {
        if (connected.value) onConnected()
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp)) {
        Spacer(Modifier.height(34.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Text("连接眼镜", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            IconButton(onClick = {}) { Icon(Icons.Default.HelpOutline, "帮助", tint = Color(0xFF778294)) }
        }

        if (connected.value) {
            Spacer(Modifier.height(34.dp))
            Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(28.dp)) {
                    Icon(Icons.Default.Bluetooth, null, tint = Color(0xFF10B981), modifier = Modifier.size(52.dp))
                    Text("已连接 ${recordName.value ?: "眼镜"}", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 18.dp))
                    Text("已保存设备", color = Color(0xFF10B981), modifier = Modifier.padding(top = 8.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = onStartInspection, modifier = Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(22.dp)) {
                Text("开始巡检", fontSize = 20.sp)
            }
            Spacer(Modifier.height(18.dp))
            return@Column
        }

        if (recordState.value) {
            Spacer(Modifier.height(28.dp))
            Text("已保存设备", style = MaterialTheme.typography.titleLarge)
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bluetooth, null, tint = Color(0xFF2E66E9), modifier = Modifier.size(42.dp))
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(recordName.value ?: "未知设备", style = MaterialTheme.typography.titleLarge)
                        Text(recordMacAddress.value ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                    Text("重新连接", color = Color(0xFF2E66E9), modifier = Modifier.clickable(onClick = reconnect))
                }
            }
        }

        Spacer(Modifier.height(34.dp))
        if (connecting.value) {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                    Column(Modifier.padding(start = 18.dp)) {
                        Text("正在连接眼镜…", style = MaterialTheme.typography.titleLarge)
                        Text("请保持眼镜靠近手机", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        } else {
            Text(
                if (scanning.value) "●  正在扫描附近设备" else "附近设备",
                color = if (scanning.value) Color(0xFF2E66E9) else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleLarge
            )
            LazyColumn(modifier = Modifier.weight(1f).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(devices.value) { device ->
                    ConnectionDeviceCard(device = device, onClick = {
                        if (connecting.value) onToast() else onItemClicked(device)
                    })
                }
            }
        }
        if (connecting.value) {
            OutlinedButton(onClick = onCancelConnection, modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp).height(60.dp), shape = RoundedCornerShape(20.dp)) {
                Text("取消连接", fontSize = 18.sp)
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = scan, modifier = Modifier.weight(1f).height(60.dp), shape = RoundedCornerShape(20.dp)) {
                    Text(if (scanning.value) "停止扫描" else "重新扫描", fontSize = 18.sp)
                }
                if (!scanning.value && devices.value.isNotEmpty()) {
                    OutlinedButton(onClick = clear, modifier = Modifier.weight(1f).height(60.dp), shape = RoundedCornerShape(20.dp)) {
                        Text("清空列表", fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionDeviceCard(device: DeviceItem, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Bluetooth, null, tint = Color(0xFF2E66E9), modifier = Modifier.size(38.dp))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                Text("${signalLabel(device.rssi)}  ·  ${device.rssi} dBm", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
            }
            Button(onClick = onClick, shape = RoundedCornerShape(12.dp)) { Text("连接") }
        }
    }
}

private fun signalLabel(rssi: Int): String = when {
    rssi >= -60 -> "信号强"
    rssi >= -75 -> "信号中等"
    else -> "信号弱"
}
