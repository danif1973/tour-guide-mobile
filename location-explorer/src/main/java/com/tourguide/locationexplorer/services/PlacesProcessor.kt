package com.tourguide.locationexplorer.services

import android.util.Log
import com.tourguide.locationexplorer.config.LocationExplorerConfig
import com.tourguide.locationexplorer.models.PlaceInfo
import kotlin.math.*

/**
 * Shared processor for filtering, ranking, and preparing place data.
 * This class contains the generic logic extracted from OverpassService.
 */
class PlacesProcessor(
    private val config: LocationExplorerConfig = LocationExplorerConfig
) {
    companion object {
        private const val TAG = "PlacesProcessor"
        // Score constants for promise calculation
        const val TOURISM_SCORE_HIGH = 0.25f
        const val TOURISM_SCORE_MEDIUM = 0.1f
        const val TOURISM_SCORE_LOW = 0.05f
        const val HISTORIC_SCORE = 0.1f
        const val HERITAGE_SCORE = 0.2f
        const val WEBSITE_SCORE = 0.04f
        const val WIKIPEDIA_SCORE = 0.03f
        const val MULTILINGUAL_BASE_SCORE = 0.05f
    }
    
    private val returnedPlaces = mutableMapOf<String, Long>()

    fun processPlaces(
        rawPlaces: List<Map<String, Any>>,
        centerLat: Double,
        centerLng: Double
    ): List<PlaceInfo> {
        Log.i(TAG, "Starting processing for ${rawPlaces.size} raw places.")

        val processedPlaces = prioritizeEnglishNames(rawPlaces)

        val historyFiltered = filterPlacesByHistory(processedPlaces)
        if (historyFiltered.isEmpty()) {
            Log.i(TAG, "All places filtered out by history. Nothing to return.")
            return emptyList()
        }

        val tagFiltered = filterPlacesByTags(historyFiltered)
        if (tagFiltered.isEmpty()) {
            Log.i(TAG, "All places filtered out by tags. Nothing to return.")
            return emptyList()
        }

        val rankedPlaces = rankPlacesByPromise(tagFiltered, centerLat, centerLng)
        val finalPlaces = filterByPromiseScore(rankedPlaces)

        if (finalPlaces.isEmpty()) {
            Log.i(TAG, "All places filtered out by promise/nominatim score. Nothing to return.")
            return emptyList()
        }

        val deduplicated = deduplicatePlacesByName(finalPlaces)
        recordPlacesInHistory(deduplicated)

        val result = convertToPlaceInfoList(deduplicated)
        Log.i(TAG, "Finished processing. Returning ${result.size} places.")
        return result
    }

    private fun filterPlacesByHistory(places: List<Map<String, Any>>): List<Map<String, Any>> {
        if (!config.enablePlaceHistory) return places
        val initialCount = places.size
        val currentTime = System.currentTimeMillis() / 1000
        returnedPlaces.entries.removeIf { currentTime - it.value > config.placeHistoryTtl }
        val filtered = places.filterNot { returnedPlaces.containsKey(generatePlaceKey(it)) }
        Log.i(TAG, "History Filter: Removed ${initialCount - filtered.size} already seen places.")
        return filtered
    }

    private fun prioritizeEnglishNames(places: List<Map<String, Any>>): List<Map<String, Any>> {
        return places.map { place ->
            val tags = (place["tags"] as? Map<*, *>)?.mapNotNull { (k, v) -> (k as? String)?.let { key -> v?.toString()?.let { value -> key to value } } }?.toMap() ?: emptyMap()
            val newTags = tags.toMutableMap()
            if (newTags.containsKey("name:en")) {
                newTags["name"] = newTags["name:en"]!!
            }
            place.toMutableMap().apply { this["tags"] = newTags }
        }
    }

    private fun filterPlacesByTags(places: List<Map<String, Any>>): List<Map<String, Any>> {
        if (config.overpassFilters.isEmpty()) return places
        val initialCount = places.size
        val filtered = places.filter { place -> config.overpassFilters.all { applyFilter(place, it) } }
        Log.i(TAG, "Tag Filter: Removed ${initialCount - filtered.size} places based on filters.")
        return filtered
    }

    private fun applyFilter(place: Map<String, Any>, filterExpr: String): Boolean {
        val tags = (place["tags"] as? Map<*, *>)?.mapNotNull { (k, v) -> (k as? String)?.let { key -> v?.toString()?.let { value -> key to value } } }?.toMap() ?: emptyMap()
        return filterExpr.split(" AND ").all { checkFilterCondition(tags, it.trim()) }
    }

    private fun checkFilterCondition(tags: Map<String, String>, condition: String): Boolean {
        val parts = condition.split(":", limit = 2)
        if (parts.size < 2) return true
        return tags[parts[0].trim()] != parts[1].trim()
    }

    private fun rankPlacesByPromise(places: List<Map<String, Any>>, centerLat: Double, centerLng: Double): List<Map<String, Any>> {
        val ranked = places.map { place ->
            val (lat, lng) = extractCoordinates(place)
            place.toMutableMap().apply {
                this["distance_m"] = calculateDistance(centerLat, centerLng, lat, lng)
                this["promise_score"] = calculateOsmPromiseScore(place)
            }
        }
        return ranked.sortedWith(
            compareByDescending<Map<String, Any>> { it["promise_score"] as? Float ?: 0.0f }
                .thenBy { it["distance_m"] as? Double ?: Double.MAX_VALUE }
        )
    }

    private fun filterByPromiseScore(places: List<Map<String, Any>>): List<Map<String, Any>> {
        if (places.isEmpty()) return emptyList()
        val scores = places.mapNotNull { it["promise_score"] as? Float }
        if (scores.isEmpty()) return emptyList()

        // MAD Method to remove outliers
        val median = scores.sorted().let { 
            if (it.size % 2 == 0) (it[it.size / 2 - 1] + it[it.size / 2]) / 2.0f 
            else it[it.size / 2] 
        }
        val mad = scores.map { abs(it - median) }.sorted().let { 
            if (it.size % 2 == 0) (it[it.size / 2 - 1] + it[it.size / 2]) / 2.0f 
            else it[it.size / 2] 
        }
        val outlierThreshold = median - 2 * mad
        
        val outliers = places.filter { (it["promise_score"] as? Float ?: 0f) < outlierThreshold }
        if (outliers.isNotEmpty()) {
            Log.i(TAG, "MAD outlier removal: Removed ${outliers.size} low-score places below threshold $outlierThreshold:")
            outliers.forEach { outlier ->
                val tags = (outlier["tags"] as? Map<*, *>)?.mapNotNull { (k, v) -> (k as? String)?.let { key -> v?.toString()?.let { value -> key to value } } }?.toMap() ?: emptyMap()
                val name = tags["name"] ?: "Unnamed"
                val score = outlier["promise_score"] as? Float ?: 0f
                Log.d(TAG, "  - Removed: $name (Score: $score)")
            }
        }

        val cleanedPlaces = places.filter { (it["promise_score"] as? Float ?: 0f) >= outlierThreshold }

        // Calculate threshold from cleaned data
        val cleanedScores = cleanedPlaces.mapNotNull { it["promise_score"] as? Float }
        val avgScore = if (cleanedScores.isNotEmpty()) cleanedScores.average().toFloat() else 0.0f
        val adaptiveThreshold = max(config.importanceThreshold, avgScore)
        Log.i(TAG, "Filtering by promise score. Avg: $avgScore, Threshold: $adaptiveThreshold")

        val filtered = cleanedPlaces.filter { (it["promise_score"] as? Float ?: 0f) >= adaptiveThreshold }
        val result = if (config.maxResults > 0) filtered.take(config.maxResults) else filtered
        Log.i(TAG, "Promise Score Filter: Kept ${result.size} of ${cleanedPlaces.size} places.")
        return result
    }

    private fun deduplicatePlacesByName(places: List<Map<String, Any>>): List<Map<String, Any>> {
        val initialCount = places.size
        val seenNames = mutableMapOf<String, Map<String, Any>>()
        places.forEach { place ->
            val name = ((place["tags"] as? Map<*, *>)?.mapNotNull { (k, v) -> (k as? String)?.let { key -> v?.toString()?.let { value -> key to value } } }?.toMap() ?: emptyMap())["name"]?.trim()?.lowercase() ?: ""
            if (name.isNotEmpty()) {
                val existing = seenNames[name]
                val currentScore = place["promise_score"] as? Float
                val existingScore = existing?.get("promise_score") as? Float
                
                if (existing == null || (currentScore != null && (existingScore == null || currentScore > existingScore))) {
                    seenNames[name] = place
                }
            } else {
                generatePlaceKey(place)?.let { key -> seenNames[key] = place }
            }
        }
        val deduplicated = seenNames.values.toList()
        Log.i(TAG, "Deduplication: Removed ${initialCount - deduplicated.size} duplicate places.")
        return deduplicated
    }
    
    private fun convertToPlaceInfoList(places: List<Map<String, Any>>): List<PlaceInfo> {
        return places.mapNotNull { place ->
            try {
                val tags = (place["tags"] as? Map<*, *>)?.mapNotNull { (k, v) -> (k as? String)?.let { key -> v?.toString()?.let { value -> key to value } } }?.toMap() ?: emptyMap()
                PlaceInfo(
                    name = tags["name"] ?: "Unnamed Place",
                    lat = (place["lat"] as? Double) ?: 0.0,
                    lng = (place["lon"] as? Double) ?: 0.0,
                    placeType = tags.keys.firstOrNull { it in listOf("tourism", "amenity", "leisure", "historic", "place") } ?: "unknown",
                    category = tags[tags.keys.firstOrNull { it in listOf("tourism", "amenity", "leisure", "historic", "place") }] ?: "unknown",
                    importance = (place["importance"] as? Float) ?: (place["promise_score"] as? Float) ?: 0f,
                    rank = (place["place_rank"] as? Int) ?: 30,
                    distanceM = (place["distance_m"] as? Double)?.toFloat(),
                    tags = tags,
                    osmId = (place["id"] as? Long),
                    osmType = place["type"] as? String
                )
            } catch (e: Exception) {
                Log.w(TAG, "Error converting place data: ${e.message}")
                null
            }
        }
    }

    private fun recordPlacesInHistory(places: List<Map<String, Any>>) {
        if (!config.enablePlaceHistory) return
        val currentTime = System.currentTimeMillis() / 1000
        places.forEach { place -> generatePlaceKey(place)?.let { key -> returnedPlaces[key] = currentTime } }
    }

     private fun calculateOsmPromiseScore(place: Map<String, Any>): Float {
        val tags = (place["tags"] as? Map<*, *>)?.mapNotNull { (k, v) -> (k as? String)?.let { key -> v?.toString()?.let { value -> key to value } } }?.toMap() ?: emptyMap()
        var score = 0.0f
        val scoreDetails = mutableListOf<String>()

        tags["tourism"]?.let {
            when (it) {
                "attraction", "museum", "monument", "theme_park", "zoo", "park" -> {
                    score += TOURISM_SCORE_HIGH; scoreDetails.add("tourism (high) (+$TOURISM_SCORE_HIGH)")
                }
                "gallery", "viewpoint", "memorial" -> {
                    score += TOURISM_SCORE_MEDIUM; scoreDetails.add("tourism (medium) (+$TOURISM_SCORE_MEDIUM)")
                }
                else -> {
                    score += TOURISM_SCORE_LOW; scoreDetails.add("tourism (low) (+$TOURISM_SCORE_LOW)")
                }
            }
        }
        if (tags.containsKey("historic")) { score += HISTORIC_SCORE; scoreDetails.add("historic (+$HISTORIC_SCORE)") }
        if (tags.containsKey("heritage")) { score += HERITAGE_SCORE; scoreDetails.add("heritage (+$HERITAGE_SCORE)") }
        if (tags.containsKey("website")) { score += WEBSITE_SCORE; scoreDetails.add("website (+$WEBSITE_SCORE)") }
        if (tags.containsKey("wikipedia") || tags.containsKey("wikidata")) { score += WIKIPEDIA_SCORE; scoreDetails.add("wiki (+$WIKIPEDIA_SCORE)") }
        val nameKeys = tags.keys.filter { it.startsWith("name:") }.size
        if (nameKeys > 2) { 
            val multiLingualScore = MULTILINGUAL_BASE_SCORE * nameKeys
            score += multiLingualScore
            scoreDetails.add("$nameKeys names (+$multiLingualScore)")
        }

        val finalScore = min(score, 1.0f)
        if (scoreDetails.isNotEmpty()) {
            val placeName = tags["name"] ?: "Unnamed"
            Log.d(TAG, "Promise score for '$placeName': $finalScore = ${scoreDetails.joinToString(" + ")}")
        }
        return finalScore
    }

    private fun extractCoordinates(place: Map<String, Any>): Pair<Double, Double> {
        (place["lat"] as? Double)?.let { lat -> (place["lon"] as? Double)?.let { lon -> return Pair(lat, lon) } }
        (place["center"] as? Map<*, *>)?.let { center ->
            (center["lat"] as? Double)?.let { lat -> (center["lon"] as? Double)?.let { lon -> return Pair(lat, lon) } }
        }
        return Pair(0.0, 0.0)
    }

    private fun generatePlaceKey(place: Map<String, Any>): String? {
        (place["id"] as? Long)?.let { id -> (place["type"] as? String)?.let { type -> return "$type:$id" } }
        return null
    }

    private fun calculateDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0 // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
