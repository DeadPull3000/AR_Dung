# Project Engineering Rules

These rules must be adhered to by all agents working on this project.

1. **Native Android/Kotlin:** All development must be in Kotlin and use the native Android SDK.
2. **Native ARCore:** Use native Google ARCore for spatial tracking and AR functionalities.
3. **OpenGL ES for rendering:** Use OpenGL ES directly for 3D rendering and graphics.
4. **No Game Engines:** Do NOT use Unity, Unreal Engine, or any other game engine. This is an Embedded Systems project.
5. **No Invented/Deprecated APIs:** Use only official, current APIs. 
6. **Source of Truth:** Official Android and ARCore documentation is the absolute source of truth. Consult it rather than guessing.
7. **Thread Management:** Do NOT perform expensive work (e.g., pathfinding, mesh generation, heavy ARCore processing) on the UI thread.
8. **Memory Management:** Avoid unnecessary per-frame memory allocation. We are writing performance-sensitive code.
9. **ARCore Anchors:** Avoid unnecessary ARCore anchors to conserve system resources.
10. **Separation of Concerns:** Separate perception (ARCore), environment modelling (grid), navigation (A*), AI (state machines), and rendering (OpenGL) into distinct modules.
11. **Scoped Changes:** Do not rewrite unrelated modules when implementing a specific feature.
12. **Preserve Functionality:** Preserve existing working functionality when making changes.
13. **Continuous Verification:** Build and test after meaningful changes. Leave the project in a runnable state.
14. **MVP Focus:** Prefer simple MVP implementations over premature complexity. We are building bottom-up.
15. **Debuggability:** Every major subsystem should eventually have a debug visualization or test mode (e.g., drawing the A* path or occupancy grid).
16. **Experimental Code:** Clearly distinguish experimental code from production/core code.
