package com.vibecoderspace.chrootengine

/**
 * A chroot-ng command prepared for a PTY-backed terminal.
 *
 * [shellPath], [cwd], [argv] and [env] are exactly the arguments expected by
 * Termux's `com.termux.terminal.TerminalSession` constructor. [bindToPty] hands
 * them to the terminal implementation so the terminal can create its own PTY
 * and exec the engine on the slave side.
 *
 * The module does not start the process. It only prepares the command for the
 * layer that owns the PTY.
 */
class ChrootTerminal internal constructor(
    /** Absolute path of the chroot-ng executable. */
    val shellPath: String,

    /** Host working directory the PTY child starts in. */
    val cwd: String,

    /** Complete engine argv, including `argv[0]`. */
    val argv: List<String>,

    /** Complete host environment as `KEY=value` entries. */
    val env: List<String>,

    /** Guest argv, without the engine options and rootfs path. */
    val guestCommand: List<String>,
) {

    /**
     * Binds this command to a PTY-backed terminal session.
     *
     * The factory receives `shellPath`, `cwd`, `argv` and `env`; its return
     * value is returned unchanged. With Termux's terminal-emulator this is:
     * ```
     * val session = terminal.bindToPty { shellPath, cwd, args, env ->
     *     TerminalSession(shellPath, cwd, args, env, transcriptRows, client)
     * }
     * ```
     *
     * @param factory creates the terminal session. The terminal owns the PTY.
     */
    fun <T> bindToPty(factory: PtySessionFactory<T>): T =
        factory.create(
            shellPath = shellPath,
            cwd = cwd,
            args = argv.toTypedArray(),
            env = env.toTypedArray(),
        )

    /** Receives the arguments needed to start a PTY-backed terminal session. */
    fun interface PtySessionFactory<T> {
        fun create(
            shellPath: String,
            cwd: String,
            args: Array<String>,
            env: Array<String>,
        ): T
    }
}
