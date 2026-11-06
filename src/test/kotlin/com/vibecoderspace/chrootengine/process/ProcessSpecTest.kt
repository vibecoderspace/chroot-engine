package com.vibecoderspace.chrootengine.process

import com.vibecoderspace.chrootengine.FakeProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProcessSpecTest {

    @Test
    fun shellWrapsScriptWithDashLc() {
        assertEquals(
            listOf("/bin/bash", "-lc", "uname -a"),
            ProcessSpec.shell("uname -a").command,
        )
    }

    @Test
    fun shellUsesTheRequestedShell() {
        assertEquals(
            listOf("/bin/sh", "-lc", "true"),
            ProcessSpec.shell("true", shell = "/bin/sh").command,
        )
    }

    @Test
    fun loginShellUsesTheProfileShell() {
        assertEquals(
            listOf("/bin/sh", "-l"),
            ProcessSpec.loginShell(FakeProfile(shell = "/bin/sh")).command,
        )
    }

    @Test
    fun rejectsEmptyCommand() {
        assertThrows(IllegalArgumentException::class.java) {
            ProcessSpec(emptyList())
        }
    }

    @Test
    fun rejectsBlankArgvZero() {
        assertThrows(IllegalArgumentException::class.java) {
            ProcessSpec(listOf(" "))
        }
    }

    @Test
    fun rejectsRelativeWorkingDirectory() {
        assertThrows(IllegalArgumentException::class.java) {
            ProcessSpec(listOf("/bin/sh"), workingDirectory = "root")
        }
    }

    @Test
    fun rejectsEnvironmentKeysWithEquals() {
        assertThrows(IllegalArgumentException::class.java) {
            ProcessSpec(listOf("/bin/sh"), environment = mapOf("A=B" to "c"))
        }
    }
}
