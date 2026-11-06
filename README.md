# chroot-engine

Android library that packages the **chroot-ng** AArch64 engine, installs an
official **Ubuntu 24.04.5** base root filesystem at runtime, and prepares the
chroot command for a PTY-backed terminal.

The module does **not** create or manage processes and does **not** open a PTY.
It provides a global `ChrootRuntime` that installs one `RootfsImage` and a
`ChrootTerminal` whose `shellPath`, `cwd`, `argv` and `env` plug directly into
Termux's `terminal-emulator` / `terminal-view` `TerminalSession`.

## Contents

| Path | Description |
| --- | --- |
| `ChrootRuntime` | Global runtime: install, reinstall, delete, build terminal |
| `ChrootTerminal` | PTY-ready command for `TerminalSession` |
| `RootfsProfile` / `DefaultProfiles` | Distro description and built-in Ubuntu profile |
| `RootfsLayout` / `RootfsImage` | Installed image layout |
| `InstallProgress` / `InstallException` | Progress and typed failures |
| `TarExtractor.java` | Gzip/tar extractor |
| `src/main/jniLibs/arm64-v8a/libchroot-ng.so` | chroot-ng AArch64 engine |

Only `arm64-v8a` is bundled. `minSdk` is 26.

## Usage

There is no `initialize` call. `ChrootRuntime.install(context)` is the entry
point and installs the image **globally**:

```kotlin
val image = ChrootRuntime.install(context) { progress ->
    // progress.stage is CHECKING, DOWNLOADING, VERIFYING,
    // EXTRACTING, CONFIGURING or READY.
    // progress.fraction is 0f..1f while downloading, otherwise -1f.
}

println(image.profile.displayName)
println(image.layout.rootfsDir)
```

`install` is idempotent: a valid image is reused, a changed config version is
reconfigured in place, and a different profile or archive replaces the sandbox
while keeping `home/`. Call it on every app start to rebind the image after a
process restart.

Java and plain threads can use the blocking form:

```kotlin
RootfsImage image = ChrootRuntime.installBlocking(context);
```

### Build a terminal command

```kotlin
val terminal = ChrootRuntime.terminal()

// terminal.shellPath  chroot-ng executable
// terminal.cwd       host working directory
// terminal.argv      complete engine argv, including argv[0]
// terminal.env       host environment ("PATH=/system/bin", "TMPDIR=...")
// terminal.guestCommand  the guest argv
```

The default command is the profile's login shell (`/bin/bash -l` for Ubuntu).
Pass a different guest command, working directory or environment when needed:

```kotlin
val terminal = ChrootRuntime.terminal(
    command = listOf("/bin/bash", "-lc", "htop"),
    workingDirectory = "/root",
    environment = mapOf("TERM" to "screen-256color"),
)
```

### Bind to a PTY / Termux terminal-emulator

`bindToPty` hands the four `TerminalSession` constructor arguments to the
terminal layer. The terminal creates the PTY and execs the engine in the child:

```kotlin
val terminal = ChrootRuntime.terminal()

val session = terminal.bindToPty { shellPath, cwd, args, env ->
    TerminalSession(shellPath, cwd, args, env, 2000, sessionClient)
}

terminalView.attachSession(session)
```

The same values are available for apps that call the Termux JNI directly:

```kotlin
val argv = terminal.argv.toTypedArray()
val env = terminal.env.toTypedArray()
JNI.createSubprocess(terminal.shellPath, terminal.cwd, argv, env, pid, rows, cols, 0, 0)
```

The module never starts the process. The PTY owner is responsible for creating
the terminal session and for its lifecycle.

## Global image

There is exactly one active image per app process. `install` binds it and every
`ChrootRuntime.terminal()` call uses that image:

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

Recovery and removal:

```kotlin
// Wipe and reinstall the active profile. keepHome defaults to true.
val reinstalled = ChrootRuntime.reinstall(context)

// Remove the image and its home directory. Cached archives stay.
ChrootRuntime.delete(context)
```

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

ChrootRuntime.install(context, MyProfile())
```

Override `configure(context)` when the distribution needs extra files written
after extraction.

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
extracting it, and `execve` will fail. `ChrootRuntime.terminal()` reports this
explicitly when `libchroot-ng.so` is missing.

## Engine argv

`ChrootRuntime.terminal()` produces:

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
The host environment is only `PATH=/system/bin` and
`TMPDIR=<filesDir>/tmp` (mode `0700`), because chroot-ng does not forward its
own environment to the guest.

## Dependency

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.vibecoderspace:chroot-engine:0.4.0")
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
