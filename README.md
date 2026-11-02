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

## 手機 App（android/）

不需要電腦或 root，直接在手機上把已安裝的 App 匯出成 APK。

- 搜尋 App，可切換是否顯示系統 App
- 點一下 App：查看版本與大小，可「儲存」到任意位置或「分享」給其他 App
- 長按可多選，再按「匯出到資料夾」批次匯出
- 分割 APK 的 App 會打包成 `.apks`，可用 SAI 安裝；一般 App 則輸出單一 `.apk`

取得 APK：到 GitHub Actions 的「Build Android app」執行結果下載 `apk-extractor` artifact，或自行建置：
```bash
cd android && ./gradlew assembleRelease   # -> app/build/outputs/apk/release/app-release.apk
```
