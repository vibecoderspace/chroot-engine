# chroot-engine

Android library that packages the **chroot-ng** AArch64 engine and an official
**Ubuntu 24.04.5** base root filesystem.

This is the initial skeleton: it exposes `ChrootEngine.initialize(context)` and
the packaged resources. Rootfs extraction, probing and process launching will be
added later.

## Contents

| Path | Description |
| --- | --- |
| `src/main/kotlin/com/vibecoderspace/chrootengine/ChrootEngine.kt` | Public entry point |
| `src/main/assets/ubuntu-base-24.04.5-base-arm64.tgz` | Official Ubuntu base rootfs |
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

The app **must** extract native libraries so the engine can be executed from
`nativeLibraryDir`:

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
    implementation("com.github.vibecoderspace:chroot-engine:0.1.0")
}
```

## Packaged artifacts

### Root filesystem

- Source:
  `https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/ubuntu-base-24.04.5-base-arm64.tar.gz`
- Size: `29,936,675` bytes
- SHA-256: `a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2`
- The checksum is also stored next to the asset as
  `ubuntu-base-24.04.5-base-arm64.tgz.sha256`.

The tarball is committed directly so consumers do not need Git LFS. It is
already gzip-compressed, so the size in the repository is unchanged.

The asset uses the `.tgz` extension instead of `.tar.gz` on purpose: Android's
asset merger automatically uncompresses assets whose extension is `.gz` and
drops that extension, which would turn the 30 MB archive into a 106 MB `*.tar`.
`.tgz` keeps the gzip-compressed bytes and the original filename intact.

### Engine

- Built from `vibecoderspace/fake-chroot-ng` at commit `74bd597`.
- Size: `2,452,032` bytes
- SHA-256: `8d4920e96025ac604f0f1f791c2827c0a37972e448b3c58bb86d66495ff7d556`

`libchroot-ng.so` is a freestanding AArch64 executable, not a shared library.
The `lib` prefix and `.so` suffix are required for Android packaging; the
library is launched directly by path rather than through `System.loadLibrary`.

## License

The library is licensed under the Apache License, Version 2.0. See `LICENSE`.

chroot-ng is Copyright 2026 Sylirre and is also licensed under Apache-2.0.
See `NOTICE` for attribution. The Ubuntu base image is redistributed from the
official Ubuntu cdimage server listed above.
