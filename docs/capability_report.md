# Project Capability Report

This report summarizes the hardware, software, and tools available for the Android AR Embedded Systems project.

## 1. Hardware

- **Target Device:** Samsung Galaxy Tab S8+ (`VERIFIED` as target, `BLOCKED` on physical USB connection)
- **Host Machine:** Windows 11 (AMD64) (`VERIFIED`)

## 2. Software & Toolchain (Host)

- **Android SDK:** Installed at `%LOCALAPPDATA%\Android\Sdk` — `VERIFIED`
- **Java Development Kit (JDK):** Microsoft OpenJDK 17 LTS (`17.0.20.1`) — `VERIFIED`
- **Kotlin Compiler:** Kotlin 1.9.24 (via Gradle plugin) — `VERIFIED`
- **Android Debug Bridge (ADB):** Platform-Tools 37.0.1 (ADB 1.0.41) — `VERIFIED`
- **Build Tools:** `34.0.0` — `VERIFIED`
- **Platform SDK:** `platforms;android-34` — `VERIFIED`
- **Gradle:** Gradle 8.7 & Gradle Wrapper — `VERIFIED`
- **Android Gradle Plugin (AGP):** 8.4.2 — `VERIFIED`

## 3. Device Capabilities (Samsung Galaxy Tab S8+)

- **SoC:** Qualcomm Snapdragon 8 Gen 1
- **GPU:** Adreno 730 (supports OpenGL ES 3.2 and Vulkan 1.1)
- **ARCore Availability:** Supported with Depth API (Motion Stereo)
- **Android Version / API Level:** Android 12+ / API 31+

## 4. Current Status

- Development toolchain established and verified.
- Project compiles and generates valid debug APK.
- Physical deployment pending device USB debugging connection.
