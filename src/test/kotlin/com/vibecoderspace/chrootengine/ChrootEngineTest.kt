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
            "ChrootEngine.initialize(image) must be called before using " +
                "start(spec), or pass the image to start(image, spec).",
            error.message,
        )
    }
}
