package com.vibecoderspace.chrootengine

import android.content.Context
import android.system.Os
import java.io.File
import java.io.IOException

/** Builds the guest environment and the small host environment for chroot-ng. */
internal object TerminalEnvironment {

    private const val GUEST_PATH =
        "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    fun guest(
        profile: RootfsProfile,
        overrides: Map<String, String>,
    ): Map<String, String> {
        val environment = LinkedHashMap<String, String>()
        environment["HOME"] = "/root"
        environment["USER"] = "root"
        environment["LOGNAME"] = "root"
        environment["SHELL"] = profile.shell
        environment["TERM"] = "xterm-256color"
        environment["COLORTERM"] = "truecolor"
        environment["LANG"] = "C.UTF-8"
        environment["LC_ALL"] = "C.UTF-8"
        environment["TMPDIR"] = "/tmp"
        environment["PATH"] = GUEST_PATH
        environment.putAll(overrides)
        return environment
    }

    /**
     * The host environment given to the engine process.
     *
     * chroot-ng does not forward this array to the guest; guest variables are
     * passed as `-E` arguments. TMPDIR must be private because the engine uses
     * it for its /dev/shm stand-in and read-only /proc views.
     */
    fun host(context: Context): List<String> {
        val temp = tempDir(context)
        return listOf(
            "PATH=/system/bin",
            "TMPDIR=${temp.absolutePath}",
        )
    }

    private fun tempDir(context: Context): File {
        val directory = File(context.filesDir, "tmp")
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Could not create ${directory.absolutePath}")
        }
        runCatching { Os.chmod(directory.absolutePath, 0x1c0) } // 0700
        return directory
    }
}
