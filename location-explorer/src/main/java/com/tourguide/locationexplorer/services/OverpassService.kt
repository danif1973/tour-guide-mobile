package com.tourguide.locationexplorer.services

import android.util.Log
import com.tourguide.locationexplorer.config.LocationExplorerConfig
import com.tourguide.locationexplorer.models.OverpassElement
import com.tourguide.locationexplorer.models.OverpassResponse
import com.tourguide.locationexplorer.models.PlaceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Fetches place data from the Overpass API.
 * The complex logic of filtering and ranking is delegated to PlacesProcessor.
 */
class OverpassService(
    private val config: LocationExplorerConfig = LocationExplorerConfig,
    private val processor: PlacesProcessor = PlacesProcessor(config)
) : PlacesService {

    companion object {
        private const val TAG = "OverpassService"
        private const val DEFAULT_SPEED_REFERENCE_BASELINE = 50.0f
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.overpassTimeout.toLong(), TimeUnit.SECONDS)
        .readTimeout(config.overpassTimeout.toLong(), TimeUnit.SECONDS)
        .build()
    
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun searchPlacesByCoordinates(
        lat: Double,
        lng: Double,
        radiusM: Int,
        speedKmh: Float
    ): List<PlaceInfo> = withContext(Dispatchers.IO) {
        val speedMultiplier = speedKmh / DEFAULT_SPEED_REFERENCE_BASELINE
        var currentRadius = (radiusM * speedMultiplier).toInt().coerceAtLeast(200)
            .coerceAtMost(config.maxRadiusM)

        for (attempt in 0 until config.maxRadiusRetries) {
            Log.i(TAG, "Searching Overpass: $lat, $lng radius=$currentRadius (Attempt ${attempt + 1})")

            try {
                val query = buildOverpassQuery(lat, lng, currentRadius)
                val rawPlaces = executeOverpassQuery(query)

                if (rawPlaces.isNotEmpty()) {
                    val processedPlaces = processor.processPlaces(rawPlaces, lat, lng)
                    if (processedPlaces.isNotEmpty()) {
                        return@withContext processedPlaces
                    }
                }
                
                // If no places found or all were filtered, expand radius for next attempt
                currentRadius = (currentRadius * 1.5).toInt().coerceAtMost(config.maxRadiusM)
                if (attempt < config.maxRadiusRetries - 1) delay((config.radiusRetryDelay * 1000).toLong())

            } catch (e: Exception) {
                Log.e(TAG, "Error fetching from Overpass", e)
                break // Stop on error
            }
        }

        return@withContext emptyList()
    }

    private fun buildOverpassQuery(lat: Double, lng: Double, radiusM: Int): String {
        val placeTypes = (config.tourismTypes + config.leisureTypes + config.historicTypes + config.placeTypes).toSet()
        val filter = placeTypes.joinToString("|")
        val query = """[out:json][timeout:${config.overpassTimeout}];
(
  node["tourism"~"$filter"](around:$radiusM,$lat,$lng);
  way["tourism"~"$filter"](around:$radiusM,$lat,$lng);
);
out body center tags;"""
        return query
    }

    private suspend fun executeOverpassQuery(query: String): List<Map<String, Any>> {
        val requestBody = query.toRequestBody("text/plain".toMediaType())
        val request = Request.Builder()
            .url(config.overpassApiUrl)
            .post(requestBody)
            .build()
        
        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.e(TAG, "Overpass request failed: ${response.code}")
            return emptyList()
        }

        val body = response.body?.string() ?: return emptyList()
        val overpassResponse = json.decodeFromString<OverpassResponse>(body)
        return convertOverpassElementsToMaps(overpassResponse.elements)
    }

    private fun convertOverpassElementsToMaps(elements: List<OverpassElement>): List<Map<String, Any>> {
        return elements.map { element ->
            mutableMapOf<String, Any>(
                "type" to element.type,
                "id" to element.id,
                "tags" to HashMap(element.tags) // Ensure it's a standard Kotlin map
            ).apply {
                element.lat?.let { put("lat", it) }
                element.lon?.let { put("lon", it) }
                element.center?.let { put("center", mapOf("lat" to it.lat, "lon" to it.lon)) }
            }
        }
    }
}
