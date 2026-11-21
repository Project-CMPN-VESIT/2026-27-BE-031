package com.visualassistant.perception

import android.graphics.Bitmap
import android.util.Log
import com.visualassistant.memory.SpatialMemory
import com.visualassistant.perception.hazard.HazardDetector
import com.visualassistant.perception.hazard.HazardEvent
import com.visualassistant.perception.object_detection.ObjectDetector
import com.visualassistant.perception.object_detection.ObjectTracker
import com.visualassistant.perception.ocr.OCRProcessor
import com.visualassistant.perception.ocr.TextRegion
import com.visualassistant.utils.Constants
import com.visualassistant.world.TrackedObject
import com.visualassistant.world.WorldModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * PerceptionPipeline orchestrates all perception modules.
 *
 * Frame scheduling:
 * - Object detection: every ~200ms (~5 FPS)
 * - Hazard detection: same pass as object detection
 * - Depth: not implemented in this milestone (uses bbox-based distance proxy)
 * - OCR: on-demand only
 *
 * Updates WorldModel and calls back onWorldModelUpdated.
 */
class PerceptionPipeline(
    private val objectDetector: ObjectDetector,
    private val ocrProcessor: OCRProcessor,
    private val hazardDetector: HazardDetector,
    private val spatialMemory: SpatialMemory,
    private val scope: CoroutineScope,
    private val isUserMovingProvider: () -> Boolean = { false },
    private val onWorldModelUpdated: (WorldModel) -> Unit
) {
    private val tracker = ObjectTracker()

    // Timestamps for throttling
    private val lastObjectDetectionMs = AtomicLong(0)
    private val lastOCRMs = AtomicLong(0)

    // Current world model (thread-safe read)
    @Volatile private var currentWorldModel = WorldModel()

    // Cached last OCR results (avoid re-running every frame)
    private var cachedTextRegions: List<TextRegion> = emptyList()

    /**
     * Process a camera frame. Called from the camera's analysis use case.
     * This is called ~30 FPS; internal throttling limits expensive operations.
     *
     * @param bitmap Current camera frame
     * @param isOCRRequested True if the user just asked to read text
     */
    fun processFrame(bitmap: Bitmap, isOCRRequested: Boolean = false) {
        val now = System.currentTimeMillis()
        val runOD = (now - lastObjectDetectionMs.get()) >= Constants.OBJECT_DETECTION_INTERVAL_MS
        val runOCR = isOCRRequested || (now - lastOCRMs.get()) >= 10_000L  // OCR auto every 10s in nav mode

        if (!runOD && !runOCR) return

        scope.launch(Dispatchers.Default) {
            try {
                var trackedObjects: List<TrackedObject> = tracker.getCurrentTracks()
                var textRegions: List<TextRegion> = cachedTextRegions
                var hazards: List<HazardEvent> = emptyList()

                // Object Detection
                if (runOD) {
                    lastObjectDetectionMs.set(now)
                    val detections = objectDetector.detect(bitmap)
                    trackedObjects = tracker.update(detections)

                    // Hazard detection runs in the same pass
                    val baseHazards = hazardDetector.detectHazards(trackedObjects).toMutableList()
                    hazardDetector.detectApproachingPerson(trackedObjects)?.let { baseHazards.add(it) }
                    hazardDetector.detectObstacleInPath(trackedObjects)?.let { baseHazards.add(it) }
                    hazards = baseHazards.distinctBy { it.type }
                }

                // OCR
                if (runOCR) {
                    lastOCRMs.set(now)
                    textRegions = ocrProcessor.recognizeText(bitmap, bitmap.width, bitmap.height)
                    cachedTextRegions = textRegions

                    // Process and passively remember detected signs and room numbers with spatial directions
                    for (region in textRegions) {
                        // Calculate relative position based on horizontal bounding box center
                        // Mirroring aware: centerX > 0.65f -> left, < 0.35f -> right, else ahead
                        val cx = region.boundingBox.centerX()
                        val relPos = when {
                            cx > 0.65f -> "to your left"
                            cx < 0.35f -> "to your right"
                            else -> "ahead"
                        }

                        if (region.isRoomNumber) {
                            val nearbyElevator = trackedObjects.find { it.label.equals("elevator", ignoreCase = true) }
                            spatialMemory.storeRoom(
                                roomNumber = region.text,
                                relativePosition = relPos,
                                nearbyLandmark = nearbyElevator?.label
                            )
                            Log.d(TAG, "Passively stored room in memory: ${region.text} ($relPos)")
                        } else if (region.isSign) {
                            // Store named place (library, cafeteria, restroom, etc.)
                            spatialMemory.storePlace(
                                name = region.text,
                                relativePosition = relPos
                            )
                            Log.d(TAG, "Passively stored place in memory: ${region.text} ($relPos)")
                        }
                    }
                }

                // Build navigation targets
                val navTargets = trackedObjects.filter {
                    it.label in setOf("door", "elevator", "exit", "stairs", "escalator")
                }

                // Also auto-store navigation landmarks in memory
                navTargets.forEach { target ->
                    spatialMemory.storeLandmark(
                        label = target.label,
                        relativePosition = target.position.toDescription(),
                        contextDescription = "${target.distanceCategory.name.lowercase()} distance"
                    )
                }

                // Update world model
                val newWorldModel = WorldModel(
                    timestampMs = now,
                    trackedObjects = trackedObjects,
                    detectedText = textRegions,
                    hazards = trackedObjects.filter { obj ->
                        hazards.any { h ->
                            com.visualassistant.perception.hazard.HazardType.fromLabel(obj.label) != null
                        }
                    },
                    navigationTargets = navTargets,
                    isUserMoving = isUserMovingProvider()
                )

                currentWorldModel = newWorldModel

                // Notify on main thread
                withContext(Dispatchers.Main) {
                    onWorldModelUpdated(newWorldModel)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Perception pipeline error: ${e.message}")
            }
        }
    }

    /**
     * Trigger an immediate OCR pass on the next frame.
     */
    fun requestOCR() {
        lastOCRMs.set(0)  // Force OCR on next frame
    }

    /**
     * Get the current world model.
     */
    fun getCurrentWorldModel(): WorldModel = currentWorldModel

    /**
     * Reset all tracking state.
     */
    fun reset() {
        tracker.reset()
        cachedTextRegions = emptyList()
        currentWorldModel = WorldModel()
        lastObjectDetectionMs.set(0)
        lastOCRMs.set(0)
    }

    private fun com.visualassistant.world.SpatialPosition.toDescription() = when (this) {
        com.visualassistant.world.SpatialPosition.LEFT -> "to your left"
        com.visualassistant.world.SpatialPosition.CENTER -> "ahead"
        com.visualassistant.world.SpatialPosition.RIGHT -> "to your right"
        else -> "nearby"
    }

    companion object {
        private const val TAG = "PerceptionPipeline"
    }
}
