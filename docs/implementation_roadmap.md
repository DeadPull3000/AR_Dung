# Project Roadmap & MVP Definition

This document defines the Minimum Viable Product (MVP), the step-by-step implementation roadmap, and the architectural boundaries to maintain project focus.

## 1. The Minimum Viable Product (MVP)

The MVP is the smallest complete end-to-end slice demonstrating the core feedback loop: the physical room directly shaping and constraining autonomous virtual agents.

**MVP Criteria:**
1. **Scan Room:** Tablet initializes ARCore and displays the camera feed.
2. **Detect Floor/Obstacle:** Detects physical planes (floor) and uses depth data to extract physical obstacles.
3. **Build Occupancy Grid:** Converts detected geometry into a 2.5D grid representation.
4. **Place Virtual Agent:** Spawns a virtual agent on the physical floor plane.
5. **Plan Path:** Computes an A* path from the agent's location to a target destination.
6. **Navigate Around Real Object:** Path actively navigates around real obstacles without collision.
7. **Render Agent in AR:** Agent smoothly traverses the path rendered via OpenGL ES.
8. **Occlude Agent Behind Real Object:** Real-world obstacles visually occlude the virtual agent using ARCore Depth.

## 2. Implementation Roadmap

- **Milestone 1:** Android project setup + device deployment (Establish toolchain, minimal app, verified APK).
  - *Status:* COMPLETED & VERIFIED on Samsung Galaxy Tab S8+ (`SM-X800`).
- **Milestone 2:** ARCore initialization & spatial perception baseline (Camera permissions, session lifecycle, live OpenGL ES camera background, real-time 6-DOF tracking).
  - *Status:* COMPLETED & VERIFIED on physical tablet at 60 FPS.
- **Milestone 3:** Plane detection & visualization (Visualizing detected horizontal floor planes in world coordinates).
- **Milestone 4:** Plane detection (Visualizing detected horizontal floor planes).
- **Milestone 5:** Coordinate systems + anchors (Placing anchored 3D objects in physical world coordinates).
- **Milestone 6:** Depth visualization (Debug rendering of raw ARCore depth map).
- **Milestone 7:** Depth/geometry processing (Background depth buffer extraction and spatial filtering).
- **Milestone 8:** Occupancy grid (Constructing and updating 2.5D occupancy map).
- **Milestone 9:** A* navigation (Grid-based path planning with obstacle inflation).
- **Milestone 10:** Virtual agent movement (Agent following computed waypoints).
- **Milestone 11:** Autonomous agent FSM (Idle, Wander, Target states).
- **Milestone 12:** Depth occlusion shader (Real-world object occlusion over virtual geometry).
- **Milestone 13:** Procedural dungeon generation (Generating virtual walls and corridors bounded by room layout).
- **Milestone 14:** Advanced AI behavior (Player chase, search, cover selection).
- **Milestone 15:** Performance profiling (FPS, frame timing, memory, thermal and battery monitoring).
- **Milestone 16:** Final game integration (Objectives, win/lose conditions, polished UI).

## 3. Deliberately Deferred ("Do Not Build Yet")
- Game engines (Unity, Unreal)
- Machine Learning / Semantic object classification
- Multiplayer / Cloud AR Anchors
- Native C++ (NDK) layer (retained in Kotlin unless profiling demands NDK)
- Complex 3D models and skeletal animation
- Full game audio, inventory, or complex menu systems
