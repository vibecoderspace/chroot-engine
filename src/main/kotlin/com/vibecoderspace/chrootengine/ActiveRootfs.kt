package com.vibecoderspace.chrootengine

/**
 * The single root filesystem used by the engine.
 *
 * There is exactly one active image per process. [ChrootEngine] binds it after
 * installation and every process start reads it from here, so no caller can
 * run a process inside a different image.
 */
internal object ActiveRootfs {

    @Volatile
    var image: RootfsImage? = null
        private set

    fun bind(image: RootfsImage) {
        this.image = image
    }

    fun require(): RootfsImage = image
        ?: throw IllegalStateException(
            "ChrootEngine.initialize(image) must be called after installing " +
                "the root filesystem before starting a process.",
        )
}
