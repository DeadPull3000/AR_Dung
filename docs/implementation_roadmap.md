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
- **Milestone 3:** Spatial plane tracking & visualization (Obtaining ARCore Plane trackables, handling plane lifecycle and subsumption, world-space coordinate transformation, OpenGL ES 3.0 dual-pass polygon rendering with type color coding, real-time spatial HUD telemetry).
  - *Status:* COMPLETED & VERIFIED on physical tablet at 60 FPS (13+ planes detected and rendered).
- **Milestone 4:** Screen hit-testing, ARCore Anchors & 3D spatial marker (Screen tap to raycast hit-testing, tracked plane filtering and scoring, single Anchor lifecycle with safe replacement and reset, 3D cube and RGB 6-DoF coordinate axes rendering, spatial stability verification).
  - *Status:* COMPLETED & VERIFIED on physical tablet at ~60 FPS.
- **Milestone 5:** Depth perception foundation (ARCore 16-bit depth acquisition, AUTOMATIC depth mode configuration, image stride and Little-Endian byte-order handling, 10 Hz throttled statistical sampling, OpenGL ES 3.0 false-color depth heatmap overlay, 5-point screen coordinate sampling, raw depth & confidence investigation, camera intrinsics extraction).
  - *Status:* COMPLETED & VERIFIED on physical tablet at 59.9 FPS (160x90 depth resolution, 100% dense coverage with motion, stable metric readings).
- **Milestone 6:** 2.5D Occupancy grid construction (Converting 16-bit depth point cloud and plane geometry into a stable, bounded 8.0m x 8.0m 2.5D occupancy map at 0.10m resolution, floor reference frame anchoring, height-band obstacle classification, evidence accumulation/decay, OpenGL ES 3.0 world-space floor grid renderer, real-time telemetry).
  - *Status:* COMPLETED & VERIFIED on physical tablet at 60.0 FPS rendering, 9.1-9.3 Hz grid update rate, verified across all 6 physical test cases.
- **Milestone 7:** Spatial filtering, obstacle inflation & traversal cost grid (Separating raw from filtered occupancy, conservative noise filtering, agent radius dilation, compact traversal cost matrix, 4-stage perception pipeline, 4-way visual display modes, verified on physical hardware).
  - *Status:* COMPLETED & VERIFIED on physical tablet at 59.8-60.3 FPS rendering, 9.0-9.4 Hz grid updates, 1.5-2.3 ms processing time, verified across Tests A through G.
- **Milestone 8:** Custom A* pathfinding, line-of-sight smoothing & 3D AR path visualization (Preallocated zero-allocation primitive indexed min-heap, 8-connected movement with metric step costs, strict diagonal corner-cutting prevention, admissible octile heuristic, supercover raycast path smoothing, world-space model matrix transformation, OpenGL ES 3.0 path ribbon & 3D start/goal diamond markers, async navigation executor, verified on physical hardware).
  - *Status:* COMPLETED & VERIFIED on Samsung Galaxy Tab S8+ (`SM-X800`) at 59.6-60.8 FPS rendering, 0.07-0.24 ms search latency, 10/10 unit tests passing, physical room verified across Tests A through F.
- **Milestone 9:** Autonomous AR agent navigation & kinematics (Single virtual agent entity spawning, deterministic floor-plane kinematics, pure pursuit directional steering with clamped turn-rate, dynamic event-driven replanning preserving goal from current agent cell, OpenGL ES 3.0 3D octagonal body and forward arrow renderer, verified on physical hardware).
  - *Status:* COMPLETED & VERIFIED on Samsung Galaxy Tab S8+ (`SM-X800`) at 59.5-60.7 FPS rendering, 0.018 ms simulation time, 20/20 unit tests passing, physical room verified across Tests A through H.
- **Milestone 10:** Reactive Creature AI, Player Awareness & Behaviour State Machine (Autonomous floor patrol, ARCore camera proxy proximity detection, 2.5D Bresenham line-of-sight raycast, pursuit pathfinding via existing A* navigation, last known location preservation, bounded multi-candidate search, return to patrol, asynchronous request token protection, safe recovery, state-reactive 3D color rendering, HUD controls & telemetry).
  - *Status:* COMPLETED & VERIFIED on Samsung Galaxy Tab S8+ (`SM-X800`) at 59.8–60.3 FPS rendering, 9.1–9.3 Hz depth perception, 40/40 unit tests passing, physical room verified across Tests A through J.
- **Milestone 11:** Depth-Aware Visibility, Occlusion & Cover-Aware Search (Geometric pinhole camera projection, 5×5 median 16-bit metric depth sampling, adaptive noise margins, tri-state VISIBLE/OCCLUDED/UNKNOWN classification with 10 diagnostic reasons, temporal confirmation hysteresis, evidence fusion with 2.5D grid line-of-sight, frozen last-known player proxy tracking, cover-aware candidate search along obstacle boundaries, candidate separation enforcement, 5s search budget, safe recovery to patrol, zero-allocation GL evaluation loops).
  - *Status:* COMPLETED & VERIFIED on Samsung Galaxy Tab S8+ (`SM-X800`) at 59.8–60.1 FPS rendering, 9.2–9.5 Hz depth perception, 65/65 unit tests passing, physical room verified across Tests A through L.
- **Milestone 12:** Playable Mission Loop, Relics & Extraction (The Relic Hunt complete mission loop, 6-state top-level game state machine, reachable BFS objective generation for 3 collectible relics and 1 extraction portal, camera-proxy collection radius, extraction gating, creature pursuit capture with continuous duration and threat decay hysteresis, OpenGL ES 3.0 3D faceted diamond and extraction portal disc rendering, HUD telemetry & controls, clean restarts and pause handling, zero per-frame heap churn).
  - *Status:* COMPLETED & VERIFIED on Samsung Galaxy Tab S8+ (`SM-X800`) at 59.7–60.4 FPS rendering, 9.3–9.5 Hz depth perception, 95/95 unit tests passing, physical room verified across Tests A through N.
- **Milestone 13:** Real-Time Depth Occlusion Shader (Real-world physical object occlusion over virtual creature geometry and mission objectives using ARCore 16-bit depth texture in OpenGL ES 3.0 fragment shaders).
- **Milestone 14:** Procedural dungeon generation (Generating virtual walls, corridors, and portals bounded by physical room occupancy grid).
- **Milestone 15:** Spatial audio & Advanced AI tactics (Spatial audio cues for creature state transitions, audio-guided perception).
- **Milestone 16:** Final game polish, performance profiling & thermal management.

## 3. Deliberately Deferred ("Do Not Build Yet")
- Game engines (Unity, Unreal)
- Machine Learning / Semantic object classification
- Multiplayer / Cloud AR Anchors
- Native C++ (NDK) layer (retained in Kotlin unless profiling demands NDK)
- Complex 3D models and skeletal animation
- Full game audio, inventory, or complex menu systems
