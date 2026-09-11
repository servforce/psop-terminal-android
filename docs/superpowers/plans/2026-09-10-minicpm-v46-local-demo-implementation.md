# MiniCPM-V 4.6 手机端本地演示实施计划

## 交付目标

在当前 Android App 的 SDK 调试功能菜单中新增 MiniCPM-V 4.6 本地演示入口，并在 8 GB RAM 的小米 11 青春版上完成完全离线的单图与文字多轮问答。

实施依据为 `docs/superpowers/specs/2026-09-10-minicpm-v46-local-demo-design.md`。本计划不包含 PSOP 推理替换、眼镜图片输入、视频、多图、语音或模型联网下载。

## 固定输入

- `MiniCPM-V-Apps` commit：`cf4ebedde4fb9d7d6b7fc76c7f312018493c0b59`。
- `llama.cpp-omni` commit：`bebcf1676fe90db4dd9b4e9764d89e48ed52bdd9`，与固定 `MiniCPM-V-Apps` commit 的 gitlink 一致。
- 模型仓库 revision：`afe9accb78d2995d214cd912920c9c92f4015faa`。
- NDK：`27.0.12077973`。
- CMake：`4.1.2`。
- ABI：仅 `arm64-v8a`。
- 推理参数：8192 context、4 threads、最多生成 512 tokens、thinking 关闭、图片切片上限 1、CPU-only。

> 注意：第一项 commit 必须与设计文档一致。执行任务 1 时先用 `git ls-remote` 校验；若远端不存在，立即停止，不允许猜测或自动改用最新版本。

## 任务 1：校验并引入固定上游源码

涉及文件：

- `.gitmodules`
- `third_party/llama.cpp-omni/`
- `third_party/minicpm-v46/UPSTREAM.md`

步骤：

1. 使用 `git ls-remote` 分别确认 `MiniCPM-V-Apps` 与 `llama.cpp-omni` 固定 commit 存在。
2. 将 `tc-mb/llama.cpp-omni` 添加为 `third_party/llama.cpp-omni` submodule，并 checkout 到固定 commit。
3. 从固定 commit 的 `MiniCPM-V-Apps` 核对 Android JNI 状态机和提示词行为，只作为兼容性参考。
4. 由于该固定 commit 没有根许可证文件，不逐字复制它的源码；JNI 使用 MIT 许可的 `llama.cpp-omni` API 独立实现。
5. 在 `UPSTREAM.md` 记录仓库 URL、commit、参考范围、未复制原因和模型卡声明的 Apache-2.0 许可。

验证：

- `git submodule status` 显示准确 commit。
- `git diff --check` 通过。
- 仓库中不存在未经说明的完整官方源码副本。

提交建议：`build: pin MiniCPM-V native sources`

## 任务 2：安装并锁定 Android native 工具链

涉及文件：

- `app/build.gradle.kts`
- `local.properties`，仅本机使用，不提交

步骤：

1. 通过 Android SDK Manager 安装 NDK `27.0.12077973` 和 CMake `4.1.2`。
2. 在 `android` 配置中增加 `ndkVersion = "27.0.12077973"`。
3. 保留当前 AGP `9.2.1`，不为迁就官方 Demo 降退项目 AGP。
4. 在 `defaultConfig.ndk.abiFilters` 中只加入 `arm64-v8a`。
5. 配置 `externalNativeBuild.cmake` 指向后续创建的 CMake 文件并锁定 `4.1.2`。
6. 设置 `GGML_NATIVE=OFF`、CPU-only、关闭 curl，并确保不会生成或打包 ARMv8.6 专用库。
7. 在 `androidResources.noCompress` 中增加 `gguf`。

验证：

- Gradle 能找到指定 NDK 与 CMake。
- `./gradlew :app:tasks` 配置阶段通过。
- 构建配置中只有 `arm64-v8a`。

提交建议：`build: configure MiniCPM-V native toolchain`

## 任务 3：建立最小 native 推理库

新增文件：

- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/cpp/minicpm_v46_jni.cpp`
- `app/src/main/cpp/logging.h`

修改文件：

- `app/build.gradle.kts`

步骤：

1. 使用固定 `llama.cpp-omni` 的公开 C/C++ API独立创建项目自己的 JNI 文件，并以固定官方 Demo 的输入输出行为做兼容性核对。
2. 将 JNI 符号绑定到 `com.rokid.cxrmsamples.minicpm.NativeMiniCpmBridge`，不保留 `com.example` 包名。
3. 只保留 MiniCPM-V 4.6 所需接口：backend 初始化、语言模型加载、mmproj 加载、版本设为 46、prepare、图片预填充、用户提示、逐 token 生成、取消、清空、卸载和 shutdown。
4. 删除上游模型下载、视频和 VoxCPM/TTS 相关 native 接口及链接目标。
5. 保留官方 MiniCPM-V 4.6 ChatML 提示格式；thinking 固定关闭，不注入默认 system prompt。
6. 固定 `N_THREADS=4`、`V46_CONTEXT_SIZE=8192`、`BATCH_SIZE=2048`、temperature `0.7`。
7. CMake 通过 `add_subdirectory` 引入固定 submodule，只链接 `llama`、`llama-common`、`mtmd`、`android` 和 `log`。
8. native 入口对重复 load、空指针、错误状态和重复 close 做保护，错误通过返回码和日志上报，不能 `abort()` App。

验证：

- 不放模型权重时执行 `./gradlew :app:assembleDebug --no-daemon --console=plain`。
- 用 `unzip -l` 确认 APK 中存在 `arm64-v8a` native 库且没有其他 ABI。
- 用 `readelf -d` 检查 JNI 主库依赖均随 APK 打包。
- 用 `nm -D` 或 `readelf -Ws` 确认 JNI 导出符号包名正确。

提交建议：`feat(minicpm): add native inference runtime`

## 任务 4：实现 Kotlin 引擎边界

新增文件：

- `app/src/main/java/com/rokid/cxrmsamples/minicpm/MiniCpmEngine.kt`
- `app/src/main/java/com/rokid/cxrmsamples/minicpm/NativeMiniCpmBridge.kt`
- `app/src/main/java/com/rokid/cxrmsamples/minicpm/NativeMiniCpmEngine.kt`
- `app/src/main/java/com/rokid/cxrmsamples/minicpm/MiniCpmConfig.kt`
- `app/src/test/java/com/rokid/cxrmsamples/minicpm/NativeEngineStateTest.kt`

步骤：

1. 定义可替换的 `MiniCpmEngine` 接口，方法与设计文档一致。
2. `NativeMiniCpmBridge` 只声明 JNI 方法和加载 native library，不持有 Android 页面对象。
3. `NativeMiniCpmEngine` 使用单线程 coroutine dispatcher 串行化全部 native 调用。
4. 明确状态转换：`Uninitialized -> Loading -> Ready -> Prefilling/Generating -> Ready -> Closing -> Closed`。
5. `cancelGeneration()` 既设置 Kotlin 取消标志，也调用 native cancel；迟到 token 通过 generation id 丢弃。
6. `clearConversation()` 只在 Ready 状态执行，清空 native KV/context 后仍保持模型加载。
7. `close()` 具备幂等性，并按 cancel、unload、shutdown 顺序释放资源。
8. 使用 fake bridge 编写状态转换、重复关闭、生成取消和错误恢复单元测试。

验证：

- `./gradlew :app:testDebugUnitTest --tests '*NativeEngineStateTest'` 通过。
- Kotlin 编译通过。

提交建议：`feat(minicpm): wrap native engine lifecycle`

## 任务 5：实现随 APK 打包的模型安装器

新增文件：

- `app/src/main/java/com/rokid/cxrmsamples/minicpm/BundledMiniCpmModelInstaller.kt`
- `app/src/main/java/com/rokid/cxrmsamples/minicpm/MiniCpmModelManifest.kt`
- `app/src/main/assets/minicpm-v-4.6/README.md`
- `app/src/test/java/com/rokid/cxrmsamples/minicpm/BundledModelInstallerTest.kt`

修改文件：

- `.gitignore`

步骤：

1. 在 manifest 类中写入固定 revision、文件名、字节数和 SHA-256。
2. 给安装器注入输入流来源与目标根目录，使核心复制逻辑可在 JVM 临时目录测试。
3. 安装目录固定为 `filesDir/models/minicpm-v-4.6/<revision>/`。
4. 复制到 `<name>.part`，同步报告当前文件和总体字节进度，同时增量计算 SHA-256。
5. 校验大小和 SHA-256 后执行同目录原子改名。
6. 已存在且 revision、长度和校验记录一致时跳过再次复制；缺失或损坏时只重建对应文件。
7. assets 缺失时返回结构化错误，不让普通 App 功能崩溃。
8. `.gitignore` 忽略 `app/src/main/assets/minicpm-v-4.6/*.gguf`，但保留 README。
9. 测试完整复制、复用已安装文件、中断留下 `.part`、长度错误、哈希错误、单文件重建和取消。

验证：

- `./gradlew :app:testDebugUnitTest --tests '*BundledModelInstallerTest'` 通过。
- 仓库状态不会列出本地 GGUF 权重。

提交建议：`feat(minicpm): install bundled model assets safely`

## 任务 6：实现设备检查和图片输入处理

新增文件：

- `app/src/main/java/com/rokid/cxrmsamples/minicpm/MiniCpmDeviceCheck.kt`
- `app/src/main/java/com/rokid/cxrmsamples/minicpm/MiniCpmImageLoader.kt`
- `app/src/test/java/com/rokid/cxrmsamples/minicpm/MiniCpmDeviceCheckTest.kt`

步骤：

1. 检查 `Build.SUPPORTED_64_BIT_ABIS` 是否包含 `arm64-v8a`。
2. 使用 `ActivityManager.MemoryInfo` 记录总内存、可用内存和 low-memory 标志。
3. 使用目标文件系统的可用空间与待复制剩余字节计算空间门槛，不写死 5 GB 作为程序判断值。
4. 设备检查结果作为 UI 可展示的数据结构，日志记录型号、ABI、RAM 和 CPU feature 摘要。
5. 图片使用 API 31 已有的 `ImageDecoder` 解码，使 EXIF 方向被正确处理。
6. 分别生成页面缩略图与受控大小 JPEG 推理字节；不把原始 Bitmap 保存进 ViewModel 状态。
7. 关闭所有流并及时回收不再使用的临时 Bitmap 引用。

验证：

- 单元测试覆盖 ABI 支持、空间边界和内存警告判断。
- 使用横竖方向不同的测试图片验证方向与缩放结果。

提交建议：`feat(minicpm): add device and image preparation`

## 任务 7：实现 ViewModel 与可测试状态机

新增文件：

- `app/src/main/java/com/rokid/cxrmsamples/activities/minicpm/MiniCpmDemoViewModel.kt`
- `app/src/main/java/com/rokid/cxrmsamples/activities/minicpm/MiniCpmDemoUiState.kt`
- `app/src/main/java/com/rokid/cxrmsamples/activities/minicpm/MiniCpmMessage.kt`
- `app/src/test/java/com/rokid/cxrmsamples/activities/minicpm/MiniCpmDemoViewModelTest.kt`

修改文件：

- `app/build.gradle.kts`，增加与当前 coroutine 版本对齐的测试依赖

步骤：

1. ViewModel 依赖安装器、图片加载器和 `MiniCpmEngine` 接口，不直接依赖具体 JNI 类。
2. 首次初始化依次执行设备检查、模型释放、模型加载，并将每一步转换为不可冲突的 UI 状态。
3. 发送时固化本轮文字和可选图片；有图片先预填充，随后发送本轮文字。
4. 第一批流式 token 到达时创建模型消息，之后按 generation id 增量更新。
5. 取消后保留非空部分回复并标记“已停止”；空回复直接移除。
6. 清空时取消当前任务、清 UI 消息、清图片并重置 native context。
7. `onCleared` 触发非阻塞的安全释放；Activity 的显式退出路径等待必要的 close 完成，但设置超时避免卡住返回。
8. 用 fake installer 和 fake engine 测试初始化成功/失败、图文发送顺序、流式输出、取消、清空、重复发送门禁和迟到 token。

验证：

- `./gradlew :app:testDebugUnitTest --tests '*MiniCpmDemoViewModelTest'` 通过。

提交建议：`feat(minicpm): add demo state machine`

## 任务 8：实现 Compose 演示页面

新增文件：

- `app/src/main/java/com/rokid/cxrmsamples/activities/minicpm/MiniCpmDemoActivity.kt`
- `app/src/main/java/com/rokid/cxrmsamples/activities/minicpm/MiniCpmDemoScreen.kt`
- `app/src/androidTest/java/com/rokid/cxrmsamples/activities/minicpm/MiniCpmDemoScreenTest.kt`

修改文件：

- `app/src/main/res/xml/file_paths.xml`，仅在现有规则不能覆盖临时拍照文件时修改

步骤：

1. Activity 注册 `GetContent` 相册选择器和 `TakePicture` 摄像头契约。
2. 拍照文件使用 App cache 目录与现有 FileProvider，不申请广泛存储权限。
3. Screen 实现初始化、错误和就绪三类主状态。
4. 就绪界面包含当前图片预览、消息列表、文字输入、相册、拍照、发送、停止和清空。
5. 初始化期间禁用媒体与发送操作；生成期间发送按钮切换为停止。
6. 返回时先请求取消并关闭引擎，再结束 Activity；释放超时写日志但不永久阻塞 UI。
7. 增加稳定的语义标签，编写 Compose 测试覆盖按钮状态和关键状态展示。

验证：

- Compose UI 测试通过。
- 无真实权重时页面能稳定展示“模型资产缺失”，返回不崩溃。

提交建议：`feat(minicpm): add local multimodal demo UI`

## 任务 9：接入 SDK 调试功能菜单

修改文件：

- `app/src/main/java/com/rokid/cxrmsamples/dataBeans/UsageType.kt`
- `app/src/main/java/com/rokid/cxrmsamples/activities/usageSelection/UsageSelectionActivity.kt`
- `app/src/main/java/com/rokid/cxrmsamples/activities/usageSelection/UsageSelectionViewModel.kt`
- `app/src/main/AndroidManifest.xml`

步骤：

1. 增加 `USAGE_TYPE_MINICPM_V46`。
2. 在 AI Scene 附近增加“MiniCPM-V 4.6 本地演示”按钮。
3. 在 `UsageSelectionViewModel.toUsage` 中启动 `MiniCpmDemoActivity`。
4. 在 Manifest 注册 Activity，`exported=false`、竖屏并沿用当前主题。
5. 保证入口不读取眼镜连接状态，也不创建 PSOP Repository。

验证：

- 从 PSOP 首页齿轮进入 SDK 调试菜单后能看到并打开演示入口。
- 断开眼镜和服务器后入口仍可打开。

提交建议：`feat(minicpm): expose demo in SDK settings menu`

## 任务 10：加入固定模型权重并构建超大 APK

本地文件，不提交 Git：

- `app/src/main/assets/minicpm-v-4.6/MiniCPM-V-4_6-Q4_K_M.gguf`
- `app/src/main/assets/minicpm-v-4.6/mmproj-model-f16.gguf`

步骤：

1. 从固定 Hugging Face revision 下载两个文件到临时目录。
2. 使用 `sha256sum` 校验设计文档中的两个 SHA-256，并核对准确字节数。
3. 校验成功后移动到 assets 目录；任何一项不匹配都停止，不构建 APK。
4. 执行 `git status --ignored` 确认权重被忽略。
5. 构建 debug APK，记录 APK 大小和构建耗时。
6. 用 `unzip -lv` 确认两个 `.gguf` 在 APK 内为 Store/uncompressed 且大小正确。
7. 执行 APK 签名校验。

验证：

- `git diff --check` 通过。
- `./gradlew :app:assembleDebug --no-daemon --console=plain` 通过。
- APK 同时包含两个正确权重和 `arm64-v8a` native 库。

权重不提交；代码提交建议：`docs(minicpm): document bundled model setup`

## 任务 11：小米 11 青春版真机验收

步骤：

1. 确认测试机显示约 8 GB RAM，并预留至少 5 GB 可用存储。
2. 使用 `adb install` 安装超大 debug APK；安装失败时保留完整 PackageManager 输出。
3. 首次进入演示页，记录模型释放进度、耗时、目标目录大小和 SHA-256 校验结果。
4. 记录 native 日志中的 ABI、backend、线程数、context、模型版本和 mmproj 加载结果。
5. 开启飞行模式并断开调试服务器，完成以下用例：
   - 纯文字：“请用中文介绍你自己。”
   - 普通图片：“请描述图片中的主要物体和环境。”
   - 含文字图片：“请读取图片里的文字并总结。”
   - 追问上一轮图片内容，验证多轮上下文。
6. 在生成中点击停止，然后立即发起新问题。
7. 清空会话并确认无需重新加载模型即可开始新会话。
8. 连续更换图片并完成至少 10 轮交互。
9. 退出页面，使用 `dumpsys meminfo` 和日志确认 native 资源释放且内存明显回落。
10. 记录模型释放时间、加载时间、图片预填充时间、首 token 延迟、tokens/s、峰值 PSS 和 10 轮后的温升体感。

通过标准：

- 全程不访问 PSOP 或模型服务器。
- 图文与纯文字回复均成功，中文问题默认得到中文回复。
- 没有 native crash、ANR、持续内存增长或无法再次生成的问题。
- 退出后现有 App 可继续进入 PSOP、ASR、TTS 和眼镜功能。

## 任务 12：完整回归与交付整理

涉及文件：

- `README.md`
- `docs/superpowers/specs/2026-09-10-minicpm-v46-local-demo-design.md`，只在实测结论改变设计时更新
- MiniCPM 相关来源说明与测试记录

步骤：

1. 运行全部 JVM 单元测试和相关 Compose 测试。
2. 执行 `git diff --check` 和 debug APK 构建。
3. 冒烟验证原有 SDK 调试选项、PSOP 模式选择、眼镜连接、相机权限、ASR 和 TTS。
4. README 补充本地权重准备、工具链版本、构建命令、磁盘需求和已验证设备。
5. 汇总已知限制：仅 arm64、CPU-only、超大 APK、首次复制耗时、单图、模型离开页面即释放。
6. 确认 Git 中没有 GGUF、APK、设备日志或其他大型生成物。

最终完成条件：

- 所有代码和文档提交均可由固定 submodule 与固定模型 revision 重现。
- 无权重构建可以验证普通功能与 native 编译；带权重构建在目标手机完成离线图文问答。
- 设计文档中的全部验收项均有实测结果或明确失败记录，不以“编译成功”代替真机部署成功。
