# X Media DL Compose

Personal-use Android app for resolving downloadable media from public X/Twitter post links. The app is written with Kotlin and Jetpack Compose, with a small HTML prototype kept in the repository as the original UI/reference experiment.

## Features

- Paste an X/Twitter post URL from the clipboard.
- Resolve media through the SaveTwitter ajax endpoint.
- Show one highest-quality MP4 download per video.
- Show video cover downloads next to their video button.
- Show standalone photo downloads separately.
- Save videos and photos into the Android media library so they appear in the gallery.
- Handle Android back gestures from the result page back to the input page.

## Project Structure

- `app/` - Native Android app built with Kotlin and Jetpack Compose.
- `index.html` - Single-file HTML UI prototype.
- `server.mjs` - Local prototype server and resolver proxy used during the HTML experiment.

## Requirements

- Android Studio
- JDK 17
- Android SDK 35
- A connected Android device or emulator

## Build

Create a local `local.properties` file pointing to your Android SDK:

```properties
sdk.dir=C\:\\Users\\your-name\\AppData\\Local\\Android\\Sdk
```

Then build the debug APK:

```bash
./gradlew :app:assembleDebug
```

On Windows:

```powershell
.\gradlew.bat :app:assembleDebug
```

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Notes

This project is intended for personal use and experimentation. Respect the rights of content owners and the terms of the services involved. The resolver depends on a third-party SaveTwitter endpoint, so availability and behavior may change outside this app's control.
