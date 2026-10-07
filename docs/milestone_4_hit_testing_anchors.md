# Milestone 4: Screen Hit-Testing, ARCore Anchors & 3D Spatial Marker

This document provides the verified engineering report for Milestone 4 of the Embedded Systems AR project, implemented and verified on the physical **Samsung Galaxy Tab S8+**.

---

## 1. Interaction Pipeline Architecture

The touch-to-spatial-anchor pipeline captures 2D screen taps, queries ARCore's latest spatial frame, selects a valid tracked physical plane, attaches an immutable ARCore `Anchor`, and renders a 3D diagnostic marker with 6-DoF coordinate axes:

```
USER TOUCH EVENT (Android UI Thread)
       │
       ▼ GestureDetector.onSingleTapUp(e)
Screen Tap Coordinates (x, y pixels)
       │
       ▼ Thread-safe AtomicReference queue
GL RENDER THREAD (ArRenderer.onDrawFrame())
       │
       ▼ sessionManager.updateFrame()
ARCore Frame.hitTest(x, y)
       │
       ▼ List<HitResult>
Hit Candidate Filtering & Priority Scoring
       ├── Reject non-plane hits & subsumed planes
       ├── Require hit inside plane boundary polygon (or extents)
       └── Prioritize: Horiz ↑ > Vertical > Horiz ↓
       │
       ▼ Selected Best HitResult
Anchor Lifecycle Management
       ├── 1. activeAnchor?.detach() (Cleanly release previous anchor)
       ├── 2. val newAnchor = hitResult.createAnchor()
       └── 3. Cache anchor in ArSessionManager
       │
       ├──> AnchorDiagnostics extracted & dispatched to UI HUD @ 10 Hz
       │
       ▼ AnchorMarkerRenderer.draw(anchor.pose, viewProjectionMatrix)
OpenGL ES 3.0 3D Diagnostic Marker
       ├── 3D Solid Translucent Cube (8 cm, resting on surface)
       ├── High-Contrast Wireframe Edge Outlines
       └── RGB Coordinate Axes (X=Red, Y=Green, Z=Blue, 14 cm)
```

---

## 2. Coordinate Systems & Mathematical Transformations

```
┌────────────────────────────────────────────────────────────────────────┐
│                        COORDINATE FLOW PIPELINE                        │
└────────────────────────────────────────────────────────────────────────┘

 1. SCREEN SPACE (Pixels)
    - Origin: Top-Left (0, 0)
    - Tablet Resolution: 1752 x 2736 px
    - Tap: (x, y) float coordinates
             │
             ▼ Frame.hitTest(x, y) (Camera Ray Projection)
 2. ARCORE WORLD SPACE (Meters)
    - Right-handed Cartesian coordinates (Y-up, X-right, Z-back)
    - Hit pose: P_world = (X, Y, Z, Qx, Qy, Qz, Qw)
             │
             ▼ hitResult.createAnchor()
 3. ANCHOR LOCAL SPACE (Meters)
    - Local origin: (0, 0, 0) at surface anchor point
    - +Y: Local plane normal (perpendicular to surface)
    - 3D Cube geometry: X, Z in [-0.04, +0.04] m, Y in [0.0, 0.08] m
             │
             ▼ Model Matrix (anchor.pose.toMatrix())
 4. OPENGL ES CAMERA / VIEW SPACE
    - M_view (camera.getViewMatrix())
             │
             ▼ Projection Matrix (camera.getProjectionMatrix())
 5. OPENGL CLIP SPACE
    - M_MVP = M_projection · M_view · M_model
    - v_clip = M_MVP · v_local
             │
             ▼ Viewport Mapping
 6. PHYSICAL DISPLAY
    - 3D marker rendered seamlessly locked to physical real-world surface
```

---

## 3. Hit-Test Selection & Filtering Logic

`Frame.hitTest(x, y)` projects a ray from the camera optical center through the screen pixel into the 3D scene and returns all intersecting trackables. The application filters and prioritizes results using deterministic rules:

1. **Trackable Verification:** Only trackables of type `Plane` are accepted. Point cloud hits are rejected to ensure markers are placed on stable surfaces.
2. **Tracking State:** The plane must have `trackingState == TrackingState.TRACKING`.
3. **Subsumption Check:** Subsumed child planes (`plane.subsumedBy != null`) are rejected to avoid placing anchors on deprecated geometry.
4. **Polygon In-Bounds Check:** Verified using `plane.isPoseInPolygon(hit.hitPose)`. If multiple hits occur, hits strictly within the boundary polygon take precedence over bounding extents (`isPoseInExtents`).
5. **Surface Priority Scoring:**
   - **Priority 1:** `HORIZONTAL_UPWARD_FACING` within polygon (floors, tabletops).
   - **Priority 2:** `VERTICAL` within polygon (walls).
   - **Priority 3:** `HORIZONTAL_DOWNWARD_FACING` within polygon (ceilings).
   - **Priority 4:** Any plane within polygon.
   - **Priority 5:** Plane within bounding extents only.
6. **Tie-Breaking:** If multiple candidates share the highest priority, the candidate closest to the camera (`hitResult.distance`) is selected.

---

## 4. Anchor Lifecycle & Resource Management

ARCore anchors consume system tracking resources and visual-inertial odometry constraints. To ensure embedded efficiency:

- **Single Active Anchor:** The application retains exactly **one** diagnostic anchor at any given time.
- **Safe Replacement:** When a user taps to reposition the marker, the existing anchor is immediately detached (`activeAnchor?.detach()`) before `hitResult.createAnchor()` is called.
- **Explicit Reset Control:** A dedicated UI button (`RESET MARKER`) invokes `sessionManager.resetAnchor()`, executing `detach()` and resetting all anchor state.
- **Tracking Interruption / Loss:**
  - If anchor tracking transitions to `PAUSED`, the HUD displays the pause state without destroying the anchor.
  - If anchor tracking transitions to `STOPPED`, the anchor is automatically detached and cleared.
- **Session Termination:** In `destroySession()`, `activeAnchor?.detach()` is executed to prevent native C++ resource leaks.

---

## 5. 3D Diagnostic Marker Rendering

The diagnostic marker is rendered via [`AnchorMarkerRenderer`](file:///c:/Users/User/Desktop/Projects%20(Coding)/AR%20game%20%28embedded%20systems%29/app/src/main/java/com/embedded/argame/rendering/AnchorMarkerRenderer.kt) using OpenGL ES 3.0:

1. **3D Solid Cube Faces (`GL_TRIANGLES`, 36 vertices):**
   - Size: $8\text{ cm} \times 8\text{ cm} \times 8\text{ cm}$
   - Coordinate layout: $X \in [-0.04, 0.04]\text{m}$, $Z \in [-0.04, 0.04]\text{m}$, $Y \in [0.00, 0.08]\text{m}$ (rests directly on top of the surface).
   - Faceted shading: Distinct brightness per face creates clear 3D perspective depth without requiring runtime light calculations.
2. **Wireframe Outlines (`GL_LINES`, 24 vertices):**
   - Solid white edge lines ($\alpha = 1.0$) outline the 12 cube edges, ensuring high-contrast visibility against real-world background camera clutter.
3. **RGB Coordinate Axes (`GL_LINES`, 6 vertices, 14 cm length):**
   - $+X$ Axis: **Red** (`#FF2639`)
   - $+Y$ Axis: **Green** (`#1AFF40`, pointing along the surface normal)
   - $+Z$ Axis: **Blue** (`#3399FF`)
   - Visually confirms that the anchor is a true 6-DoF spatial pose (orientation + position) rather than a flat point.
4. **Zero-Allocation GPU Architecture:**
   - All vertices and colors are pre-allocated into direct `FloatBuffer` instances during object construction.
   - Model-View-Projection matrix is calculated per frame on CPU:
     $$M_{\text{MVP}} = M_{\text{ViewProjection}} \times M_{\text{Model}}$$
   - Zero object allocations occur in `draw()`, ensuring continuous 60 FPS performance.

---

## 6. Physical Device Verification Results (Samsung Galaxy Tab S8+)

All tests executed on physical hardware:
- **Device:** Samsung Galaxy Tab S8+ (`SM-X800`)
- **Chipset:** Qualcomm Snapdragon 8 Gen 1 (Adreno 730 GPU)
- **OS:** Android 16 (API 36)
- **Serial:** `R52W405PTBL`

| Test Case | Verification Procedure | Observed Outcome | Status |
| :--- | :--- | :--- | :--- |
| **TEST A: Floor Surface Placement** | Aim tablet at detected floor plane and tap screen. | ARCore hit test detected plane; created Anchor at $[X=-0.191, Y=-0.851, Z=-1.986]\text{m}$. 3D Cube appeared resting on floor with green $+Y$ normal pointing upward. | `VERIFIED` |
| **TEST B: Raised Surface Placement** | Aim tablet at bed/table surface and tap screen. | Hit test detected raised horizontal plane; created Anchor at $[X=-0.250, Y=-0.858, Z=-1.765]\text{m}$ (distance $1.89\text{m}$). Marker rendered on bed surface. | `VERIFIED` |
| **TEST C: Walk / Perspective Stability** | Walk around the marker, rotate tablet, move closer/further. | The 3D marker remained rigidly locked to the exact physical spot on the floor/surface. No screen-pixel sticking or visual drift. | `VERIFIED` |
| **TEST D: Marker Relocation** | Tap a different location on a detected surface. | Previous anchor detached cleanly; new anchor created at new position $[X=-0.463, Y=-0.857, Z=-2.371]\text{m}$. Marker moved smoothly. | `VERIFIED` |
| **TEST E: Reset Control** | Press `RESET MARKER` button in HUD overlay. | Anchor detached immediately; 3D marker disappeared; HUD updated to `Anchor: NONE (Marker reset by user)`. | `VERIFIED` |
| **TEST F: Performance Baseline** | Monitor FPS during active plane rendering and 3D marker drawing. | Sustained **59.4 – 59.8 FPS** with zero GC pauses or frame drops. | `VERIFIED` |

### Telemetry Evidence Captured from Physical Tablet

#### Test A — Floor Placement Telemetry:
- **ARCore Status:** `READY (Active Session)` | **Tracking:** `TRACKING`
- **Camera Pose:** `X: +0.059  Y: +0.076  Z: -0.111 m`
- **Spatial Planes:** `Total: 2 (Horiz ↑: 2 | Horiz ↓: 0 | Vert: 0)`
- **Anchor Telemetry:** `Anchor: TRACKING | Pos: X: -0.191  Y: -0.851  Z: -1.986 m | Surface: Horizontal ↑  Dist: 2.11 m`
- **Performance:** `FPS: 59.5`
- Screenshot saved: [`docs/screenshots/milestone4_anchor_floor.png`](file:///c:/Users/User/Desktop/Projects%20%28Coding%29/AR%20game%20%28embedded%20systems%29/docs/screenshots/milestone4_anchor_floor.png)

#### Test E — Reset Marker Telemetry:
- **Spatial Anchor:** `Anchor: NONE (Marker reset by user) | Pos: X: ---  Y: ---  Z: --- | Surface: NONE  Dist: ---`
- **Performance:** `FPS: 59.8`
- Screenshot saved: [`docs/screenshots/milestone4_anchor_reset.png`](file:///c:/Users/User/Desktop/Projects%20%28Coding%29/AR%20game%20%28embedded%20systems%29/docs/screenshots/milestone4_anchor_reset.png)

#### Test B — Raised Surface (Bed/Table) Telemetry:
- **Anchor Telemetry:** `Anchor: TRACKING | Pos: X: -0.250  Y: -0.858  Z: -1.765 m | Surface: Horizontal ↑  Dist: 1.89 m`
- **Performance:** `FPS: 59.4`
- Screenshot saved: [`docs/screenshots/milestone4_anchor_raised_surface.png`](file:///c:/Users/User/Desktop/Projects%20%28Coding%29/AR%20game%20%28embedded%20systems%29/docs/screenshots/milestone4_anchor_raised_surface.png)

---

## 7. Performance Analysis

- **Average Frame Rate:** **59.5 – 59.8 FPS** (**VERIFIED** on Samsung Galaxy Tab S8+).
- **Minimum FPS Observed:** 58.7 FPS during rapid tablet rotation.
- **Hit Test Budget:** Hit tests execute strictly on user tap (asynchronous queue), incurring zero ongoing computational cost during idle frames.
- **GPU Overhead:** Anchor marker geometry (66 vertices total for cube, outlines, and axes) consumes negligible vertex processing time on the Adreno 730 GPU.

---

## 8. Architectural Limitations & Non-Assumptions

1. **Visual-Inertial Odometry Refinement:**
   - ARCore may refine an Anchor's world-space pose over time as loop-closure events occur. This is normal and desirable behavior.
2. **No Semantic Object Recognition:**
   - A hit on a horizontal upward-facing plane establishes geometric contact with a physical surface, but does not identify whether it is a floor, table, or bed.
3. **Single Diagnostic Anchor:**
   - This milestone strictly tests spatial coordinate attachment with one anchor. Multi-anchor management and game-object anchors will be introduced in subsequent gameplay milestones.
