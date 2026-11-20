package com.visualassistant.perception.hazard

import com.visualassistant.perception.object_detection.DetectedObject
import com.visualassistant.world.TrackedObject
import com.visualassistant.utils.Constants

/**
 * HazardDetector filters tracked objects for hazardous entities.
 *
 * Strategy:
 * 1. Filter COCO classes known to be hazardous (knife, scissors, vehicle)
 * 2. Classify hazard type
 * 3. Apply lower confidence threshold for safety
 * 4. Generate HazardEvent
 *
 * Note: Fire is NOT in COCO classes. The system generates a "possible fire"
 * heuristic if bright orange/red color occupies a large portion of the frame.
 * A dedicated fire model can be added as a drop-in replacement.
 */
class HazardDetector {

    /**
     * Extract hazard events from the current set of tracked objects.
     */
    fun detectHazards(trackedObjects: List<TrackedObject>): List<HazardEvent> {
        val hazards = mutableListOf<HazardEvent>()

        for (obj in trackedObjects) {
            val hazardType = HazardType.fromLabel(obj.label) ?: continue

            // Apply lower confidence threshold for safety-critical objects
            if (obj.confidence < Constants.HAZARD_CONFIDENCE) continue

            val surfaceContext = findSupportingSurface(obj, trackedObjects)

            hazards.add(
                HazardEvent(
                    type = hazardType,
                    position = obj.position,
                    confidence = obj.confidence,
                    isInPath = obj.isInPath,
                    label = obj.label,
                    surfaceContext = surfaceContext
                )
            )
        }

        // Sort by priority descending
        return hazards.sortedByDescending { it.priority() }
    }

    /**
     * Check if any object represents a person approaching (special hazard case).
     */
    fun detectApproachingPerson(trackedObjects: List<TrackedObject>): HazardEvent? {
        val approachingPerson = trackedObjects.find {
            it.label.equals("person", ignoreCase = true) && it.isApproaching
        } ?: return null

        return HazardEvent(
            type = HazardType.PERSON_APPROACHING,
            position = approachingPerson.position,
            confidence = approachingPerson.confidence,
            isInPath = approachingPerson.isInPath,
            label = "person"
        )
    }

    /**
     * Check if any object in path is a collision risk.
     */
    fun detectObstacleInPath(trackedObjects: List<TrackedObject>): HazardEvent? {
        val obstacle = trackedObjects.find { obj ->
            obj.isInPath &&
            obj.distanceCategory == com.visualassistant.perception.object_detection.DistanceCategory.VERY_CLOSE &&
            obj.label !in setOf("person")  // Person approaching handled separately
        } ?: return null

        val surfaceContext = findSupportingSurface(obstacle, trackedObjects)

        return HazardEvent(
            type = HazardType.OBSTACLE_IN_PATH,
            position = obstacle.position,
            confidence = obstacle.confidence,
            isInPath = true,
            label = obstacle.label,
            surfaceContext = surfaceContext
        )
    }

    /**
     * Determine if an object is resting on or supported by a surface (e.g. "on the desk", "on the table").
     */
    fun findSupportingSurface(obj: TrackedObject, allObjects: List<TrackedObject>): String? {
        val boxA = obj.lastDetectedObject.boundingBox

        // 1. Check if obj overlaps with an explicit surface object in the scene
        for (other in allObjects) {
            if (other.id == obj.id) continue
            val label = other.label.lowercase()
            val isSurface = label in setOf("dining table", "table", "desk", "counter", "couch", "sofa", "bed", "chair", "sink", "bench")
            if (!isSurface) continue

            val boxS = other.lastDetectedObject.boundingBox
            val horizontalOverlap = maxOf(0f, minOf(boxA.right, boxS.right) - maxOf(boxA.left, boxS.left))
            val verticalOverlap = maxOf(0f, minOf(boxA.bottom, boxS.bottom) - maxOf(boxA.top, boxS.top))

            val overlaps = (horizontalOverlap > 0.1f * boxA.width() && verticalOverlap > 0f) ||
                    boxS.contains(boxA.centerX(), boxA.centerY()) ||
                    (other.position == obj.position && boxA.top >= boxS.top - 0.25f && boxA.bottom <= boxS.bottom + 0.25f)

            if (overlaps) {
                return when {
                    label.contains("table") || label.contains("desk") -> "on the desk"
                    label.contains("couch") || label.contains("sofa") -> "on the couch"
                    label.contains("bed") -> "on the bed"
                    label.contains("chair") -> "on the chair"
                    label.contains("sink") -> "in the sink"
                    label.contains("counter") -> "on the counter"
                    else -> "on the table"
                }
            }
        }

        // 2. If any table/desk is detected in the scene at the same horizontal position
        val tableAtSamePos = allObjects.find { 
            (it.label.contains("table", true) || it.label.contains("desk", true)) &&
            it.position == obj.position
        }
        if (tableAtSamePos != null) {
            return "on the desk"
        }

        // 3. If obj is a small tabletop item (knife, scissors, blade, cutter, cup, fork)
        // and other tabletop items (laptop, mouse, keyboard, cup, book, bottle) or table is present
        val isTabletopHazard = obj.label.lowercase() in setOf("knife", "scissors", "blade", "cutter", "fork", "bottle", "cup")
        val hasTabletopContext = allObjects.any {
            it.id != obj.id && it.label.lowercase() in setOf("dining table", "table", "desk", "laptop", "keyboard", "mouse", "cup", "book", "bottle")
        }
        if (isTabletopHazard && (hasTabletopContext || obj.lastDetectedObject.centerY > 0.35f)) {
            return "on the desk"
        }

        return null
    }
}
