# CXRMSamples

基于 `CXR-M 1.0.9` 的官方 Android Demo，用于验证 Rokid Glasses 与 Android 手机之间的连接、采集、显示、通知/TTS 以及实时视频等能力。

这份 README 以“后续调试接手文档”为目标编写，重点记录：

- 项目入口和整体流程
- 连接前必须修改的配置项
- 各功能模块对应的 Activity / ViewModel
- 当前项目中已经实现、可直接验证的能力
- 对后续眼镜调试有价值的观察结论

## 1. 项目概览

- 包名：`com.rokid.cxrmsamples`
- Android 最低版本：`minSdk 31`
- `compileSdk`: `36`
- `targetSdk`: `36`
- App 版本：`1.0.4`
- SDK 依赖：`com.rokid.cxr:client-m:1.0.9`
- UI 技术栈：Jetpack Compose

核心依赖定义位于：

- `app/build.gradle.kts`

## 2. 项目入口流程

应用的使用路径如下：

1. `MainActivity`
   - 检查蓝牙权限
   - 检查系统蓝牙是否开启
   - 满足条件后跳转连接页
2. `BluetoothInitActivity`
   - 扫描 Rokid Glasses
   - `initBluetooth`
   - `connectBluetooth`
   - 连接成功后进入功能选择页
3. `UsageSelectionActivity`
   - 进入各功能 Demo 页面

对应文件：

- `app/src/main/java/com/rokid/cxrmsamples/activities/main/MainActivity.kt`
- `app/src/main/java/com/rokid/cxrmsamples/activities/main/MainViewModel.kt`
- `app/src/main/java/com/rokid/cxrmsamples/activities/bluetoothConnection/BluetoothInitActivity.kt`
- `app/src/main/java/com/rokid/cxrmsamples/activities/bluetoothConnection/BluetoothIniViewModel.kt`
- `app/src/main/java/com/rokid/cxrmsamples/activities/usageSelection/UsageSelectionActivity.kt`
- `app/src/main/java/com/rokid/cxrmsamples/activities/usageSelection/UsageSelectionViewModel.kt`

## 3. 连接前必须确认的配置

项目当前通过 `CONSTANT.kt` 持有 SDK 连接关键配置：

- `SERVICE_UUID`
- `CLIENT_SECRET`
- `getSNResource()` 返回的 `.lc` 授权文件
- `CUSTOM_CMD`

关键文件：

- `app/src/main/java/com/rokid/cxrmsamples/dataBeans/CONSTANT.kt`
- `app/src/main/res/raw/`

当前项目内包含多个 `.lc` 文件，实际连接哪副眼镜，取决于：

1. `CLIENT_SECRET` 是否属于你的 Rokid 开发者账号
2. `getSNResource()` 返回的 `.lc` 是否覆盖目标眼镜 SN

如果连接时出现类似 `SN_CHECK_FAILED`，优先检查这里。

## 4. Android 权限

`AndroidManifest.xml` 中已经声明了以下核心权限：

- 蓝牙：`BLUETOOTH` / `BLUETOOTH_ADMIN` / `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`
- 定位：`ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`
- 网络与 Wi‑Fi：`INTERNET` / `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` / `ACCESS_NETWORK_STATE` / `CHANGE_NETWORK_STATE`
- Android 13+：`NEARBY_WIFI_DEVICES`

文件：

- `app/src/main/AndroidManifest.xml`

## 5. 功能模块清单

当前 `UsageSelectionActivity` 暴露了以下功能：

| 功能 | 说明 | 入口文件 |
| --- | --- | --- |
| Device Information | 获取设备信息、监听音量/亮度/电量/屏幕状态、设置亮度和音量 | `activities/deviceInformation/*` |
| Audio | 从眼镜采集音频到手机，保存为 PCM，并在手机本地播放 | `activities/audio/*` |
| Picture | 调用眼镜拍照 | `activities/picture/*` |
| Video | 调用眼镜录像 | `activities/video/*` |
| Live Video | 实时取流预览，支持 H264/H265，支持录制 MP4 | `activities/liveVideo/*` |
| Media Files | Wi‑Fi P2P 连接、查询未同步数量、同步音频/图片/视频文件 | `activities/mediaFile/*` |
| Self View | 自定义页面/自定义视图显示 | `activities/customView/*` |
| Custom Protocol | 自定义消息通道 | `activities/customProtocol/*` |
| TTS or Notification | 全局通知、Toast、TTS、人声和语速控制 | `activities/ttsAndNotification/*` |
| AI Scene | AI 场景示例 | `activities/useAIScene/*` |
| Teleprompter Scene | 提词器场景示例 | `activities/useTeleprompter/*` |
| Translation Scene | 翻译场景示例 | `activities/useTranslation/*` |

## 6. TTS / Notification 模块

这是新 demo 相比旧 sample 最值得关注的新增点之一。

入口：

- `app/src/main/java/com/rokid/cxrmsamples/activities/ttsAndNotification/TTSAndNotificationActivity.kt`
- `app/src/main/java/com/rokid/cxrmsamples/activities/ttsAndNotification/TTSAndNotificationViewModel.kt`

当前页面已经覆盖的能力：

1. 发送全局通知
   - API：`sendGlobalMsgContent(iconType, content, playTTS)`
2. 发送全局 Toast
   - API：`sendGlobalToastContent(iconType, content, playTTS)`
3. 设置本地 TTS 发音人
   - API：`setLocalTtsParam(...)`
   - 当前封装了 `Girl` / `Boy`
4. 设置本地 TTS 语速
   - API：`setLocalTtsSpeed(speed)`
   - 当前 UI 限制范围：`0.75 ~ 4.0`
5. 直接让眼镜播报 TTS
   - API：`sendGlobalTtsContent(content)`

这意味着：

- 新 demo 已经验证了“眼镜音频输出”中的一个关键场景：TTS 播报
- 该能力不是通过手机本地 `AudioTrack` 实现，而是通过 `CxrApi` 直接向眼镜下发 TTS / 通知内容

## 7. Audio 模块现状

当前 `AudioUsageActivity` 做的是：

1. 从眼镜采音
2. 将 PCM 保存到手机本地目录
3. 用 Android `AudioTrack` 在手机本地回放

对应文件：

- `app/src/main/java/com/rokid/cxrmsamples/activities/audio/AudioUsageActivity.kt`
- `app/src/main/java/com/rokid/cxrmsamples/activities/audio/AudioUsageViewModel.kt`

重要结论：

- 这个模块当前验证的是“眼镜 -> 手机”的音频输入链路
- 不是“手机 -> 眼镜扬声器”的通用音频播放链路

不过在 `DeviceInformationViewModel` 中可以看到：

- `setGlassVolume(level)`
- `setCommunicationDevice()`
- `clearCommunicationDevice()`

说明新 SDK 至少已经具备：

- 控制眼镜音量
- 切换通信音频设备的能力线索

这对后续继续验证“眼镜扬声器输出”非常重要。

## 8. Live Video 模块

这是新 demo 另一个明显增强点。

当前实现包含：

- 眼镜实时视频流拉取
- H264 / H265 编码模式切换
- 预览渲染
- 可选 MP4 录制
- 录制完成后写入系统相册

对应文件：

- `app/src/main/java/com/rokid/cxrmsamples/activities/liveVideo/LiveVideoActivity.kt`
- `app/src/main/java/com/rokid/cxrmsamples/activities/liveVideo/LiveVideoViewModel.kt`
- `app/src/main/java/com/rokid/cxrmsamples/activities/liveVideo/LiveVideoFrameBuffer.kt`
- `app/src/main/java/com/rokid/cxrmsamples/activities/liveVideo/LiveVideoPreviewRenderer.kt`
- `app/src/main/java/com/rokid/cxrmsamples/activities/liveVideo/LiveVideoMp4Recorder.kt`

## 9. 自定义视图与自定义协议

项目依然保留了：

- `Custom View`
- `Custom Protocol`

用途分别是：

- `Custom View`：向眼镜显示自定义页面/组件
- `Custom Protocol`：在手机与眼镜之间传递自定义消息

需要注意：

- `Custom Protocol` 本身只是消息通道
- 默认不会自动驱动 `Custom View`
- 如果要实现“发消息 -> 眼镜页面显示内容”，需要你在业务层主动把两条链路串起来

## 10. 设备信息模块

`DeviceInformationViewModel` 覆盖的信息和控制项比较全，适合拿来做连接后的健康检查页：

- 设备名、SN、系统版本
- 佩戴状态
- TTS 状态
- 镜腿状态
- 电量 / 充电状态
- 屏幕状态监听
- 音量监听与设置
- 亮度监听与设置
- 通知眼镜灭屏

对应文件：

- `app/src/main/java/com/rokid/cxrmsamples/activities/deviceInformation/DeviceInformationActivity.kt`
- `app/src/main/java/com/rokid/cxrmsamples/activities/deviceInformation/DeviceInformationViewModel.kt`

## 11. 建议的调试顺序

如果后续以这个官方新 demo 为主继续调试，建议顺序如下：

1. 先校准连接配置
   - `CLIENT_SECRET`
   - `.lc`
   - 手机蓝牙权限
2. 跑通蓝牙连接主链路
   - `MainActivity -> BluetoothInitActivity -> UsageSelectionActivity`
3. 用 `Device Information` 做连接后验证
   - 确认 SN、系统版本、音量、亮度、电量都能读到
4. 验证输入链路
   - Audio / Picture / Video / Live Video
5. 验证输出链路
   - Self View
   - TTS / Notification
6. 最后再验证复杂链路
   - Media Files
   - Custom Protocol
   - AI / Teleprompter / Translation

## 12. 本地构建与安装

如果本机环境已配好 Android SDK / Gradle / ADB，可直接使用：

```powershell
Set-Location D:\code\servforce.ai\psop\CXRMSamples
gradle :app:assembleDebug
```

APK 默认输出路径：

```text
app\build\outputs\apk\debug\app-debug.apk
```

安装到真机：

```powershell
$adb = "D:\android-sdk\platform-tools\adb.exe"
& $adb devices -l
& $adb -s <device-serial> install -r .\app\build\outputs\apk\debug\app-debug.apk
& $adb -s <device-serial> shell am start -n com.rokid.cxrmsamples/.activities.main.MainActivity
```

## 13. 这份项目对当前工作的意义

相较于旧的 `RokidGlassesSamples`，这个新的 `CXRMSamples` 对后续调试更有价值的点是：

- SDK 版本更新到 `1.0.9`
- 新增了 `TTSAndNotification` 模块
- 新增了 `LiveVideo` 模块
- 连接、设备信息、媒体同步、自定义视图、自定义协议等功能仍然保留
- 代码整体注释和结构更适合继续做功能验证与二次开发

## 14. 当前建议

后续如果以这个项目为主线继续调试，优先关注这几个文件：

- `app/src/main/java/com/rokid/cxrmsamples/dataBeans/CONSTANT.kt`
- `app/src/main/java/com/rokid/cxrmsamples/activities/bluetoothConnection/BluetoothIniViewModel.kt`
- `app/src/main/java/com/rokid/cxrmsamples/activities/deviceInformation/DeviceInformationViewModel.kt`
- `app/src/main/java/com/rokid/cxrmsamples/activities/ttsAndNotification/TTSAndNotificationViewModel.kt`
- `app/src/main/java/com/rokid/cxrmsamples/activities/liveVideo/LiveVideoViewModel.kt`

后续如果我们要继续做“眼镜扬声器输出能力”验证，这个新项目最值得先试的两个方向是：

1. `TTSAndNotification` 的 TTS 播报链路
2. `DeviceInformationViewModel` 里的音量 / `setCommunicationDevice()` 相关能力
