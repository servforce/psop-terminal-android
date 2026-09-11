package com.rokid.cxrmsamples.minicpm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniCpmModelSpecTest {
    @Test
    fun pinnedAssetsMatchExpectedRelease() {
        assertEquals(
            "afe9accb78d2995d214cd912920c9c92f4015faa",
            MiniCpmModelSpec.REVISION,
        )
        assertEquals(529_101_504L, MiniCpmModelSpec.languageModel.sizeBytes)
        assertEquals(1_108_746_944L, MiniCpmModelSpec.visionModel.sizeBytes)
        assertEquals(1_637_848_448L, MiniCpmModelSpec.totalSizeBytes)
        MiniCpmModelSpec.assets.forEach { asset ->
            assertEquals(64, asset.sha256.length)
            assertTrue(asset.sha256.all { it in '0'..'9' || it in 'a'..'f' })
        }
    }

    @Test
    fun runtimeDefaultsAreBoundedForTargetPhone() {
        assertEquals(8192, MiniCpmModelSpec.CONTEXT_SIZE)
        assertEquals(512, MiniCpmModelSpec.MAX_OUTPUT_TOKENS)
        assertEquals(4, MiniCpmModelSpec.DEFAULT_THREADS)
    }
}
