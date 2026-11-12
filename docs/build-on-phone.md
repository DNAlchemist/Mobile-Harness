# Build from a phone

Run commands from the repository root.

## Local build

```bash
git submodule update --init --recursive
bash scripts/build-phone.sh
```

The phone script reduces Gradle memory/concurrency and disables automatic SDK
component downloads. The SDK/JDK alone are not sufficient: this app compiles
PRoot, its loader, android-shmem, talloc, and pocketspawn from source. Local
native builds also need a complete Android NDK and CMake that run on the host.
The Play button's `gradle --no-daemon assembleDebug` works without script-only
NDK flags: on ARM64 the project automatically selects the complete NDK at
`/opt/native-tools/android-ndk-r29` and reads its real revision. Explicit
`-PmhNdkPath` (or `MH_NDK_PATH`) and `-PmhNdkVersion` remain supported.
Desktop/CI builds retain NDK 26.1.10909125 as the default. CMake 3.22.1 must
also be installed; on this phone `local.properties` points `cmake.dir` to
`/opt/native-tools/cmake-3.22.1-linux-aarch64`.
Google's ordinary Linux NDK download is not a native ARM64-phone toolchain.

Memory/concurrency limits now live in `gradle.properties`, so they apply to
Play too. The phone script additionally cleans the host `LD_LIBRARY_PATH`.
The online debug APK is copied to `app/build/outputs/apk/debug/app-debug.apk`,
where Play looks for it. Only `app-online-debug.apk` is copied, never a stale
manually re-signed `*-update.apk`.

When the ignored local file `jvm-run-debug.keystore` exists, Gradle uses its
`androiddebugkey` alias with the standard Android debug password to sign debug
builds. This preserves the local update identity across Play builds. Do not
commit or distribute this private key. Without it Gradle uses its usual debug
keystore; a different key cannot update an already installed app. Do not work around a
missing NDK by removing the native targets; the resulting app cannot run its
Linux environment.

## GitHub-hosted build

`.github/workflows/build-apk.yml` builds on an Ubuntu GitHub runner instead.
It checks out the pinned native submodules, installs JDK 17 and the required
Android SDK/NDK/CMake, verifies the bundled agent against the checked-in SHA-256,
runs online debug unit tests, and uploads the debug APK.

After committing and pushing the workflow to your GitHub repository:

1. Open **Actions → Build Online Debug APK → Run workflow** (or push relevant
   source changes to `main`).
2. Wait for the build to finish successfully.
3. Download **mobile-harness-online-debug** from the run's **Artifacts** section
   and extract `app-online-debug.apk`.

A local workflow file is not a submitted GitHub job. You must push it first;
GitHub may also ask you to enable Actions for the fork. The APK uses a debug
signing key generated on the runner, not the signing identity of any existing
installed copy. Separate runs may have different keys, so an in-place update
is not guaranteed. Do not uninstall an existing app without backing up its data.

Output: `app/build/outputs/apk/online/debug/app-online-debug.apk`.
Online builds download the core Linux environment on first use.
