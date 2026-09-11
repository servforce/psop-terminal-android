package com.rokid.cxrmsamples.activities.minicpm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniCpmDemoUiStateTest {
    @Test
    fun readyAndGeneratingAreMutuallyExclusive() {
        val ready = MiniCpmDemoUiState(phase = MiniCpmPhase.READY)
        val generating = MiniCpmDemoUiState(phase = MiniCpmPhase.GENERATING)

        assertTrue(ready.ready)
        assertFalse(ready.generating)
        assertFalse(generating.ready)
        assertTrue(generating.generating)
    }
}
