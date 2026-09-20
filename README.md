# CloudGrip

A highly customizable, floating virtual gamepad overlay optimized for cloud gaming platforms like NVIDIA GeForce NOW and Xbox Cloud Gaming.

## Features
- **Floating Overlay Engine**: Built with Jetpack Compose and `WindowManager`, allowing the gamepad to float seamlessly over any cloud gaming app.
- **Custom Layouts**: Drag-and-drop UI components to position buttons exactly where you need them.
- **System-Level Emulation Scaffolding**: Includes `GamepadController` logic designed to interface with `/dev/uinput` (requires root) to emulate a physical Xbox controller at the system level, ensuring cloud gaming apps recognize the inputs natively.
- **Automated CI/CD**: Fully configured GitHub Actions workflow to automatically build and output the Android APK on every push.

## Architecture
- **UI**: Jetpack Compose (Material 3)
- **Background Service**: Android Foreground Service with `SYSTEM_ALERT_WINDOW` permission.
- **Build System**: Gradle (Kotlin DSL)

## Getting Started
1. Clone the repository.
2. Open the project in Android Studio.
3. Build and run on an Android device or emulator (API 26+).
4. Grant the "Display over other apps" permission when prompted.
5. Click "Start Floating Gamepad" to launch the overlay.

## CI/CD
This repository uses GitHub Actions. Every push to the `main` branch triggers a build. You can download the compiled `app-debug.apk` from the Artifacts section of the GitHub Actions run.