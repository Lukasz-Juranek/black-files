# Black Files

A minimal black Android file explorer.

- Browse internal storage and SD cards, with folders listed first
- Tap a file to open it. Long-press for Open with, Share, Copy, Move, Rename and Delete
- New folder, show/hide hidden files, sort by name, date or size
- Remembers the last folder and where you were scrolled in each folder
- **Updates itself.** On launch it checks this repo's latest GitHub release and offers to install it if it's
  newer. You can also use **⋮ → Check for updates**. The first time, Android asks you to allow
  "install unknown apps" for Black Files.

It needs "All files access" on Android 11+, or the storage permission on Android 8–10. The app asks for it on first launch.

Built with Kotlin and Jetpack Compose. It needs Android 8.0 or newer.

## Building

The Android SDK is not needed locally. Every push to `main` builds a signed APK on GitHub Actions and
publishes it on the **Releases** page as `v1.0.<run number>`. The in-app updater compares that number with
its own `versionCode`. Install `BlackFiles.apk` once from the Releases page, and after that the app updates itself.

The signing key is stored in the `KEYSTORE_B64` / `KEYSTORE_PASSWORD` repo secrets. It's the same key as
Black Player's. Keep a backup of `keystore/release.p12`. It is gitignored.

### Optional local build (Docker)

```sh
docker run --rm -v "$PWD":/project -w /project mingc/android-build-box gradle assembleDebug
```
