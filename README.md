# SmartRover7 Android BLE Controller

An Android application built using Jetpack Compose and Kotlin, designed to control a hardware robotic rover via Bluetooth Low Energy (BLE). The application handles BLE connection states, permission requests, location validation, and sends remote movement control packets to a receiver module (e.g., ESP32 or Arduino BLE).

## Features
- **Modern Jetpack Compose UI**: Dynamic dark-themed Material Design interface with state-driven transitions and loading/retry dialogues.
- **Robust BLE Connection Manager**: Handles scanning, connecting, service discovery, and automatic reconnection attempts (up to 3 times) upon connection loss.
- **Intelligent Permission Handling**: Evaluates system permissions for Bluetooth scan, connect, and fine location (required for BLE on Android), prompting settings guides if location services are disabled.
- **Background Beacon Service**: Integrates a background `BeaconService` capability for scanning BLE advertising beacons.

## Tech Stack
- **Kotlin** & **Coroutines** for asynchronous event flows and background tasking.
- **Jetpack Compose** for building declarative, state-driven interfaces.
- **Android Bluetooth Gentry APIs** for scanning and communication.
- **StateFlow / Flow** for reactive UI status binding.

## Project Structure
- `app/src/main/java/com/example/smartrover7/`
  - **`MainActivity.kt`**: Root activity managing layout states, Compose screens, permissions, and service triggers.
  - **`BleConnectionManager.kt`**: Encapsulates Bluetooth scan, connection hooks, MTU settings, and write characteristics.
  - **`BeaconService.kt`**: Background service layer for BLE scanning.
  - **`ui/theme/`**: Theme tokens including customized styling, typography, and color schemes.

## Getting Started

### Prerequisites
- Android Studio Ladybug (or newer)
- Android SDK 34 (Android 14) or higher
- A physical Android device with Bluetooth & GPS capability (BLE cannot be tested on an emulator)

### Build and Run
1. Open the project folder in Android Studio.
2. Allow Gradle to sync and download dependencies.
3. Enable developer mode and USB debugging on your physical Android device.
4. Click **Run** in Android Studio to build the APK and install it on your device.
5. Grant Location and Bluetooth permissions when prompted. Ensure your Rover's BLE transceiver is powered on.

## License
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
