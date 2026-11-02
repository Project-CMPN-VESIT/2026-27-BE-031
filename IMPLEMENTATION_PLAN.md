# Visual Assistant: Next-Generation Feature Implementation Plan

> **Goal:** Transform the visual assistant into a world-class, empathetic mobility and spatial awareness tool for visually impaired and blind users.

---

## 📋 Task Checklist & Status

### Phase 1: Critical Danger & Hazard System
- [x] **Task 1.1: High-Priority Danger Alert Engine** ✅ *Completed 2026-11-02*
  - Detect sharp objects (knife, scissors, blade, cutter) and fire/flame.
  - Speaks the exact label clearly: `"Knife ahead."` / `"Fire to your left."`
  - 60-second cooldown — announced **once**, never repeated on loop.
  - Always interrupts any ongoing lower-priority speech.
  - Priority raised to CRITICAL (same as FIRE) for all sharp objects.
- [ ] **Task 1.2: Haptic Alarm & Upper Frame (Head-Height) Hazard Detection**
  - Add distinct vibration patterns (Vibrator API) for immediate physical alert.
  - Detect obstacles in the upper 40% of the camera frame (tree branches, low signs, open doors).
  - Test haptic triggers and verify no audio clutter.

---

### Phase 2: Spatial & Landmark Memory ("Remember & Guide")
- [ ] **Task 2.1: Persistent Memory Database (Room DB)**
  - Migrate `SpatialMemory` from in-memory map to Android Room SQLite database.
  - Schema: `label`, `customName`, `latitude`, `longitude`, `compassHeading`, `stepOffset`, `nearbyLandmarks`, `timestamp`.
  - Test database insertion, retrieval, and persistence across app restarts.
- [ ] **Task 2.2: Voice Bookmarking & Recall Intents**
  - Support commands:
    - `"Remember this location as the library"`
    - `"Remember here as my office"`
    - `"Where is the library?"`
    - `"Guide me to the library"`
  - Map intents in `IntentParser` and verify parsing unit tests.
- [ ] **Task 2.3: Visual Search & Clock-Face Guidance**
  - Target search mode with clock-face directions (`"Library doors at 12 o'clock, approximately 4 meters away"`).
  - Guide the user with rotation prompts (`"Turn slightly to your 2 o'clock"`).
  - Test guidance state machine from search to arrival.

---

### Phase 3: GPS Outdoor Macro-Navigation & Haptic Compass
- [ ] **Task 3.1: Fused Location Provider & Geofencing**
  - Integrate `LocationServices.getFusedLocationProviderClient`.
  - Periodic location updates for outdoor walking mode.
  - Geofenced landmark alerts: notify when within 20m of a remembered outdoor location.
- [ ] **Task 3.2: "Where Am I?" Reverse Geocoding**
  - Voice query: `"Where am I?"`
  - Fetch street address using Android `Geocoder` and speak calmly.
- [ ] **Task 3.3: Haptic Compass Wayfinding**
  - Use device Magnetometer/Accelerometer to track compass heading.
  - Calculate bearing to the target destination.
  - Pulse vibration when the user points their phone directly toward the destination.

---

### Phase 4: Gemini Multimodal Cloud Fallback
- [ ] **Task 4.1: Secure API Key Storage**
  - Move Gemini API key from hardcoded code to `local.properties` / `BuildConfig`.
- [ ] **Task 4.2: Multimodal Scene Q&A**
  - Support queries like: `"Describe in detail"`, `"Read this document"`, `"What is on the table?"`.
  - Handle offline graceful degradation with clear audio feedback.

---

## 🛠️ Testing Protocol (Before Marking Tasks Complete)
Each task must pass:
1. **Compilation Check**: No unresolved symbols or missing imports.
2. **Unit / Logic Verification**: Ensure edge cases (e.g., no GPS signal, offline network, missing camera frame) fail gracefully.
3. **User Experience Verification**: Zero screaming/overwhelm, priority interruption for actual dangers, and clear clock-face directions.
