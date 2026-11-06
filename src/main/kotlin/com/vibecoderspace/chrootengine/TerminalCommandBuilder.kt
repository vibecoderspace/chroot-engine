package com.vibecoderspace.chrootengine

import java.io.File

/** Builds the chroot-ng argv for a PTY-backed terminal. */
internal object TerminalCommandBuilder {

    fun build(
        engine: EngineBinary,
        image: RootfsImage,
        guestCommand: List<String>,
        workingDirectory: String,
        environment: Map<String, String>,
        hostEnvironment: List<String>,
    ): ChrootTerminal {
        val guestEnvironment = TerminalEnvironment.guest(image.profile, environment)
        val argv = ArrayList<String>()

        argv.add(engine.file.absolutePath)
        argv.add("--link2symlink")
        argv.add("--fake-id")
        argv.add("0:0")
        argv.add("--shared-proc")
        argv.add("--work-dir")
        argv.add(workingDirectory)

        addBind(argv, "/sys", "/sys")
        addBind(argv, image.layout.homeDir.absolutePath, "/home")
        addBind(argv, image.layout.homeDir.absolutePath, "/root")

        for ((key, value) in guestEnvironment) {
            argv.add("-E")
            argv.add("$key=$value")
        }

        argv.add(image.rootfsDir.absolutePath)
        argv.addAll(guestCommand)

        return ChrootTerminal(
            shellPath = engine.file.absolutePath,
            cwd = image.layout.homeDir.absolutePath,
            argv = argv,
            env = hostEnvironment,
            guestCommand = guestCommand,
        )
    }

    private fun addBind(argv: MutableList<String>, host: String, guest: String) {
        if (!File(host).exists()) return
        argv.add("-b")
        argv.add("$host:$guest")
    }
}
