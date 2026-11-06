package com.vibecoderspace.chrootengine

import android.content.Context

/**
 * Entry point for the bundled chroot-ng engine and the Ubuntu root filesystem.
 *
 * The engine is packaged as a native library. The root filesystem is installed
 * on demand from the official Ubuntu cdimage server by [RootFsInstaller].
 */
object ChrootEngine {

    /** Official Ubuntu 24.04.5 arm64 base root filesystem. */
    const val ROOTFS_URL: String =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/" +
            "ubuntu-base-24.04.5-base-arm64.tar.gz"

    /** SHA-256 of [ROOTFS_URL] at the time of writing. */
    const val ROOTFS_SHA256: String =
        "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"

    /** Exact size of [ROOTFS_URL] in bytes. */
    const val ROOTFS_SIZE: Long = 29_936_675L

    /** Directory name used for the installed root filesystem. */
    const val ROOTFS_DIR_NAME: String = "ubuntu-24.04.5"

    /** Native library name of the bundled chroot-ng engine. */
    const val ENGINE_LIBRARY: String = "libchroot-ng.so"

    @Volatile
    private var appContext: Context? = null

    /**
     * Stores the application context used by the engine.
     *
     * Call this once from [android.app.Application.onCreate] or before any
     * other engine API is used.
     */
    @JvmStatic
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }
}
