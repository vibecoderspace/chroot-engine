# chroot-engine

Android library that packages the **chroot-ng** AArch64 engine and installs an
official **Ubuntu 24.04.5** base root filesystem at runtime.

The rootfs is no longer bundled in the AAR. `RootFsInstaller` downloads it from
the official Ubuntu cdimage server, verifies it, and extracts it into the app's
private storage.

## Contents

| Path | Description |
| --- | --- |
| `src/main/kotlin/com/vibecoderspace/chrootengine/ChrootEngine.kt` | Public entry point and rootfs constants |
| `src/main/kotlin/com/vibecoderspace/chrootengine/RootFsInstaller.kt` | Runtime download + extraction |
| `src/main/java/com/vibecoderspace/chrootengine/TarExtractor.java` | Gzip/tar extractor |
| `src/main/jniLibs/arm64-v8a/libchroot-ng.so` | chroot-ng AArch64 engine |

Only `arm64-v8a` is bundled. `minSdk` is 26.

## Usage

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ChrootEngine.initialize(this)
    }
}
```

Install the rootfs from a background thread:

```kotlin
Thread {
    RootFsInstaller.install(this) { stage, bytes, total ->
        // stage is DOWNLOADING, VERIFYING or EXTRACTING
        // bytes/total are 0 for EXTRACTING
        Log.d("rootfs", "$stage $bytes/$total")
    }
}.start()
```

`install()` is blocking and idempotent: once the rootfs is installed, later
calls return immediately. The extracted filesystem lives at:

```text
filesDir/ubuntu-24.04.5
```

A marker file inside that directory records the source URL, size and SHA-256.
The download is first written to `cacheDir/chroot-engine/*.part` and deleted
after a successful install.

## Rootfs source and verification

- URL:
  `https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/ubuntu-base-24.04.5-base-arm64.tar.gz`
- Size: `29,936,675` bytes
- SHA-256: `a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2`

`RootFsInstaller` rejects a download whose byte count or SHA-256 does not
match. The archive is extracted with the bundled `TarExtractor`, which handles
symbolic links, hard links (falling back to symbolic links when Android denies
`link(2)`), PAX headers and GNU long names.

## Native packaging requirement

The consuming app **must** extract native libraries so the engine can be
executed from `nativeLibraryDir`:

```kotlin
android {
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}
```

Without that setting Android may load the engine in place instead of extracting
it, and `execve` will fail.

## Dependency

The project publishes a release variant through `maven-publish`, so it can be
consumed from JitPack once the repository is tagged:

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.vibecoderspace:chroot-engine:0.2.0")
}
```

## Engine

- Built from `vibecoderspace/fake-chroot-ng` at commit `74bd597`.
- Size: `2,452,032` bytes
- SHA-256: `8d4920e96025ac604f0f1f791c2827c0a37972e448b3c58bb86d66495ff7d556`

`libchroot-ng.so` is a freestanding AArch64 executable, not a shared library.
The `lib` prefix and `.so` suffix are required for Android packaging; the
library is launched directly by path rather than through `System.loadLibrary`.

## License

The library is licensed under the Apache License, Version 2.0. See `LICENSE`.

chroot-ng is Copyright 2026 Sylirre and is also licensed under Apache-2.0.
See `NOTICE` for attribution. The Ubuntu base image is downloaded from the
official Ubuntu cdimage server listed above and is not redistributed by this
project.
