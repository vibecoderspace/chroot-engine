package com.vibecoderspace.chrootengine

import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File

/**
 * Rootfs checks that never follow a symbolic link out of the image.
 *
 * Base images contain absolute symlinks such as Alpine's
 * `bin/sh -> /bin/busybox`. `File.exists()` and `File.isDirectory()` follow
 * those links into the host filesystem, which is both wrong for validation and
 * dangerous when deleting a tree.
 */
internal object RootfsValidation {

    fun containsAll(rootDir: File, requiredPaths: List<String>): Boolean =
        requiredPaths.all { exists(rootDir, it) }

    fun exists(rootDir: File, relativePath: String): Boolean {
        val path = relativePath.trimStart('/')
        if (path.isEmpty()) return rootDir.isDirectory
        return lstat(File(rootDir, path)) != null
    }

    fun deleteRecursively(file: File) {
        val stat = lstat(file) ?: return
        if (OsConstants.S_ISDIR(stat.st_mode)) {
            file.listFiles()?.forEach(::deleteRecursively)
        }
        file.delete()
    }

    private fun lstat(file: File): StructStat? =
        runCatching { Os.lstat(file.absolutePath) }.getOrNull()
}
