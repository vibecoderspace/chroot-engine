package com.vibecoderspace.chrootengine

import android.content.Context

/**
 * Entry point for the bundled chroot-ng engine and Ubuntu root filesystem.
 *
 * The engine and rootfs are packaged as library resources, so callers only
 * need to initialize the engine with their application context. Extraction,
 * probing and process launching will be added in later versions.
 */
object ChrootEngine {

    /**
     * Asset name of the Ubuntu 24.04.5 arm64 base root filesystem.
     *
     * The `.tgz` extension is intentional: Android's asset merger transparently
     * uncompresses `.gz` assets, so a literal `.tar.gz` name would be packaged
     * as a 106 MB `*.tar` with the suffix removed.
     */
    const val ROOTFS_ASSET: String = "ubuntu-base-24.04.5-base-arm64.tgz"

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
