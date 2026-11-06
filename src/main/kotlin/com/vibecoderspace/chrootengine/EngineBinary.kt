package com.vibecoderspace.chrootengine

import android.content.Context
import android.system.Os
import java.io.File

/**
 * The chroot-ng executable bundled in the app's native library directory.
 *
 * `libchroot-ng.so` is a freestanding AArch64 executable, not a shared object.
 * It must be extracted to disk so it can be launched directly by path, which
 * requires `jniLibs.useLegacyPackaging = true` in the host app.
 */
internal class EngineBinary(val file: File) {

    companion object {
        fun resolve(context: Context): EngineBinary {
            val directory = context.applicationInfo.nativeLibraryDir
                ?: throw IllegalStateException(
                    "The app has no native library directory; cannot locate " +
                        ChrootEngine.ENGINE_LIBRARY,
                )
            val file = File(directory, ChrootEngine.ENGINE_LIBRARY)
            if (!file.isFile) {
                throw IllegalStateException(
                    "chroot-ng engine not found at ${file.absolutePath}. " +
                        "Set android.packaging.jniLibs.useLegacyPackaging = true in " +
                        "the host app so native libraries are extracted to disk.",
                )
            }
            makeExecutable(file)
            return EngineBinary(file)
        }

        private fun makeExecutable(file: File) {
            if (!file.canExecute()) {
                file.setExecutable(true, false)
            }
            runCatching { Os.chmod(file.absolutePath, 0x1ed) } // 0755
        }
    }
}
