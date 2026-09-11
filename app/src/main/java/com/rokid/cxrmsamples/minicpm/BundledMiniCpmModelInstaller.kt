package com.rokid.cxrmsamples.minicpm

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class InstalledMiniCpmModels(
    val modelFile: File,
    val mmprojFile: File,
)

data class MiniCpmInstallProgress(
    val fileName: String,
    val copiedBytes: Long,
    val totalBytes: Long,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (copiedBytes.toDouble() / totalBytes).toFloat()
}

class BundledMiniCpmModelInstaller(private val context: Context) {
    private val modelDirectory = File(
        context.filesDir,
        "models/minicpm-v-4.6/${MiniCpmModelSpec.REVISION}",
    )

    fun install(onProgress: (MiniCpmInstallProgress) -> Unit): InstalledMiniCpmModels {
        val bundledNames = context.assets.list(MiniCpmModelSpec.ASSET_DIRECTORY)
            ?.toSet()
            .orEmpty()
        val missingAssets = MiniCpmModelSpec.assets
            .filterNot { it.fileName in bundledNames }
            .map { it.fileName }
        if (missingAssets.isNotEmpty()) {
            throw IOException("模型资产缺失：${missingAssets.joinToString()}。请按 assets 目录说明放入模型后重新构建 APK。")
        }

        if (!modelDirectory.exists() && !modelDirectory.mkdirs()) {
            throw IOException("无法创建模型目录：${modelDirectory.absolutePath}")
        }

        val assetsToCopy = MiniCpmModelSpec.assets.filterNot(::isInstalledAndValid)
        val requiredBytes = assetsToCopy.sumOf { it.sizeBytes } + COPY_HEADROOM_BYTES
        val availableBytes = StatFs(context.filesDir.absolutePath).availableBytes
        if (assetsToCopy.isNotEmpty() && availableBytes < requiredBytes) {
            throw IOException(
                "存储空间不足：需要约 ${formatGb(requiredBytes)} GB，当前可用 ${formatGb(availableBytes)} GB。",
            )
        }

        assetsToCopy.forEach { copyAndVerify(it, onProgress) }
        return InstalledMiniCpmModels(
            modelFile = File(modelDirectory, MiniCpmModelSpec.languageModel.fileName),
            mmprojFile = File(modelDirectory, MiniCpmModelSpec.visionModel.fileName),
        )
    }

    internal fun isInstalledAndValid(spec: MiniCpmAssetSpec): Boolean {
        val target = File(modelDirectory, spec.fileName)
        return target.isFile &&
            target.length() == spec.sizeBytes &&
            sha256(target).equals(spec.sha256, ignoreCase = true)
    }

    private fun copyAndVerify(
        spec: MiniCpmAssetSpec,
        onProgress: (MiniCpmInstallProgress) -> Unit,
    ) {
        val target = File(modelDirectory, spec.fileName)
        val part = File(modelDirectory, "${spec.fileName}.part")
        if (part.exists() && !part.delete()) {
            throw IOException("无法清理未完成的模型文件：${part.name}")
        }

        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        context.assets.open(
            "${MiniCpmModelSpec.ASSET_DIRECTORY}/${spec.fileName}",
            android.content.res.AssetManager.ACCESS_STREAMING,
        ).use { input ->
            part.outputStream().buffered(COPY_BUFFER_BYTES).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    copied += read
                    onProgress(MiniCpmInstallProgress(spec.fileName, copied, spec.sizeBytes))
                }
            }
        }

        val actualHash = digest.digest().toHex()
        if (copied != spec.sizeBytes || !actualHash.equals(spec.sha256, ignoreCase = true)) {
            part.delete()
            throw IOException(
                "${spec.fileName} 校验失败：大小 $copied，SHA-256 $actualHash。",
            )
        }

        if (target.exists() && !target.delete()) {
            part.delete()
            throw IOException("无法替换损坏的模型文件：${target.name}")
        }
        try {
            Files.move(
                part.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: IOException) {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered(COPY_BUFFER_BYTES).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun formatGb(bytes: Long): String = "%.2f".format(bytes / 1_073_741_824.0)

    private companion object {
        const val COPY_BUFFER_BYTES = 1024 * 1024
        const val COPY_HEADROOM_BYTES = 512L * 1024L * 1024L
    }
}
