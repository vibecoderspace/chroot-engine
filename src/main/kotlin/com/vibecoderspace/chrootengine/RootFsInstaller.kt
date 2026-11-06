package com.vibecoderspace.chrootengine

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads and installs the Ubuntu 24.04.5 arm64 base root filesystem.
 *
 * The archive is fetched from the official Ubuntu cdimage URL, verified against
 * the published size and SHA-256, and then extracted into the app's private
 * files directory. Call [install] from a background thread: it performs network
 * I/O and extraction and may take a few minutes on a slow connection.
 *
 * The install is idempotent. A marker file inside the rootfs means repeated
 * calls return immediately after the first successful install.
 */
object RootFsInstaller {

    private const val TAG = "RootFsInstaller"
    private const val MARKER_FILE = ".chroot-engine-installed"
    private const val BUFFER_SIZE = 64 * 1024
    private const val PROGRESS_STEP = 512L * 1024L

    /** Stages reported through [ProgressListener]. */
    enum class Stage {
        DOWNLOADING,
        VERIFYING,
        EXTRACTING,
    }

    /**
     * Receives install progress. For [Stage.EXTRACTING] the byte counts are
     * both zero because the extractor does not expose per-entry progress.
     */
    fun interface ProgressListener {
        fun onProgress(stage: Stage, bytes: Long, total: Long)
    }

    /**
     * Installs the root filesystem if it is not already present and returns
     * its directory.
     *
     * @throws IOException when the download, verification or extraction fails.
     */
    @JvmStatic
    @JvmOverloads
    @Synchronized
    fun install(context: Context, listener: ProgressListener? = null): File {
        val appContext = context.applicationContext
        val rootfs = rootfsDir(appContext)
        if (isInstalled(appContext)) {
            return rootfs
        }

        val cache = File(appContext.cacheDir, "chroot-engine").apply { mkdirs() }
        val part = File(cache, "$ARCHIVE_NAME.part")
        part.delete()

        try {
            Log.i(TAG, "Downloading ${ChrootEngine.ROOTFS_URL}")
            download(part, listener)
            verify(part, listener)
            extract(part, rootfs, listener)
            Log.i(TAG, "Installed Ubuntu rootfs at $rootfs")
            return rootfs
        } finally {
            part.delete()
        }
    }

    /** True when a completed rootfs is present in the app's private storage. */
    @JvmStatic
    fun isInstalled(context: Context): Boolean {
        val rootfs = rootfsDir(context)
        return File(rootfs, MARKER_FILE).isFile && File(rootfs, "usr/bin/bash").isFile
    }

    /** Directory that holds the extracted Ubuntu root filesystem. */
    @JvmStatic
    fun rootfsDir(context: Context): File =
        File(context.applicationContext.filesDir, ChrootEngine.ROOTFS_DIR_NAME)

    // --------------------------------------------------------------- download

    private fun download(output: File, listener: ProgressListener?) {
        val connection = URL(ChrootEngine.ROOTFS_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "chroot-engine")
        connection.setRequestProperty("Accept", "application/octet-stream")

        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw IOException("Rootfs server returned HTTP $status")
            }

            listener?.onProgress(Stage.DOWNLOADING, 0, ChrootEngine.ROOTFS_SIZE)

            var copied = 0L
            var reported = 0L
            connection.inputStream.use { input ->
                BufferedInputStream(input, BUFFER_SIZE).use { buffered ->
                    FileOutputStream(output).use { out ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = buffered.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            copied += read
                            if (copied - reported >= PROGRESS_STEP) {
                                reported = copied
                                listener?.onProgress(
                                    Stage.DOWNLOADING, copied, ChrootEngine.ROOTFS_SIZE)
                            }
                        }
                        out.fd.sync()
                    }
                }
            }

            if (copied != ChrootEngine.ROOTFS_SIZE) {
                throw IOException(
                    "Incomplete rootfs download ($copied of ${ChrootEngine.ROOTFS_SIZE} bytes)")
            }
            listener?.onProgress(
                Stage.DOWNLOADING, copied, ChrootEngine.ROOTFS_SIZE)
        } finally {
            connection.disconnect()
        }
    }

    // --------------------------------------------------------------- verify

    private fun verify(archive: File, listener: ProgressListener?) {
        listener?.onProgress(Stage.VERIFYING, 0, ChrootEngine.ROOTFS_SIZE)

        val size = archive.length()
        if (size != ChrootEngine.ROOTFS_SIZE) {
            throw IOException("Rootfs has unexpected size $size")
        }

        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(archive).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }

        val actual = digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
        if (!actual.equals(ChrootEngine.ROOTFS_SHA256, ignoreCase = true)) {
            throw IOException(
                "Rootfs checksum mismatch (expected ${ChrootEngine.ROOTFS_SHA256}, got $actual)")
        }

        listener?.onProgress(Stage.VERIFYING, size, size)
    }

    // --------------------------------------------------------------- extract

    private fun extract(
        archive: File,
        rootfs: File,
        listener: ProgressListener?,
    ) {
        val parent = rootfs.parentFile
            ?: throw IOException("Rootfs has no parent directory: $rootfs")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Could not create $parent")
        }

        val staging = File(parent, "${rootfs.name}.tmp")
        deleteRecursively(staging)
        if (!staging.mkdirs() && !staging.isDirectory) {
            throw IOException("Could not create $staging")
        }

        try {
            listener?.onProgress(Stage.EXTRACTING, 0, 0)
            TarExtractor.extractGzip(archive, staging)

            if (!File(staging, "usr/bin/bash").isFile) {
                throw IOException("Extracted rootfs is missing /usr/bin/bash")
            }

            deleteRecursively(rootfs)
            if (!staging.renameTo(rootfs)) {
                throw IOException("Could not move $staging to $rootfs")
            }

            configure(rootfs)
            writeMarker(rootfs)
            listener?.onProgress(Stage.EXTRACTING, 0, 0)
        } catch (error: Throwable) {
            deleteRecursively(staging)
            throw error
        }
    }

    private fun configure(rootfs: File) {
        // tar's permission bits cannot express the sticky bit, so restore it
        // on the shared temporary directories.
        for (relative in arrayOf("tmp", "var/tmp")) {
            val directory = File(rootfs, relative)
            if (!directory.isDirectory) continue
            try {
                Os.chmod(directory.absolutePath, 0x03ff) // 01777
            } catch (error: Exception) {
                Log.w(TAG, "Could not chmod $directory", error)
            }
        }
    }

    private fun writeMarker(rootfs: File) {
        val marker = File(rootfs, MARKER_FILE)
        marker.writeText(
            buildString {
                appendLine("source=${ChrootEngine.ROOTFS_URL}")
                appendLine("sha256=${ChrootEngine.ROOTFS_SHA256}")
                appendLine("size=${ChrootEngine.ROOTFS_SIZE}")
            },
        )
    }

    private fun deleteRecursively(file: File) {
        if (!file.exists()) return
        if (file.isDirectory) {
            file.listFiles()?.forEach(::deleteRecursively)
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete()
    }

    private const val ARCHIVE_NAME = "ubuntu-base-24.04.5-base-arm64.tar.gz"
}
