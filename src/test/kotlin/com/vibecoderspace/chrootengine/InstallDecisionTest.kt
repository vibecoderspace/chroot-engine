package com.vibecoderspace.chrootengine

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InstallDecisionTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun layout(): RootfsLayout = RootfsLayout(temporary.newFolder())

    @Test
    fun forceAlwaysReinstalls() {
        val layout = layout()
        val profile = FakeProfile()
        layout.installMarker(profile)

        assertEquals(
            InstallDecision.REINSTALL,
            decideInstall(layout, profile, RootfsMetadata.read(layout.metadataFile), force = true),
        )
    }

    @Test
    fun missingMarkerReinstalls() {
        val layout = layout()
        val profile = FakeProfile()

        assertEquals(
            InstallDecision.REINSTALL,
            decideInstall(layout, profile, null),
        )
    }

    @Test
    fun missingMetadataReinstalls() {
        val layout = layout()
        val profile = FakeProfile()
        layout.sandboxDir.mkdirs()
        layout.markerFile.writeText("")

        assertEquals(
            InstallDecision.REINSTALL,
            decideInstall(layout, profile, null),
        )
    }

    @Test
    fun differentSchemaReinstalls() {
        val layout = layout()
        val profile = FakeProfile()
        layout.installMarker(profile)
        val metadata = checkNotNull(RootfsMetadata.read(layout.metadataFile))
            .copy(schema = RootfsMetadata.SCHEMA + 1)

        assertEquals(
            InstallDecision.REINSTALL,
            decideInstall(layout, profile, metadata),
        )
    }

    @Test
    fun differentProfileReinstalls() {
        val layout = layout()
        val profile = FakeProfile()
        layout.installMarker(profile)
        val other = FakeProfile(id = "other")

        assertEquals(
            InstallDecision.REINSTALL,
            decideInstall(layout, other, RootfsMetadata.read(layout.metadataFile)),
        )
    }

    @Test
    fun differentSourceReinstalls() {
        val layout = layout()
        val profile = FakeProfile()
        layout.installMarker(profile)
        val other = FakeProfile(
            source = RootfsSource.Remote("https://example.com/other.tar.gz", TEST_SHA, 1L),
        )

        assertEquals(
            InstallDecision.REINSTALL,
            decideInstall(layout, other, RootfsMetadata.read(layout.metadataFile)),
        )
    }

    @Test
    fun differentChecksumReinstalls() {
        val layout = layout()
        val profile = FakeProfile()
        layout.installMarker(profile)
        val other = FakeProfile(
            source = RootfsSource.Remote(profile.source.label, "0".repeat(64), 1L),
        )

        assertEquals(
            InstallDecision.REINSTALL,
            decideInstall(layout, other, RootfsMetadata.read(layout.metadataFile)),
        )
    }

    @Test
    fun differentConfigVersionReconfigures() {
        val layout = layout()
        val profile = FakeProfile()
        layout.installMarker(profile, configVersion = 1)
        val other = FakeProfile(configVersion = 2)

        assertEquals(
            InstallDecision.RECONFIGURE,
            decideInstall(layout, other, RootfsMetadata.read(layout.metadataFile)),
        )
    }

    @Test
    fun matchingImageIsReused() {
        val layout = layout()
        val profile = FakeProfile()
        layout.installMarker(profile)

        assertEquals(
            InstallDecision.USE_EXISTING,
            decideInstall(layout, profile, RootfsMetadata.read(layout.metadataFile)),
        )
    }
}
