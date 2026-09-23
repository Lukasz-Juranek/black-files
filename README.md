# Black Files

A minimal black Android file explorer.

- Browse internal storage and SD cards, with folders listed first
- Tap a file to open it. Long-press for Open with, Share, Copy, Move, Rename and Delete
- **Extract here** for `.zip`, `.tar`, `.tar.gz` and `.tgz`. It extracts into a new folder named after the archive
- **⋮ → Find duplicates** scans the current folder and its subfolders for files that have the same size and a
  similar name, like `photo.jpg`, `photo (1).jpg`, `photo - Copy.jpg`, `photo_2.jpg` or `Kopia photo.jpg`. The copies come pre-ticked
  and you review the list before anything is deleted
- New folder, show/hide hidden files, sort by name, date or size
- Remembers the last folder and where you were scrolled in each folder

It needs "All files access" on Android 11+, or the storage permission on Android 8–10. The app asks for it on first launch.

Built with Kotlin and Jetpack Compose. It needs Android 8.0 or newer.

## Building

The Android SDK is not needed locally. Every push to `main` builds a signed APK on GitHub Actions and
publishes it on the **Releases** page. Open that page on your phone and install `BlackFiles.apk`
(allow "install unknown apps" for your browser).

The signing key is stored in the `KEYSTORE_B64` / `KEYSTORE_PASSWORD` repo secrets. It's the same key as
Black Player's. Keep a backup of `keystore/release.p12`. It is gitignored.

### Optional local build (Docker)

```sh
docker run --rm -v "$PWD":/project -w /project mingc/android-build-box gradle assembleDebug
```
