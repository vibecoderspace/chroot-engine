package com.vibecoderspace.chrootengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RootfsLayoutTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun pathsLiveUnderTheSingleImageDirectory() {
        val local = temporary.newFolder("local")
        val layout = RootfsLayout(local)

        assertEquals(local, layout.localDir)
        assertEquals(File(local, "rootfs"), layout.rootfsDir)
        assertEquals(File(layout.rootfsDir, "sandbox"), layout.sandboxDir)
        assertEquals(File(layout.rootfsDir, "sandbox.tmp"), layout.stagingDir)
        assertEquals(File(layout.rootfsDir, "home"), layout.homeDir)
        assertEquals(File(layout.rootfsDir, "meta.properties"), layout.metadataFile)
        assertEquals(File(layout.rootfsDir, ".installed"), layout.markerFile)
        assertEquals(File(layout.rootfsDir, ".install.lock"), layout.lockFile)
        assertEquals(File(layout.rootfsDir, ".failed"), layout.failedFile)
        assertEquals(local.parentFile, layout.privateDir)
    }

    @Test
    fun installedRequiresMarkerAndSandbox() {
        val layout = RootfsLayout(temporary.newFolder())

        assertFalse(layout.isInstalled())

        layout.sandboxDir.mkdirs()
        assertFalse(layout.isInstalled())

        layout.markerFile.writeText("")
        assertTrue(layout.isInstalled())
    }
}
