package com.rokid.cxrmsamples.activities.bluetoothConnection

// 文档参考：01设备连接 - 扫描与连接（BLE 扫描、initBluetooth、connectBluetooth）

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.cxrmsamples.R
import com.rokid.cxrmsamples.ui.theme.PsopTheme

class BluetoothInitActivity : ComponentActivity() {

    private val viewModel: BluetoothIniViewModel by viewModels()
    lateinit var btManager: BluetoothManager
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PsopTheme {
                BluetoothInitScreen(viewModel = viewModel, reconnect = {
                    viewModel.connectBTSocket(this)
                }, scan = {
                    viewModel.handleScan(btManager.adapter.bluetoothLeScanner)
                }, onItemClicked = { deviceItem ->
                    viewModel.handleScan(btManager.adapter.bluetoothLeScanner)
                    viewModel.deviceClicked(this, deviceItem)
                }, onToast = {
                    Toast.makeText(
                        this@BluetoothInitActivity,
                        resources.getString(R.string.bt_connecting),
                        Toast.LENGTH_SHORT
                    ).show()
                }, clear = {
                    viewModel.clearDevices()
                }, doAfterConnected = {
                    viewModel.record(this)
                }, disconnect = {
                    viewModel.disconnect()
                }, toPsopDemo = {
                    viewModel.toPsopDemo(this)
                }, toSdkDebug = {
                    viewModel.toUseGlasses(this)
                })
            }
        }
        btManager = getSystemService(BluetoothManager::class.java)
        viewModel.toConnect.observe(this) {
            if (it) {
                viewModel.connectBTSocket(this)
            }
        }
        viewModel.checkRecordState(this)
        viewModel.checkConnection()
    }

}

//Jetpack Compose

@SuppressLint("MissingPermission")
@Composable
fun BluetoothInitScreen(
    viewModel: BluetoothIniViewModel = viewModel(),
    reconnect: () -> Unit,
    scan: () -> Unit,
    onItemClicked: (DeviceItem?) -> Unit,
    onToast: () -> Unit,
    clear: () -> Unit,
    doAfterConnected: () -> Unit,
    disconnect: () -> Unit,
    toPsopDemo: () -> Unit,
    toSdkDebug: () -> Unit
) {
    val recordState = viewModel.recordState.collectAsState()
    val scanning = viewModel.isScanningState.collectAsState()
    val devices = viewModel.devicesList.collectAsState()

    val recordName = viewModel.recordName.collectAsState()
    val recordMacAddress = viewModel.recordMacAddress.collectAsState()
    val recordUuid = viewModel.recordUUID.collectAsState()
    val connecting = viewModel.connecting.collectAsState()
    val connected = viewModel.connected.collectAsState()
    if (connected.value) {
        doAfterConnected()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painterResource(R.drawable.glasses_bg),
            modifier = Modifier.fillMaxSize(),
            alpha = 0.12f,
            contentDescription = null
        )
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                text = "", modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
            )
            if (connected.value) {
                // 连接成功：只显示设备名，隐藏 mac/uuid
                Text(
                    text = "✓ 已连接：${recordName.value ?: "未知设备"}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF4CAF50),
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                // 未连接：显示完整设备记录信息（用于调试/重连）
                Text(
                    text = if (recordState.value) {
                        stringResource(R.string.is_record)
                    } else {
                        stringResource(R.string.not_record)
                    },
                    modifier = Modifier
                )
                if (recordState.value) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.device_record),
                            fontSize = 12.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier
                                .fillMaxWidth(0.3f)
                                .padding(end = 8.dp)
                        )
                        Text(
                            text = recordName.value ?: "",
                            fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.mac_address_record),
                            fontSize = 12.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier
                                .fillMaxWidth(0.3f)
                                .padding(end = 8.dp)
                        )
                        Text(
                            text = recordMacAddress.value ?: "",
                            fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.uuid_record),
                            fontSize = 12.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier
                                .fillMaxWidth(0.3f)
                                .padding(end = 8.dp)
                        )
                        Text(
                            text = recordUuid.value ?: "",
                            fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Button(onClick = reconnect, modifier = Modifier.fillMaxWidth(0.9f)) {
                        Text(text = stringResource(R.string.reconnect))
                    }
                }
            }
            if (!connected.value) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .padding(top = 12.dp)
                ) {
                    Button(
                        onClick = scan,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp, end = 4.dp)
                    ) {
                        Text(
                            text = if (!scanning.value) {
                                stringResource(R.string.scan)
                            } else {
                                stringResource(R.string.stop_scan)
                            }
                        )
                    }
                    if (!scanning.value && devices.value.isNotEmpty()) {
//                if (!scanning.value){
                        OutlinedButton(
                            onClick = clear,
                            modifier = Modifier
                                .padding(start = 4.dp, end = 4.dp)
                        ) {
                            Text(text = stringResource(R.string.clear_items))
                        }
                    }
                }
            }

            // 扫描结果列表
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                items(devices.value) { deviceItem ->
                    BluetoothDeviceItem(
                        item = deviceItem,
                        onClick = {
                            if (!connecting.value) {
                                onItemClicked(deviceItem)
                            } else {
                                onToast()
                            }
                        }
                    )
                }
            }

            // 连接中 loading 提示
            if (connecting.value && !connected.value) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp).padding(end = 12.dp),
                        strokeWidth = 2.dp
                    )
                    Text(text = "正在连接...", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (connected.value) {
                Button(
                    onClick = toPsopDemo,
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(52.dp)
                ) {
                    Text(text = "开始巡检", fontWeight = FontWeight.Medium)
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = disconnect, modifier = Modifier.fillMaxWidth(0.8f)) {
                    Text(text = stringResource(R.string.bt_disconnect))
                }
            }

            // 底部 Spacer 撑满，把 SDK 调试入口推到最底部
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "SDK 调试功能",
                fontSize = 13.sp,
                color = Color(0xFF9E9E9E),
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .clickable { toSdkDebug() }
                    .padding(16.dp)
            )
        }
    }
}

@Composable
fun BluetoothDeviceItem(item: DeviceItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = item.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(text = item.macAddress, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(text = "${item.rssi} dBm", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    PsopTheme {
        BluetoothInitScreen(
            reconnect = {},
            viewModel = viewModel { BluetoothIniViewModel() },
            scan = {},
            onItemClicked = {},
            onToast = {},
            clear = {},
            doAfterConnected = {},
            disconnect = {},
            toPsopDemo = {},
            toSdkDebug = {}
        )
    }
}