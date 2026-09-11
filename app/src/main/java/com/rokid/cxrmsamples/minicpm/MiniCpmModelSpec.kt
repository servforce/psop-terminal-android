package com.rokid.cxrmsamples.minicpm

data class MiniCpmAssetSpec(
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
)

object MiniCpmModelSpec {
    const val ASSET_DIRECTORY = "minicpm-v-4.6"
    const val REVISION = "afe9accb78d2995d214cd912920c9c92f4015faa"
    const val CONTEXT_SIZE = 8192
    const val MAX_OUTPUT_TOKENS = 512
    const val DEFAULT_THREADS = 4

    val languageModel = MiniCpmAssetSpec(
        fileName = "MiniCPM-V-4_6-Q4_K_M.gguf",
        sizeBytes = 529_101_504L,
        sha256 = "6b0c74962c44bc6bf4b655b9b02c13eda9d5a0491543ae976d1ac18e4b7892e2",
    )

    val visionModel = MiniCpmAssetSpec(
        fileName = "mmproj-model-f16.gguf",
        sizeBytes = 1_108_746_944L,
        sha256 = "ca931d861d0801d9003e50697cd764721a334107c0e0415a51168ee1938462de",
    )

    val assets = listOf(languageModel, visionModel)
    val totalSizeBytes = assets.sumOf { it.sizeBytes }
}
