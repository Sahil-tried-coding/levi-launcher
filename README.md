# Levi TV Launcher

A lightweight Android TV home screen. The UI is a bundled HTML page shown in a WebView; the list of apps
comes from the native side, which asks PackageManager for every launchable TV app on the device.

## What it does
- **Apps are discovered from the TV.** `MainActivity` queries `LEANBACK_LAUNCHER` first, then `LAUNCHER`,
  de-duplicates by package, excludes Levi Home itself, and sorts the list. Apps installed or removed later are
  picked up automatically (package broadcasts plus a refresh on every resume).
- **Home**: a few favourites (ordered by `FAVOURITE_ORDER` in `MainActivity.java` when installed, otherwise
  alphabetical) and an **All Apps** tile.
- **All Apps**: every discovered app in a 6-column grid that scrolls with focus.
- **Settings** is a header tab that opens the Android settings screen.
- **Remote**: D-pad moves between rows and columns, OK activates, Back returns from All Apps to Home and never
  exits the launcher, and Home restores the last focused tile.
- **Safe bridge**: the page can only start packages that native discovery returned. Labels are passed as
  JSON and rendered as text, never as HTML.

## Performance changes
- The 55-particle canvas, the 5-second wallpaper slideshow, the blade-cut animation (which cloned scenes),
  the infinite border spinner, and the film-grain overlay are removed.
- Wallpapers are separate WebP files in `assets/wallpapers/` (previously base64 inside a 2.2 MB HTML file).
  Only the visible wallpaper is mounted.
- No external fonts or network access. The launcher has no INTERNET permission.
- Probing `images/levi-N.*` on startup is removed.

## Tuning
- `ROTATE_MS` in `index.html`: `0` keeps one stable wallpaper. A value such as `120000` enables a slow crossfade.
- `HOME_MAX` in `index.html`: how many favourites appear on Home.
- `FAVOURITE_ORDER` in `MainActivity.java`: preferred package order for favourites.

## Build (pick one)
A) Command line (JDK 17 + Android SDK + Gradle 8.7): `gradle assembleDebug`
   -> app/build/outputs/apk/debug/app-debug.apk
B) No local tools: push this folder to a GitHub repo; Actions > "Build APK" > download the `levi-launcher` artifact.

## Install and make default (from a PC on the same network as the TV)
    adb connect <TV-IP>:5555
    adb install -r app-debug.apk
    adb shell cmd package set-home-activity com.levi.tv/.MainActivity
    adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME

Note: this build changes the app's manifest (no INTERNET permission). If the install fails with a signature
mismatch, the previous debug build was signed with a different key: uninstall `com.levi.tv` first.

## Undo
    adb shell cmd package set-home-activity <stock launcher>
    (find it with: adb shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME)
    adb uninstall com.levi.tv
