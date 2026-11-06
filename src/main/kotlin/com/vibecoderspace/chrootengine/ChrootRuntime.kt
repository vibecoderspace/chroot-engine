package com.vibecoderspace.chrootengine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The single chroot runtime used by the host app.
 *
 * Install one root filesystem once, then ask for a [ChrootTerminal] and bind it
 * to the PTY owned by a terminal such as Termux's `terminal-emulator` /
 * `TerminalView`. This module never creates or manages a process; the PTY
 * child is started by the terminal layer.
 *
 * There is no separate initialization step. [install] is idempotent and acts as
 * the global entry point:
 * ```
 * val image = ChrootRuntime.install(context)
 * val terminal = ChrootRuntime.terminal()
 * val session = terminal.bindToPty { shellPath, cwd, args, env ->
 *     TerminalSession(shellPath, cwd, args, env, 2000, client)
 * }
 * ```
 */
object ChrootRuntime {

    /** Native library name of the bundled chroot-ng engine. */
    const val ENGINE_LIBRARY: String = "libchroot-ng.so"

    private val lock = Any()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var activeImage: RootfsImage? = null

    /**
     * Installs or reuses the global image and binds it as the active container.
     *
     * This is [installBlocking] on [Dispatchers.IO]. It is safe to call on every
     * app start: a valid installed image is reused without downloading again.
     */
    @JvmStatic
    suspend fun install(
        context: Context,
        profile: RootfsProfile = DefaultProfiles.ubuntu,
        onProgress: (InstallProgress) -> Unit = {},
    ): RootfsImage = withContext(Dispatchers.IO) {
        installNow(context.applicationContext, profile, onProgress)
    }

    /**
     * Blocking form of [install] for Java and plain threads.
     *
     * The returned image becomes the single active image used by [terminal].
     */
    @JvmStatic
    @JvmOverloads
    fun installBlocking(
        context: Context,
        profile: RootfsProfile = DefaultProfiles.ubuntu,
        onProgress: (InstallProgress) -> Unit = {},
    ): RootfsImage = installNow(context.applicationContext, profile, onProgress)

    /** Wipes and reinstalls the active image, keeping `home/` by default. */
    @JvmStatic
    suspend fun reinstall(
        context: Context,
        keepHome: Boolean = true,
        onProgress: (InstallProgress) -> Unit = {},
    ): RootfsImage = withContext(Dispatchers.IO) {
        reinstallNow(context.applicationContext, keepHome, onProgress)
    }

    /** Blocking form of [reinstall] for Java and plain threads. */
    @JvmStatic
    @JvmOverloads
    fun reinstallBlocking(
        context: Context,
        keepHome: Boolean = true,
        onProgress: (InstallProgress) -> Unit = {},
    ): RootfsImage = reinstallNow(context.applicationContext, keepHome, onProgress)

    /** Removes the active image, including `home/`. Cached archives stay. */
    @JvmStatic
    suspend fun delete(context: Context) = withContext(Dispatchers.IO) {
        deleteNow(context.applicationContext)
    }

    /** Blocking form of [delete] for Java and plain threads. */
    @JvmStatic
    fun deleteBlocking(context: Context) {
        deleteNow(context.applicationContext)
    }

    /** The single active image, or null before [install]. */
    @JvmStatic
    fun image(): RootfsImage? = activeImage

    /**
     * Builds the chroot-ng command for a PTY-backed terminal.
     *
     * @param command guest argv; when empty, the profile's shell is started as
     *                a login shell.
     * @param workingDirectory guest working directory, for example `/root`.
     * @param environment extra guest environment variables.
     */
    @JvmStatic
    @JvmOverloads
    fun terminal(
        command: List<String> = emptyList(),
        workingDirectory: String = "/root",
        environment: Map<String, String> = emptyMap(),
    ): ChrootTerminal {
        val context = synchronized(lock) { appContext }
            ?: throw IllegalStateException(
                "ChrootRuntime.install(context) must be called before " +
                    "building a terminal.",
            )
        val image = requireImage()

        require(workingDirectory.startsWith("/")) {
            "workingDirectory must be an absolute guest path: $workingDirectory"
        }

        val guestCommand = if (command.isEmpty()) {
            listOf(image.profile.shell, "-l")
        } else {
            command
        }
        require(guestCommand.first().isNotBlank()) {
            "argv[0] must not be blank"
        }
        environment.keys.forEach { key ->
            require(key.isNotBlank()) { "environment key must not be blank" }
            require('=' !in key) { "environment key must not contain '=': $key" }
        }

        val engine = EngineBinary.resolve(context)
        return TerminalCommandBuilder.build(
            engine = engine,
            image = image,
            guestCommand = guestCommand,
            workingDirectory = workingDirectory,
            environment = environment,
            hostEnvironment = TerminalEnvironment.host(context),
        )
    }

    private fun installNow(
        appContext: Context,
        profile: RootfsProfile,
        onProgress: (InstallProgress) -> Unit,
    ): RootfsImage {
        val image = RootfsInstaller(appContext).install(
            profile = profile,
            onProgress = onProgress,
        )
        bind(appContext, image)
        return image
    }

    private fun reinstallNow(
        appContext: Context,
        keepHome: Boolean,
        onProgress: (InstallProgress) -> Unit,
    ): RootfsImage {
        val installer = RootfsInstaller(appContext)
        val current = activeImage ?: installer.installedImage()
            ?: throw IllegalStateException(
                "No root filesystem is installed; call " +
                    "ChrootRuntime.install(context) first.",
            )
        val image = installer.install(
            profile = current.profile,
            keepHome = keepHome,
            force = true,
            onProgress = onProgress,
        )
        bind(appContext, image)
        return image
    }

    private fun deleteNow(appContext: Context) {
        RootfsInstaller(appContext).uninstall(deleteHome = true)
        synchronized(lock) {
            this.appContext = appContext
            activeImage = null
        }
    }

    private fun bind(appContext: Context, image: RootfsImage) {
        synchronized(lock) {
            this.appContext = appContext
            activeImage = image
        }
    }

    private fun requireImage(): RootfsImage = activeImage
        ?: throw IllegalStateException(
            "No root filesystem is installed; call " +
                "ChrootRuntime.install(context) first.",
        )
}
