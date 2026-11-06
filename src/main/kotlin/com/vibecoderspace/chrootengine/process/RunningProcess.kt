package com.vibecoderspace.chrootengine.process

import java.io.InputStream
import java.util.concurrent.TimeUnit

/** A process started inside an installed root filesystem. */
interface RunningProcess {
    val pid: Long
    val isAlive: Boolean

    /** Standard output, or an empty stream when output is redirected to a file. */
    val output: InputStream

    /** Stops the process, escalating to a forced kill after a short grace period. */
    fun stop()

    /** Blocks until the process exits and returns its exit code. */
    fun awaitExit(): Int
}

internal class ProcessBuilderProcess(
    private val process: Process,
) : RunningProcess {

    override val pid: Long get() = process.pid()

    override val isAlive: Boolean get() = process.isAlive

    override val output: InputStream get() = process.inputStream

    override fun stop() {
        if (!process.isAlive) return
        process.destroy()
        runCatching {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        }
    }

    override fun awaitExit(): Int = process.waitFor()
}
