package com.rokid.cxrmsamples.minicpm

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

class NativeMiniCpmBridge(context: Context) : MiniCpmEngine {
    private val cancelled = AtomicBoolean(false)

    init {
        val error = nativeInitialize(context.applicationInfo.nativeLibraryDir)
        check(error.isEmpty()) { error }
    }

    override fun load(models: InstalledMiniCpmModels) {
        val error = nativeLoad(
            modelPath = models.modelFile.absolutePath,
            mmprojPath = models.mmprojFile.absolutePath,
            contextSize = MiniCpmModelSpec.CONTEXT_SIZE,
            threads = MiniCpmModelSpec.DEFAULT_THREADS,
        )
        check(error.isEmpty()) { error }
    }

    override fun generate(
        prompt: String,
        encodedImage: ByteArray?,
        onToken: (String) -> Unit,
    ): MiniCpmGenerationResult {
        cancelled.set(false)
        if (encodedImage != null) {
            nativeSetImage(encodedImage).throwIfError()
        }
        nativeBeginPrompt(prompt, MiniCpmModelSpec.MAX_OUTPUT_TOKENS).throwIfError()

        while (nativeIsGenerating()) {
            val token = nativeNextToken() ?: break
            if (token.isNotEmpty()) onToken(token)
        }
        nativeTakeLastError().throwIfError()
        return MiniCpmGenerationResult(stopped = cancelled.get())
    }

    override fun cancelGeneration() {
        cancelled.set(true)
        nativeCancel()
    }

    override fun clearConversation() = nativeClear()

    override fun close() = nativeUnload()

    private fun String.throwIfError() {
        check(isEmpty()) { this }
    }

    private external fun nativeInitialize(nativeLibDir: String): String
    private external fun nativeLoad(
        modelPath: String,
        mmprojPath: String,
        contextSize: Int,
        threads: Int,
    ): String
    private external fun nativeSetImage(encodedImage: ByteArray): String
    private external fun nativeBeginPrompt(prompt: String, maxTokens: Int): String
    private external fun nativeNextToken(): String?
    private external fun nativeIsGenerating(): Boolean
    private external fun nativeTakeLastError(): String
    private external fun nativeCancel()
    private external fun nativeClear()
    private external fun nativeUnload()

    private companion object {
        init {
            System.loadLibrary("minicpm_v46")
        }
    }
}
