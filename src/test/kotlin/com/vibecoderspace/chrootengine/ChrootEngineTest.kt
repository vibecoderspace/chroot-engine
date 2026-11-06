package com.vibecoderspace.chrootengine

import com.vibecoderspace.chrootengine.process.ProcessSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ChrootEngineTest {

    @Test
    fun imageIsUnboundBeforeInitialization() {
        assertNull(ChrootEngine.image())
    }

    @Test
    fun startWithoutImageFailsWithAUsefulMessage() {
        val error = assertThrows(IllegalStateException::class.java) {
            ChrootEngine.start(ProcessSpec.shell("true"))
        }

        assertEquals(
            "ChrootEngine.initialize(image) must be called after installing " +
                "the root filesystem before starting a process.",
            error.message,
        )
    }
}
