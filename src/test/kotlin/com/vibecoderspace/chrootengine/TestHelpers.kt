package com.vibecoderspace.chrootengine

import java.io.File

internal const val TEST_SHA = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"

internal class FakeProfile(
    override val id: String = "fake",
    override val displayName: String = "Fake",
    override val source: RootfsSource = RootfsSource.Remote(
        url = "https://example.com/rootfs.tar.gz",
        sha256 = TEST_SHA,
        size = 29_936_675L,
    ),
    override val requiredPaths: List<String> = emptyList(),
    override val configVersion: Int = 1,
    override val shell: String = "/bin/sh",
) : RootfsProfile

internal fun RootfsLayout.installMarker(
    profile: RootfsProfile,
    configVersion: Int = profile.configVersion,
) {
    sandboxDir.mkdirs()
    markerFile.writeText("")
    RootfsMetadata(
        schema = RootfsMetadata.SCHEMA,
        id = profile.id,
        displayName = profile.displayName,
        source = profile.source.label,
        sha256 = profile.source.sha256,
        installedAt = 1L,
        configVersion = configVersion,
        libraryVersion = RootfsMetadata.LIBRARY_VERSION,
    ).write(metadataFile)
}

internal fun fakeImage(layout: RootfsLayout, profile: RootfsProfile = FakeProfile()): RootfsImage {
    layout.sandboxDir.mkdirs()
    layout.homeDir.mkdirs()
    return RootfsImage(profile, layout, 1L)
}

internal fun temporaryFile(directory: File, name: String): File =
    File(directory, name).apply { writeText("") }
