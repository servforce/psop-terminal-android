# Sherpa-ONNX 离线 ASR 模型文件

请将以下两个文件放到此目录：

1. `model.onnx` — SenseVoice Small FP32 模型（约 895MB）
2. `tokens.txt` — 词表文件

## 下载地址

从 GitHub Releases 下载：
```
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2
```

解压后取出 `model.onnx` 和 `tokens.txt` 放到此目录即可。

## 模型说明

- SenseVoice Small：支持中文、英文、粤语、日文、韩文 + 多种中文方言
- 支持 ITN（逆文本归一化）：自动将数字、日期等转为阿拉伯数字
- 支持语音情感识别和声学事件检测
- 模型体积约 895MB（FP32 完整版）

## 注意

- 此目录下的模型文件会打包进 APK
- APK 体积会增大约 900MB
- 首次安装启动时 App 自动从 assets 解压到内部存储
# Sherpa-ONNX 离线 ASR 模型文件

请将以下两个文件放到此目录：

1. `model.int8.onnx` — Paraformer Small int8 量化模型（约 79MB）
2. `tokens.txt` — 词表文件

## 下载地址

从 GitHub Releases 下载：
```
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2
```

解压后取出 `model.int8.onnx` 和 `tokens.txt` 放到此目录即可。

## 模型说明

- Paraformer Small：支持中文普通话 + 英文 + 多种方言（河南话、天津话、四川话等）
- 模型体积小（79MB vs SenseVoice 228MB），识别速度更快
- 完全满足短指令识别场景

## 注意

- 此目录下的模型文件会打包进 APK
- APK 体积会增大约 80MB
- 首次安装启动时 App 自动从 assets 解压到内部存储
