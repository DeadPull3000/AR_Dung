# AR Dungeon — Room-Scale Embedded Systems AR Game

[![Platform](https://img.shields.io/badge/Platform-Android%2014%20(API%2034)-green.svg)](https://developer.android.com/)
[![Language](https://img.shields.io/badge/Language-Kotlin%201.9.24-blue.svg)](https://kotlinlang.org/)
[![Graphics](https://img.shields.io/badge/Rendering-OpenGL%20ES%203.0%2B-orange.svg)](https://www.khronos.org/opengles/)
[![AR Framework](https://img.shields.io/badge/AR-Google%20ARCore-red.svg)](https://developers.google.com/ar)
[![Target Device](https://img.shields.io/badge/Target-Samsung%20Galaxy%20Tab%20S8%2B-purple.svg)](https://www.samsung.com/)

A real-time, room-scale Augmented Reality application developed for an **Android Embedded Systems** course.

The central premise of the project:
> **The physical room becomes the computational game world.**

Rather than relying on pre-packaged commercial game engines (Unity, Unreal) or cloud-based spatial services, this project implements spatial perception, geometric environment modeling, 2.5D occupancy grid mapping, custom A* path planning, finite-state-machine agent behaviors, and hardware-accelerated OpenGL ES rendering directly on native Android.

---

## 🏛️ System Feedback Loop

```
PHYSICAL WORLD
    │
    ▼ (Camera Feed + IMU Sensors)
GOOGLE ARCORE
    │
    ▼ (Pose, Feature Points, Planes, Raw Depth API)
SPATIAL PERCEPTION
    │
    ▼ (Floor Detection, Obstacle Filtering, Raycasting)
2.5D OCCUPANCY GRID (ENVIRONMENT MODEL)
    │
    ▼ (Inflation & Traversal Matrix)
PROCEDURAL DUNGEON & A* PATH PLANNING
    │
    ▼ (State Transitions: Patrol, Chase, Search, Cover)
FINITE STATE MACHINE (AUTONOMOUS AI)
    │
    ▼ (Occlusion Fragment Shaders, 3D Meshes, OES Background)
OPENGL ES GRAPHICS PIPELINE
    │
    ▼ (Stereoscopic Depth Occlusion & Real-Time Feedback)
PLAYER INTERACTION
```

---

## 🎯 Target Hardware & Technical Specifications

- **Target Device:** Samsung Galaxy Tab S8+ (Wi-Fi / 5G)
- **SoC / Chipset:** Qualcomm Snapdragon 8 Gen 1 (4 nm)
- **CPU:** Octa-core (1x3.00 GHz Cortex-X2 & 3x2.50 GHz Cortex-A710 & 4x1.80 GHz Cortex-A510)
- **GPU:** Adreno 730 (supporting OpenGL ES 3.2 and Vulkan 1.1)
- **Display:** 12.4" Super AMOLED (120Hz, 2800 x 1752)
- **Sensing Suite:** Dual camera array, IMU (accelerometer, gyroscope), ARCore Depth API (Motion Stereo)
- **Operating System:** Android 12 / 13 / 14 (Target SDK: API 34, Min SDK: API 26)

---

## 🛠️ Technology Stack & Architectural Constraints

In strict accordance with Embedded Systems engineering principles:

- **Language:** 100% Kotlin
- **Build System:** Gradle 8.7 with Android Gradle Plugin (AGP) 8.4.2
- **AR Subsystem:** Native Google ARCore SDK (Java/Kotlin API)
- **Rendering Subsystem:** Direct OpenGL ES 3.0+ via `android.opengl.GLSurfaceView`
- **Navigation:** Custom Occupancy Grid and A* pathfinding algorithm
- **Agent Intelligence:** Lightweight Finite-State Machine (FSM)
- **Zero Third-Party Game Engines:** Strictly no Unity, Unreal Engine, Godot, or Sceneform
- **Geometric Grounding:** Environment perception relies strictly on physical geometry (planes, point clouds, depth maps). No reliance on cloud semantic object recognition.

---

## 🧵 Threading & Concurrency Architecture

To guarantee smooth 60 FPS graphics and prevent tracking degradation, tasks are partitioned strictly across separate threads:

| Thread | Responsibility | Frequency / Budget |
| :--- | :--- | :--- |
| **Android UI Thread** | Touch input, lifecycle events, debug telemetry displays | 60 Hz (event-driven) |
| **GL Render Thread** | `Session.update()`, camera background rendering, 3D virtual drawing, depth occlusion shader | 60 FPS (~16.6 ms budget) |
| **Perception Worker** | Depth buffer unpacking, point cloud filtering, 2.5D occupancy grid updates | 10 – 15 Hz |
| **AI & Navigation Pool** | Obstacle inflation, A* path planning, agent state evaluation | Asynchronous (Kotlin Coroutines) |

---

## 📂 Project Structure

```
AR_Dung/
├── .github/                       # GitHub workflows and automation
├── app/                           # Android application module
│   ├── build.gradle.kts           # Module-level Gradle configuration (dependencies, SDKs)
│   ├── proguard-rules.pro         # Proguard/R8 rules
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           ├── java/com/embedded/argame/
│           │   ├── environment/   # Machine-readable spatial environment model
│           │   │   ├── FloorReference.kt
│           │   │   ├── GridCellState.kt
│           │   │   ├── GridDiagnostics.kt
│           │   │   ├── ObstacleInflater.kt
│           │   │   ├── OccupancyGrid.kt
│           │   │   ├── SpatialFilter.kt
│           │   │   └── TraversalCostGrid.kt
│           │   ├── perception/    # ARCore session management & decoupled spatial models
│           │   │   ├── AnchorData.kt
│           │   │   ├── ArSessionManager.kt
│           │   │   ├── DepthData.kt
│           │   │   ├── PlaneData.kt
│           │   │   └── TrackingState.kt
│           │   └── rendering/     # Hardware-accelerated OpenGL ES 3.0 renderers
│           │       ├── AnchorMarkerRenderer.kt
│           │       ├── ArRenderer.kt
│           │       ├── BackgroundRenderer.kt
│           │       ├── DepthHeatmapRenderer.kt
│           │       ├── OccupancyGridRenderer.kt
│           │       └── PlaneRenderer.kt
│           └── res/
│               ├── layout/
│               │   └── activity_main.xml
│               └── values/
│                   ├── colors.xml
│                   ├── strings.xml
│                   └── themes.xml
├── docs/                          # Comprehensive architectural specifications & screenshots
│   ├── capability_report.md       # Hardware & software environment assessment
│   ├── implementation_roadmap.md  # Step-by-step milestone breakdown
│   ├── milestone_3_planes.md      # Milestone 3 plane detection & rendering verification
│   ├── milestone_4_hit_testing_anchors.md # Milestone 4 hit-testing & anchor verification
│   ├── milestone_5_depth.md       # Milestone 5 16-bit depth & heatmap verification
│   ├── milestone_6_occupancy_grid.md # Milestone 6 2.5D occupancy grid verification
│   ├── milestone_7_spatial_filtering_inflation.md # Milestone 7 filtering, inflation & cost grid
│   ├── software_architecture.md   # System modules, data flow, and contracts
│   └── screenshots/               # On-device physical verification captures
├── gradle/wrapper/                # Gradle wrapper binaries & properties
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties
├── build.gradle.kts               # Root build script
├── gradle.properties              # JVM args and AndroidX configuration
├── gradlew                        # Unix Gradle wrapper executable
├── gradlew.bat                    # Windows Gradle wrapper batch script
├── PROJECT_RULES.md               # Mandatory engineering rules for contributors and agents
├── README.md                      # Project documentation
└── settings.gradle.kts            # Project settings & repository declarations
```

---

## 🚀 Getting Started & Building

### 1. Prerequisites
- **JDK:** OpenJDK 17 LTS (Microsoft OpenJDK 17 or Eclipse Temurin 17 recommended)
- **Android SDK:** Platform API 34 (`platforms;android-34`) and Build Tools `34.0.0`
- **Platform Tools:** ADB 37.0.1+ (`platform-tools`)
- **Environment Variables:**
  - `JAVA_HOME` pointing to JDK 17
  - `ANDROID_HOME` pointing to Android SDK directory

### 2. Build the Debug APK
Using the provided Gradle wrapper:

```powershell
# On Windows PowerShell
.\gradlew.bat assembleDebug

# On macOS / Linux
./gradlew assembleDebug
```

The compiled APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

### 3. Deploy to Samsung Galaxy Tab S8+
1. Connect the tablet to your computer via USB-C.
2. Enable **Developer Options** and **USB Debugging** on the tablet:
   - *Settings → About tablet → Software information → Tap "Build number" 7 times*
   - *Settings → Developer options → Enable "USB Debugging"*
3. Authorize the computer on the tablet pop-up ("Always allow from this computer").
4. Install and launch the application:

```powershell
# Verify ADB connection
adb devices -l

# Install the APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch the application
adb shell am start -n com.embedded.argame/.MainActivity
```

---

## 🗺️ Implementation Roadmap

| Milestone | Goal | Status |
| :---: | :--- | :---: |
| **1** | **Android Toolchain Setup & Minimal Device Deployment** | **Completed & Verified** |
| **2** | **ARCore Session Initialization, Camera Background & 6-DOF Tracking** | **Completed & Verified (60 FPS)** |
| **3** | **Spatial Plane Detection, Subsumption Handling & 3D Visualization** | **Completed & Verified (60 FPS)** |
| **4** | **Screen Hit-Testing, ARCore Anchors & 3D Spatial Marker** | **Completed & Verified (60 FPS)** |
| **5** | **ARCore Depth Perception, 16-Bit Sampling & False-Color Heatmap** | **Completed & Verified (60 FPS)** |
| **6** | **2.5D Occupancy Grid Representation & Spatial Discretization** | **Completed & Verified (60 FPS, 9.2 Hz grid)** |
| **7** | **Spatial Filtering, Obstacle Inflation & Traversal Cost Matrix** | **Completed & Verified (60 FPS, 9.2 Hz grid, 1.8 ms latency)** |
| **8** | Custom A* Pathfinding on Traversal Cost Grid | Planned |
| **9** | Virtual Agent World Placement & Path Following | Planned |
| **10** | Autonomous Agent Finite-State Machine (FSM) | Planned |
| **11** | Depth-Aware Shader Occlusion of Virtual Objects | Planned |
| **12** | Procedural Dungeon Generation on Physical Grid | Planned |
| **13** | Intelligent AI Behaviors (Chase, Search, Cover) | Planned |
| **14** | Embedded Systems Telemetry (FPS, CPU, Thermals, Battery) | Planned |
| **15** | Course Project Final Polish & Interactive Gameplay | Planned |

---

## 📜 Engineering Rules

All contributions and automated agents must adhere strictly to [PROJECT_RULES.md](file:///c:/Users/User/Desktop/Projects%20%28Coding%29/AR%20game%20%28embedded%20systems%29/PROJECT_RULES.md):
- Native Android/Kotlin and OpenGL ES only.
- No commercial or third-party game engines.
- Official Android and ARCore documentation is the source of truth.
- Zero expensive operations on the UI thread.
- Avoid per-frame object allocations in hot loops.
- Follow conventional commits (`feat:`, `fix:`, `docs:`, `chore:`, `refactor:`, `test:`).

---

## 📄 License & Course Context
Developed for the **Android Embedded Systems** course project.
All source code and documentation are maintained for educational and embedded systems research purposes.
