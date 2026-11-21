package com.visualassistant.navigation

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume

/**
 * MapsDirectionsService handles walking route queries using Google Maps Directions API.
 *
 * It:
 * 1. Obtains the user's current GPS location via FusedLocationProviderClient.
 * 2. Queries Google Maps Directions REST API with mode=walking and alternatives=true.
 * 3. Assesses feasibility on foot (distance and time).
 * 4. Summarizes the primary route and alternative ways into natural speech.
 */
class MapsDirectionsService(
    private val context: Context,
    private val apiKey: String = "" // Injected or auto-resolved from AndroidManifest meta-data
) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    /**
     * Resolves the Google Maps API key from constructor parameter or AndroidManifest meta-data.
     */
    private val resolvedApiKey: String by lazy {
        if (apiKey.isNotBlank() && apiKey != "xx" && apiKey != "YOUR_MAPS_API_KEY") {
            apiKey
        } else {
            try {
                val appInfo = context.packageManager.getApplicationInfo(
                    context.packageName,
                    android.content.pm.PackageManager.GET_META_DATA
                )
                val metaKey = appInfo.metaData?.getString("com.google.android.geo.API_KEY") ?: ""
                if (metaKey == "YOUR_MAPS_API_KEY_HERE" || metaKey.isBlank()) "" else metaKey
            } catch (e: Exception) {
                Log.w(TAG, "Could not read API key from meta-data: ${e.message}")
                ""
            }
        }
    }

    /**
     * Get natural language navigation directions to a requested destination.
     */
    suspend fun getDirections(destination: String): String = withContext(Dispatchers.IO) {
        val location = getCurrentLocation()
        if (location == null) {
            return@withContext "I couldn't access your current location. Please ensure location services are enabled."
        }

        val key = resolvedApiKey
        if (key.isBlank()) {
            return@withContext "I found $destination. To get live Google Maps turn-by-turn walking routes, please configure your Google Maps API key in AndroidManifest.xml or local.properties. Meanwhile, follow pedestrian paths safely."
        }

        try {
            val originStr = "${location.latitude},${location.longitude}"
            val encodedDest = URLEncoder.encode(destination, "UTF-8")
            val urlString = "https://maps.googleapis.com/maps/api/directions/json" +
                    "?origin=$originStr" +
                    "&destination=$encodedDest" +
                    "&mode=walking" +
                    "&alternatives=true" +
                    "&key=$key"

            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Directions API returned HTTP $responseCode")
                return@withContext "Unable to reach directions service right now. Please check your network connection."
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val responseText = reader.use { it.readText() }
            connection.disconnect()

            parseDirectionsResponse(responseText, destination)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching directions: ${e.message}", e)
            "Sorry, I encountered an error while finding walking routes to $destination."
        }
    }

    /**
     * Parse the Directions API JSON response and format a concise spoken summary.
     */
    fun parseDirectionsResponse(jsonStr: String, destination: String): String {
        val json = JSONObject(jsonStr)
        val status = json.optString("status")

        if (status != "OK") {
            return when (status) {
                "ZERO_RESULTS" -> "I couldn't find any walking route to $destination. It might be across water or unreachable by foot."
                "NOT_FOUND" -> "I couldn't locate $destination on the map. Please try a more specific place name."
                "REQUEST_DENIED" -> "Directions request was denied. Please verify your Google Maps API key permissions."
                else -> "Directions service reported: $status. Could not retrieve walking route."
            }
        }

        val routes = json.getJSONArray("routes")
        if (routes.length() == 0) {
            return "No walking routes found for $destination."
        }

        // Primary route
        val primaryRoute = routes.getJSONObject(0)
        val primarySummary = primaryRoute.optString("summary").ifBlank { "main road" }
        val legs = primaryRoute.getJSONArray("legs")
        if (legs.length() == 0) return "Route information is incomplete."

        val leg = legs.getJSONObject(0)
        val distance = leg.getJSONObject("distance").getString("text")
        val distanceMeters = leg.getJSONObject("distance").optInt("value", 0)
        val duration = leg.getJSONObject("duration").getString("text")

        // Feasibility check
        val feasibility = when {
            distanceMeters > 15_000 -> "Walking to $destination is about $distance, which will take over $duration. That is quite far on foot; transit or taxi is recommended."
            distanceMeters > 5_000 -> "Walking to $destination is possible. It is $distance away, taking approximately $duration."
            else -> "Yes, it is easily walkable. It is $distance away, about $duration on foot."
        }

        // Steps summary (take first 2-3 key turn instructions)
        val steps = leg.getJSONArray("steps")
        val stepInstructions = mutableListOf<String>()
        val maxSteps = minOf(3, steps.length())
        for (i in 0 until maxSteps) {
            val step = steps.getJSONObject(i)
            val html = step.getString("html_instructions")
            val cleanStep = html.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
            val stepDist = step.getJSONObject("distance").getString("text")
            stepInstructions.add("$cleanStep for $stepDist")
        }

        val primaryRouteSpoken = buildString {
            append("$feasibility ")
            append("Primary way is via $primarySummary. ")
            if (stepInstructions.isNotEmpty()) {
                append("First, ${stepInstructions.joinToString(". Then, ")}.")
            }
        }

        // Alternatives check
        if (routes.length() > 1) {
            val altRoutes = mutableListOf<String>()
            for (r in 1 until minOf(3, routes.length())) {
                val alt = routes.getJSONObject(r)
                val altSummary = alt.optString("summary").ifBlank { "alternate path" }
                val altLeg = alt.getJSONArray("legs").optJSONObject(0)
                if (altLeg != null) {
                    val altDuration = altLeg.getJSONObject("duration").getString("text")
                    altRoutes.add("via $altSummary taking $altDuration")
                }
            }
            if (altRoutes.isNotEmpty()) {
                return "$primaryRouteSpoken Also available: ${altRoutes.joinToString(", or ")}."
            }
        }

        return primaryRouteSpoken
    }

    @SuppressLint("MissingPermission")
    private suspend fun getCurrentLocation(): Location? = suspendCancellableCoroutine { cont ->
        try {
            val cts = CancellationTokenSource()
            cont.invokeOnCancellation { cts.cancel() }

            fusedLocationClient.lastLocation
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        cont.resume(loc)
                    } else {
                        // If lastLocation is null, try current location
                        fusedLocationClient.getCurrentLocation(
                            com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
                            cts.token
                        ).addOnSuccessListener { freshLoc ->
                            cont.resume(freshLoc)
                        }.addOnFailureListener {
                            cont.resume(null)
                        }
                    }
                }
                .addOnFailureListener {
                    cont.resume(null)
                }
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission not granted: ${e.message}")
            cont.resume(null)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting location: ${e.message}")
            cont.resume(null)
        }
    }

    companion object {
        private const val TAG = "MapsDirectionsService"
    }
}
