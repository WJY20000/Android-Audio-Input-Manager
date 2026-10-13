# 🎙️ Android Audio Input Manager

A simple yet powerful Android utility to help you manage your device's audio input source. Perfect for gamers, streamers, and anyone who wants more control over whether their phone uses the built-in mic or a connected Bluetooth headset.




---

## 🚀 Key Features

*   **🎧 Device Discovery:** Automatically scans and lists all available audio inputs, including the internal microphone, wired headsets, Bluetooth Classic (SCO) **and Bluetooth LE Audio** headsets (Android 15-ready), and USB devices.
*   **✅ Set Preference:** Choose an input device from the list. The app will then run a background service to strongly suggest to the Android system that your chosen device should be used.
*   **🔄 Revert to Default:** A one-tap button to stop the service and instantly return all audio control to the standard Android OS behavior.
*   **✨ Modern UI:** Built with a clean splash screen and modern Material Design components for a great user experience.

---

## 🔧 How It Works (An Easy Explanation)

This app uses official Android APIs to influence, but not force, the microphone selection.

#### What it Does
When you select a device and tap "Set," the app starts a foreground service. On Android 12+ it targets the **exact device you picked** via `AudioManager.setCommunicationDevice()`; on older versions it falls back to the legacy Bluetooth SCO APIs. The service also registers an `AudioDeviceCallback`, so if you plug in/unplug a headset or a Bluetooth device connects or drops, your chosen routing is **re-applied automatically** — the preference genuinely stays active, and most apps (games, voice recorders) follow this system-level routing hint.

> **Important Limitation:** This app cannot *force* another app to use a specific microphone. Android's security model prevents one app from hijacking another's audio stream. Our method works for the vast majority of apps, but some may be hard-coded to ignore system suggestions.

---

## 📋 Prerequisites

Before you begin, ensure you have the following installed and set up:

*   **Android Studio**: Version `Electric Eel` or newer.
*   **JDK 11**: The project is configured to use Java Development Kit version 11 for Gradle.
*   **Android Device**: A physical device running Android 10 (API 29) or higher.
*   **Bluetooth Headset**: A headset with a microphone is required to test the core functionality.

---

## ⚙️ Setup and Installation

You can build and run this project from the source code.

1.  **Clone the Repository**
    Open your terminal or command prompt and run:
    ```
    git clone https://github.com/pvmeht/android-audio-input-manager.git
    ```

2.  **Open in Android Studio**
    *   Launch Android Studio.
    *   Go to `File > Open` and select the cloned project folder.
    *   Allow Gradle to sync and download all the necessary project dependencies.

3.  **Build and Run**
    *   Connect your Android device that meets the prerequisites.
    *   Click the **Run 'app'** button (the green play icon ▶️).

4.  **Grant Permissions**
    *   On first launch, the app will ask for **Audio** and **Nearby Devices (Bluetooth)** permissions. You must grant these for the app to function correctly.

---

## 📸 Screenshot

<p align="center">
  <img src="https://github.com/user-attachments/assets/18e7de7b-c1f0-4c2f-a74d-2363ca456098" width="250"/>
</p>


