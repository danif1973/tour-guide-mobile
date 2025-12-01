package com.tourguide.tourguideclient

import android.location.Location
import android.util.Log
import com.tourguide.locationexplorer.config.LocationExplorerConfig
import com.tourguide.locationexplorer.models.PlaceInfo
import com.tourguide.locationexplorer.services.GeminiService
import com.tourguide.locationexplorer.services.OverpassService
import com.tourguide.locationexplorer.services.TtsService
import com.tourguide.tourguideclient.config.TourGuideClientConfig
import com.tourguide.tourguideclient.models.ContentResponse
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
    private val overpassService: OverpassService,
    private val geminiService: GeminiService,
    private val ttsService: TtsService? = null,
    private val config: TourGuideClientConfig = TourGuideClientConfig
) {
    companion object {
        private const val TAG = "TourGuideClient"
    }
    
    private var isRunning = false
    private var locationCount = 0
    private var lastLocation: Location? = null
    private var lastContentLocation: Location? = null
    
    // Content generation state variables
    private var latestContent: List<String> = emptyList()
    private var latestAudio: List<String> = emptyList() // base64-encoded MP3
    private var hasNewContent = false
    private var isGeneratingContent = false
    
    // Destination information
    private var destination: Location? = null
    private var destinationSummaryGenerated = false
    
    // Current data for content generation
    private var currentData: Map<String, Any> = emptyMap()
    
    private val clientScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
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
        
        // Store current data
        currentData = mapOf(
            "location" to mapOf("lat" to location.latitude, "lng" to location.longitude),
            "speed" to (speedKmh ?: 0f),
            "heading" to (headingDegrees ?: 0f),
            "status" to "Active"
        )
        
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
        
        val lastContentLoc = lastContentLocation ?: run {
            // No previous content location - set baseline
            lastContentLocation = location
            return
        }
        
        val distance = location.distanceTo(lastContentLoc)
        val thresholdM = config.contentGenerationDistanceThresholdM.toFloat()
        
        // Adjust threshold based on speed
        val speedMultiplier = speedKmh / config.speedReferenceBaseline
        val adjustedThreshold = thresholdM * speedMultiplier
        
        if (distance < adjustedThreshold) {
            return // Not enough movement
        }
        
        Log.i(TAG, "Distance threshold met ($distance m >= $adjustedThreshold m, speed: $speedKmh km/h). Generating content.")
        
        // Generate destination summary if needed
        if (!destinationSummaryGenerated && destination != null) {
            generateDestinationSummary()
        }
        
        // Generate content
        generateContent(location, speedKmh)
    }
    
    /**
     * Generate content using Overpass + Gemini services.
     */
    private suspend fun generateContent(location: Location, speedKmh: Float) = withContext(Dispatchers.IO) {
        if (isGeneratingContent) {
            Log.d(TAG, "Content generation already in progress, skipping")
            return@withContext
        }
        
        isGeneratingContent = true
        
        try {
            Log.i(TAG, "Starting content generation for location: ${location.latitude}, ${location.longitude}")
            
            // Try to predict future position if heading is available
            var searchLat = location.latitude
            var searchLng = location.longitude
            val heading = (currentData["heading"] as? Number)?.toFloat()
            
            if (heading != null && heading != 0f) {
                val futurePos = calculateFuturePositionAlongHeading(
                    location.latitude,
                    location.longitude,
                    heading,
                    speedKmh,
                    config.contentGenerationEstimatedTimeS
                )
                searchLat = futurePos.first
                searchLng = futurePos.second
                
                val distancePredicted = calculateDistanceM(
                    location.latitude,
                    location.longitude,
                    searchLat,
                    searchLng
                )
                
                Log.i(TAG, "Using future position: $searchLat, $searchLng (predicted ahead: $distancePredicted m)")
            }
            
            // Search for places
            val places = overpassService.searchPlacesByCoordinates(
                searchLat,
                searchLng,
                LocationExplorerConfig.defaultQueryRadiusM,
                speedKmh
            )
            
            if (places.isEmpty()) {
                Log.i(TAG, "No places found near location")
                latestContent = emptyList()
                hasNewContent = false
                lastContentLocation = location
                return@withContext
            }
            
            Log.i(TAG, "Found ${places.size} places, generating summaries...")
            
            // Generate summaries for each place
            val summaries = mutableListOf<String>()
            val audioList = mutableListOf<String?>()
            
            places.forEachIndexed { index, place ->
                val placeName = place.name
                Log.d(TAG, "Generating summary for place ${index + 1}/${places.size}: $placeName")
                
                try {
                    // Calculate dynamic max_sentences
                    val maxSentences = calculateMaxSentences(
                        totalPlaces = places.size,
                        placeIndex = index,
                        importance = place.importance
                    )
                    
                    // Convert PlaceInfo to map for GeminiService
                    val placeMap = mapOf(
                        "id" to (place.osmId ?: 0L),
                        "type" to (place.osmType ?: "node"),
                        "lat" to place.lat,
                        "lon" to place.lng,
                        "tags" to place.tags,
                        "distance_m" to (place.distanceM ?: 0f),
                        "importance" to place.importance,
                        "place_rank" to place.rank
                    )
                    
                    // Add relative direction if heading is available
                    if (heading != null && heading != 0f) {
                        val relativeDirection = calculateRelativeDirection(
                            location.latitude,
                            location.longitude,
                            heading,
                            place.lat,
                            place.lng
                        )
                        val placeMapWithDirection = placeMap.toMutableMap()
                        placeMapWithDirection["relative_direction"] = relativeDirection
                        
                        val summary = geminiService.generateOsmSummary(
                            placeMapWithDirection,
                            maxSentences = maxSentences
                        )
                        
                        if (summary != null) {
                            summaries.add(summary)
                            
                            // Generate audio
                            val audio = ttsService?.generateAudio(summary)
                            audioList.add(audio?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) })
                            
                            Log.d(TAG, "[${index + 1}/${places.size}] Generated summary for: $placeName")
                        } else {
                            Log.i(TAG, "[${index + 1}/${places.size}] Skipped $placeName: insufficient information")
                        }
                    } else {
                        val summary = geminiService.generateOsmSummary(
                            placeMap,
                            maxSentences = maxSentences
                        )
                        
                        if (summary != null) {
                            summaries.add(summary)
                            
                            // Generate audio
                            val audio = ttsService?.generateAudio(summary)
                            audioList.add(audio?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) })
                            
                            Log.d(TAG, "[${index + 1}/${places.size}] Generated summary for: $placeName")
                        } else {
                            Log.i(TAG, "[${index + 1}/${places.size}] Skipped $placeName: insufficient information")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[${index + 1}/${places.size}] Failed to generate summary for $placeName: ${e.message}", e)
                }
            }
            
            // Update state
            latestContent = summaries
            latestAudio = audioList.mapNotNull { it }
            hasNewContent = summaries.isNotEmpty()
            lastContentLocation = location
            
            Log.i(TAG, "Content generation completed: ${summaries.size} summaries ready")
        } catch (e: Exception) {
            Log.e(TAG, "Content generation failed: ${e.message}", e)
            latestContent = emptyList()
            latestAudio = emptyList()
            hasNewContent = false
        } finally {
            isGeneratingContent = false
        }
    }
    
    /**
     * Generate a summary for the destination when navigation starts.
     */
    private suspend fun generateDestinationSummary() = withContext(Dispatchers.IO) {
        if (destination == null || destinationSummaryGenerated) {
            return@withContext
        }
        
        val dest = destination!!
        Log.i(TAG, "Generating destination summary for: ${dest.latitude}, ${dest.longitude}")
        
        try {
            // Create a place dict for the destination
            val destinationPlace = mapOf(
                "lat" to dest.latitude,
                "lon" to dest.longitude,
                "tags" to mapOf(
                    "name" to "Destination",
                    "place" to "destination"
                )
            )
            
            val summary = geminiService.generateOsmSummary(
                destinationPlace,
                maxSentences = config.destinationMaxSentences
            )
            
            if (summary != null) {
                // Add destination summary to content array (at the beginning)
                val updatedContent = mutableListOf(summary)
                updatedContent.addAll(latestContent)
                latestContent = updatedContent
                
                // Generate audio for destination summary
                val audio = ttsService?.generateAudio(summary)
                if (audio != null) {
                    val audioBase64 = android.util.Base64.encodeToString(audio, android.util.Base64.NO_WRAP)
                    val updatedAudio = mutableListOf(audioBase64)
                    updatedAudio.addAll(latestAudio)
                    latestAudio = updatedAudio
                }
                
                hasNewContent = true
                destinationSummaryGenerated = true
                Log.i(TAG, "Destination summary generated (${summary.length} characters)")
            } else {
                Log.i(TAG, "Destination summary skipped: insufficient information")
                destinationSummaryGenerated = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate destination summary: ${e.message}", e)
            destinationSummaryGenerated = true
        }
    }
    
    /**
     * Calculate dynamic max_sentences based on number of places, place index and place importance.
     */
    private fun calculateMaxSentences(
        totalPlaces: Int,
        placeIndex: Int,
        importance: Float
    ): Int {
        val threshold = OverpassService.PLACE_STATE_CITY_SCORE
        val minSentences = 4
        val defaultMax = LocationExplorerConfig.defaultMaxSentences
        
        val calculated = when {
            totalPlaces <= 2 -> {
                if (importance >= threshold) {
                    defaultMax
                } else {
                    max(minSentences, defaultMax - 2)
                }
            }
            placeIndex == 0 -> {
                if (importance >= threshold) {
                    defaultMax
                } else {
                    max(minSentences, defaultMax - 2)
                }
            }
            else -> {
                max(minSentences, defaultMax - placeIndex)
            }
        }
        
        return min(calculated, defaultMax)
    }
    
    /**
     * Calculate relative direction of place from current location based on driving direction.
     */
    private fun calculateRelativeDirection(
        currentLat: Double,
        currentLng: Double,
        headingDegrees: Float,
        placeLat: Double,
        placeLng: Double
    ): String {
        // Calculate bearing from current location to place
        val lat1Rad = Math.toRadians(currentLat)
        val lat2Rad = Math.toRadians(placeLat)
        val deltaLon = Math.toRadians(placeLng - currentLng)
        
        val y = sin(deltaLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(deltaLon)
        
        var bearingDegrees = Math.toDegrees(atan2(y, x))
        bearingDegrees = (bearingDegrees + 360) % 360 // Normalize to 0-360
        
        // Calculate angle difference between heading and bearing
        var angleDiff = bearingDegrees - headingDegrees
        
        // Normalize to -180 to 180 range
        if (angleDiff > 180) angleDiff -= 360
        else if (angleDiff < -180) angleDiff += 360
        
        // Determine direction
        val absAngle = abs(angleDiff)
        
        return when {
            absAngle <= 22.5 -> "directly ahead"
            absAngle >= 157.5 -> "behind you"
            angleDiff > 0 -> "to your right"
            else -> "to your left"
        }
    }
    
    /**
     * Calculate distance between two coordinates in meters using Haversine formula.
     */
    private fun calculateDistanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0 // Earth's radius in meters
        
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val deltaLat = Math.toRadians(lat2 - lat1)
        val deltaLng = Math.toRadians(lng2 - lng1)
        
        val a = sin(deltaLat / 2).pow(2) +
                cos(lat1Rad) * cos(lat2Rad) *
                sin(deltaLng / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        
        return R * c
    }
    
    /**
     * Calculate future position along heading.
     */
    private fun calculateFuturePositionAlongHeading(
        lat: Double,
        lng: Double,
        headingDegrees: Float,
        speedKmh: Float,
        timeSeconds: Float
    ): Pair<Double, Double> {
        val R = 6371000.0 // Earth's radius in meters
        val distanceM = (speedKmh / 3.6) * timeSeconds // Convert km/h to m/s
        
        val latRad = Math.toRadians(lat)
        val headingRad = Math.toRadians(headingDegrees.toDouble())
        
        val newLat = asin(
            sin(latRad) * cos(distanceM / R) +
            cos(latRad) * sin(distanceM / R) * cos(headingRad)
        )
        
        val newLng = lng + Math.toDegrees(
            atan2(
                sin(headingRad) * sin(distanceM / R) * cos(latRad),
                cos(distanceM / R) - sin(latRad) * sin(newLat)
            )
        )
        
        return Pair(Math.toDegrees(newLat), newLng)
    }
    
    /**
     * Get current content with status code.
     */
    fun getContent(): ContentResponse {
        if (isGeneratingContent) {
            Log.d(TAG, "No content available: generation in progress")
            return ContentResponse(status = 0)
        }
        
        if (hasNewContent && latestContent.isNotEmpty()) {
            val content = latestContent.toList()
            val audio = latestAudio.toList()
            hasNewContent = false
            latestAudio = emptyList() // Clear audio after returning
            
            Log.i(TAG, "Returning ${content.size} summaries to client")
            return ContentResponse(
                status = 1,
                content = content,
                audio = audio
            )
        } else {
            Log.d(TAG, "No content available")
            return ContentResponse(status = 0)
        }
    }
    
    /**
     * Start the client.
     */
    fun start() {
        if (isRunning) {
            Log.w(TAG, "Client already running")
            return
        }
        
        isRunning = true
        Log.i(TAG, "Tour guide client started")
    }
    
    /**
     * Stop the client.
     */
    fun stop() {
        if (!isRunning) {
            return
        }
        
        isRunning = false
        clientScope.cancel()
        Log.i(TAG, "Tour guide client stopped")
    }
}


