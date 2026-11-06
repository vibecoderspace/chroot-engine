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
 *
 * There is a single active image. Bind it once after installation, then every
 * process starts inside that image:
 * ```
 * ChrootEngine.initialize(context)
 * val image = ChrootEngine.runtime().install()
 * ChrootEngine.initialize(image)
 * ChrootEngine.start(ProcessSpec.shell("uname -a")).awaitExit()
 * ```
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

    /**
     * Stores [context] and binds [image] as the single active image.
     *
     * This is the usual call after [ChrootRuntime.install] returns.
     */
    @JvmStatic
    fun initialize(context: Context, image: RootfsImage) {
        appContext = context.applicationContext
        ActiveRootfs.bind(image)
    }

    /**
     * Binds [image] as the single active image.
     *
     * Use this when the context was initialized once in `Application.onCreate`
     * and the image is installed later. Re-binding replaces the previous image;
     * processes always use the currently bound one.
     */
    @JvmStatic
    fun initialize(image: RootfsImage) {
        requireContext()
        ActiveRootfs.bind(image)
    }

    /** The single active image, or null when none is bound. */
    @JvmStatic
    fun image(): RootfsImage? = ActiveRootfs.image

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
     * Starts [spec] inside the single active image.
     *
     * The caller owns the returned process and must call
     * [RunningProcess.stop] or [RunningProcess.awaitExit] when done.
     */
    @JvmStatic
    fun start(spec: ProcessSpec): RunningProcess {
        ActiveRootfs.require()
        return processRunner().start(spec)
    }

    private fun requireContext(): Context = appContext
        ?: throw IllegalStateException(
            "ChrootEngine.initialize(context) must be called before " +
                "using the stored context.",
        )
}
