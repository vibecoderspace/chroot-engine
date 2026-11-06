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
 * Typical use:
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

    @Volatile
    private var activeImage: RootfsImage? = null

    /**
     * Stores the application context used by [runtime] and [processRunner].
     *
     * Call this once from [android.app.Application.onCreate] or before
     * using the no-argument helpers. Call [initialize] with an image after
     * installing the root filesystem to use [start] without passing it.
     */
    @JvmStatic
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Stores [context] and the [image] that [start] and [image] use.
     *
     * This is the usual call after [ChrootRuntime.install] returns.
     */
    @JvmStatic
    fun initialize(context: Context, image: RootfsImage) {
        appContext = context.applicationContext
        activeImage = image
    }

    /**
     * Binds [image] to the context previously passed to [initialize].
     *
     * Use this when the context was initialized once in `Application.onCreate`
     * and the image is installed later.
     */
    @JvmStatic
    fun initialize(image: RootfsImage) {
        requireContext()
        activeImage = image
    }

    /** The image bound by [initialize], or null when none is bound. */
    @JvmStatic
    fun image(): RootfsImage? = activeImage

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
     * Starts [spec] inside the image bound by [initialize].
     *
     * The caller owns the returned process and must call
     * [RunningProcess.stop] or [RunningProcess.awaitExit] when done.
     */
    @JvmStatic
    fun start(spec: ProcessSpec): RunningProcess {
        val image = requireImage()
        return processRunner().start(image, spec)
    }

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

    private fun requireImage(): RootfsImage = activeImage
        ?: throw IllegalStateException(
            "ChrootEngine.initialize(image) must be called before using " +
                "start(spec), or pass the image to start(image, spec).",
        )
}
