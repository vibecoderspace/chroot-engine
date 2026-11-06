package com.vibecoderspace.chrootengine

import java.io.File

/** Where a root filesystem archive comes from. */
sealed interface RootfsSource {

    /** Human-readable source description, used in metadata and error messages. */
    val label: String

    /** Expected SHA-256, or null when the source is not pinned. */
    val sha256: String?

    /** A remote tarball downloaded on first install. */
    data class Remote(
        val url: String,
        override val sha256: String? = null,
        val size: Long? = null,
    ) : RootfsSource {
        override val label: String get() = url
    }

    /** An archive supplied by the host app, for example from a document picker. */
    data class Imported(
        val file: File,
        override val sha256: String? = null,
    ) : RootfsSource {
        override val label: String get() = file.absolutePath
    }
}
