package com.vibecoderspace.chrootengine.process

import android.content.Context
import com.vibecoderspace.chrootengine.ActiveRootfs
import com.vibecoderspace.chrootengine.EngineBinary

/**
 * Starts processes inside the single active root filesystem with chroot-ng.
 *
 * The runner owns only process creation: it builds the engine argv and
 * environment, starts the process, and returns a [RunningProcess]. Root
 * filesystem installation stays in [com.vibecoderspace.chrootengine.RootfsInstaller].
 *
 * The image always comes from [ActiveRootfs]. There is no way to start a
 * process with another image; [com.vibecoderspace.chrootengine.ChrootEngine]
 * binds the installed image once and every runner uses it.
 *
 * The host app owns any PTY. This runner starts headless processes with pipes
 * or a log file, which is enough for commands and long-running services.
 */
class ChrootProcessRunner(context: Context) {

    private val appContext: Context = context.applicationContext
    private val engine = EngineBinary.resolve(appContext)

    /**
     * Starts [spec] inside the active image.
     *
     * The caller owns the returned process and must call
     * [RunningProcess.stop] or [RunningProcess.awaitExit] when done.
     */
    fun start(spec: ProcessSpec): RunningProcess {
        val image = ActiveRootfs.require()
        if (!image.layout.isInstalled()) {
            throw IllegalStateException(
                "No root filesystem is installed at ${image.layout.rootfsDir}",
            )
        }

        val guestEnvironment = ChrootEnvironment.guest(image.profile, spec)
        val hostEnvironment = ChrootEnvironment.host(appContext)
        val command = ChrootCommandBuilder.build(engine, image, spec, guestEnvironment)
        val workingDirectory = image.layout.homeDir.apply { mkdirs() }

        val builder = ProcessBuilder(command)
            .directory(workingDirectory)
            .redirectErrorStream(spec.redirectErrorStream)

        builder.environment().apply {
            clear()
            putAll(hostEnvironment)
        }

        spec.logFile?.let { logFile ->
            logFile.parentFile?.mkdirs()
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        }

        return ProcessBuilderProcess(builder.start())
    }
}
