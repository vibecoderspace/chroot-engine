package com.vibecoderspace.chrootengine

/**
 * Progress reported while a root filesystem is being provisioned.
 *
 * [fraction] is in `0f..1f` while downloading and `-1f` when the step has no
 * meaningful progress value. [message] is an optional human-readable detail.
 */
data class InstallProgress(
    val stage: Stage,
    val fraction: Float = -1f,
    val message: String? = null,
) {
    enum class Stage {
        /** Reading the marker and metadata and deciding what to do. */
        CHECKING,

        /** Downloading or copying the archive. */
        DOWNLOADING,

        /** Checking the archive's size and SHA-256. */
        VERIFYING,

        /** Unpacking the archive into the sandbox. */
        EXTRACTING,

        /** Writing hostname, DNS, groups, and temporary directories. */
        CONFIGURING,

        /** Marker committed; the image is ready for the host app. */
        READY,
    }
}
