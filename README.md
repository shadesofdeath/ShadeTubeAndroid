# ShadeTube for Android

Your Spotify library, played from YouTube — no ads, no Premium. Kotlin / Jetpack Compose port of
[ShadeTube for Windows](https://github.com/shadesofdeath/ShadeTube).

## Install

1. Download **`ShadeTube-<version>-android.apk`** from the [latest release](https://github.com/shadesofdeath/ShadeTubeAndroid/releases/latest).
2. Open it and allow installing apps from that source when Android asks (Android 9 or newer).

Updates: *Settings › About* checks this repository's releases and installs new versions in place.

## Türkçe

1. [Son sürümden](https://github.com/shadesofdeath/ShadeTubeAndroid/releases/latest) **`ShadeTube-<sürüm>-android.apk`** dosyasını indir.
2. Dosyayı aç; Android sorarsa bu kaynaktan uygulama yüklemeye izin ver (Android 9 veya üstü).

Güncellemeler: *Ayarlar › Hakkında* bu deponun sürümlerini kontrol eder ve yeni sürümü üstüne kurar.

## Build

```
./gradlew :app:assembleRelease
```

Release signing reads `keystore.properties` at the project root (see `app/build.gradle.kts`); without it the APK is
built unsigned. Every release must be signed with the same key, or the in-app updater refuses it.
