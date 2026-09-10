# MiniCPM-V 4.6 手机端本地演示设计

## 目标

在现有 Android App 的 SDK 调试功能菜单中增加一个 MiniCPM-V 4.6 本地演示入口。演示在手机上完成单张图片与文字的多轮问答，模型推理完全离线，不调用 PSOP 接口，也不依赖眼镜连接。

首版目标是尽快在指定测试手机上跑通端侧推理，因此接受超大调试 APK、较长的首次模型释放时间和仅支持 `arm64-v8a` 的限制。

## 范围

- 在首页右上角齿轮进入的 `UsageSelectionActivity` 中新增“MiniCPM-V 4.6 本地演示”选项，位置靠近现有 AI Scene。
- 点击选项进入独立的 Compose 演示 Activity，返回时回到 SDK 调试功能菜单。
- 支持从手机相册选择单张图片、使用手机摄像头拍照、输入文字并查看流式回复。
- 支持纯文字提问、图文提问、停止生成和清空当前会话。
- 使用 OpenBMB 官方 MiniCPM-V 4.6 GGUF 权重及其 Android `llama.cpp-omni` 适配代码。
- 模型权重随本地调试 APK 打包，首次进入演示页时释放到 App 私有文件目录。

首版不支持视频、多图同时输入、语音输入、模型联网下载、模型切换、后台持续推理或接入 PSOP 巡检流程。

## 方案选择

采用“官方 GGUF + `llama.cpp-omni` JNI + 模型随 APK 打包”方案：

- 语言模型使用 `MiniCPM-V-4_6-Q4_K_M.gguf`，约 0.5 GB。
- 视觉模型使用 `mmproj-model-f16.gguf`，约 1.1 GB。
- Android 端适配以 OpenBMB 官方 `MiniCPM-V-Apps` 为基线，保留其 MiniCPM-V 4.6 提示词模板和必要的 `llama.cpp-omni` 扩展。
- 模型源为 OpenBMB 官方 MiniCPM-V 4.6 GGUF 仓库，使用下文固定的 revision、文件大小和 SHA-256。

不选用联网模型管理器，因为首版只要求本地跑通；不选用 MNN 或 ONNX 转换，因为官方已有可复用的 Android `llama.cpp-omni` 端侧适配；不单独安装官方 Demo APK，因为入口需要位于当前 App 的 SDK 调试菜单中。

## 固定依赖与目标设备

为保证实现期间上游代码和模型格式不漂移，首版固定以下版本：

- OpenBMB `MiniCPM-V-Apps`：`cf4ebedde4fb9d7d6b7fc76c7f312018493c0b59`。
- `tc-mb/llama.cpp-omni`：`bebcf1676fe90db4dd9b4e9764d89e48ed52bdd9`（与上述 `MiniCPM-V-Apps` commit 的 gitlink 完全一致）。
- OpenBMB `MiniCPM-V-4.6-gguf`：`afe9accb78d2995d214cd912920c9c92f4015faa`。
- Android NDK：`27.0.12077973`。
- CMake：`4.1.2`。

模型资产固定为：

| 文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `MiniCPM-V-4_6-Q4_K_M.gguf` | 529101504 | `6b0c74962c44bc6bf4b655b9b02c13eda9d5a0491543ae976d1ac18e4b7892e2` |
| `mmproj-model-f16.gguf` | 1108746944 | `ca931d861d0801d9003e50697cd764721a334107c0e0415a51168ee1938462de` |

目标测试机为 8 GB RAM 的小米 11 青春版，Snapdragon 780G、`arm64-v8a`。演示页仍通过系统 API 记录实际总内存和当前可用内存，加载前不足时提示关闭其他高内存应用。

首版只编译通用 `arm64-v8a` CPU 路径，不打包或加载官方额外的 ARMv8.6 `i8mm/bf16` 优化库。Snapdragon 780G 不作为 ARMv8.6 目标处理。完成正确性验证后，可以单独评估针对该设备指令集的优化构建，但优化不属于本次跑通条件。

原生源码采用以下落地方式：

- `llama.cpp-omni` 作为固定 commit 的 Git submodule 放入 `third_party/`。
- 从固定版本的 `MiniCPM-V-Apps` 引入 Android JNI 核心实现及必要辅助代码，并在当前仓库内保留来源、commit 和许可证说明。
- 当前 App 增加自己的薄 Kotlin/native 封装，业务页面不直接依赖官方 Demo 的 Activity、View 或模型下载器。
- 不直接复制官方 Demo 的完整 Gradle 工程，避免把无关的模型管理、传统 View UI 和 TTS 能力带入当前 App。

## 页面与交互

### SDK 调试功能菜单

`UsageSelectionActivity` 保持现有多选项布局，在 AI Scene 附近增加“MiniCPM-V 4.6 本地演示”按钮。该入口不经过眼镜连接门禁，也不要求 PSOP 服务器可用。

### 初始化状态

演示页按以下状态展示：

1. 检查设备是否为 `arm64-v8a`，并检查可用存储空间。
2. 模型尚未释放或版本不一致时，展示两个模型文件的释放进度。
3. 释放完成后校验文件大小和 SHA-256，然后加载语言模型与视觉模型。
4. 加载成功后进入对话状态；失败时展示明确原因并提供重试。

初始化期间禁用发送、选图和拍照操作，但允许返回。用户返回时取消仍可安全取消的复制或加载任务，并释放已经创建的 native 资源。

### 对话状态

页面由图片预览区、消息列表和底部输入区组成：

- 图片预览区显示当前选中的一张图片，可移除或替换。
- 消息列表显示用户输入与模型回复，模型生成时逐步追加 token。
- 输入区提供相册、拍照、文本框、发送和停止按钮。
- 发送图文问题后，当前图片作为该轮用户消息的一部分保存；后续替换图片不改变历史消息的缩略图。
- 清空会话会清除 Kotlin 侧消息和 native 侧上下文，但不卸载模型。
- 页面离开时停止生成并卸载模型，避免与现有 ASR、TTS 或其他页面长期争用内存。

## 架构与组件

### `MiniCpmDemoActivity` 与 Compose 页面

Activity 只负责页面承载、相册/拍照结果注册和返回行为。Composable 只消费 UI 状态并发送用户操作，不直接调用 JNI 或操作模型文件。

### `MiniCpmDemoViewModel`

ViewModel 维护以下单向状态：

- 设备检查、模型释放、模型校验和模型加载进度；
- 当前图片、消息列表和输入文本；
- `initializing`、`ready`、`generating`、`error` 等互斥运行状态；
- 当前生成任务的取消控制。

所有模型操作在专用单线程调度器上串行执行，同一时刻只允许一次加载、生成、清空或释放操作。

### `BundledMiniCpmModelInstaller`

安装器负责把 assets 下的两个 `.gguf` 文件复制到 `filesDir/models/minicpm-v-4.6/<revision>/`：

- 复制时写入 `.part` 临时文件并计算 SHA-256。
- 校验通过后原子改名，防止中断留下看似完整的模型。
- 通过固定 revision、文件长度和校验值判断是否需要重新释放。
- 出现损坏时只重建损坏文件，不删除其他 App 数据。
- 权重文件加入 Git 忽略规则；仓库只保留目录说明、官方下载位置、固定 revision、期望文件名及校验信息。

Gradle 对 `.gguf` 使用 `noCompress`，避免构建阶段压缩已经量化的超大权重。由于 `llama.cpp-omni` 需要普通文件路径，权重不能直接留在 APK assets 中供推理，首次使用必须复制到 App 私有目录。

### `MiniCpmEngine`

Kotlin 层通过接口隔离 native 实现，接口提供：

- `load(modelPath, mmprojPath, config)`；
- `prefillImage(imageBytes)`；
- `generate(userPrompt, maxTokens, onToken)`；
- `cancelGeneration()`；
- `clearConversation()`；
- `close()`。

JNI 层持有 `llama.cpp-omni` model、context、multimodal projector、原生对话上下文和采样器资源。Kotlin 消息列表只用于 UI 展示，每轮只把可选图片和本轮用户文字增量送入 native；不在每轮重放完整历史。所有回调切回 Kotlin 前检查任务代次，已取消或已被新任务替换的 token 不再写入 UI。

MiniCPM-V 4.6 按固定官方实现使用 8192-token 上下文，单轮最多生成 512 tokens。首版关闭 thinking，不注入默认英文 system prompt，沿用官方 MiniCPM-V 4.6 ChatML 模板，保证中文提问不会被默认提示词引导为英文。图片切片上限固定为 1，优先降低 Snapdragon 780G 上的图像预填充耗时；提高切片数属于后续画质优化。

首版使用 CPU 推理，不承诺 GPU/NPU 加速；默认使用 4 个推理线程，与固定版本的官方 Android JNI 默认值一致，实机基准后仅在有明确收益时调整。

## 数据流

1. 用户从 SDK 调试菜单进入演示页。
2. ViewModel 检查 ABI、空间和本地模型版本。
3. 安装器按需从 assets 释放并校验两个 GGUF 文件。
4. ViewModel 在 native 单线程上加载模型。
5. 用户选图或拍照，Kotlin 层校正 EXIF 方向并生成受控尺寸的推理输入和 UI 缩略图。
6. 用户发送问题，ViewModel 固化本轮消息和图片；有图片时先调用 `MiniCpmEngine.prefillImage`，随后调用 `generate` 发送本轮文字。
7. JNI 完成可选的图像编码与文本生成，并通过回调流式返回 token。
8. ViewModel 合并 token 更新当前回复；完成、取消或失败后恢复可发送状态。

## 图片与内存控制

- 不在 Compose 状态中保存相机原始 Bitmap，只保存 URI、缩略图或受控尺寸的推理副本。
- 读取图片时处理 EXIF 方向并按模型适配代码要求缩放，避免无边界解码原图。
- 图片送入 native 层后及时释放临时像素缓冲，不同时长期保留原图、完整 Kotlin Bitmap 和 native 副本。
- 模型加载前记录系统可用内存；明显不足时提示用户关闭其他应用，而不是直接尝试加载。
- 默认只保留一个已加载实例，禁止并发生成。

## 构建与模型资产

- 首期 APK 只包含 `arm64-v8a` native 库。
- 开始 native 构建前，在开发环境安装并锁定 NDK `27.0.12077973` 和 CMake `4.1.2`；当前开发环境尚未安装这两项，属于编码前置准备。
- 本地构建前需要把两个官方权重放入约定的 assets 目录；缺少权重时构建仍可用于普通功能，但演示页必须显示“模型资产缺失”及所需文件名。
- 模型总计约 1.6 GB，调试 APK 会相应增大。
- 安装及首次释放期间 APK、安装临时文件和释放后的模型可能同时存在，测试手机建议至少预留 5 GB 可用空间。
- 首版仅通过本地 `adb install` 分发，不处理 Play 商店、应用市场或增量更新限制。
- 保留 OpenBMB、MiniCPM-V 和所用 `llama.cpp-omni` 代码的许可证及来源声明。

构建验证分为两个阶段：

1. 不放入 GGUF 权重，先验证 submodule、CMake、JNI、`arm64-v8a` native 库加载和普通 APK 安装，排除工具链及符号问题。
2. 放入两个已校验权重，构建超大 APK，单独验证 aapt2 打包、APK 签名、`adb install`、assets 释放和模型加载。

只有第二阶段的离线图文问答通过，才视为端侧部署跑通；第一阶段不能替代最终验收。

## 错误处理

- 非 `arm64-v8a`：阻止初始化并显示不支持原因。
- 空间不足：复制前阻止操作，显示需求和当前可用空间。
- assets 缺失：列出缺少的文件，不影响 App 其他页面。
- 复制中断：保留或清理 `.part` 文件，下次进入重新复制，不把它识别为可用模型。
- 校验失败：删除对应损坏目标文件并允许重新释放。
- native 加载失败：释放所有已创建资源并显示 native 错误摘要。
- 推理失败或取消：保留已完成的历史消息，移除空回复或把部分回复标记为已停止。
- Activity 销毁：停止 token 回调，串行执行取消与资源释放，防止回调访问已销毁页面。

## 测试与验收

- SDK 调试功能菜单中出现“MiniCPM-V 4.6 本地演示”，位置靠近 AI Scene。
- 未连接眼镜、未连接 PSOP 服务器时仍能进入演示页。
- 首次进入可以看到模型释放、校验和加载进度，完成后无需网络即可使用。
- 模型复制过程中强制退出，再次进入能够安全恢复或重建，不会加载半成品。
- 可从相册选图，也可使用手机摄像头拍照；图片方向显示正确。
- 中文图文问题产生中文流式回复，纯文字问题也能正常生成。
- 生成期间只能启动一个任务；停止后可以再次发送。
- 清空会话后历史和 native 上下文均被清除，但无需重新加载模型。
- 连续更换图片和完成多轮对话不发生 native 崩溃或明显内存持续增长。
- 返回菜单后 native 模型资源最终释放。
- 记录目标手机的模型释放时间、加载时间、首 token 延迟、生成速度、峰值内存与连续运行温升。
- 在目标手机上记录系统报告的实际 RAM 容量、ABI 与 CPU feature 检测结果，确认运行的是通用 arm64 CPU 库而非 ARMv8.6 优化库。
- 现有 PSOP、WebSocket、眼镜连接、离线 ASR 和离线 TTS 功能可正常编译和使用。

## 后续扩展边界

模型联网下载、断点续传、多图、视频、眼镜拍照输入、语音输入以及替代 PSOP 推理都属于后续独立需求，不在本次本地跑通范围内。首版通过稳定的 `MiniCpmEngine` 接口保留扩展位置，但不提前实现这些能力。
