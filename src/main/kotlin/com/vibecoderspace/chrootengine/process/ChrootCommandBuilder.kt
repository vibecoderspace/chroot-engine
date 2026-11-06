package com.vibecoderspace.chrootengine.process

import com.vibecoderspace.chrootengine.EngineBinary
import com.vibecoderspace.chrootengine.RootfsImage
import java.io.File

/** Builds the chroot-ng argv for a guest process. */
internal object ChrootCommandBuilder {

    fun build(
        engine: EngineBinary,
        image: RootfsImage,
        spec: ProcessSpec,
        guestEnvironment: Map<String, String>,
    ): List<String> {
        val args = ArrayList<String>()
        args.add(engine.file.absolutePath)
        args.add("--link2symlink")
        args.add("--fake-id")
        args.add("0:0")
        args.add("--shared-proc")
        args.add("--work-dir")
        args.add(spec.workingDirectory)

        addBind(args, "/sys", "/sys")
        addBind(args, image.layout.homeDir.absolutePath, "/home")
        addBind(args, image.layout.homeDir.absolutePath, "/root")

        args.addAll(spec.engineArgs)

        for ((key, value) in guestEnvironment) {
            args.add("-E")
            args.add("$key=$value")
        }

        args.add(image.rootfsDir.absolutePath)
        args.addAll(spec.command)
        return args
    }

    private fun addBind(args: MutableList<String>, host: String, guest: String) {
        if (!File(host).exists()) return
        args.add("-b")
        args.add("$host:$guest")
    }
}
