package com.vibecoderspace.chrootengine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Provisions the single Linux root filesystem used by the host app.
 *
 * Exactly one image is installed at a time. [install] reuses a valid image or
 * replaces it when the profile or archive changed; [reinstall] always wipes the
 * sandbox; [delete] removes the image and its home directory.
 */
interface ChrootRuntime {

    /** The installed image, or null when nothing usable is present. */
    fun installedImage(): RootfsImage?

    /**
     * Installs [profile] if no usable image is present.
     *
     * When an image is already installed it is reused as-is, re-configured in
     * place if the profile's configuration changed, or replaced if the profile
     * or archive changed.
     */
    suspend fun install(
        profile: RootfsProfile = DefaultProfiles.ubuntu,
        onProgress: (InstallProgress) -> Unit = {},
    ): RootfsImage

    /**
     * Wipes and reinstalls the currently installed profile.
     *
     * @param keepHome keep `home/` when the sandbox is replaced.
     */
    suspend fun reinstall(
        keepHome: Boolean = true,
        onProgress: (InstallProgress) -> Unit = {},
    ): RootfsImage

    /** Removes the installed image, including `home/`. Cached archives stay. */
    suspend fun delete()
}

internal class DefaultChrootRuntime(
    context: Context,
    profiles: List<RootfsProfile> = DefaultProfiles.all,
) : ChrootRuntime {

    private val installer = RootfsInstaller(context.applicationContext, profiles)

    override fun installedImage(): RootfsImage? = installer.installedImage()

    override suspend fun install(
        profile: RootfsProfile,
        onProgress: (InstallProgress) -> Unit,
    ): RootfsImage = withContext(Dispatchers.IO) {
        installer.install(profile, onProgress = onProgress)
    }

    override suspend fun reinstall(
        keepHome: Boolean,
        onProgress: (InstallProgress) -> Unit,
    ): RootfsImage = withContext(Dispatchers.IO) {
        val image = installer.installedImage()
            ?: throw IllegalStateException("No image is installed")
        installer.install(
            profile = image.profile,
            keepHome = keepHome,
            force = true,
            onProgress = onProgress,
        )
    }

    override suspend fun delete() = withContext(Dispatchers.IO) {
        installer.uninstall(deleteHome = true)
    }
}
