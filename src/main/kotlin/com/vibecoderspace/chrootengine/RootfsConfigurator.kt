package com.vibecoderspace.chrootengine

import android.system.Os
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Writes the files shared by the supported Linux images. */
internal object RootfsConfigurator {

    private const val TAG = "RootfsConfigurator"

    /** DNS servers written into every image. */
    private val DNS_SERVERS = listOf("1.1.1.1", "8.8.8.8")

    fun configure(context: RootfsContext) {
        val sandbox = context.layout.sandboxDir
        val etc = ensureDir(File(sandbox, "etc"))

        writeFile(File(etc, "hostname"), hostname())
        writeFile(File(etc, "resolv.conf"), resolvConf(DNS_SERVERS))
        writeFile(File(etc, "hosts"), hosts())

        appendAndroidGroups(File(etc, "group"))
        ensureDir(File(sandbox, "usr/local/bin"))
        for (relative in arrayOf("tmp", "var/tmp")) {
            chmod(ensureDir(File(sandbox, relative)), 0x03ff) // 01777
        }
        ensureDir(context.layout.homeDir)
    }

    internal fun hostname(): String = "localhost\n"

    internal fun resolvConf(dnsServers: List<String>): String =
        dnsServers.joinToString(separator = "\n", postfix = "\n") {
            "nameserver $it"
        }

    internal fun hosts(): String =
        "127.0.0.1   localhost\n" +
            "\n" +
            "# IPv6.\n" +
            "::1         localhost ip6-localhost ip6-loopback\n" +
            "fe00::0     ip6-localnet\n" +
            "ff00::0     ip6-mcastprefix\n" +
            "ff02::1     ip6-allnodes\n" +
            "ff02::2     ip6-allrouters\n" +
            "ff02::3     ip6-allhosts\n"

    internal fun androidGroupAdditions(gid: Long): List<String> = listOf(
        "inet:x:3003",
        "everybody:x:9997",
        "android_app:x:20455",
        "android_debug:x:50455",
        "android_cache:x:${10000L + gid}",
        "android_storage:x:${40000L + gid}",
        "android_media:x:${50000L + gid}",
        "android_external_storage:x:1077",
    )

    /**
     * Appends [additions] whose GID is not already present in [existing].
     *
     * Existing lines are preserved verbatim and the result always ends with a
     * newline when anything is written.
     */
    internal fun mergeGroups(existing: String, additions: List<String>): String {
        val knownGids = existing.lineSequence()
            .mapNotNull { line ->
                line.split(':').getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
            }
            .toMutableSet()

        val result = StringBuilder(existing)
        if (result.isNotEmpty() && result.last() != '\n') {
            result.append('\n')
        }
        for (addition in additions) {
            val gid = addition.split(':').getOrNull(2)?.trim().orEmpty()
            if (knownGids.add(gid)) {
                result.append(addition).append('\n')
            }
        }
        return result.toString()
    }

    private fun appendAndroidGroups(groupFile: File) {
        val gid = runCatching { Os.getgid() }.getOrDefault(0).toLong()
        val additions = androidGroupAdditions(gid)

        val existing = if (groupFile.isFile) {
            runCatching { groupFile.readText(StandardCharsets.UTF_8) }.getOrDefault("")
        } else {
            ""
        }

        val merged = mergeGroups(existing, additions)
        runCatching { writeFile(groupFile, merged) }
            .onFailure { Log.w(TAG, "Could not update ${groupFile.absolutePath}", it) }
    }

    internal fun ensureDir(dir: File): File {
        if (!dir.isDirectory && !dir.mkdirs()) {
            Log.w(TAG, "Failed to create ${dir.absolutePath}")
        }
        return dir
    }

    internal fun createEmptyFile(file: File): File {
        file.parentFile?.let { ensureDir(it) }
        if (!file.exists() && !file.createNewFile()) {
            throw IOException("Could not create ${file.absolutePath}")
        }
        return file
    }

    internal fun writeFile(file: File, content: String) {
        file.parentFile?.let { ensureDir(it) }
        // Delete first: some rootfs entries (for example /etc/resolv.conf) can be
        // symlinks, and following them would write outside the container.
        if (runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)) {
            file.delete()
        }
        if (file.exists() && !file.delete()) {
            throw IOException("Could not replace ${file.absolutePath}")
        }
        file.outputStream().use { out ->
            out.write(content.toByteArray(StandardCharsets.UTF_8))
        }
    }

    internal fun chmod(file: File, mode: Int) {
        runCatching { Os.chmod(file.absolutePath, mode) }
            .onFailure { Log.w(TAG, "chmod ${Integer.toOctalString(mode)} failed for $file", it) }
    }
}
