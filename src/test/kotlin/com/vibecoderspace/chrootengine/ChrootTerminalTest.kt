package com.vibecoderspace.chrootengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChrootTerminalTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun terminal(
        command: List<String> = listOf("/bin/sh", "-l"),
        environment: Map<String, String> = emptyMap(),
    ): ChrootTerminal {
        val layout = RootfsLayout(temporary.newFolder("local"))
        val image = fakeImage(layout, FakeProfile(shell = "/bin/sh"))
        val engine = temporaryFile(temporary.newFolder("engine"), "libchroot-ng.so")

        return TerminalCommandBuilder.build(
            engine = EngineBinary(engine),
            image = image,
            guestCommand = command,
            workingDirectory = "/root",
            environment = environment,
            hostEnvironment = listOf("PATH=/system/bin", "TMPDIR=/tmp"),
        )
    }

    @Test
    fun buildsTheEngineArgvForTermux() {
        val terminal = terminal(environment = mapOf("FOO" to "bar"))

        assertEquals(terminal.shellPath, terminal.argv.first())
        assertTrue(terminal.argv.containsAll(listOf(
            "--link2symlink",
            "--fake-id",
            "0:0",
            "--shared-proc",
            "--work-dir",
            "/root",
            "HOME=/root",
            "SHELL=/bin/sh",
            "FOO=bar",
        )))
        assertEquals(listOf("/bin/sh", "-l"), terminal.argv.takeLast(2))
        assertTrue(terminal.argv[terminal.argv.size - 3].endsWith("/sandbox"))
    }

    @Test
    fun bindsHomeAndSysWhenPresent() {
        val terminal = terminal()
        val home = terminal.cwd

        assertTrue(terminal.argv.contains("$home:/home"))
        assertTrue(terminal.argv.contains("$home:/root"))
        assertTrue(terminal.argv.contains("/sys:/sys"))
    }

    @Test
    fun bindToPtyPassesTheTerminalSessionArguments() {
        val terminal = terminal()

        var shellPath: String? = null
        var cwd: String? = null
        var args: List<String>? = null
        var env: List<String>? = null

        val session = terminal.bindToPty { path, directory, argv, environment ->
            shellPath = path
            cwd = directory
            args = argv.toList()
            env = environment.toList()
            "session"
        }

        assertEquals("session", session)
        assertEquals(terminal.shellPath, shellPath)
        assertEquals(terminal.cwd, cwd)
        assertEquals(terminal.argv, args)
        assertEquals(terminal.env, env)
        assertEquals(listOf("PATH=/system/bin", "TMPDIR=/tmp"), env)
    }

    @Test
    fun keepsTheRootfsBeforeTheGuestCommand() {
        val terminal = terminal(command = listOf("/usr/bin/env", "true"))

        assertTrue(terminal.argv[terminal.argv.size - 3].endsWith("/sandbox"))
        assertEquals(listOf("/usr/bin/env", "true"), terminal.argv.takeLast(2))
        assertEquals(listOf("/usr/bin/env", "true"), terminal.guestCommand)
    }
}
