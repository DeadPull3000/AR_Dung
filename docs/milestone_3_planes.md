# Milestone 3: AR Spatial Plane Detection & Visualization

This document provides the verified engineering report for Milestone 3 of the Embedded Systems AR project, implemented and validated on the physical **Samsung Galaxy Tab S8+**.

---

## 1. Plane Detection Architecture

The spatial plane perception pipeline is designed to extract planar trackables from the active ARCore session, decouple them into application-level representations, and render their spatial boundaries with zero per-frame garbage collector churn.

```
ARCore Session (session.update())
       │
       ▼
ARCore Frame
       │
       ├──> session.getAllTrackables(Plane::class.java)
       │       │
       │       ▼ (Filter: trackingState == TRACKING && subsumedBy == null)
       │    Active Non-Subsumed ARCore Planes
       │       │
       │       ├──> ArSessionManager (Diagnostics & Decoupled State Extraction)
       │       │       │
       │       │       ├──> PlaneSnapshot / PlaneDiagnostics
       │       │       │       │
       │       │       │       ▼ (Throttled UI Callback @ 10 Hz)
       │       │       │    HUD Diagnostics Card (Screen Overlay)
       │       │       │
       │       │       ▼ (Thread-safe snapshot list)
       │       │    ArRenderer.onDrawFrame() (GL Render Thread)
       │       │
       │       ▼
       └──> PlaneRenderer.draw(activePlanes, viewProjectionMatrix)
               │
               ├──> Translucent Polygon Interior (GL_TRIANGLE_FAN, Alpha: 0.20)
               └──> Crisp Polygon Boundary (GL_LINE_LOOP, Alpha: 1.0)
```

### Components
1. **[ArSessionManager](file:///c:/Users/User/Desktop/Projects%20(Coding)/AR%20game%20(embedded%20systems)/app/src/main/java/com/embedded/argame/perception/ArSessionManager.kt)**: Configures `Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL`. Manages active plane extraction, filters subsumed planes, maintains a thread-safe list of active planes, and computes plane counts and dimensional statistics.
2. **[PlaneData.kt](file:///c:/Users/User/Desktop/Projects%20(Coding)/AR%20game%20(embedded%20systems)/app/src/main/java/com/embedded/argame/perception/PlaneData.kt)**: Contains decoupled representations:
   - `TrackedPlaneType`: High-level classification (`HORIZONTAL_UPWARD_FACING`, `HORIZONTAL_DOWNWARD_FACING`, `VERTICAL`, `UNKNOWN`).
   - `PlaneSnapshot`: Decoupled spatial state containing plane identity, bounds, center coordinates, and area.
   - `PlaneDiagnostics`: Aggregated telemetry for the diagnostic HUD.
3. **[PlaneRenderer](file:///c:/Users/User/Desktop/Projects%20(Coding)/AR%20game%20(embedded%20systems)/app/src/main/java/com/embedded/argame/rendering/PlaneRenderer.kt)**: OpenGL ES 3.0 renderer responsible for transforming plane-local 2D polygon vertices into 3D world space and rendering both boundary outlines and translucent fills.
4. **[MainActivity](file:///c:/Users/User/Desktop/Projects%20(Coding)/AR%20game%20(embedded%20systems)/app/src/main/java/com/embedded/argame/MainActivity.kt)**: Displays real-time plane telemetry at 10 Hz on the HUD card without taxing the main UI thread.

---

## 2. ARCore Plane Perception Concepts

| Concept | Description & Implementation Handling |
| :--- | :--- |
| **`Plane`** | A planar surface in the physical environment detected by ARCore using feature point analysis and visual-inertial odometry. |
| **`Plane.Type`** | Orientation category: `HORIZONTAL_UPWARD_FACING` (floors, tabletops), `HORIZONTAL_DOWNWARD_FACING` (ceilings), or `VERTICAL` (walls, doors). |
| **`TrackingState`** | Tracking status of the plane (`TRACKING`, `PAUSED`, `STOPPED`). Only planes with `TrackingState.TRACKING` are treated as active spatial surfaces. |
| **Plane Pose (`centerPose`)** | Center translation and rotation of the plane in ARCore world coordinates (meters). Defines the plane's local coordinate frame. |
| **Plane Polygon (`plane.polygon`)** | 2D convex polygon representing the physical boundary of the surface. Expressed as sequential `(x, z)` pairs in the plane's local frame with $Y=0$. |
| **Extents (`extentX`, `extentZ`)** | Width and depth of the tight bounding rectangle enclosing the plane boundary. Extent product ($X \times Z$) provides an initial bounding area estimate. |
| **Subsumption (`plane.subsumedBy`)** | ARCore merges smaller planes when it discovers they belong to the same physical continuous surface. Subsumed child planes point to a parent plane (`subsumedBy != null`) and are immediately excluded from active rendering and diagnostics. |

---

## 3. Coordinate Systems & Mathematical Transformations

ARCore expresses plane geometry in a local coordinate frame centered at the plane's anchor pose, with the surface lying on the local $X$-$Z$ plane ($Y = 0$, local normal pointing along $+Y$).

```
┌────────────────────────────────────────────────────────────────────────┐
│                        COORDINATE FLOW PIPELINE                        │
└────────────────────────────────────────────────────────────────────────┘

 1. PLANE-LOCAL SPACE
    - ARCore plane polygon: [x₀, z₀, x₁, z₁, ...]
    - Local vertex: V_local = (x, 0, z, 1)
             │
             ▼ Model Matrix (plane.centerPose.toMatrix())
 2. ARCORE WORLD SPACE
    - V_world = M_model · V_local
    - Right-handed 3D Cartesian coordinates (meters)
    - Origin fixed at ARCore session initialization
             │
             ▼ View Matrix (camera.getViewMatrix())
 3. CAMERA / EYE SPACE
    - V_camera = M_view · V_world
    - Relative to physical tablet camera sensor
             │
             ▼ Projection Matrix (camera.getProjectionMatrix())
 4. OPENGL CLIP SPACE
    - V_clip = M_projection · V_camera = (M_viewProjection · M_model) · V_local
    - Normalized Device Coordinates [-1, 1]³ post-perspective divide
             │
             ▼ Viewport Mapping (1752 x 2736 px)
 5. PHYSICAL DISPLAY SCREEN
    - Rendered on tablet display overlaying the camera stream
```

### Transformation Formulation
To eliminate CPU-side vertex multiplication overhead, the Model-View-Projection matrix is calculated on CPU once per plane:
$$M_{\text{MVP}} = M_{\text{ViewProjection}} \times M_{\text{Model}}$$
where $M_{\text{Model}}$ is `plane.centerPose.toMatrix()`, and $M_{\text{ViewProjection}} = M_{\text{Projection}} \times M_{\text{View}}$.
In the vertex shader:
$$\mathbf{v}_{\text{clip}} = M_{\text{MVP}} \times \begin{bmatrix} x \\ 0.0 \\ z \\ 1.0 \end{bmatrix}$$
This guarantees that plane geometry remains locked to the physical world irrespective of tablet motion.

---

## 4. Application Data Structures

```kotlin
enum class TrackedPlaneType {
    HORIZONTAL_UPWARD_FACING,
    HORIZONTAL_DOWNWARD_FACING,
    VERTICAL,
    UNKNOWN
}

data class PlaneSnapshot(
    val id: String,
    val type: TrackedPlaneType,
    val trackingStatus: TrackingStatus,
    val centerX: Float,
    val centerY: Float,
    val centerZ: Float,
    val extentX: Float,
    val extentZ: Float,
    val estimatedArea: Float,
    val isCandidateLargeSurface: Boolean = false
)

data class PlaneDiagnostics(
    val totalPlanes: Int = 0,
    val horizontalUpwardCount: Int = 0,
    val horizontalDownwardCount: Int = 0,
    val verticalCount: Int = 0,
    val largestPlaneWidth: Float = 0f,
    val largestPlaneDepth: Float = 0f,
    val largestPlaneArea: Float = 0f,
    val hasCandidateLargeSurface: Boolean = false,
    val candidateSurfaceHeightY: Float = 0f
)
```

---

## 5. Rendering Implementation

### Shaders
- **Vertex Shader:**
  ```glsl
  uniform mat4 u_ModelViewProjection;
  attribute vec3 a_Position;
  void main() {
      gl_Position = u_ModelViewProjection * vec4(a_Position, 1.0);
  }
  ```
- **Fragment Shader:**
  ```glsl
  precision mediump float;
  uniform vec4 u_Color;
  void main() {
      gl_FragColor = u_Color;
  }
  ```

### Dual-Pass Polygon Rendering
1. **Translucent Fill (`GL_TRIANGLE_FAN`):**
   - The polygon center $(0, 0, 0)$ is placed as the origin vertex of the fan.
   - Sequential boundary points $(x_i, 0, z_i)$ wrap around the perimeter and close back to $(x_0, 0, z_0)$.
   - Rendered with blending enabled (`GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA`) and $\alpha = 0.20$.
2. **Boundary Line Loop (`GL_LINE_LOOP`):**
   - Sequential boundary points are rendered as an unbroken line loop with $\alpha = 1.0$.

### Color-Coded Differentiation
- **Horizontal Upward-Facing:** Cyan (`#00D9FF`)
- **Horizontal Downward-Facing:** Amber/Orange (`#FF8C00`)
- **Vertical:** Magenta (`#E040FA`)
- **Default/Unknown:** Light Grey (`#CCCCCC`)

---

## 6. Plane Lifecycle & Subsumption Handling

1. **Initial Detection:**
   - ARCore detects initial feature clusters and creates a new plane trackable with small extent.
   - The plane enters `TrackingState.TRACKING`.
2. **Expansion / Refinement:**
   - As the user scans the area, ARCore expands `plane.polygon` and updates `extentX`, `extentZ`.
   - The vertex buffer is dynamically repopulated with updated vertex coordinates.
3. **Subsumption (Merging):**
   - When ARCore recognizes that two independently tracked planar patches belong to the same physical continuous surface, it designates one as the parent and sets `subsumedBy` on the other.
   - The tracker checks `if (plane.subsumedBy != null) continue`, instantly dropping the subsumed plane from rendering and diagnostic counts to prevent duplicate geometry artifacts.
4. **Tracking Interruption / Loss:**
   - If the camera loses feature points or is obstructed, `plane.trackingState` transitions to `PAUSED`.
   - Render filtering excludes paused planes (`plane.trackingState == TrackingState.TRACKING`).
   - If tracking permanently terminates, `plane.trackingState` becomes `STOPPED`, and references are garbage-collected cleanly.

---

## 7. Physical Device Verification (Samsung Galaxy Tab S8+)

All tests executed on physical hardware:
- **Device:** Samsung Galaxy Tab S8+ (`SM-X800`)
- **Chipset:** Qualcomm Snapdragon 8 Gen 1 (Adreno 730 GPU)
- **OS:** Android 16 (API 36)
- **Serial:** `R52W405PTBL`

| Test Scenario | Verification Procedure | Observed Outcome | Status |
| :--- | :--- | :--- | :--- |
| **TEST A: Empty Initial Scene** | Launch app pointing into open space. | Camera feed active at 60 FPS, 0 planes detected initially, no crashes or errors. | `VERIFIED` |
| **TEST B: Scan Floor** | Slowly pan tablet toward the floor. | Multiple horizontal upward-facing planes appeared with cyan boundaries and translucent fills. Boundaries expanded smoothly as more floor was scanned. | `VERIFIED` |
| **TEST C: Scan Table / Raised Surface** | Aim tablet at desk / mattress / elevated surface. | Independent horizontal upward-facing planes detected at higher Y coordinate (e.g., $Y = -0.31\text{m}$ vs $Y = -0.72\text{m}$). Telemetry correctly labeled surface as candidate surface rather than claiming floor. | `VERIFIED` |
| **TEST D: Scan Wall** | Aim tablet at vertical wall / partitions. | 3 vertical planes detected and visualized with crisp magenta (`#E040FA`) outlines. | `VERIFIED` |
| **TEST E: Room Motion & Stability** | Walk around physical room, rotating and translating tablet. | Plane boundaries remained spatially anchored to real physical objects. Merging/subsumption occurred seamlessly as planes grew. | `VERIFIED` |
| **TEST F: Performance Baseline** | Monitor FPS counter during multi-plane rendering (13+ active planes). | Sustained **59.8 FPS** average with zero stutter. No GC-induced frame drops. | `VERIFIED` |

### Telemetry Evidence Captured from Physical Tablet

#### Initial Detection (Table Surface Scan):
- **ARCore Status:** `ARCore: READY (Active Session)`
- **Tracking:** `TRACKING`
- **Camera Pose:** `X: +0.447  Y: +0.300  Z: -0.234 m | Q: [-0.01, -0.12, -0.69, 0.72]`
- **Detected Planes:** `Total: 3 (Horiz ↑: 2 | Horiz ↓: 0 | Vert: 1)`
- **Largest Plane:** `W: 2.91m  D: 0.42m  Area: 1.23 m²`
- **Candidate Surface:** `YES (Y: -0.31m, Area: 1.23m²)`
- **Performance:** `FPS: 59.8`
- Screenshot: `docs/screenshots/milestone3_planes_initial.png`

#### Full Room Scan (Multiple Surfaces & Walls):
- **Camera Pose:** `X: +0.403  Y: +0.252  Z: -0.028 m | Q: [0.36, -0.50, -0.60, 0.51]`
- **Detected Planes:** `Total: 13 (Horiz ↑: 10 | Horiz ↓: 0 | Vert: 3)`
- **Largest Plane:** `W: 2.99m  D: 2.22m  Area: 6.64 m²`
- **Candidate Surface:** `YES (Y: -0.72m, Area: 6.64m²)`
- **Performance:** `FPS: 59.8`
- Screenshot: `docs/screenshots/milestone3_planes_room_scan.png`

---

## 8. Performance Analysis

- **Frame Rate:** Sustained **59.8 – 60.0 FPS** on the Samsung Galaxy Tab S8+.
- **Minimum FPS Observed:** 58.9 FPS during rapid room scanning.
- **Garbage Collection Optimization:** Zero allocation per-frame inside `onDrawFrame()`.
  - Vertex buffers are pre-allocated (`MAX_VERTICES_PER_PLANE = 1024`).
  - Transformation matrices (`viewMatrix`, `projectionMatrix`, `viewProjectionMatrix`, `modelMatrix`, `mvpMatrix`) are statically pre-allocated.
  - UI telemetry updates are throttled to 10 Hz (`DIAGNOSTIC_UPDATE_INTERVAL_MS = 100L`), avoiding main thread congestion.

---

## 9. Architectural Limitations & Non-Assumptions

1. **Horizontal Upward-Facing $\neq$ Floor:**
   - A detected horizontal upward-facing plane can be a floor, table, desk, bed, chair, or shelf.
   - For Milestone 3, all upward surfaces are classified strictly as `Candidate Large Surface` or horizontal planes.
   - Reliable floor segmentation and height classification will be addressed in subsequent milestones using Depth API and spatial height heuristics.
2. **Convex Boundary Simplification:**
   - Triangle fan rendering assumes standard ARCore convex polygon outputs. Highly irregular concave boundaries may exhibit triangulation artifacts; robust Delaunay/ear-clipping triangulation will be introduced if complex concave room boundaries are required.
3. **No Unnecessary Anchors:**
   - Planes are tracked directly via ARCore's native `Plane` trackables without creating artificial anchors.
