# Visual Assistant - Development Tickets (Jira-style Tracking)

> **Project:** Visual Assistant for Visually Impaired (VQA)  
> **Workspace:** `C:\Users\NIKHIL\Documents\CapstoneImpl\VQA`  
> **Main Tech Stack:** Android (Kotlin, Jetpack Compose, CameraX, TensorFlow Lite, ML Kit Text Recognition, Google Play Services Location & Maps, Google Directions API, Gemini 1.5 Flash)  
> **Maintainer Context:** This file tracks all feature tickets, architectural specifications, technical decisions, and current progress. Any AI assistant or developer reading this can pick up work seamlessly from the ticket statuses, implementation details, and verification logs below.

---

## Ticket Overview & Status Board

| Ticket ID | Epic / Feature | Status | Priority | Description |
|-----------|----------------|--------|----------|-------------|
| **VA-101** | Motion-Aware Narration | **DONE** | High | Detect idle vs. walking; say *"X object ahead"* if stationary, evasive instructions only when moving |
| **VA-102** | Passive Landmark Auto-Memory | **DONE** | High | Passively capture room numbers and signs (Library, Cafeteria, etc.); answer location queries from memory |
| **VA-103** | Google Maps & Directions Integration | **DONE** | Medium-High | Support *"Take me to [place]"*, fetch route options/feasibility via Google Directions API & summarize turn-by-turn |
| **VA-104** | Anti-Spam Speech & Smart Priority Filtering | **DONE** | Critical | Fix continuous spamming; rate-limit proactive narrations (12s), announce only the #1 prioritized obstacle ahead |
| **VA-105** | Object Detection Accuracy & Aspect Ratio Fix | **DONE** | Critical | Fix severe squishing distortion via letterbox preprocessing, box unmapping, NMS deduplication, and 720p frame resolution |

---

## Detailed Tickets

---

### [VA-101] Motion-Aware Narration & Dynamic Evasion Guidance
- **Issue Type:** Story / Feature
- **Component:** `perception.motion.MotionDetector`, `assistant.reasoning.ResponseGenerator`, `world.WorldModel`, `assistant.AssistantEngine`
- **Status:** **DONE**
- **Description:**
  Evasive navigation instructions (e.g. *"X obstacle, move slightly left/right"*) are now conditioned on whether the user is actively walking vs. idle/stationary.
  - **Idle / Stationary:** The assistant states *"X object ahead"* or *"X is in front of you"*, without directional steering or evasion commands.
  - **Walking / Moving:** The assistant provides directional evasion: *"X obstacle in path, move slightly left"* / *"move slightly right"* / *"move to either side"*.
- **Implementation Specifics:**
  1. Created `com.visualassistant.perception.motion.MotionDetector` using Android hardware accelerometer (`Sensor.TYPE_ACCELEROMETER`), tracking rolling-window magnitude variance (30 samples, ~1.5s). Rhythmic accelerations (>0.5 m/s² variance) indicate walking; stationary users have <0.2 m/s² variance. Includes 2-second hold-time hysteresis to prevent jitter.
  2. Added `isUserMoving: Boolean` to `WorldModel`.
  3. Wired `MotionDetector` into `PerceptionPipeline` and `VisualAssistantViewModel` (started/stopped with assistant lifecycle).
  4. Updated `ResponseGenerator.generateHazardWarning()`: if `!isUserMoving`, returns only the hazard warning text without appending directional evasion instructions.
  5. Updated `ResponseGenerator.describeObjectBriefly()`: stationary users hear concise informational phrases (`"[Object] ahead"`), while moving users hear evasion cues (`"[Object] ahead in path, move slightly left or right"`).
- **Files Modified/Created:**
  - `app/src/main/java/com/visualassistant/perception/motion/MotionDetector.kt` (NEW)
  - `app/src/main/java/com/visualassistant/world/WorldModel.kt`
  - `app/src/main/java/com/visualassistant/perception/PerceptionPipeline.kt`
  - `app/src/main/java/com/visualassistant/assistant/reasoning/ResponseGenerator.kt`
  - `app/src/main/java/com/visualassistant/VisualAssistantViewModel.kt`
- **Acceptance Criteria:**
  - [x] Stationary user seated or standing still hears *"Chair ahead"* or *"Person ahead"*, NOT *"move slightly left"*.
  - [x] Walking user approaching an obstacle receives evasion guidance *"Person ahead, move slightly left or right"*.
  - [x] Hardware accelerometer listener lifecycle cleanly managed with start/stop on ViewModel.

---

### [VA-102] Passive Landmark Auto-Memory (Room Numbers & Places like Library)
- **Issue Type:** Story / Feature
- **Component:** `perception.PerceptionPipeline`, `perception.ocr.TextRegion`, `memory.SpatialMemory`, `assistant.intent.IntentParser`, `assistant.reasoning.ResponseGenerator`
- **Status:** **DONE**
- **Description:**
  When a blind user walks past room numbers (e.g. *"Room 204"*) or named place signs (e.g. *"LIBRARY"*, *"CAFETERIA"*, *"RESTROOM"*, *"OFFICE"*, *"LAB"*), the app automatically extracts the text and relative horizontal position (left/right/ahead based on bounding box `centerX()`) and stores it in `SpatialMemory` with a timestamp. When the user later asks *"Where is the library?"* or *"Where is Room 204?"*, the app answers with the spatial direction and elapsed time.
- **Implementation Specifics:**
  1. Extended `SpatialMemory` with `storePlace(name, relativePosition, contextDescription)` and `findPlace(query)` supporting fuzzy/substring matching and individual word indexing (e.g. "Library" matches "City Central Library").
  2. Added `SpatialMemory.formatTimeAgo(timestampMs)` returning natural strings like *"just now"*, *"about 2 minutes ago"*, *"about 15 minutes ago"*.
  3. In `PerceptionPipeline`, automated OCR processing on camera frames classifies signs and calculates relative direction via camera-sensor-aware horizontal center (`cx > 0.65f -> "to your left"`, `cx < 0.35f -> "to your right"`, else `"ahead"`).
  4. In `IntentParser`, supported queries: `"where is the library"`, `"where was the cafeteria"`, `"have you seen the restroom"` mapping to `UserIntent.QueryMemory` and `UserIntent.FindObject`.
  5. In `AssistantEngine.onUserSpeech`, if `FindObject` is not in the immediate camera view, it checks `spatialMemory.findPlace(label)` and answers from memory before bothering cloud fallback.
  6. In `ResponseGenerator.queryMemory()` and `findObject()`, formatted natural responses: *"I don't see the library in front of you right now, but you passed it to your left about 3 minutes ago."*
- **Files Modified/Created:**
  - `app/src/main/java/com/visualassistant/memory/SpatialMemory.kt`
  - `app/src/main/java/com/visualassistant/perception/PerceptionPipeline.kt`
  - `app/src/main/java/com/visualassistant/assistant/intent/IntentParser.kt`
  - `app/src/main/java/com/visualassistant/assistant/reasoning/ResponseGenerator.kt`
  - `app/src/main/java/com/visualassistant/assistant/AssistantEngine.kt`
- **Acceptance Criteria:**
  - [x] Walking past signs like "LIBRARY" or "Room 204" automatically saves them in spatial memory with side ("to your left" / "to your right") and timestamp.
  - [x] Asking *"Where is the library?"* or *"Where was the cafeteria?"* answers accurately from memory when out of view.
  - [x] Asking for a currently visible object/room prioritizes the current camera view.

---

### [VA-103] Outdoor Navigation via Google Maps & Directions API ("Take me to [place]")
- **Issue Type:** Story / Feature
- **Component:** `navigation.MapsDirectionsService`, `assistant.intent.UserIntent`, `assistant.intent.IntentParser`, `assistant.AssistantEngine`, `VisualAssistantViewModel`
- **Status:** **DONE**
- **Description:**
  When the user says *"Take me to [destination]"*, *"Navigate to [destination]"*, or *"How do I get to [destination]"*, the app determines the user's current GPS location via `FusedLocationProviderClient`, calls Google Maps Directions API (`mode=walking`, `alternatives=true`), checks feasibility on foot, and summarizes the route turn-by-turn for speech.
- **Implementation Specifics:**
  1. Created `com.visualassistant.navigation.MapsDirectionsService` with asynchronous REST integration using `HttpURLConnection` (zero external dependency bloat).
  2. Implemented `FusedLocationProviderClient` GPS resolution with high accuracy and cancellation support.
  3. Implemented JSON route parser:
     - Feasibility check: Calculates distance and walking duration. Flags routes > 10 km as too far on foot and suggests public transit/taxi.
     - Primary route summary: Strips HTML step tags and summarizes first 2-3 key walking maneuvers with distances.
     - Alternative route listing: Mentions alternative paths (e.g. *"Also available: via Elm Street taking 18 mins"*).
     - Graceful fallback: Clean spoken guidance if offline, GPS disabled, or missing API key.
  4. Added `data class NavigateTo(val destination: String) : UserIntent()` in `UserIntent.kt`.
  5. Updated `IntentParser` to distinguish outdoor navigation (`"take me to central park"`) from indoor targets (`"take me to the exit"`, `"take me to the elevator"`, `"take me to room 204"`).
  6. Connected `assistantEngine.onNavigateRequested` in `VisualAssistantViewModel` to invoke `MapsDirectionsService.getDirections()` and speak results via TTS.
- **Files Modified/Created:**
  - `app/src/main/java/com/visualassistant/navigation/MapsDirectionsService.kt` (NEW)
  - `app/src/main/java/com/visualassistant/assistant/intent/UserIntent.kt`
  - `app/src/main/java/com/visualassistant/assistant/intent/IntentParser.kt`
  - `app/src/main/java/com/visualassistant/assistant/AssistantEngine.kt`
  - `app/src/main/java/com/visualassistant/VisualAssistantViewModel.kt`
- **Acceptance Criteria:**
  - [x] Phrases like *"Take me to Central Park"* or *"Navigate to Starbucks"* trigger `NavigateTo` intent.
  - [x] Indoor commands (`"take me to room 204"`, `"take me to exit"`) continue to route to local navigation tasks.
  - [x] Spoken summary informs the user of walking feasibility, primary path with turn instructions, and alternative routes.

---

### [VA-104] Anti-Spam Speech & Smart Priority Filtering
- **Issue Type:** Bug / Quality of Life
- **Component:** `assistant.AssistantEngine`, `assistant.reasoning.ResponseGenerator`, `utils.Constants`
- **Status:** **DONE**
- **Description:**
  Fixed continuous, repetitive speech spam caused by unstable substring cooldown keys (`narration.take(20)`) and lack of scene change detection.
- **Implementation Specifics:**
  1. **Global Exploration Speech Rate Limit:** Added `Constants.PROACTIVE_NARRATION_INTERVAL_MS = 12_000L`. Assistant enforces at least 12 seconds between proactive exploration narrations.
  2. **Semantic Cooldown Keys:** Cooldown keys are now strictly based on object label and position (`"proactive_${obj.label}_${obj.position.name}"`), eliminating key jitter caused by slight wording/distance variations.
  3. **Scene Change Detection & Stationary Suppression:** The assistant tracks `lastAnnouncedLabel`, `lastAnnouncedPosition`, and `lastAnnouncedDistance`. If the scene is unchanged (e.g. user sitting at a desk with the same chair ahead), speech is suppressed until a 30-second reminder cooldown (`Constants.PROACTIVE_REPEAT_SAME_OBJECT_MS = 30_000L`).
  4. **Single-Obstacle Prioritization:** Refactored `ResponseGenerator.generateProactiveNarration()` to pick only the single most critical obstacle directly ahead, avoiding long run-on sentences.
  5. **Hazard Isolation:** If a safety hazard is announced during a frame update, exploration narration is automatically skipped in that cycle.
- **Files Modified/Created:**
  - `app/src/main/java/com/visualassistant/utils/Constants.kt`
  - `app/src/main/java/com/visualassistant/assistant/AssistantEngine.kt`
  - `app/src/main/java/com/visualassistant/assistant/reasoning/ResponseGenerator.kt`
- **Acceptance Criteria:**
  - [x] Standing or sitting still in front of an object produces at most one calm announcement, then stays silent.
  - [x] Minimum 12-second interval between proactive exploration narrations.
  - [x] Only the top prioritized obstacle ahead is narrated.
  - [x] Critical safety hazards (sharp objects, fire) retain immediate priority interruption.

---

### [VA-105] Object Detection Accuracy, Aspect Ratio Letterboxing & NMS
- **Issue Type:** Bug / Quality of Service
- **Component:** `perception.object_detection.ObjectDetector`, `camera.CameraManager`, `utils.Constants`
- **Status:** **DONE**
- **Description:**
  Addressed poor object identification accuracy where the model failed to recognize everyday objects or reported garbage noise detections.
- **Root Cause Identified:**
  The camera delivers portrait frames (~9:16 aspect ratio). The original code used `Bitmap.createScaledBitmap(bitmap, 320, 320, true)`, which non-uniformly squished the vertical dimensions by ~50%. In Python empirical verification, this caused object detection confidence to collapse from 31% to 1-4% (lost beneath the 0.30 threshold).
- **Implementation Specifics:**
  1. **Aspect-Ratio-Preserving Letterboxing (`ObjectDetector.kt`):** Implemented `letterboxBitmap()` to scale the image proportionally to fit within 320×320 and pad remaining borders with neutral grey (`rgb(127,127,127)`).
  2. **Bounding Box Unmapping (`unmapBox`):** Normalized coordinates returned by EfficientDet-Lite0 in 320×320 letterbox space are mathematically unmapped back to original frame space `[0,1]` by subtracting padding offsets and dividing by scaled dimensions.
  3. **Non-Maximum Suppression (`applyNMS`):** Added class-aware IoU-based NMS (threshold = 0.45) to remove redundant multi-box overlaps for the same physical object.
  4. **Placeholder `???` Label Filtering:** Filtered out unassigned class slots (`???`, `unknown`, blank) from COCO label mapping.
  5. **Camera Stream Resolution (`CameraManager.kt`):** Upgraded `ImageAnalysis` target resolution to `1280x720` (from default low-res preview) so that smaller or distant objects preserve sufficient pixel detail before inference.
  6. **Confidence Threshold Optimization:** Tuned `Constants.OBJECT_DETECTION_CONFIDENCE` from `0.30f` to `0.20f` to capture valid detections while blocking background class noise.
  7. **Thread Safety:** Added `interpreterLock` serialization across TFLite inference and delegate operations.
- **Files Modified/Created:**
  - `app/src/main/java/com/visualassistant/perception/object_detection/ObjectDetector.kt`
  - `app/src/main/java/com/visualassistant/camera/CameraManager.kt`
  - `app/src/main/java/com/visualassistant/utils/Constants.kt`
- **Acceptance Criteria:**
  - [x] Aspect ratio preserved without squishing or stretching.
  - [x] Letterbox padding inverted so output bounding boxes accurately map to the user's camera view.
  - [x] Overlapping duplicate boxes suppressed via NMS.
  - [x] Placeholder/corrupt labels filtered out.

---

## Verification & Build Log

- **Unit Tests:** `gradle testDebugUnitTest` — **PASSED** (24 tests completed, 0 failed).
- **Compilation & Packaging:** `gradle assembleDebug` — **BUILD SUCCESSFUL** (output APK generated at `app/build/outputs/apk/debug/app-debug.apk`).
- **All 5 tickets marked DONE.**
