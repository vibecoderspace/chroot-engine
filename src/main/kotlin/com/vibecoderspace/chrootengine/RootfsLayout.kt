package com.vibecoderspace.chrootengine

import android.content.Context
import java.io.File

/**
 * Directory layout for the single installed root filesystem.
 *
 * There is exactly one image at `<private>/local/rootfs/`. Switching
 * distributions replaces the image in place; there is no per-profile directory.
 */
class RootfsLayout(val localDir: File) {

    val rootfsDir: File = File(localDir, "rootfs")
    val sandboxDir: File = File(rootfsDir, "sandbox")
    val stagingDir: File = File(rootfsDir, "sandbox.tmp")
    val homeDir: File = File(rootfsDir, "home")
    val metadataFile: File = File(rootfsDir, "meta.properties")
    val markerFile: File = File(rootfsDir, ".installed")
    val lockFile: File = File(rootfsDir, ".install.lock")
    val failedFile: File = File(rootfsDir, ".failed")

    /** `/data/data/<package>` (or `/data/user/0/<package>`). */
    val privateDir: File = localDir.parentFile ?: localDir

    fun isInstalled(): Boolean = markerFile.isFile && sandboxDir.isDirectory

    companion object {
        fun localDir(context: Context): File {
            val files = context.applicationContext.filesDir
            return File(files.parentFile ?: files, "local")
        }

        /** Location used by chroot-engine 0.2.0 and earlier. */
        fun legacyDir(context: Context): File =
            File(context.applicationContext.filesDir, "ubuntu-24.04.5")
    }
}
