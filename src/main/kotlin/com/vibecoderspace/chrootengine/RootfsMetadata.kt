package com.vibecoderspace.chrootengine

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * Install metadata stored next to the root filesystem.
 *
 * The marker file is always written after this metadata, so an interrupted
 * install never looks complete.
 */
internal data class RootfsMetadata(
    val schema: Int,
    val id: String,
    val displayName: String,
    val source: String,
    val sha256: String?,
    val installedAt: Long,
    val configVersion: Int,
    val libraryVersion: String,
) {

    fun write(file: File) {
        file.parentFile?.mkdirs()
        val properties = Properties().apply {
            setProperty(KEY_SCHEMA, schema.toString())
            setProperty(KEY_ID, id)
            setProperty(KEY_DISPLAY_NAME, displayName)
            setProperty(KEY_SOURCE, source)
            sha256?.let { setProperty(KEY_SHA256, it) }
            setProperty(KEY_INSTALLED_AT, installedAt.toString())
            setProperty(KEY_CONFIG_VERSION, configVersion.toString())
            setProperty(KEY_LIBRARY_VERSION, libraryVersion)
        }

        val temporary = File(file.absolutePath + ".tmp")
        temporary.outputStream().use { properties.store(it, "chroot-engine rootfs") }
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (error: IOException) {
            // Some Android filesystems reject ATOMIC_MOVE; a plain replace is
            // still safe because the marker is written separately.
            if (file.exists() && !file.delete()) {
                throw IOException("Could not replace ${file.absolutePath}", error)
            }
            if (!temporary.renameTo(file)) {
                throw IOException("Could not finalize ${file.absolutePath}", error)
            }
        } finally {
            temporary.delete()
        }
    }

    companion object {
        const val SCHEMA: Int = 1
        const val LIBRARY_VERSION: String = "0.3.0"

        private const val KEY_SCHEMA = "schema"
        private const val KEY_ID = "id"
        private const val KEY_DISPLAY_NAME = "displayName"
        private const val KEY_SOURCE = "source"
        private const val KEY_SHA256 = "sha256"
        private const val KEY_INSTALLED_AT = "installedAt"
        private const val KEY_CONFIG_VERSION = "configVersion"
        private const val KEY_LIBRARY_VERSION = "libraryVersion"

        /** Returns null when the file is missing or unreadable. */
        fun read(file: File): RootfsMetadata? {
            if (!file.isFile) return null
            val properties = Properties()
            runCatching { file.inputStream().use { properties.load(it) } }
                .onFailure { return null }

            val id = properties.getProperty(KEY_ID) ?: return null
            return RootfsMetadata(
                schema = properties.getProperty(KEY_SCHEMA)?.toIntOrNull() ?: 0,
                id = id,
                displayName = properties.getProperty(KEY_DISPLAY_NAME) ?: id,
                source = properties.getProperty(KEY_SOURCE).orEmpty(),
                sha256 = properties.getProperty(KEY_SHA256),
                installedAt = properties.getProperty(KEY_INSTALLED_AT)?.toLongOrNull() ?: 0L,
                configVersion = properties.getProperty(KEY_CONFIG_VERSION)?.toIntOrNull() ?: 0,
                libraryVersion = properties.getProperty(KEY_LIBRARY_VERSION).orEmpty(),
            )
        }
    }
}
