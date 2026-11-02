package com.visualassistant.perception.hazard

import com.visualassistant.world.SpatialPosition

/**
 * A detected hazard event.
 *
 * Hazards have higher priority than normal objects and trigger different behavior.
 */
data class HazardEvent(
    val type: HazardType,
    val position: SpatialPosition,
    val confidence: Float,
    val isInPath: Boolean,
    val timestampMs: Long = System.currentTimeMillis(),
    val label: String? = null
) {
    fun toSpeechText(): String = when (type) {
        HazardType.FIRE -> {
            val name = label?.replaceFirstChar { it.uppercase() } ?: "Fire"
            when {
                isInPath -> "$name ahead."
                else -> "$name ${position.toDescription()}."
            }
        }
        HazardType.SHARP_OBJECT -> {
            val name = label?.replaceFirstChar { it.uppercase() } ?: "Sharp object"
            when {
                isInPath -> "$name ahead."
                else -> "$name ${position.toDescription()}."
            }
        }
        HazardType.VEHICLE -> {
            val name = label?.replaceFirstChar { it.uppercase() } ?: "Vehicle"
            "$name ${position.toDescription()}."
        }
        HazardType.STAIRS -> when {
            isInPath -> "Stairs begin ahead."
            else -> "Stairs ${position.toDescription()}."
        }
        HazardType.OBSTACLE_IN_PATH -> {
            val name = label?.replaceFirstChar { it.uppercase() } ?: "Obstacle"
            "$name directly ahead."
        }
        HazardType.BICYCLE -> "Bicycle ${position.toDescription()}."
        HazardType.PERSON_APPROACHING -> "A person is approaching from ${position.toDescription()}."
        HazardType.UNKNOWN -> "Potential hazard ${position.toDescription()}."
    }

    fun priority(): Int = type.priority
}

enum class HazardType(val priority: Int) {
    FIRE(100),
    SHARP_OBJECT(100),   // Same critical priority as fire — knives/blades are life-safety
    VEHICLE(95),
    OBSTACLE_IN_PATH(90),
    STAIRS(80),
    BICYCLE(70),
    PERSON_APPROACHING(65),
    UNKNOWN(50);

    companion object {
        fun fromLabel(label: String): HazardType? = when (label.lowercase()) {
            "fire", "flame", "oven", "toaster" -> FIRE
            "knife", "scissors", "blade", "cutter" -> SHARP_OBJECT
            "car", "truck", "bus", "motorcycle", "vehicle" -> VEHICLE
            "stairs", "staircase", "steps" -> STAIRS
            "bicycle", "bike" -> BICYCLE
            else -> null
        }
    }
}
