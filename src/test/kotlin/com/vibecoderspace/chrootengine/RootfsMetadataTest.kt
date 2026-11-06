package com.vibecoderspace.chrootengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RootfsMetadataTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun roundTripsAllFields() {
        val file = File(temporary.newFolder(), "meta.properties")
        val metadata = RootfsMetadata(
            schema = RootfsMetadata.SCHEMA,
            id = "ubuntu-24.04",
            displayName = "Ubuntu 24.04.5",
            source = "https://example.com/rootfs.tar.gz",
            sha256 = TEST_SHA,
            installedAt = 1234L,
            configVersion = 2,
            libraryVersion = "0.4.0",
        )

        metadata.write(file)

        assertEquals(metadata, RootfsMetadata.read(file))
    }

    @Test
    fun readsNullForMissingFile() {
        assertNull(RootfsMetadata.read(File(temporary.newFolder(), "missing.properties")))
    }

    @Test
    fun readsNullWithoutAnId() {
        val file = File(temporary.newFolder(), "meta.properties")
        file.writeText("schema=1\nsource=https://example.com\n")

        assertNull(RootfsMetadata.read(file))
    }
}
