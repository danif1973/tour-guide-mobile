package com.tourguide.tourguideclient

import android.content.Context
import android.location.Location
import android.util.Log
import com.tourguide.locationexplorer.config.LocationExplorerConfig
import com.tourguide.locationexplorer.services.GeminiService
import com.tourguide.locationexplorer.services.PlacesService
import com.tourguide.locationexplorer.services.TtsService
import com.tourguide.tourguideclient.config.TourGuideClientConfig
import com.tourguide.tourguideclient.models.ContentResponse
import com.tourguide.tourguideclient.utils.broadcastDebug
import kotlinx.coroutines.*
import kotlin.math.*

/**
 * Tour Guide Client - Android-aware client for consuming location data and generating content.
 * Ported from tour_guide_client/client.py
 * 
 * Note: This version is designed for Android and accepts Location objects directly
 * from Android Location Services, rather than polling a REST API.
 */
class TourGuideClient(
    private val context: Context,
    private val placesService: PlacesService,
    private val geminiService: GeminiService,
    private val ttsService: TtsService? = null,
    private val config: TourGuideClientConfig = TourGuideClientConfig
) {
    companion object {
        private const val TAG = "TourGuideClient"
    }
    
    private var locationCount = 0
    private var lastLocation: Location? = null
    private var lastContentLocation: Location? = null
    
    // Content generation state variables
    private var latestContent: List<String> = emptyList()
    private var hasNewContent = false
    private var isGeneratingContent = false
    
    // Destination information
    private var destination: Location? = null
    private var destinationSummaryGenerated = false
    
    // Current data
    private var currentHeading: Float = 0f

    /**
     * Resets the last known location, forcing the next GPS update to be processed.
     */
    fun resetLocationState() {
        Log.d(TAG, "Resetting location state after simulation.")
        context.broadcastDebug("TourGuideClient", "Resetting location state after simulation.")
        lastLocation = null
        lastContentLocation = null
    }

    /**
     * Handle location update from Android Location Services.
     * This replaces the REST API polling mechanism.
     */
    suspend fun handleLocationUpdate(location: Location, speedKmh: Float? = null, headingDegrees: Float? = null) {
        locationCount++
        
        // Check if location actually changed
        if (lastLocation != null && 
            abs(lastLocation!!.latitude - location.latitude) < 0.000001 &&
            abs(lastLocation!!.longitude - location.longitude) < 0.000001) {
            return // No change, skip
        }

        lastLocation = location
        currentHeading = headingDegrees ?: 0f
        
        Log.d(TAG, "Location received: ${location.latitude}, ${location.longitude}, count: $locationCount")
        
        // Check if we should generate content based on distance threshold
        checkAndGenerateContent(location, speedKmh ?: 0f)
    }
    
    /**
     * Set destination location.
     */
    fun setDestination(location: Location, name: String? = null) {
        destination = location
        destinationSummaryGenerated = false
        Log.d(TAG, "Destination set: ${location.latitude}, ${location.longitude}, name: $name")
    }
    
    /**
     * Check if distance threshold is met and generate content if needed.
     */
    private suspend fun checkAndGenerateContent(location: Location, speedKmh: Float) {
        if (isGeneratingContent) {
            Log.d(TAG, "Content generation already in progress, skipping")
            return
        }
        
        val lastContentLoc = lastContentLocation
        if (lastContentLoc != null) {
            // Standard behavior: check distance
            val distance = location.distanceTo(lastContentLoc)
            val thresholdM = config.contentGenerationDistanceThresholdM.toFloat()
            
            // Adjust threshold based on speed, ensuring it doesn't fall below the base threshold
            val speedMultiplier = speedKmh / config.speedReferenceBaseline
            val adjustedThreshold = (thresholdM * speedMultiplier).coerceAtLeast(thresholdM)
            
            if (distance < adjustedThreshold) {
                Log.i(TAG, "Distance threshold not met ($distance m < $adjustedThreshold m, speed: $speedKmh km/h). Skipping content generating.")
                context.broadcastDebug("TourGuideClient", "Distance threshold not met ($distance m < $adjustedThreshold m, speed: $speedKmh km/h). Skipping content generating.")
                return // Not enough movement
            }
            Log.i(TAG, "Distance threshold met ($distance m >= $adjustedThreshold m, speed: $speedKmh km/h). Generating content.")
            context.broadcastDebug("TourGuideClient", "Distance threshold met ($distance m >= $adjustedThreshold m, speed: $speedKmh km/h). Generating content.")
        } else {
            // First run behavior: generate immediately
            Log.i(TAG, "First location update. Generating content immediately.")
            context.broadcastDebug("TourGuideClient", "First location update. Generating content immediately.")
        }
        
        // Generate destination summary if needed
        if (!destinationSummaryGenerated && destination != null) {
            generateDestinationSummary()
        }
        
        // Generate content
        generateContent(location, speedKmh)
    }
    
    /**
     * Generate content using the configured places service and Gemini API.
     * If skipGeminiContentGeneration is true, it generates placeholder content.
     */
    private suspend fun generateContent(location: Location, speedKmh: Float) = withContext(Dispatchers.IO) {
        if (isGeneratingContent) {
            Log.d(TAG, "Content generation already in progress, skipping")
            return@withContext
        }
        
        isGeneratingContent = true
        
        try {
            Log.i(TAG, "Starting content generation for location: ${location.latitude}, ${location.longitude}")
            
            // Predict future position
            var searchLat = location.latitude
            var searchLng = location.longitude
            val heading = currentHeading
            if (heading != 0f) {
                val futurePos = calculateFuturePositionAlongHeading(location.latitude, location.longitude, heading, speedKmh, config.contentGenerationEstimatedTimeS)
                searchLat = futurePos.first
                searchLng = futurePos.second
            }
            
            // Search for places
            val places = placesService.searchPlacesByCoordinates(searchLat, searchLng, LocationExplorerConfig.defaultQueryRadiusM, speedKmh)
            
            if (places.isEmpty()) {
                Log.i(TAG, "No places of interest found near location")
                latestContent = emptyList()
                hasNewContent = false
                lastContentLocation = location
                return@withContext
            }
            
            Log.i(TAG, "Found ${places.size} places. Generating summaries...")
            
            // Generate summaries for each place
            val summaries = places.mapNotNull { place ->
                if (config.skipGeminiContentGeneration) {
                    "[Test Mode] Summary for: ${place.name}"
                } else {
                    try {
                        val placeMap = mapOf(
                            "lat" to place.lat,
                            "lon" to place.lng,
                            "tags" to place.tags
                        )
                        val maxSentences = calculateMaxSentences(places.size, places.indexOf(place), place.importance)
                        geminiService.generateOsmSummary(place = placeMap, maxSentences = maxSentences)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to generate summary for ${place.name}: ${e.message}", e)
                        null
                    }
                }
            }
            
            // Update state
            latestContent = summaries
            hasNewContent = summaries.isNotEmpty()
            lastContentLocation = location
            
            Log.i(TAG, "Content generation completed: ${summaries.size} summaries ready")
        } catch (e: Exception) {
            Log.e(TAG, "Content generation failed: ${e.message}", e)
            latestContent = emptyList()
            hasNewContent = false
        } finally {
            isGeneratingContent = false
        }
    }
    
    /**
     * Generate a summary for the destination when navigation starts.
     */
    private suspend fun generateDestinationSummary() = withContext(Dispatchers.IO) {
        if (destination == null || destinationSummaryGenerated || config.skipGeminiContentGeneration) {
            return@withContext
        }
        
        val dest = destination!!
        Log.i(TAG, "Generating destination summary for: ${dest.latitude}, ${dest.longitude}")
        
        try {
            val destinationPlace = mapOf("lat" to dest.latitude, "lon" to dest.longitude, "tags" to mapOf("name" to "Destination"))
            val summary = geminiService.generateOsmSummary(place = destinationPlace, maxSentences = config.destinationMaxSentences)
            
            if (summary != null) {
                val updatedContent = mutableListOf(summary) 
                updatedContent.addAll(latestContent)
                latestContent = updatedContent
                hasNewContent = true
            }
            
            destinationSummaryGenerated = true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate destination summary: ${e.message}", e)
            destinationSummaryGenerated = true // Mark as done to avoid retries
        }
    }
    
    /**
     * Calculate dynamic max_sentences based on number of places, place index and place importance.
     */
    private fun calculateMaxSentences(totalPlaces: Int, placeIndex: Int, importance: Float): Int {
        val threshold = 0.32f // A reasonable default importance threshold
        val minSentences = 4
        val defaultMax = LocationExplorerConfig.defaultMaxSentences
        
        return when {
            totalPlaces <= 2 && importance >= threshold -> defaultMax
            totalPlaces <= 2 -> max(minSentences, defaultMax - 2)
            placeIndex == 0 && importance >= threshold -> defaultMax
            placeIndex == 0 -> max(minSentences, defaultMax - 2)
            else -> max(minSentences, defaultMax - placeIndex)
        }.let { min(it, defaultMax) }
    }
    
    // ... (rest of the helper functions: calculateRelativeDirection, calculateDistanceM, calculateFuturePositionAlongHeading, getContent) ...
    private fun calculateRelativeDirection(currentLat: Double, currentLng: Double, headingDegrees: Float, placeLat: Double, placeLng: Double): String {
        val lat1Rad = Math.toRadians(currentLat)
        val lat2Rad = Math.toRadians(placeLat)
        val deltaLon = Math.toRadians(placeLng - currentLng)
        val y = sin(deltaLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(deltaLon)
        var bearingDegrees = Math.toDegrees(atan2(y, x))
        bearingDegrees = (bearingDegrees + 360) % 360
        var angleDiff = bearingDegrees - headingDegrees
        if (angleDiff > 180) angleDiff -= 360 else if (angleDiff < -180) angleDiff += 360
        return when {
            abs(angleDiff) <= 22.5 -> "directly ahead"
            abs(angleDiff) >= 157.5 -> "behind you"
            angleDiff > 0 -> "to your right"
            else -> "to your left"
        }
    }

    private fun calculateDistanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val deltaLat = Math.toRadians(lat2 - lat1)
        val deltaLng = Math.toRadians(lng2 - lng1)
        val a = sin(deltaLat / 2).pow(2) + cos(lat1Rad) * cos(lat2Rad) * sin(deltaLng / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    private fun calculateFuturePositionAlongHeading(lat: Double, lng: Double, headingDegrees: Float, speedKmh: Float, timeSeconds: Float): Pair<Double, Double> {
        val R = 6371000.0
        val distanceM = (speedKmh / 3.6) * timeSeconds
        val latRad = Math.toRadians(lat)
        val headingRad = Math.toRadians(headingDegrees.toDouble())
        val newLat = asin(sin(latRad) * cos(distanceM / R) + cos(latRad) * sin(distanceM / R) * cos(headingRad))
        val newLng = lng + Math.toDegrees(atan2(sin(headingRad) * sin(distanceM / R) * cos(latRad), cos(distanceM / R) - sin(latRad) * sin(newLat)))
        return Pair(Math.toDegrees(newLat), newLng)
    }

    fun getContent(): ContentResponse {
        if (isGeneratingContent) {
            return ContentResponse(status = 0)
        }
        if (hasNewContent && latestContent.isNotEmpty()) {
            val content = latestContent.toList()
            hasNewContent = false
            return ContentResponse(status = 1, content = content)
        }
        return ContentResponse(status = 0)
    }
}
