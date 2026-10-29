# Levi TV Launcher

## Build (pick one)
A) Android Studio: File > Open this folder > Build > Build APK(s).
B) Command line (JDK 17 + Android SDK + Gradle 8.7): `gradle assembleDebug`
   -> app/build/outputs/apk/debug/app-debug.apk
C) No install: push this folder to a GitHub repo; Actions > "Build APK" > download artifact.

## Install and make default (from a PC on the same Wi-Fi)
adb connect 10.83.94.21:5555
adb install -r app-debug.apk
adb shell cmd package set-home-activity com.levi.tv/.MainActivity
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME

## Undo
adb shell cmd package set-home-activity <stock launcher>
(find it with: adb shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME)
adb uninstall com.levi.tv
