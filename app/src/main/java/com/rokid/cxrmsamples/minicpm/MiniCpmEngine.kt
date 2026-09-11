package com.rokid.cxrmsamples.minicpm

interface MiniCpmEngine : AutoCloseable {
    fun load(models: InstalledMiniCpmModels)

    fun generate(
        prompt: String,
        encodedImage: ByteArray?,
        onToken: (String) -> Unit,
    ): MiniCpmGenerationResult

    fun cancelGeneration()

    fun clearConversation()
}

data class MiniCpmGenerationResult(val stopped: Boolean)
