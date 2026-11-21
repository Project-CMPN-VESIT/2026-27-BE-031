package com.visualassistant.assistant.reasoning

import com.visualassistant.assistant.intent.UserIntent
import com.visualassistant.assistant.priority.Priority
import com.visualassistant.memory.SpatialMemory
import com.visualassistant.navigation.NavigationTask
import com.visualassistant.perception.hazard.HazardEvent
import com.visualassistant.world.TrackedObject
import com.visualassistant.world.WorldModel
import com.visualassistant.world.SpatialPosition
import com.visualassistant.perception.object_detection.DistanceCategory

/**
 * ResponseGenerator converts WorldModel + UserIntent + Memory into natural language responses.
 *
 * This is a template-based NLG (Natural Language Generation) engine — no LLM required.
 * Responses are designed to be concise, clear, and useful for visually impaired users.
 */
class ResponseGenerator {

    /**
     * Generate a response to a user question/command given the current world state.
     */
    fun generateResponse(
        intent: UserIntent,
        world: WorldModel,
        currentTask: NavigationTask,
        memory: SpatialMemory,
        lastMentionedLabel: String?
    ): String {
        return when (intent) {
            is UserIntent.DescribeScene -> describeScene(world)
            is UserIntent.DescribeLeft -> describeDirection(world, SpatialPosition.LEFT)
            is UserIntent.DescribeRight -> describeDirection(world, SpatialPosition.RIGHT)
            is UserIntent.DescribeSurroundings -> describeSurroundings(world)
            is UserIntent.CheckHazards -> checkHazards(world)
            is UserIntent.CheckForPeople -> checkForPeople(world)
            is UserIntent.FindObject -> findObject(intent.label, world, memory)
            is UserIntent.AskCurrentRoom -> askCurrentRoom(world, memory)
            is UserIntent.ReadText -> readText(world)
            is UserIntent.GuideToExit -> "Searching for an exit. ${findObject("door", world, memory)}"
            is UserIntent.GuideToElevator -> "Searching for the elevator. ${findObject("elevator", world, memory)}"
            is UserIntent.GuideToRoom -> guideToRoom(intent.roomNumber, world, memory)
            is UserIntent.GuideTo -> "Looking for ${intent.label}. ${findObject(intent.label, world, memory)}"
            is UserIntent.NavigateTo -> "Looking up walking route to ${intent.destination}..."
            is UserIntent.StartWalkingMode -> "Walking mode started. I'll alert you to obstacles and important objects."
            is UserIntent.Stop -> "Stopping. Let me know if you need anything."
            is UserIntent.RememberCurrentLocation -> "I'll remember your current location."
            is UserIntent.QueryMemory -> queryMemory(intent.query, memory, world)
            is UserIntent.GuideToLastMentioned ->
                if (lastMentionedLabel != null) {
                    "Looking for $lastMentionedLabel. ${findObject(lastMentionedLabel, world, memory)}"
                } else {
                    "I'm not sure what to guide you to. What would you like to find?"
                }
            is UserIntent.MoreDetail -> describeSurroundings(world)
            is UserIntent.DetailedDescription -> "Analyzing scene deeply..."
            is UserIntent.Unknown -> handleUnknown(intent.rawText, world)
        }
    }

    /**
     * Generate a proactive narration (not triggered by user) for exploration mode.
     * Prioritizes the single most critical obstacle ahead, eliminating speech spam.
     * Returns null if nothing important enough to say.
     */
    fun generateProactiveNarration(
        importantEvents: List<Pair<TrackedObject, Priority>>,
        hazards: List<HazardEvent>,
        world: WorldModel,
        isUserMoving: Boolean = world.isUserMoving
    ): String? {
        if (hazards.isNotEmpty()) {
            // Hazards take absolute precedence
            val topHazard = hazards.maxByOrNull { it.type.priority }!!
            return generateHazardWarning(topHazard, world, isUserMoving)
        }

        if (importantEvents.isEmpty()) return null

        // Priority 1: Path obstacles ahead
        val pathObstacle = importantEvents.firstOrNull { (obj, _) -> obj.isInPath }?.first
        if (pathObstacle != null) {
            return describeObjectBriefly(pathObstacle, pathContext = true, isUserMoving = isUserMoving)
        }

        // Priority 2: Approaching objects
        val approaching = importantEvents.firstOrNull { (obj, priority) ->
            obj.isApproaching && priority.value >= Priority.HIGH.value
        }?.first
        if (approaching != null) {
            val posDesc = approaching.position.toDescription()
            return if (approaching.label == "person") {
                if (isUserMoving) "A person is approaching directly ahead, please step aside"
                else "A person is approaching from $posDesc"
            } else {
                "A ${displayLabel(approaching.label)} is approaching from $posDesc"
            }
        }

        // Priority 3: Close navigation targets (door, stairs, elevator)
        val navTarget = importantEvents.firstOrNull { (obj, priority) ->
            priority.value >= Priority.HIGH.value &&
            obj.label in setOf("door", "stairs", "elevator", "exit") &&
            obj.distanceCategory != DistanceCategory.FAR
        }?.first
        if (navTarget != null) {
            return describeObjectBriefly(navTarget, pathContext = false, isUserMoving = isUserMoving)
        }

        // Priority 4: Any close object directly ahead in view
        val aheadObj = importantEvents.firstOrNull { (obj, _) ->
            obj.position == SpatialPosition.CENTER &&
            (obj.distanceCategory == DistanceCategory.VERY_CLOSE || obj.distanceCategory == DistanceCategory.CLOSE)
        }?.first
        if (aheadObj != null) {
            return describeObjectBriefly(aheadObj, pathContext = false, isUserMoving = isUserMoving)
        }

        return null
    }

    /**
     * Generate hazard warning with actionable safety guidance.
     *
     * For life-safety hazards (FIRE, SHARP_OBJECT): speak the label clearly, once, no extra instructions.
     * If user is idle/stationary: speak the warning without evasion directions.
     * If user is walking/moving: suggest left/right avoidance if in path.
     */
    fun generateHazardWarning(
        hazard: HazardEvent,
        world: WorldModel,
        isUserMoving: Boolean = world.isUserMoving
    ): String {
        val baseText = hazard.toSpeechText()

        // Critical life-safety hazards: just the clear label, no directions appended
        if (hazard.type == com.visualassistant.perception.hazard.HazardType.FIRE ||
            hazard.type == com.visualassistant.perception.hazard.HazardType.SHARP_OBJECT) {
            return baseText
        }

        if (!hazard.isInPath) return baseText

        // If the user is idle/stationary, do NOT give evasion directions; just state the obstacle
        if (!isUserMoving) {
            return baseText
        }

        // For path obstacles while walking, suggest a safe direction
        val isLeftClear = world.objectsOnLeft.none { it.distanceCategory == DistanceCategory.VERY_CLOSE }
        val isRightClear = world.objectsOnRight.none { it.distanceCategory == DistanceCategory.VERY_CLOSE }

        val actionText = when {
            isLeftClear && isRightClear -> "Move to either side."
            isLeftClear -> "Move slightly left."
            isRightClear -> "Move slightly right."
            else -> "Proceed with caution."
        }

        return "$baseText $actionText"
    }

    // ─── Private helpers ──────────────────────────────────────────────────────────

    private fun describeScene(world: WorldModel): String {
        val ahead = world.objectsAhead
        if (ahead.isEmpty()) {
            if (world.trackedObjects.isNotEmpty()) {
                val other = world.trackedObjects.take(3).map {
                    "${it.label} ${it.position.toDescription()}, ${it.lastDetectedObject.distanceDescription}"
                }
                return "The path directly ahead is clear. Nearby: ${other.joinToString(", ")}."
            }
            return "The path ahead appears clear."
        }
        val important = ahead.filter { it.label !in lowPriorityObjects }
        return if (important.isEmpty()) {
            "There is a ${ahead.first().label} ahead, ${ahead.first().lastDetectedObject.distanceDescription}."
        } else {
            val descriptions = important.take(3).map { describeObjectBriefly(it, pathContext = true) }
            descriptions.joinToString(". ")
        }
    }

    private fun describeDirection(world: WorldModel, direction: SpatialPosition): String {
        val objects = when (direction) {
            SpatialPosition.LEFT -> world.objectsOnLeft
            SpatialPosition.RIGHT -> world.objectsOnRight
            else -> world.objectsAhead
        }
        val dirText = direction.toDescription()
        if (objects.isEmpty()) return "I don't see anything significant $dirText."
        val descriptions = objects.take(3).map { "${it.label} ${it.lastDetectedObject.distanceDescription}" }
        return "To your ${direction.name.lowercase()}: ${descriptions.joinToString(", ")}."
    }

    private fun describeSurroundings(world: WorldModel): String {
        if (world.isEmpty) return "I don't currently see anything in your surroundings."

        val parts = mutableListOf<String>()

        val ahead = world.objectsAhead.take(2)
        val left = world.objectsOnLeft.take(2)
        val right = world.objectsOnRight.take(2)

        if (ahead.isNotEmpty()) {
            parts.add("Ahead: ${ahead.joinToString(", ") { "${it.label} (${it.lastDetectedObject.distanceDescription})" }}")
        }
        if (left.isNotEmpty()) {
            parts.add("To your left: ${left.joinToString(", ") { "${it.label} (${it.lastDetectedObject.distanceDescription})" }}")
        }
        if (right.isNotEmpty()) {
            parts.add("To your right: ${right.joinToString(", ") { "${it.label} (${it.lastDetectedObject.distanceDescription})" }}")
        }

        if (parts.isEmpty() && world.trackedObjects.isNotEmpty()) {
            val list = world.trackedObjects.take(3).map { "${it.label} ${it.position.toDescription()}" }
            return "I see: ${list.joinToString(", ")}."
        }

        return if (parts.isEmpty()) "The area around you appears clear."
        else parts.joinToString(". ") + "."
    }

    private fun checkHazards(world: WorldModel): String {
        val hazards = world.hazards
        return if (hazards.isEmpty()) {
            "I don't currently detect any dangerous objects. Please remain cautious."
        } else {
            val hazardDescriptions = hazards.take(3).map {
                "${it.label} ${it.position.toDescription()}"
            }
            "Caution. I detect: ${hazardDescriptions.joinToString(", ")}."
        }
    }

    private fun checkForPeople(world: WorldModel): String {
        val people = world.findByLabel("person")
        return when {
            people.isEmpty() -> "I don't currently see any people nearby."
            people.size == 1 -> {
                val p = people.first()
                if (p.isApproaching) "A person is approaching from ${p.position.toDescription()}."
                else "There is one person ${p.position.toDescription()}, ${p.lastDetectedObject.distanceDescription}."
            }
            else -> {
                val approaching = people.count { it.isApproaching }
                if (approaching > 0) "There are ${people.size} people nearby. ${approaching} ${if (approaching == 1) "is" else "are"} approaching."
                else "There are ${people.size} people around you."
            }
        }
    }

    fun findObject(label: String, world: WorldModel, memory: SpatialMemory): String {
        // Check current view first
        val found = world.findFirst(label)
        if (found != null) {
            val surface = findSupportingSurface(found, world.trackedObjects)
            val surfaceStr = if (surface != null) " $surface" else ""
            val display = displayLabel(found.label)
            return "Yes. There is a $display$surfaceStr ${found.position.toDescription()}, ${found.lastDetectedObject.distanceDescription}."
        }

        // Check spatial memory for landmark/place/room
        val placeEntry = memory.findPlace(label)
        if (placeEntry != null) {
            val timeAgo = memory.formatTimeAgo(placeEntry.timestampMs)
            val placeName = placeEntry.text ?: label
            return "I don't see $placeName in front of you right now, but you passed it ${placeEntry.relativePosition} $timeAgo."
        }

        // Check memory
        val memEntry = memory.findRecent(label)
        if (memEntry != null) {
            val timeAgo = memory.formatTimeAgo(memEntry.timestampMs)
            return "I previously detected a $label ${memEntry.relativePosition} $timeAgo. I can't currently see it from this direction."
        }

        return "I can't currently see a $label from this direction."
    }

    private fun askCurrentRoom(world: WorldModel, memory: SpatialMemory): String {
        // Check detected text for room numbers
        val roomText = world.detectedText.find { it.isRoomNumber }
        if (roomText != null) return "I can see ${roomText.text} nearby."

        val recentRoom = memory.getMostRecentRoom()
        return if (recentRoom != null) {
            "The last room number I detected was ${recentRoom}."
        } else {
            "I haven't detected a room number recently. Look for room signs on walls or doors."
        }
    }

    private fun readText(world: WorldModel): String {
        val texts = world.detectedText
        return when {
            texts.isEmpty() -> "I don't currently detect any readable text. Please point the camera at a sign or text."
            texts.size == 1 -> "The text reads: ${texts.first().text}."
            else -> {
                val textList = texts.take(3).joinToString("; ") { it.text }
                "I can see the following text: $textList."
            }
        }
    }

    private fun guideToRoom(roomNumber: String, world: WorldModel, memory: SpatialMemory): String {
        // Check memory
        val memEntry = memory.findRoom(roomNumber)
        if (memEntry != null) {
            val timeAgo = memory.formatTimeAgo(memEntry.timestampMs)
            return "Room $roomNumber was detected ${memEntry.relativePosition} $timeAgo. I'll keep searching as you walk."
        }
        return "Searching for Room $roomNumber. I'll let you know when I spot it. Walk slowly and I'll guide you."
    }

    private fun queryMemory(query: String, memory: SpatialMemory, world: WorldModel): String {
        val roomMatch = Regex("""room:([A-Z0-9]+)""").find(query)
        if (roomMatch != null) {
            val roomNum = roomMatch.groupValues[1]
            val entry = memory.findRoom(roomNum)
            return if (entry != null) {
                val timeAgo = memory.formatTimeAgo(entry.timestampMs)
                "Room $roomNum was detected ${entry.relativePosition} $timeAgo."
            } else {
                "I don't have Room $roomNum in my memory. I'll look out for it as you walk."
            }
        }

        // Clean query prefixes to extract target keyword
        val cleanQuery = query.lowercase()
            .removePrefix("where did i see the ")
            .removePrefix("where did i see ")
            .removePrefix("where was the ")
            .removePrefix("where was ")
            .removePrefix("where is the ")
            .removePrefix("where is ")
            .removePrefix("have you seen the ")
            .removePrefix("have you seen ")
            .trim()

        val placeEntry = memory.findPlace(cleanQuery)
        if (placeEntry != null) {
            val timeAgo = memory.formatTimeAgo(placeEntry.timestampMs)
            val placeName = placeEntry.text ?: cleanQuery
            return "$placeName was detected ${placeEntry.relativePosition} $timeAgo."
        }

        // Generic memory query
        val keywords = listOf("elevator", "door", "exit", "reception", "cafeteria", "library", "restroom", "office", "stairs")
        for (kw in keywords) {
            if (query.contains(kw)) {
                val entry = memory.findPlace(kw) ?: memory.findRecent(kw)
                if (entry != null) {
                    val timeAgo = memory.formatTimeAgo(entry.timestampMs)
                    return "I detected $kw ${entry.relativePosition} $timeAgo."
                } else {
                    return "I haven't seen $kw recently. I will watch for it."
                }
            }
        }
        return "I don't have that in memory yet. As you walk past signs or rooms, I'll remember them."
    }

    private fun handleUnknown(rawText: String, world: WorldModel): String {
        return "I didn't understand that. You can ask me what's ahead, find the exit, " +
                "read a sign, or say 'start walking mode'."
    }

    private fun describeObjectBriefly(
        obj: TrackedObject,
        pathContext: Boolean,
        isUserMoving: Boolean = false
    ): String {
        val label = displayLabel(obj.label)
        val pos = obj.position.toDescription()
        val dist = obj.lastDetectedObject.distanceDescription

        return when {
            label == "stairs" && pathContext -> {
                if (isUserMoving) "Caution. Stairs ahead, proceed with care"
                else "Stairs ahead"
            }
            label == "stairs" -> "Stairs $pos"
            label == "person" && obj.isApproaching -> "A person is approaching from $pos"
            label == "person" -> {
                if (obj.isInPath && isUserMoving) "Person ahead, move slightly to the side"
                else if (obj.isInPath) "A person is ahead, $dist"
                else "A person is $pos, $dist"
            }
            label in setOf("door", "doorway") -> "Door $pos, $dist"
            label == "elevator" -> "Elevator $pos"
            obj.isInPath && obj.distanceCategory == DistanceCategory.VERY_CLOSE -> {
                if (isUserMoving) "Caution. $label directly ahead, move slightly to either side"
                else "$label directly ahead"
            }
            obj.isInPath -> {
                if (isUserMoving) "$label ahead in path, move slightly left or right"
                else "$label ahead"
            }
            else -> "$label $pos"
        }
    }

    private fun displayLabel(label: String): String = when (label.lowercase()) {
        "dining table" -> "table"
        "cell phone" -> "phone"
        else -> label
    }

    private fun findSupportingSurface(obj: TrackedObject, allObjects: List<TrackedObject>): String? {
        val boxA = obj.lastDetectedObject.boundingBox

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

        val tableAtSamePos = allObjects.find { 
            (it.label.contains("table", true) || it.label.contains("desk", true)) &&
            it.position == obj.position
        }
        if (tableAtSamePos != null) {
            return "on the desk"
        }

        val isTabletopItem = obj.label.lowercase() in setOf("knife", "scissors", "blade", "cutter", "fork", "bottle", "cup", "laptop", "mouse", "keyboard")
        val hasTabletopContext = allObjects.any {
            it.id != obj.id && it.label.lowercase() in setOf("dining table", "table", "desk", "laptop", "keyboard", "mouse", "cup", "book", "bottle")
        }
        if (isTabletopItem && (hasTabletopContext || obj.lastDetectedObject.centerY > 0.35f)) {
            return "on the desk"
        }

        return null
    }

    private val lowPriorityObjects = setOf(
        "chair", "table", "laptop", "bottle", "book", "plant",
        "monitor", "keyboard", "cup", "vase", "clock", "mouse"
    )
}
