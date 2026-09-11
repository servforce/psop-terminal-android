# MiniCPM-V 4.6 upstream provenance

This integration is derived from the Android demo maintained by OpenBMB.

- Demo repository: https://github.com/OpenBMB/MiniCPM-V-Apps
- Pinned demo commit: `cf4ebedde4fb9d7d6b7fc76c7f312018493c0b59`
- Native runtime repository: https://github.com/tc-mb/llama.cpp-omni
- Pinned runtime commit: `bebcf1676fe90db4dd9b4e9764d89e48ed52bdd9`
- Model repository: https://huggingface.co/openbmb/MiniCPM-V-4.6-gguf
- Pinned model revision: `afe9accb78d2995d214cd912920c9c92f4015faa`

The Android integration uses the public behavior of the upstream
`llama_jni.cpp`, `LlamaEngine.kt`, and `CpuFeatures.kt` as a compatibility
reference. The fixed MiniCPM-V-Apps commit does not contain a root license file,
so its source is not copied verbatim. The local JNI wrapper is implemented from
the MIT-licensed `llama.cpp-omni` APIs and keeps only the MiniCPM-V 4.6
image/text inference path.

`third_party/llama.cpp-omni` is a Git submodule. Its own MIT license and notices
are kept inside the submodule. The pinned GGUF model card declares the model
license as Apache-2.0.
