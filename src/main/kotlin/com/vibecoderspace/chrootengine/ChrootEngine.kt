package com.vibecoderspace.chrootengine

import android.content.Context
import com.vibecoderspace.chrootengine.process.ChrootProcessRunner
import com.vibecoderspace.chrootengine.process.ProcessSpec
import com.vibecoderspace.chrootengine.process.RunningProcess

/**
 * Entry point for the bundled chroot-ng engine and the Ubuntu root filesystem.
 *
 * The engine is packaged as a native library. The root filesystem is installed
 * on demand from the official Ubuntu cdimage server by [RootfsInstaller].
 */
object ChrootEngine {

    /** Native library name of the bundled chroot-ng engine. */
    const val ENGINE_LIBRARY: String = "libchroot-ng.so"

    @Volatile
    private var appContext: Context? = null

    /**
     * Stores the application context used by [runtime] and [processRunner].
     *
     * Call this once from [android.app.Application.onCreate] or before
     * using the no-argument helpers.
     */
    @JvmStatic
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /** Runtime backed by the context previously passed to [initialize]. */
    @JvmStatic
    fun runtime(): ChrootRuntime = runtime(requireContext())

    /** Runtime backed by [context]. */
    @JvmStatic
    fun runtime(context: Context): ChrootRuntime =
        DefaultChrootRuntime(context.applicationContext)

    /** Process runner backed by the context previously passed to [initialize]. */
    @JvmStatic
    fun processRunner(): ChrootProcessRunner = processRunner(requireContext())

    /** Process runner backed by [context]. */
    @JvmStatic
    fun processRunner(context: Context): ChrootProcessRunner =
        ChrootProcessRunner(context.applicationContext)

    /**
     * Starts [spec] inside [image] using the context stored by [initialize].
     *
     * The caller owns the returned process and must call
     * [RunningProcess.stop] or [RunningProcess.awaitExit] when done.
     */
    @JvmStatic
    fun start(image: RootfsImage, spec: ProcessSpec): RunningProcess =
        processRunner().start(image, spec)

    private fun requireContext(): Context = appContext
        ?: throw IllegalStateException(
            "ChrootEngine.initialize(context) must be called before " +
                "using the stored context.",
        )
}
