package com.visualassistant.memory

import android.content.Context
import com.visualassistant.utils.Constants
import com.visualassistant.world.TrackedObject

/**
 * SpatialMemory — high-level API for storing and retrieving environmental memory.
 *
 * Stores:
 * - Room numbers detected by OCR
 * - Important landmarks (elevator, door, exit, reception)
 * - User-remembered locations
 *
 * Uses an in-memory map for fast access, backed by Room database for persistence.
 */
class SpatialMemory(context: Context) {

    // In-memory store: label → list of entries (most recent first)
    private val memoryMap = mutableMapOf<String, MutableList<MemoryEntry>>()
    private val roomMemory = mutableMapOf<String, MemoryEntry>()  // roomNumber → entry
    private val placeMemory = mutableMapOf<String, MemoryEntry>() // placeName → entry

    // TODO: Room DB integration for persistence across sessions
    // private val db = MemoryDatabase.getDatabase(context)

    /**
     * Store a detected named place (e.g. "Library", "Cafeteria", "Restroom")
     */
    fun storePlace(
        name: String,
        relativePosition: String,
        contextDescription: String? = null,
        floor: Int? = null
    ) {
        val cleanName = name.trim()
        val entry = MemoryEntry(
            label = "place",
            text = cleanName,
            relativePosition = relativePosition,
            contextDescription = contextDescription,
            floor = floor,
            timestampMs = System.currentTimeMillis()
        )
        val lowerKey = cleanName.lowercase()
        placeMemory[lowerKey] = entry
        storeLandmark(lowerKey, relativePosition, contextDescription, floor)

        // Also index individual significant words (e.g., "Library" from "Central Library")
        cleanName.split(Regex("\\s+")).forEach { word ->
            val cleanWord = word.lowercase().trim()
            if (cleanWord.length > 3 && cleanWord !in setOf("room", "hall", "near", "side")) {
                placeMemory[cleanWord] = entry
            }
        }
    }

    /**
     * Find a place by name or keyword using fuzzy / substring matching.
     */
    fun findPlace(query: String): MemoryEntry? {
        val q = query.trim().lowercase()
        // 1. Direct match in placeMemory
        placeMemory[q]?.let { return it }

        // 2. Substring match in placeMemory
        val subMatch = placeMemory.entries.find { (key, entry) ->
            key.contains(q) || q.contains(key) || (entry.text?.contains(q, ignoreCase = true) == true)
        }
        if (subMatch != null) return subMatch.value

        // 3. Check room memory
        val roomEntry = roomMemory[q.uppercase()] ?: roomMemory.entries.find { it.key.contains(q, ignoreCase = true) }?.value
        if (roomEntry != null) return roomEntry

        // 4. Fallback to generic landmarks
        return findRecent(q)
    }

    /**
     * Format timestamp into natural language time description.
     */
    fun formatTimeAgo(timestampMs: Long): String {
        val elapsedSec = (System.currentTimeMillis() - timestampMs) / 1000
        val elapsedMin = elapsedSec / 60
        return when {
            elapsedMin < 1 -> "just now"
            elapsedMin == 1L -> "about 1 minute ago"
            elapsedMin < 60 -> "about $elapsedMin minutes ago"
            else -> "earlier today"
        }
    }

    /**
     * Store a detected landmark (elevator, door, exit, etc.)
     */
    fun storeLandmark(
        label: String,
        relativePosition: String,
        contextDescription: String? = null,
        floor: Int? = null
    ) {
        val entry = MemoryEntry(
            label = label,
            relativePosition = relativePosition,
            contextDescription = contextDescription,
            floor = floor
        )
        memoryMap.getOrPut(label.lowercase()) { mutableListOf() }.apply {
            add(0, entry)  // most recent first
            if (size > 10) removeLast()  // cap entries per label
        }
    }

    /**
     * Store a detected room number.
     */
    fun storeRoom(
        roomNumber: String,
        relativePosition: String,
        nearbyLandmark: String? = null,
        floor: Int? = null
    ) {
        val contextDesc = if (nearbyLandmark != null) "near $nearbyLandmark" else null
        val entry = MemoryEntry(
            label = "room",
            text = roomNumber,
            relativePosition = relativePosition,
            contextDescription = contextDesc,
            floor = floor
        )
        roomMemory[roomNumber.uppercase()] = entry
        storeLandmark("room $roomNumber", relativePosition, contextDesc, floor)
    }

    /**
     * Store the current location as user-bookmarked.
     */
    fun storeCurrentLocation(trackedObject: TrackedObject?) {
        if (trackedObject != null) {
            storeLandmark(
                label = trackedObject.label,
                relativePosition = trackedObject.position.toDescription(),
                contextDescription = "User-bookmarked"
            )
        }
    }

    /**
     * Find the most recent memory entry for a label.
     */
    fun findRecent(label: String): MemoryEntry? =
        memoryMap[label.lowercase()]?.firstOrNull()

    /**
     * Find a room entry by room number.
     */
    fun findRoom(roomNumber: String): MemoryEntry? =
        roomMemory[roomNumber.uppercase()]

    /**
     * Get the most recently seen room number.
     */
    fun getMostRecentRoom(): String? {
        return roomMemory.entries
            .maxByOrNull { it.value.timestampMs }
            ?.key
    }

    /**
     * Get all stored landmark labels.
     */
    fun getAllLandmarks(): List<String> = memoryMap.keys.toList()

    /**
     * Clear all memory.
     */
    fun clear() {
        memoryMap.clear()
        roomMemory.clear()
        placeMemory.clear()
    }

    /**
     * Summarize all memory for debugging.
     */
    fun toDebugString(): String = buildString {
        append("SpatialMemory:\n")
        append("  Rooms: ${roomMemory.keys.joinToString(", ")}\n")
        append("  Places: ${placeMemory.keys.joinToString(", ")}\n")
        append("  Landmarks: ${memoryMap.keys.joinToString(", ")}\n")
    }

    // Extension for position description
    private fun com.visualassistant.world.SpatialPosition.toDescription() = when (this) {
        com.visualassistant.world.SpatialPosition.LEFT -> "to your left"
        com.visualassistant.world.SpatialPosition.CENTER -> "ahead"
        com.visualassistant.world.SpatialPosition.RIGHT -> "to your right"
        else -> "nearby"
    }
}
