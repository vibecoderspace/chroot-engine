package com.vibecoderspace.chrootengine

import com.vibecoderspace.chrootengine.profile.UbuntuProfile

/** Profiles shipped with the library. */
object DefaultProfiles {
    val ubuntu: RootfsProfile = UbuntuProfile()

    val all: List<RootfsProfile> = listOf(ubuntu)
}
