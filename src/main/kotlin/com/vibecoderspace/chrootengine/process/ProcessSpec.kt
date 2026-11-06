package com.vibecoderspace.chrootengine.process

import com.vibecoderspace.chrootengine.RootfsProfile
import java.io.File

/**
 * A guest process to start inside an installed root filesystem.
 *
 * [command] is the guest argv and must include `argv[0]`. [workingDirectory] is
 * a guest path such as `/root`; the host working directory is handled by the
 * runner. [environment] overrides the default guest environment.
 */
data class ProcessSpec(
    val command: List<String>,
    val workingDirectory: String = "/root",
    val environment: Map<String, String> = emptyMap(),
    val logFile: File? = null,
    val redirectErrorStream: Boolean = true,
    /** Additional chroot-ng options inserted before the rootfs argument. */
    val engineArgs: List<String> = emptyList(),
) {
    init {
        require(command.isNotEmpty()) { "command must not be empty" }
        require(command.first().isNotBlank()) { "argv[0] must not be blank" }
        require(workingDirectory.startsWith("/")) {
            "workingDirectory must be an absolute guest path: $workingDirectory"
        }
        environment.keys.forEach { key ->
            require(key.isNotBlank()) { "environment key must not be blank" }
            require('=' !in key) { "environment key must not contain '=': $key" }
        }
    }

    companion object {
        /** Runs [script] with `shell -lc`. */
        fun shell(script: String, shell: String = "/bin/bash"): ProcessSpec =
            ProcessSpec(command = listOf(shell, "-lc", script))

        /** Starts the profile's shell as a login shell. */
        fun loginShell(profile: RootfsProfile): ProcessSpec =
            ProcessSpec(command = listOf(profile.shell, "-l"))
    }
}
