package com.vibecoderspace.chrootengine.process

import com.vibecoderspace.chrootengine.EngineBinary
import com.vibecoderspace.chrootengine.FakeProfile
import com.vibecoderspace.chrootengine.RootfsLayout
import com.vibecoderspace.chrootengine.fakeImage
import com.vibecoderspace.chrootengine.temporaryFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChrootCommandBuilderTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun buildsEngineArgv() {
        val layout = RootfsLayout(temporary.newFolder("local"))
        val image = fakeImage(layout, FakeProfile())
        val engineFile = temporaryFile(temporary.newFolder("engine"), "libchroot-ng.so")
        val spec = ProcessSpec(
            command = listOf("/bin/bash", "-lc", "echo hi"),
            workingDirectory = "/root",
            environment = mapOf("FOO" to "bar"),
            engineArgs = listOf("--no-proc"),
        )

        val argv = ChrootCommandBuilder.build(
            engine = EngineBinary(engineFile),
            image = image,
            spec = spec,
            guestEnvironment = linkedMapOf("HOME" to "/root", "FOO" to "bar"),
        )

        assertEquals(engineFile.absolutePath, argv.first())
        assertEquals(listOf("/bin/bash", "-lc", "echo hi"), argv.takeLast(3))
        assertEquals(image.rootfsDir.absolutePath, argv[argv.size - 4])

        for (expected in listOf(
            "--link2symlink",
            "--fake-id",
            "0:0",
            "--shared-proc",
            "--work-dir",
            "/root",
            "--no-proc",
            "HOME=/root",
            "FOO=bar",
            "${layout.homeDir.absolutePath}:/home",
            "${layout.homeDir.absolutePath}:/root",
        )) {
            assertTrue("Missing $expected in $argv", argv.contains(expected))
        }
    }

    @Test
    fun omitsBindsForMissingHostPaths() {
        val layout = RootfsLayout(temporary.newFolder("local"))
        val image = fakeImage(layout, FakeProfile())
        val engineFile = temporaryFile(temporary.newFolder("engine"), "libchroot-ng.so")
        image.layout.homeDir.delete()

        val argv = ChrootCommandBuilder.build(
            engine = EngineBinary(engineFile),
            image = image,
            spec = ProcessSpec(listOf("/bin/sh")),
            guestEnvironment = emptyMap(),
        )

        assertTrue(argv.none { it == "${layout.homeDir.absolutePath}:/home" })
        assertTrue(argv.none { it == "${layout.homeDir.absolutePath}:/root" })
    }
}
