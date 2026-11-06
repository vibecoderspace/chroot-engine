package com.vibecoderspace.chrootengine.process

import android.content.Context
import android.system.Os
import com.vibecoderspace.chrootengine.RootfsProfile
import java.io.File
import java.io.IOException

/** Builds the complete environment for a chroot-ng process. */
internal object ChrootEnvironment {

    private const val GUEST_PATH =
        "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    fun guest(profile: RootfsProfile, spec: ProcessSpec): Map<String, String> {
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
        environment.putAll(spec.environment)
        return environment
    }

    /**
     * The host environment given to the engine process.
     *
     * chroot-ng does not forward this array to the guest; guest variables are
     * passed as `-E` arguments. TMPDIR must be private because the engine uses
     * it for its /dev/shm stand-in and read-only /proc views.
     */
    fun host(context: Context): Map<String, String> = mapOf(
        "PATH" to "/system/bin",
        "TMPDIR" to tempDir(context).absolutePath,
    )

    private fun tempDir(context: Context): File {
        val directory = File(context.filesDir, "tmp")
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Could not create ${directory.absolutePath}")
        }
        runCatching { Os.chmod(directory.absolutePath, 0x1c0) } // 0700
        return directory
    }
}
