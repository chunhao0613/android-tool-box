# apk-extractor

Export apps already installed on your Android phone to APK files, over adb. Read-only: nothing on the phone is changed.

## Setup
1. Install [Android platform-tools](https://developer.android.com/tools/releases/platform-tools) and put `adb` on PATH (or set `ADB=C:\path\to\adb.exe`).
2. On the phone: Settings → About → tap Build number 7× → Developer options → enable **USB debugging**.
3. Connect by USB and tap **Allow** on the phone. `adb devices` should show `device`.
4. Python 3.8+, no extra packages.

## Usage
```bash
python apkx.py list sky                      # find the package name
python apkx.py pull com.tgc.sky.android      # -> out/com.tgc.sky.android-<version>/base.apk + split_*.apk
python apkx.py pull com.tgc.sky.android --bundle   # also out/<pkg>-<version>.apks
python apkx.py libs com.tgc.sky.android      # -> out/<pkg>-<version>/lib/arm64-v8a/*.so
```
`-s <serial>` picks a device when several are connected.

Modern Play Store apps are **split APKs** (`base.apk` + `split_config.arm64_v8a.apk` + …).
Native libraries usually live in the ABI split, which `libs` handles. Reinstall splits with
`adb install-multiple out/<pkg>-<ver>/*.apk`.

Only export apps you have installed yourself, for your own backup or research. Don't redistribute them.
