package com.tourguide.locationexplorer.services

import android.util.Log
import com.tourguide.locationexplorer.config.LocationExplorerConfig
import com.tourguide.locationexplorer.models.PlaceInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.math.*

/**
 * Service for searching nearby places using Overpass API.
 * Ported from location_explorer/services/overpass_service.py
 */
class OverpassService(
    private val config: LocationExplorerConfig = LocationExplorerConfig
) : PlacesService {
    companion object {
        private const val TAG = "OverpassService"
        private const val DEFAULT_SPEED_REFERENCE_BASELINE = 50.0f
        
        // Score constants for promise calculation
        const val TOURISM_SCORE_HIGH = 0.25f
        const val TOURISM_SCORE_MEDIUM = 0.1f
        const val TOURISM_SCORE_LOW = 0.05f
        const val HISTORIC_SCORE = 0.1f
        const val HERITAGE_SCORE = 0.2f
        const val WATERWAY_SCORE = 0.04f
        const val WEBSITE_SCORE = 0.04f
        const val PLACE_STATE_CITY_SCORE = 0.32f
        const val PLACE_TOWN_VILLAGE_SCORE = 0.32f
        const val PLACE_SUBURB_SCORE = 0.1f
        const val PLACE_SQUARE_SCORE = 0.2f
        const val WIKIPEDIA_SCORE = 0.03f
        const val MULTILINGUAL_BASE_SCORE = 0.05f
        const val BUILDING_HIGH_SCORE = 0.05f
        const val BUILDING_MEDIUM_SCORE = 0.03f
        const val IMAGE_SCORE = 0.05f
    }
    
    private val okHttpClient: OkHttpClient
    private val json = Json { ignoreUnknownKeys = true }
    
    // Place history tracking (key -> timestamp)
    private val returnedPlaces = mutableMapOf<String, Long>()
    
    init {
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(config.overpassTimeout.toLong(), TimeUnit.SECONDS)
            .readTimeout(config.overpassTimeout.toLong(), TimeUnit.SECONDS)
            .writeTimeout(config.overpassTimeout.toLong(), TimeUnit.SECONDS)
            .build()
        
        Log.d(TAG, "Initialized Overpass service with API URL: ${config.overpassApiUrl}")
    }
    
    /**
     * Search for places near coordinates using Overpass API.
     * If no results after filtering, progressively increases radius up to MAX_RADIUS_M.
     */
    override suspend fun searchPlacesByCoordinates(
        lat: Double,
        lng: Double,
        radiusM: Int,
        speedKmh: Float
    ): List<PlaceInfo> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val maxAttempts = config.maxRadiusRetries
        val minRadiusM = 200
        
        // Adjust initial radius based on speed
        val speedMultiplier = speedKmh / DEFAULT_SPEED_REFERENCE_BASELINE
        var currentRadius = (radiusM * speedMultiplier).toInt().coerceAtLeast(minRadiusM)
            .coerceAtMost(config.maxRadiusM)
        
        val originalRadius = currentRadius
        
        Log.i(TAG, "Searching places near coordinates: $lat, $lng within ${currentRadius}m")
        
        for (attempt in 0 until maxAttempts) {
            Log.i(TAG, "Search attempt ${attempt + 1}/$maxAttempts with radius: ${currentRadius}m")
            
            try {
                val query = buildOverpassQuery(lat, lng, currentRadius)
                val places = executeOverpassQuery(query)
                
                if (places.isEmpty()) {
                    Log.i(TAG, "No places found with radius ${currentRadius}m")
                    if (attempt < maxAttempts - 1) {
                        currentRadius = (currentRadius * 1.5).toInt().coerceAtMost(config.maxRadiusM)
                        delay((config.radiusRetryDelay * 1000).toLong())
                    }
                    continue
                }
                
                Log.d(TAG, "Found ${places.size} raw places from Overpass with radius ${currentRadius}m")
                
                // History filter first
                var filteredPlaces = if (config.enablePlaceHistory) {
                    filterPlacesByHistory(places)
                } else {
                    places
                }
                
                if (filteredPlaces.isEmpty()) {
                    Log.i(TAG, "All places filtered by history, returning empty result")
                    return@withContext emptyList()
                }
                
                // Prioritize English names
                filteredPlaces = prioritizeEnglishNames(filteredPlaces)
                
                // Apply tag-based filters
                filteredPlaces = filterPlacesByTags(filteredPlaces)
                
                // Rank by promise score
                filteredPlaces = rankPlacesByPromise(filteredPlaces, lat, lng)
                
                // Apply Nominatim-based filters
                val finalPlaces = if (config.skipNominatimCalls) {
                    filterByPromiseScore(filteredPlaces)
                } else {
                    filterPlacesByNominatim(filteredPlaces)
                }
                
                if (finalPlaces.isNotEmpty()) {
                    val deduplicated = deduplicatePlacesByName(finalPlaces)
                    recordPlacesInHistory(deduplicated)
                    
                    if (currentRadius > originalRadius) {
                        Log.i(TAG, "Found ${deduplicated.size} places with expanded radius ${currentRadius}m")
                    } else {
                        Log.i(TAG, "Found ${deduplicated.size} places")
                    }
                    
                    return@withContext convertToPlaceInfoList(deduplicated)
                }
                
                Log.i(TAG, "No places passed filters with radius ${currentRadius}m")
                if (attempt < maxAttempts - 1) {
                    currentRadius = (currentRadius * 1.5).toInt().coerceAtMost(config.maxRadiusM)
                    delay((config.radiusRetryDelay * 1000).toLong())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in search attempt ${attempt + 1}: ${e.message}", e)
                if (attempt < maxAttempts - 1) {
                    currentRadius = (currentRadius * 1.5).toInt().coerceAtMost(config.maxRadiusM)
                    delay((config.radiusRetryDelay * 1000).toLong())
                } else {
                    return@withContext emptyList()
                }
            }
        }
        
        Log.w(TAG, "No places found after $maxAttempts attempts")
        return@withContext emptyList()
    }
    
    /**
     * Search for places near a named location.
     */
    suspend fun searchPlacesByName(
        locationName: String,
        radiusM: Int
    ): List<PlaceInfo> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        Log.d(TAG, "Searching places near location: '$locationName' within ${radiusM}m")
        
        try {
            val coordinates = geocodeLocationName(locationName)
            if (coordinates == null) {
                Log.w(TAG, "Could not geocode location: $locationName")
                return@withContext emptyList()
            }
            
            val (lat, lng) = coordinates
            Log.d(TAG, "Geocoded '$locationName' to: $lat, $lng")
            
            return@withContext searchPlacesByCoordinates(lat, lng, radiusM, DEFAULT_SPEED_REFERENCE_BASELINE)
        } catch (e: Exception) {
            Log.e(TAG, "Error searching places by name: ${e.message}", e)
            return@withContext emptyList()
        }
    }
    
    /**
     * Build Overpass QL query for searching places.
     */
    private fun buildOverpassQuery(lat: Double, lng: Double, radiusM: Int): String {
        val tourismFilter = config.tourismTypes.joinToString("|")
        val amenityFilter = config.amenityTypes.joinToString("|")
        val leisureFilter = config.leisureTypes.joinToString("|")
        val historicFilter = config.historicTypes.joinToString("|")
        val placeFilter = config.placeTypes.joinToString("|")
        
        val queryParts = mutableListOf<String>()
        
        if (tourismFilter.isNotEmpty()) {
            queryParts.add("node[\"tourism\"~\"$tourismFilter\"](around:$radiusM,$lat,$lng);")
            queryParts.add("way[\"tourism\"~\"$tourismFilter\"](around:$radiusM,$lat,$lng);")
        }
        
        if (leisureFilter.isNotEmpty()) {
            queryParts.add("node[\"leisure\"~\"$leisureFilter\"](around:$radiusM,$lat,$lng);")
            queryParts.add("way[\"leisure\"~\"$leisureFilter\"](around:$radiusM,$lat,$lng);")
        }
        
        if (placeFilter.isNotEmpty()) {
            queryParts.add("node[\"place\"~\"$placeFilter\"](around:$radiusM,$lat,$lng);")
            queryParts.add("way[\"place\"~\"$placeFilter\"](around:$radiusM,$lat,$lng);")
        }
        
        if (queryParts.isEmpty()) {
            Log.w(TAG, "No place types configured for search")
            return ""
        }
        
        return """[out:json][timeout:${config.overpassTimeout}];
(
${queryParts.joinToString("\n")}
);
out body center tags;"""
    }
    
    /**
     * Execute Overpass query and return results with retry logic.
     */
    private suspend fun executeOverpassQuery(query: String): List<Map<String, Any>> {
        if (query.isEmpty()) return emptyList()
        
        val maxRetries = 3
        var baseDelay = 5000L // 5 seconds
        
        for (attempt in 0 until maxRetries) {
            try {
                delay(100) // Rate limiting
                
                val requestBody = query.toRequestBody("text/plain".toMediaType())
                val request = Request.Builder()
                    .url(config.overpassApiUrl)
                    .post(requestBody)
                    .addHeader("User-Agent", "Location-Explorer/1.0")
                    .addHeader("Accept", "application/json")
                    .build()
                
                val response = okHttpClient.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return emptyList()
                    try {
                        val jsonResponse = json.decodeFromString<OverpassResponse>(body)
                        Log.i(TAG, "Overpass returned ${jsonResponse.elements.size} elements")
                        return convertOverpassElementsToMaps(jsonResponse.elements)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse Overpass response: ${e.message}")
                        // Try to parse as generic JSON
                        val genericResponse = json.decodeFromString<JsonObject>(body)
                        val elements = (genericResponse["elements"]?.toString()?.let { json.decodeFromString<List<Map<String, Any>>>(it) }) ?: emptyList()
                        return elements
                    }
                } else {
                    Log.w(TAG, "Overpass request failed with status ${response.code}")
                    if (attempt < maxRetries - 1) {
                        delay(baseDelay)
                        baseDelay *= 2
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Overpass request exception: ${e.message}")
                if (attempt < maxRetries - 1) {
                    delay(baseDelay)
                    baseDelay *= 2
                } else {
                    return emptyList()
                }
            }
        }
        
        return emptyList()
    }
    
    /**
     * Convert Overpass elements to map format for compatibility.
     */
    private fun convertOverpassElementsToMaps(elements: List<OverpassElement>): List<Map<String, Any>> {
        return elements.map { element ->
            val map = mutableMapOf<String, Any>(
                "type" to element.type,
                "id" to element.id,
                "tags" to element.tags
            )
            
            if (element.lat != null && element.lon != null) {
                map["lat"] = element.lat
                map["lon"] = element.lon
            } else if (element.center != null) {
                map["center"] = mapOf(
                    "lat" to element.center.lat,
                    "lon" to element.center.lon
                )
            }
            
            map
        }
    }
    
    /**
     * Prioritize English names from multilingual tags.
     */
    private fun prioritizeEnglishNames(places: List<Map<String, Any>>): List<Map<String, Any>> {
        var englishCount = 0
        return places.map { place ->
            val tags = (place["tags"] as? Map<String, String>)?.toMutableMap() ?: mutableMapOf()
            
            if (tags.containsKey("name:en") && tags["name:en"]?.isNotEmpty() == true) {
                tags["name"] = tags["name:en"]!!
                englishCount++
            }
            
            if (tags.containsKey("description:en") && tags["description:en"]?.isNotEmpty() == true) {
                tags["description"] = tags["description:en"]!!
            }
            
            if (tags.containsKey("alt_name:en") && tags["alt_name:en"]?.isNotEmpty() == true && 
                tags["name"]?.isEmpty() != false) {
                tags["name"] = tags["alt_name:en"]!!
                englishCount++
            }
            
            place.toMutableMap().apply {
                this["tags"] = tags
            }
        }
    }
    
    /**
     * Extract coordinates from a place object.
     */
    private fun extractCoordinates(place: Map<String, Any>): Pair<Double, Double> {
        val lat = (place["lat"] as? Number)?.toDouble() ?: 0.0
        val lon = (place["lon"] as? Number)?.toDouble() ?: 0.0
        
        if (lat != 0.0 || lon != 0.0) {
            return Pair(lat, lon)
        }
        
        val center = place["center"] as? Map<*, *>
        if (center != null) {
            val centerLat = (center["lat"] as? Number)?.toDouble() ?: 0.0
            val centerLon = (center["lon"] as? Number)?.toDouble() ?: 0.0
            return Pair(centerLat, centerLon)
        }
        
        return Pair(0.0, 0.0)
    }
    
    /**
     * Generate a unique key for a place to use in history tracking.
     */
    private fun generatePlaceKey(place: Map<String, Any>): String? {
        val osmId = (place["id"] as? Number)?.toLong()
        val osmType = place["type"] as? String
        
        if (osmId != null && osmType != null) {
            return "$osmType:$osmId"
        }
        
        val tags = place["tags"] as? Map<String, String> ?: emptyMap()
        val name = tags["name"]?.trim()?.lowercase() ?: ""
        val (lat, lng) = extractCoordinates(place)
        
        if (name.isNotEmpty() && lat != 0.0 && lng != 0.0) {
            val latRounded = round(lat * 10000) / 10000
            val lngRounded = round(lng * 10000) / 10000
            return "name:$name:$latRounded:$lngRounded"
        }
        
        if (lat != 0.0 && lng != 0.0) {
            val latRounded = round(lat * 10000) / 10000
            val lngRounded = round(lng * 10000) / 10000
            return "coord:$latRounded:$lngRounded"
        }
        
        return null
    }
    
    /**
     * Filter out places that have already been returned (history tracking).
     */
    private fun filterPlacesByHistory(places: List<Map<String, Any>>): List<Map<String, Any>> {
        if (!config.enablePlaceHistory) return places
        
        val currentTime = System.currentTimeMillis() / 1000
        
        // Clean up expired entries
        val expiredKeys = returnedPlaces.filter { 
            currentTime - it.value > config.placeHistoryTtl 
        }.keys
        
        expiredKeys.forEach { returnedPlaces.remove(it) }
        
        // Filter places
        val filtered = places.filter { place ->
            val key = generatePlaceKey(place)
            if (key == null) return@filter true
            
            if (returnedPlaces.containsKey(key)) {
                val placeName = (place["tags"] as? Map<*, *>)?.get("name") ?: "Unknown"
                Log.d(TAG, "Skipping already-seen place: '$placeName'")
                false
            } else {
                true
            }
        }
        
        return filtered
    }
    
    /**
     * Record places in history after they are returned to the user.
     */
    private fun recordPlacesInHistory(places: List<Map<String, Any>>) {
        if (!config.enablePlaceHistory) return
        
        val currentTime = System.currentTimeMillis() / 1000
        var recordedCount = 0
        
        places.forEach { place ->
            val key = generatePlaceKey(place)
            if (key != null) {
                returnedPlaces[key] = currentTime
                recordedCount++
            }
        }
        
        if (recordedCount > 0) {
            Log.d(TAG, "Recorded $recordedCount places in history")
        }
    }
    
    /**
     * Apply generic tag-based filters to places.
     */
    private fun filterPlacesByTags(places: List<Map<String, Any>>): List<Map<String, Any>> {
        if (config.overpassFilters.isEmpty()) {
            return places
        }
        
        return places.filter { place ->
            config.overpassFilters.all { filterExpr ->
                applyFilter(place, filterExpr)
            }
        }
    }
    
    /**
     * Apply a single filter expression to a place.
     */
    private fun applyFilter(place: Map<String, Any>, filterExpr: String): Boolean {
        val tags = (place["tags"] as? Map<String, String>) ?: emptyMap()
        
        if (" AND " in filterExpr) {
            val conditions = filterExpr.split(" AND ")
            return conditions.all { condition ->
                checkFilterCondition(tags, condition.trim())
            }
        } else {
            return checkFilterCondition(tags, filterExpr.trim())
        }
    }
    
    private fun checkFilterCondition(tags: Map<String, String>, condition: String): Boolean {
        if (":" !in condition) return true
        
        val (field, value) = condition.split(":", limit = 2)
        val fieldTrimmed = field.trim()
        val valueTrimmed = value.trim()
        
        if (valueTrimmed.isEmpty()) {
            return tags.containsKey(fieldTrimmed) && 
                   tags[fieldTrimmed]?.isNotEmpty() == true
        }
        
        return tags[fieldTrimmed] != valueTrimmed
    }
    
    /**
     * Calculate promise scores and distances, then sort by promise.
     */
    private fun rankPlacesByPromise(
        places: List<Map<String, Any>>,
        centerLat: Double,
        centerLng: Double
    ): List<Map<String, Any>> {
        return places.map { place ->
            val (lat, lng) = extractCoordinates(place)
            val distanceM = if (lat != 0.0 && lng != 0.0) {
                calculateDistance(centerLat, centerLng, lat, lng)
            } else {
                0.0
            }
            val promiseScore = calculateOsmPromiseScore(place)
            
            place.toMutableMap().apply {
                this["distance_m"] = distanceM
                this["promise_score"] = promiseScore
            }
        }.sortedWith(compareByDescending<Map<String, Any>> { 
            (it["promise_score"] as? Number)?.toFloat() ?: 0f
        }.thenBy {
            (it["distance_m"] as? Number)?.toDouble() ?: Double.MAX_VALUE
        })
    }
    
    /**
     * Calculate a promise score based on OSM tags.
     */
    private fun calculateOsmPromiseScore(place: Map<String, Any>): Float {
        val tags = (place["tags"] as? Map<String, String>) ?: emptyMap()
        var score = 0.0f
        val scoreDetails = mutableListOf<String>()
        
        // Tourism attractions
        tags["tourism"]?.let { tourismType ->
            when (tourismType) {
                "attraction", "museum", "monument", "theme_park", "zoo", "park" -> {
                    score += TOURISM_SCORE_HIGH
                    scoreDetails.add("tourism=$tourismType (+$TOURISM_SCORE_HIGH)")
                }
                "gallery", "viewpoint", "memorial" -> {
                    score += TOURISM_SCORE_MEDIUM
                    scoreDetails.add("tourism=$tourismType (+$TOURISM_SCORE_MEDIUM)")
                }
                else -> {
                    score += TOURISM_SCORE_LOW
                    scoreDetails.add("tourism=$tourismType (+$TOURISM_SCORE_LOW)")
                }
            }
        }
        
        // Historic places
        if (tags.containsKey("historic")) {
            score += HISTORIC_SCORE
            scoreDetails.add("historic=${tags["historic"]} (+$HISTORIC_SCORE)")
        }
        
        // Heritage designation
        if (tags.containsKey("heritage")) {
            score += HERITAGE_SCORE
            scoreDetails.add("heritage=${tags["heritage"]} (+$HERITAGE_SCORE)")
        }
        
        // Waterway
        tags["waterway"]?.let { waterwayTag ->
            if (waterwayTag == "waterfall") {
                score += WATERWAY_SCORE
                scoreDetails.add("waterway=$waterwayTag (+$WATERWAY_SCORE)")
            }
        }
        
        // Website
        if (tags.containsKey("website")) {
            score += WEBSITE_SCORE
            scoreDetails.add("website (+$WEBSITE_SCORE)")
        }
        
        // Place types
        tags["place"]?.let { placeType ->
            val placeScore = when (placeType) {
                "state", "city" -> PLACE_STATE_CITY_SCORE
                "town", "village" -> PLACE_TOWN_VILLAGE_SCORE
                "suburb" -> PLACE_SUBURB_SCORE
                "square" -> PLACE_SQUARE_SCORE
                else -> 0.0f
            }
            if (placeScore > 0) {
                score += placeScore
                scoreDetails.add("place=$placeType (+$placeScore)")
            }
        }
        
        // Wikipedia
        if (tags.containsKey("wikipedia") || tags.containsKey("wikidata")) {
            score += WIKIPEDIA_SCORE
            scoreDetails.add("wikipedia/wikidata (+$WIKIPEDIA_SCORE)")
        }
        
        // Multilingual names
        val nameKeys = tags.keys.filter { it.startsWith("name:") }
        if (nameKeys.size > 2) {
            val multilingualScore = MULTILINGUAL_BASE_SCORE * nameKeys.size
            score += multilingualScore
            scoreDetails.add("multilingual names (${nameKeys.size} languages) (+$multilingualScore)")
        }
        
        // Building type
        tags["building"]?.let { buildingType ->
            when (buildingType) {
                "palace", "castle", "church", "cathedral" -> {
                    score += BUILDING_HIGH_SCORE
                    scoreDetails.add("building=$buildingType (+$BUILDING_HIGH_SCORE)")
                }
                "museum", "theatre", "library" -> {
                    score += BUILDING_MEDIUM_SCORE
                    scoreDetails.add("building=$buildingType (+$BUILDING_MEDIUM_SCORE)")
                }
                else -> {} // Missing branch added here
            }
        }
        
        // Image tag
        if (tags.containsKey("image") && tags["image"]?.isNotEmpty() == true) {
            score += IMAGE_SCORE
            scoreDetails.add("image tag (+$IMAGE_SCORE)")
        }
        
        val finalScore = min(score, 1.0f)
        val placeName = tags["name"] ?: "Unnamed"
        
        if (scoreDetails.isNotEmpty()) {
            Log.i(TAG, "Promise score breakdown for '$placeName': $finalScore = ${scoreDetails.joinToString(" + ")}")
        }
        
        return finalScore
    }
    
    /**
     * Calculate distance between two coordinates in meters using Haversine formula.
     */
    private fun calculateDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
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
     * Filter places by promise score (when Nominatim is skipped).
     */
    private fun filterByPromiseScore(places: List<Map<String, Any>>): List<Map<String, Any>> {
        if (places.isEmpty()) return emptyList()
        
        val avgScore = places.mapNotNull { 
            (it["promise_score"] as? Number)?.toFloat() 
        }.average().toFloat()
        
        val adaptiveThreshold = max(config.importanceThreshold, avgScore)
        Log.i(TAG, "Filtering ${places.size} places by promise_score (avg: $avgScore, threshold: $adaptiveThreshold)")
        
        val filtered = places.filter {
            ((it["promise_score"] as? Number)?.toFloat() ?: 0f) >= adaptiveThreshold
        }
        
        val limited = if (config.maxResults > 0) {
            filtered.take(config.maxResults)
        } else {
            filtered
        }
        
        Log.i(TAG, "After filtering (no Nominatim): ${limited.size} places out of ${places.size} total")
        return limited
    }
    
    /**
     * Filter places using Nominatim's rank and importance.
     */
    private suspend fun filterPlacesByNominatim(places: List<Map<String, Any>>): List<Map<String, Any>> {
        val maxCalls = min(config.maxNominatimCalls, places.size)
        Log.i(TAG, "Processing $maxCalls most promising places out of ${places.size} total")
        
        val filtered = mutableListOf<Map<String, Any>>()
        
        for (i in 0 until maxCalls) {
            try {
                val place = places[i]
                val (lat, lng) = extractCoordinates(place)
                
                if (lat == 0.0 || lng == 0.0) {
                    continue
                }
                
                val tags = (place["tags"] as? Map<String, String>) ?: emptyMap()
                val placeName = tags["name"] ?: "Unnamed"
                
                val (importance, placeRank) = getNominatimRankAndImportance(lat, lng, placeName)
                
                val updatedPlace = place.toMutableMap().apply {
                    this["importance"] = importance
                    this["place_rank"] = placeRank
                }
                
                if (placeRank <= config.placeRankThreshold && importance >= config.importanceThreshold) {
                    filtered.add(updatedPlace)
                    
                    if (filtered.size >= config.maxResults && config.maxResults > 0) {
                        Log.i(TAG, "Early termination: Found ${filtered.size} good places")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error processing place ${i + 1}: ${e.message}")
                continue
            }
        }
        
        val sorted = filtered.sortedWith(compareBy<Map<String, Any>> {
            (it["place_rank"] as? Number)?.toInt() ?: 30
        }.thenByDescending {
            (it["importance"] as? Number)?.toFloat() ?: 0f
        })
        
        val finalResults = if (config.maxResults > 0) {
            sorted.take(config.maxResults)
        } else {
            sorted
        }
        
        Log.i(TAG, "After Nominatim filtering: ${finalResults.size} places")
        return finalResults
    }
    
    /**
     * Get place_rank and importance from Nominatim reverse lookup.
     */
    private suspend fun getNominatimRankAndImportance(
        lat: Double,
        lng: Double,
        placeName: String
    ): Pair<Float, Int> {
        val maxRetries = 3
        var baseDelay = 5000L
        
        for (attempt in 0 until maxRetries) {
            try {
                delay(1000) // Rate limiting - 1 call per second
                
                val url = "https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lng&format=json&accept-language=en&addressdetails=1"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Location-Explorer/1.0")
                    .addHeader("Accept", "application/json")
                    .build()
                
                val response = okHttpClient.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: continue
                    val jsonResponse = json.decodeFromString<Map<String, Any>>(body)
                    
                    val importance = ((jsonResponse["importance"] as? Number)?.toFloat() ?: 0f)
                    val placeRank = ((jsonResponse["place_rank"] as? Number)?.toInt() ?: 30)
                    
                    Log.i(TAG, "Nominatim data for $lat, $lng: importance=$importance, place_rank=$placeRank")
                    return Pair(importance, placeRank)
                } else {
                    if (attempt < maxRetries - 1) {
                        delay(baseDelay)
                        baseDelay *= 2
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Nominatim request exception: ${e.message}")
                if (attempt < maxRetries - 1) {
                    delay(baseDelay)
                    baseDelay *= 2
                }
            }
        }
        
        return Pair(0.0f, 30) // Fallback values
    }
    
    /**
     * Geocode location name to coordinates using Nominatim.
     */
    private suspend fun geocodeLocationName(locationName: String): Pair<Double, Double>? {
        val maxRetries = 3
        var baseDelay = 5000L
        
        for (attempt in 0 until maxRetries) {
            try {
                delay(100) // Rate limiting
                
                val url = "https://nominatim.openstreetmap.org/search?q=${java.net.URLEncoder.encode(locationName, "UTF-8")}&format=json&limit=1&addressdetails=1&accept-language=en"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Location-Explorer/1.0")
                    .addHeader("Accept", "application/json")
                    .build()
                
                val response = okHttpClient.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: continue
                    val jsonResponse = json.decodeFromString<List<Map<String, Any>>>(body)
                    
                    if (jsonResponse.isNotEmpty()) {
                        val result = jsonResponse[0]
                        val lat = ((result["lat"] as? String)?.toDouble()) ?: continue
                        val lng = ((result["lon"] as? String)?.toDouble()) ?: continue
                        Log.i(TAG, "Geocoded '$locationName' to: $lat, $lng")
                        return Pair(lat, lng)
                    }
                } else {
                    if (attempt < maxRetries - 1) {
                        delay(baseDelay)
                        baseDelay *= 2
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Geocoding request exception: ${e.message}")
                if (attempt < maxRetries - 1) {
                    delay(baseDelay)
                    baseDelay *= 2
                }
            }
        }
        
        return null
    }
    
    /**
     * Remove duplicate places with the same name, keeping the one with the highest promise_score.
     */
    private fun deduplicatePlacesByName(places: List<Map<String, Any>>): List<Map<String, Any>> {
        val seenNames = mutableMapOf<String, Float>()
        val deduplicated = mutableListOf<Map<String, Any>>()
        var removedCount = 0
        
        places.forEach { place ->
            val tags = (place["tags"] as? Map<String, String>) ?: emptyMap()
            val name = tags["name"]?.trim() ?: ""
            
            if (name.isEmpty()) {
                deduplicated.add(place)
                return@forEach
            }
            
            val nameNormalized = name.lowercase()
            val currentScore = (place["promise_score"] as? Number)?.toFloat() ?: 0f
            
            if (seenNames.containsKey(nameNormalized)) {
                val existingScore = seenNames[nameNormalized]!!
                if (currentScore < existingScore) {
                    removedCount++
                    Log.d(TAG, "Removing duplicate '$name' (existing score: $existingScore, current score: $currentScore)")
                    return@forEach
                }
            }
            
            seenNames[nameNormalized] = currentScore
            deduplicated.add(place)
        }
        
        if (removedCount > 0) {
            Log.i(TAG, "Deduplicated places by name: ${deduplicated.size} kept, $removedCount removed")
        }
        
        return deduplicated
    }
    
    /**
     * Convert place maps to PlaceInfo objects.
     */
    private fun convertToPlaceInfoList(places: List<Map<String, Any>>): List<PlaceInfo> {
        return places.mapNotNull { place ->
            try {
                val tags = (place["tags"] as? Map<String, String>) ?: emptyMap()
                val name = tags["name"] ?: "Unnamed ${tags["tourism"] ?: tags["amenity"] ?: tags["leisure"] ?: tags["historic"] ?: "place"}"
                
                val (lat, lng) = extractCoordinates(place)
                
                val placeType = when {
                    tags.containsKey("tourism") -> "tourism"
                    tags.containsKey("amenity") -> "amenity"
                    tags.containsKey("leisure") -> "leisure"
                    tags.containsKey("historic") -> "historic"
                    tags.containsKey("place") -> "place"
                    else -> "unknown"
                }
                
                val category = tags[placeType] ?: "unknown"
                val importance = ((place["importance"] as? Number)?.toFloat()) ?: 0f
                val rank = ((place["place_rank"] as? Number)?.toInt()) ?: 30
                val distanceM = ((place["distance_m"] as? Number)?.toFloat())
                val osmId = (place["id"] as? Number)?.toLong()
                val osmType = place["type"] as? String
                
                PlaceInfo(
                    name = name,
                    lat = lat,
                    lng = lng,
                    placeType = placeType,
                    category = category,
                    importance = importance,
                    rank = rank,
                    distanceM = distanceM,
                    tags = tags,
                    osmId = osmId,
                    osmType = osmType
                )
            } catch (e: Exception) {
                Log.w(TAG, "Error processing place data: ${e.message}")
                null
            }
        }
    }
}
