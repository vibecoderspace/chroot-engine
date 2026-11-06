package com.vibecoderspace.chrootengine.profile

import com.vibecoderspace.chrootengine.RootfsProfile
import com.vibecoderspace.chrootengine.RootfsSource

/**
 * Ubuntu 24.04.5 base image.
 *
 * The archive is Canonical's official Ubuntu Base root filesystem. The URL is
 * versioned rather than `noble/release` so the pinned size and checksum keep
 * matching after a later point release.
 */
class UbuntuProfile(
    override val source: RootfsSource = RootfsSource.Remote(
        url = ROOTFS_URL,
        sha256 = ROOTFS_SHA256,
        size = ROOTFS_SIZE,
    ),
) : RootfsProfile {

    override val id: String = "ubuntu-24.04"

    override val displayName: String = "Ubuntu 24.04.5"

    override val requiredPaths: List<String> =
        listOf("bin/sh", "bin/bash", "usr/bin/env")

    override val shell: String = "/bin/bash"

    companion object {
        const val ROOTFS_URL: String =
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.5/release/" +
                "ubuntu-base-24.04.5-base-arm64.tar.gz"

        const val ROOTFS_SHA256: String =
            "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"

        const val ROOTFS_SIZE: Long = 29_936_675L
    }
}
