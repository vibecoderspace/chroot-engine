package com.vibecoderspace.chrootengine

import java.io.File

/** An installed root filesystem that the host app can use. */
data class RootfsImage(
    val profile: RootfsProfile,
    val layout: RootfsLayout,
    val installedAt: Long,
) {
    val id: String get() = profile.id
    val rootfsDir: File get() = layout.sandboxDir
    val homeDir: File get() = layout.homeDir
}
