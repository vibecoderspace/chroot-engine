# chroot-engine

Android library that packages the **chroot-ng** AArch64 engine and installs an
official **Ubuntu 24.04.5** base root filesystem at runtime.

The rootfs is not bundled in the AAR. `RootfsInstaller` downloads it from the
official Ubuntu cdimage server, verifies its size and SHA-256, extracts it into
the app's private storage, and configures it. `ChrootProcessRunner` then starts
guest processes inside that image with chroot-ng.

## Contents

| Path | Description |
| --- | --- |
| `ChrootEngine` | Entry point: context, runtime, process runner |
| `ChrootRuntime` | Suspend install / reinstall / delete API |
| `RootfsInstaller` | Blocking installer and downloader |
| `RootfsProfile` / `DefaultProfiles` | Distro description and built-in Ubuntu profile |
| `RootfsLayout` / `RootfsImage` | Installed image layout |
| `TarExtractor.java` | Gzip/tar extractor |
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

### Install the rootfs

```kotlin
val runtime = ChrootEngine.runtime()
val image = runtime.install(DefaultProfiles.ubuntu) { progress ->
    // progress.stage is CHECKING, DOWNLOADING, VERIFYING,
    // EXTRACTING, CONFIGURING or READY.
    // progress.fraction is 0f..1f while downloading, otherwise -1f.
}
```

`install()` is idempotent: a valid installed image is reused, a changed config
version reconfigures it in place, and a different profile or archive replaces
the sandbox while keeping `home/`.

The blocking form is available for Java and plain threads:

```kotlin
val image = RootfsInstaller(context).install(DefaultProfiles.ubuntu)
```

### Start the engine

Bind the installed image to the engine once, then start processes without
repeating it:

```kotlin
ChrootEngine.initialize(this, image)

val process = ChrootEngine.start(
    ProcessSpec.shell("uname -a", shell = "/bin/bash"),
)

println(process.output.bufferedReader().readText())
val exitCode = process.awaitExit()
```

`initialize(image)` can also be called later when the context was already
initialized in `Application.onCreate`. `start(image, spec)` remains available
when no image is bound.

Long-running services can write straight to a log file:

```kotlin
val server = ChrootEngine.processRunner().start(
    image,
    ProcessSpec(
        command = listOf("/usr/bin/my-server", "--port", "4097"),
        workingDirectory = "/root",
        logFile = File(context.cacheDir, "server.log"),
    ),
)

server.stop()
```

`ProcessSpec` accepts a guest working directory, extra guest environment
variables, a log file, stderr merging, and additional engine arguments.

The host app owns the PTY. This version starts headless processes with pipes or
a log file; an interactive terminal needs a PTY layer supplied by the host.

## Rootfs layout

There is exactly one installed image:

```text
<private>/local/rootfs/
  sandbox/          extracted root filesystem        (engine rootfs)
  home/             user data                        (kept on reinstall)
  meta.properties   schema, profile, source, checksum
  .installed        commit marker, written last
  .install.lock     install serialization
  .failed           last failure, if any
```

`<private>` is `filesDir.parentFile` (`/data/user/0/<package>`). `home/` is
bound to both `/home` and `/root` in the guest, so files survive a reinstall.

Installing a different profile replaces `sandbox/` and leaves `home/` alone.
The legacy `filesDir/ubuntu-24.04.5` directory from 0.2.0 is removed on the
first new install.

## Rootfs source and verification

Ubuntu 24.04.5, pinned to the versioned release URL:

```text
https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.5/release/
  ubuntu-base-24.04.5-base-arm64.tar.gz
size   29,936,675 bytes
sha256 a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2
```

The download is written to `cacheDir/chroot-engine/*.part`, retried up to three
times, and rejected unless both the exact size and SHA-256 match. Extraction
happens in `sandbox.tmp`; the image is published only after the profile's
required paths validate. The `.installed` marker is written last.

The extractor handles symbolic links, hard links (falling back to symbolic
links when Android denies `link(2)`), PAX headers and GNU long names. All
rootfs deletions use `lstat`, so absolute symlinks inside the image are never
followed into the host filesystem.

## Profiles

| Profile | ID | Source |
| --- | --- | --- |
| `DefaultProfiles.ubuntu` | `ubuntu-24.04` | Ubuntu Base 24.04.5 (cdimage.ubuntu.com) |

A custom profile only describes the archive and the paths it must contain:

```kotlin
class MyProfile : RootfsProfile {
    override val id = "my-distro"
    override val displayName = "My distro"
    override val source = RootfsSource.Imported(File("/sdcard/my-rootfs.tar.gz"))
    override val requiredPaths = listOf("bin/sh")
    override val shell = "/bin/sh"
}
```

Profiles are the extension point for adding Alpine or another distribution
later; the installer does not need to change.

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

Without that setting Android may keep the library inside the APK instead of
extracting it, and `execve` will fail. `ChrootProcessRunner` reports this
explicitly when `libchroot-ng.so` is missing.

## Engine argv

The runner starts:

```text
libchroot-ng.so
  --link2symlink --fake-id 0:0 --shared-proc
  --work-dir <guest cwd>
  -b /sys:/sys
  -b <home>:/home
  -b <home>:/root
  -E HOME=/root -E USER=root -E LOGNAME=root -E SHELL=/bin/bash
  -E TERM=xterm-256color -E COLORTERM=truecolor
  -E LANG=C.UTF-8 -E LC_ALL=C.UTF-8 -E TMPDIR=/tmp
  -E PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
  <sandbox> <command...>
```

The engine synthesizes `/proc`, `/dev` and `/dev/shm`; only `/sys` is bound.
The host environment given to the engine is only `PATH=/system/bin` and
`TMPDIR=<filesDir>/tmp` (mode `0700`), because chroot-ng does not forward its
own environment to the guest.

## Dependency

The project publishes a release variant through `maven-publish`, so it can be
consumed from JitPack once the repository is tagged:

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.vibecoderspace:chroot-engine:0.3.1")
}
```

## Engine

- Built from `vibecoderspace/fake-chroot-ng` at commit `74bd597`.
- Size: `2,452,032` bytes
- SHA-256: `8d4920e96025ac604f0f1f791c2827c0a37972e448b3c58bb86d66495ff7d556`

`libchroot-ng.so` is a freestanding AArch64 executable, not a shared library.
The `lib` prefix and `.so` suffix are required for Android packaging; the
engine is launched directly by path rather than through `System.loadLibrary`.

## License

The library is licensed under the Apache License, Version 2.0. See `LICENSE`.

chroot-ng is Copyright 2026 Sylirre and is also licensed under Apache-2.0.
See `NOTICE` for attribution. The Ubuntu base image is downloaded from the
official Ubuntu cdimage server listed above and is not redistributed by this
project.
