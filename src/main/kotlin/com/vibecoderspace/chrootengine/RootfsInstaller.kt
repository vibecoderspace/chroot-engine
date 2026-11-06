package com.vibecoderspace.chrootengine

import android.content.Context
import android.os.StatFs
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.channels.FileLock
import java.security.MessageDigest

/**
 * Downloads, extracts, and configures the single installed root filesystem.
 *
 * There is one image at [RootfsLayout.rootfsDir]. A different profile or a
 * different archive source replaces it; there is no per-profile directory.
 * User data in [RootfsLayout.homeDir] survives a replacement unless the caller
 * asks to delete it.
 *
 * [install] is blocking and serialized. Prefer [ChrootRuntime] when calling
 * from coroutines; this class is public for Java callers and plain threads.
 */
internal class RootfsInstaller(
    context: Context,
    private val profiles: List<RootfsProfile> = DefaultProfiles.all,
) {

    private val appContext: Context = context.applicationContext
    private val layout = RootfsLayout(RootfsLayout.localDir(appContext))
    private val monitor = Any()

    /** The installed image, or null when nothing usable is present. */
    fun installedImage(): RootfsImage? {
        if (!layout.isInstalled()) return null
        val metadata = RootfsMetadata.read(layout.metadataFile) ?: return null
        if (metadata.schema != RootfsMetadata.SCHEMA) return null
        val profile = profiles.firstOrNull { it.id == metadata.id } ?: return null
        if (!profile.validate(layout.sandboxDir)) return null
        return RootfsImage(profile, layout, metadata.installedAt)
    }

    /**
     * Installs [profile], replacing the current image when the profile, source,
     * schema, or configuration changed.
     *
     * @param keepHome keep `home/` when replacing the image.
     * @param force    wipe and reinstall even when the current image is valid.
     */
    @JvmOverloads
    fun install(
        profile: RootfsProfile = DefaultProfiles.ubuntu,
        keepHome: Boolean = true,
        force: Boolean = false,
        onProgress: (InstallProgress) -> Unit = {},
    ): RootfsImage = withInstallLock {
        onProgress(InstallProgress(InstallProgress.Stage.CHECKING))
        removeLegacyInstall()
        val metadata = RootfsMetadata.read(layout.metadataFile)

        when (decideInstall(layout, profile, metadata, force)) {
            InstallDecision.USE_EXISTING -> {
                val installed = checkNotNull(metadata)
                onProgress(InstallProgress(InstallProgress.Stage.READY, 1f))
                RootfsImage(profile, layout, installed.installedAt)
            }

            InstallDecision.RECONFIGURE -> {
                val installed = checkNotNull(metadata)
                onProgress(InstallProgress(InstallProgress.Stage.CONFIGURING))
                configure(profile)
                val updated = installed.copy(
                    schema = RootfsMetadata.SCHEMA,
                    configVersion = profile.configVersion,
                    libraryVersion = RootfsMetadata.LIBRARY_VERSION,
                )
                updated.write(layout.metadataFile)
                onProgress(InstallProgress(InstallProgress.Stage.READY, 1f))
                RootfsImage(profile, layout, updated.installedAt)
            }

            InstallDecision.REINSTALL -> reinstall(profile, keepHome, onProgress)
        }
    }

    /** Removes the image. Cached archives are left in the cache directory. */
    @JvmOverloads
    fun uninstall(deleteHome: Boolean = true) = withInstallLock {
        RootfsValidation.deleteRecursively(layout.sandboxDir)
        layout.markerFile.delete()
        layout.metadataFile.delete()
        layout.failedFile.delete()
        if (deleteHome) {
            RootfsValidation.deleteRecursively(layout.homeDir)
            RootfsValidation.deleteRecursively(layout.rootfsDir)
        }
        Unit
    }

    // ---------------------------------------------------------------- install

    private fun reinstall(
        profile: RootfsProfile,
        keepHome: Boolean,
        onProgress: (InstallProgress) -> Unit,
    ): RootfsImage {
        prepare()
        checkSpace(estimateArchiveBytes(profile) + ESTIMATED_EXTRACTED_BYTES)

        val archive = obtainArchive(profile, onProgress)
        try {
            checkSpace(archive.length() + ESTIMATED_EXTRACTED_BYTES)

            // Commit point starts here: once the marker is gone the image is
            // no longer considered installed, even if extraction fails.
            layout.markerFile.delete()
            layout.metadataFile.delete()
            layout.failedFile.delete()
            if (!keepHome) {
                RootfsValidation.deleteRecursively(layout.homeDir)
            }

            prepareSandbox()
            if (profile.source is RootfsSource.Imported) {
                validateArchive(archive)
            }

            onProgress(InstallProgress(InstallProgress.Stage.EXTRACTING))
            try {
                TarExtractor.extractGzip(archive, layout.stagingDir)
            } catch (error: IOException) {
                throw InstallException.Extraction(
                    "Could not extract ${profile.displayName}",
                    error,
                )
            }
            if (!profile.validate(layout.stagingDir)) {
                throw InstallException.Validation(
                    "${profile.displayName} is missing " +
                        profile.requiredPaths.joinToString(),
                )
            }

            RootfsValidation.deleteRecursively(layout.sandboxDir)
            if (!layout.stagingDir.renameTo(layout.sandboxDir)) {
                throw InstallException.Extraction(
                    "Could not move ${layout.stagingDir} to ${layout.sandboxDir}",
                )
            }

            onProgress(InstallProgress(InstallProgress.Stage.CONFIGURING))
            configure(profile)
            prepare()

            val installedAt = System.currentTimeMillis()
            RootfsMetadata(
                schema = RootfsMetadata.SCHEMA,
                id = profile.id,
                displayName = profile.displayName,
                source = profile.source.label,
                sha256 = profile.source.sha256,
                installedAt = installedAt,
                configVersion = profile.configVersion,
                libraryVersion = RootfsMetadata.LIBRARY_VERSION,
            ).write(layout.metadataFile)
            RootfsConfigurator.createEmptyFile(layout.markerFile)
            layout.failedFile.delete()

            onProgress(InstallProgress(InstallProgress.Stage.READY, 1f))
            Log.i(TAG, "${profile.displayName} installed at ${layout.sandboxDir}")
            return RootfsImage(profile, layout, installedAt)
        } catch (error: Throwable) {
            writeFailure(error)
            throw error
        } finally {
            RootfsValidation.deleteRecursively(layout.stagingDir)
            if (profile.source !is RootfsSource.Imported) {
                archive.delete()
            }
        }
    }

    private fun configure(profile: RootfsProfile) {
        profile.configure(
            RootfsContext(
                appContext = appContext,
                layout = layout,
                profile = profile,
            ),
        )
    }

    private fun prepare() {
        RootfsConfigurator.ensureDir(layout.rootfsDir)
        RootfsConfigurator.ensureDir(layout.homeDir)
    }

    private fun prepareSandbox() {
        RootfsValidation.deleteRecursively(layout.stagingDir)
        RootfsConfigurator.ensureDir(layout.stagingDir)
        val tmp = RootfsConfigurator.ensureDir(File(layout.stagingDir, "tmp"))
        RootfsConfigurator.chmod(tmp, 0x03ff) // 01777
    }

    private fun removeLegacyInstall() {
        val legacy = RootfsLayout.legacyDir(appContext)
        if (legacy.exists()) {
            Log.i(TAG, "Removing legacy rootfs at $legacy")
            RootfsValidation.deleteRecursively(legacy)
        }
    }

    private fun writeFailure(error: Throwable) {
        runCatching {
            layout.failedFile.writeText(
                "${error::class.java.name}: ${error.message}\n",
            )
        }
    }

    // ------------------------------------------------------------------ space

    private fun estimateArchiveBytes(profile: RootfsProfile): Long =
        when (val source = profile.source) {
            is RootfsSource.Imported -> source.file.length()
            is RootfsSource.Remote ->
                source.size
                    ?: cacheFile(profile).takeIf { it.isFile }?.length()
                    ?: ESTIMATED_ARCHIVE_BYTES
        }

    private fun checkSpace(requiredBytes: Long) {
        val available = runCatching {
            StatFs(layout.privateDir.absolutePath).availableBytes
        }.getOrDefault(Long.MAX_VALUE)
        if (available < requiredBytes) {
            throw InstallException.InsufficientSpace(requiredBytes, available)
        }
    }

    // ------------------------------------------------------------------ archive

    private fun cacheFile(profile: RootfsProfile): File {
        val suffix = profile.source.sha256?.take(12) ?: "archive"
        return File(
            File(appContext.cacheDir, CACHE_DIR),
            "${profile.id}-$suffix.tar.gz",
        )
    }

    private fun obtainArchive(
        profile: RootfsProfile,
        onProgress: (InstallProgress) -> Unit,
    ): File = when (val source = profile.source) {
        is RootfsSource.Remote -> {
            val cached = cacheFile(profile)
            if (cached.isFile && verify(cached, source)) {
                cached
            } else {
                cached.delete()
                download(source, cached, onProgress)
                cached
            }
        }

        is RootfsSource.Imported -> {
            if (!source.file.isFile) {
                throw InstallException.Network(
                    "Rootfs archive not found: ${source.file}",
                )
            }
            if (!verify(source.file, source)) {
                throw InstallException.Checksum(
                    "Size or SHA-256 mismatch for ${source.file.name}",
                )
            }
            source.file
        }
    }

    private fun download(
        source: RootfsSource.Remote,
        target: File,
        onProgress: (InstallProgress) -> Unit,
    ) {
        target.parentFile?.let { RootfsConfigurator.ensureDir(it) }

        var lastError: Throwable? = null
        for (attempt in 1..MAX_DOWNLOAD_ATTEMPTS) {
            try {
                downloadOnce(source, target, onProgress)
                onProgress(InstallProgress(InstallProgress.Stage.VERIFYING))
                if (verify(target, source)) {
                    return
                }
                target.delete()
                throw InstallException.Checksum(
                    "Size or SHA-256 mismatch for ${target.name}",
                )
            } catch (error: InstallException.Checksum) {
                lastError = error
                // One re-download is enough; a second identical failure means
                // the published checksum is wrong.
                if (attempt >= 2) break
            } catch (error: IOException) {
                lastError = error
                target.delete()
                if (attempt < MAX_DOWNLOAD_ATTEMPTS) {
                    runCatching { Thread.sleep(500L * attempt) }
                }
            }
        }
        throw lastError ?: InstallException.Network(
            "Could not download ${source.url}",
        )
    }

    private fun downloadOnce(
        source: RootfsSource.Remote,
        target: File,
        onProgress: (InstallProgress) -> Unit,
    ) {
        val part = File(target.absolutePath + ".part")
        part.delete()

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(source.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "chroot-engine")
                setRequestProperty("Accept", "application/octet-stream")
            }

            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw IOException("Server returned HTTP $status for ${source.url}")
            }

            val expected = source.size
            val contentLength = connection.contentLengthLong
            if (expected != null && contentLength > 0 && contentLength != expected) {
                throw IOException(
                    "Unexpected archive size $contentLength (expected $expected)",
                )
            }
            val total = expected ?: contentLength.takeIf { it > 0 } ?: -1L

            var copied = 0L
            var lastReport = 0L
            onProgress(
                InstallProgress(
                    InstallProgress.Stage.DOWNLOADING,
                    if (total > 0) 0f else -1f,
                ),
            )

            connection.inputStream.use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (copied - lastReport >= PROGRESS_STEP) {
                            lastReport = copied
                            onProgress(
                                InstallProgress(
                                    InstallProgress.Stage.DOWNLOADING,
                                    fraction(copied, total),
                                ),
                            )
                        }
                    }
                    output.flush()
                }
            }

            if (expected != null && copied != expected) {
                throw IOException(
                    "Incomplete download ($copied of $expected bytes)",
                )
            }
            if (expected == null && total > 0 && copied != total) {
                throw IOException(
                    "Incomplete download ($copied of $total bytes)",
                )
            }
            if (target.exists() && !target.delete()) {
                throw IOException("Could not replace $target")
            }
            if (!part.renameTo(target)) {
                throw IOException("Could not finalize $target")
            }
            onProgress(InstallProgress(InstallProgress.Stage.DOWNLOADING, 1f))
            Log.i(TAG, "Downloaded $copied bytes to $target")
        } finally {
            connection?.disconnect()
            part.delete()
        }
    }

    private fun verify(file: File, source: RootfsSource): Boolean {
        val expectedSha = source.sha256
        if (!expectedSha.isNullOrBlank() &&
            !sha256(file).equals(expectedSha, ignoreCase = true)
        ) {
            return false
        }
        if (source is RootfsSource.Remote &&
            source.size != null &&
            file.length() != source.size
        ) {
            return false
        }
        return true
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Rejects imported archives that are not plain, relative tar paths.
     *
     * Remote archives are pinned by SHA-256, so they are not inspected a second
     * time. TarExtractor also refuses escaping entries while unpacking.
     */
    private fun validateArchive(archive: File) {
        val listing = runCatching {
            java.util.zip.GZIPInputStream(archive.inputStream()).use { input ->
                val entries = ArrayList<String>()
                val buffer = ByteArray(512)
                while (true) {
                    var read = 0
                    while (read < buffer.size) {
                        val count = input.read(buffer, read, buffer.size - read)
                        if (count == -1) break
                        read += count
                    }
                    if (read < buffer.size) break
                    if (buffer.all { it.toInt() == 0 }) break

                    val name = String(buffer, 0, 100, Charsets.UTF_8)
                        .substringBefore('\u0000')
                    if (name.isNotEmpty()) entries.add(name)

                    val sizeField = String(buffer, 124, 12, Charsets.UTF_8)
                        .trim('\u0000', ' ')
                    val size = sizeField.toLongOrNull(8) ?: 0L
                    var remaining = (size + 511) / 512 * 512
                    val skip = ByteArray(512)
                    while (remaining > 0) {
                        val count = input.read(skip, 0, minOf(skip.size.toLong(), remaining).toInt())
                        if (count == -1) break
                        remaining -= count
                    }
                }
                entries
            }
        }.getOrElse { error ->
            throw InstallException.Extraction(
                "Could not inspect ${archive.name}",
                error,
            )
        }

        for (entry in listing) {
            if (entry.startsWith("/") || entry.split('/').any { it == ".." }) {
                throw InstallException.Extraction("Unsafe archive entry: $entry")
            }
        }
    }

    // --------------------------------------------------------------------- lock

    /**
     * Serializes installs across coroutines and, when the filesystem supports
     * it, across processes. The file lock is best-effort; the in-process
     * monitor always applies.
     */
    private fun <T> withInstallLock(block: () -> T): T = synchronized(monitor) {
        RootfsConfigurator.ensureDir(layout.rootfsDir)
        var handle: RandomAccessFile? = null
        var lock: FileLock? = null
        try {
            handle = runCatching { RandomAccessFile(layout.lockFile, "rw") }.getOrNull()
            lock = runCatching { handle?.channel?.lock() }.getOrNull()
            block()
        } finally {
            runCatching { lock?.release() }
            runCatching { handle?.close() }
        }
    }

    private fun fraction(copied: Long, total: Long): Float =
        if (total > 0) (copied.toFloat() / total).coerceIn(0f, 1f) else -1f

    private companion object {
        const val TAG = "RootfsInstaller"
        const val CACHE_DIR = "chroot-engine"
        const val BUFFER_SIZE = 64 * 1024
        const val PROGRESS_STEP = 512L * 1024L
        const val MAX_DOWNLOAD_ATTEMPTS = 3

        /** Used only when the archive size cannot be known before downloading. */
        const val ESTIMATED_ARCHIVE_BYTES = 64L * 1024L * 1024L

        /** Conservative upper bound for the unpacked base image. */
        const val ESTIMATED_EXTRACTED_BYTES = 200L * 1024L * 1024L
    }
}

internal enum class InstallDecision {
    USE_EXISTING,
    RECONFIGURE,
    REINSTALL,
}

/**
 * Decides whether the current image can be reused, only needs its
 * configuration refreshed, or must be replaced.
 */
internal fun decideInstall(
    layout: RootfsLayout,
    profile: RootfsProfile,
    metadata: RootfsMetadata?,
    force: Boolean = false,
): InstallDecision {
    if (force) return InstallDecision.REINSTALL
    if (!layout.isInstalled()) return InstallDecision.REINSTALL
    if (metadata == null) return InstallDecision.REINSTALL
    if (metadata.schema != RootfsMetadata.SCHEMA) return InstallDecision.REINSTALL
    if (!profile.validate(layout.sandboxDir)) return InstallDecision.REINSTALL
    if (metadata.id != profile.id) return InstallDecision.REINSTALL
    if (metadata.source != profile.source.label) return InstallDecision.REINSTALL
    if (metadata.sha256 != profile.source.sha256) return InstallDecision.REINSTALL
    if (metadata.configVersion != profile.configVersion) return InstallDecision.RECONFIGURE
    return InstallDecision.USE_EXISTING
}
