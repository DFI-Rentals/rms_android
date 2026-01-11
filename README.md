# DFI Rentals RMS - Android App

Android mobile application for the DFI Rentals Rental Management System (RMS).

## Overview

This app provides a native Android interface to access the DFI Rentals RMS web application (https://rms2.dfirentals.com) with additional mobile-optimized features:

- Full WebView integration with the RMS web app
- Scanner/Keyboard toggle for barcode scanner devices
- Automatic update system via GitHub releases
- Offline-capable with intelligent caching

## Features

- **WebView Integration**: Full access to all RMS web application features
- **Scanner Mode**: Hardware barcode scanner support for warehouse devices
- **Keyboard Mode**: On-screen keyboard for manual text input
- **Auto-Updates**: Automatically checks for and installs updates from GitHub releases
- **Session Management**: Maintains login sessions across app restarts

## Technical Stack

- **Language**: Java
- **Min SDK**: API 21 (Android 5.0 Lollipop)
- **Target SDK**: API 30 (Android 11)
- **Build System**: Gradle 6.7.1
- **Package**: com.dfirentals.rms

## Building the App

### Prerequisites

- JDK 11 or higher
- Android SDK Platform 30
- Android SDK Build-Tools 30.0.3

### Debug Build

```bash
export JAVA_HOME=/path/to/jdk-11
./gradlew assembleDebug
```

### Release Build

```bash
export JAVA_HOME=/path/to/jdk-11
./gradlew assembleRelease
```

## Version Management

Version information is configured in `app/build.gradle`:

```gradle
defaultConfig {
    versionCode 2        // Integer version for Play Store/updates
    versionName "1.1"    // Display version for users
}
```

**Important**: Increment both values when creating a new release.

## Creating a Release

### Automated Release (Recommended)

1. Update version in `app/build.gradle`
2. Commit and push changes
3. Create and push a version tag:
   ```bash
   git tag v1.2
   git push origin v1.2
   ```
4. GitHub Actions automatically builds and releases

## Auto-Update System

The app automatically checks for updates on startup by querying the GitHub releases API.

## Installation for Users

Users can download the APK from the RMS web application Downloads page.

## License

Proprietary - DFI Rentals © 2024
