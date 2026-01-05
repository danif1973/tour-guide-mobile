package com.tourguide.locationexplorer.services

// https://apidocs.geoapify.com/docs/places/#categories

import android.util.Log
import com.tourguide.locationexplorer.config.LocationExplorerConfig
import com.tourguide.locationexplorer.models.PlaceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches place data from the Geoapify Places API.
 * The complex logic of filtering and ranking is delegated to PlacesProcessor.
 */
class GeoapifyService(
    private val config: LocationExplorerConfig = LocationExplorerConfig,
    private val processor: PlacesProcessor = PlacesProcessor(config)
) : PlacesService {

    companion object {
        private const val TAG = "GeoapifyService"
        private const val BASE_URL = "https://api.geoapify.com/v2/places"
        private const val DEFAULT_SPEED_REFERENCE_BASELINE = 50.0f
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun searchPlacesByCoordinates(
        lat: Double,
        lng: Double,
        radiusM: Int,
        speedKmh: Float
    ): List<PlaceInfo> = withContext(Dispatchers.IO) {
        if (config.geoapifyApiKey.isNullOrBlank()) {
            Log.e(TAG, "Geoapify API key is missing.")
            return@withContext emptyList()
        }

        val categories = buildCategoriesList()
        if (categories.isEmpty()) {
            Log.w(TAG, "No categories configured for Geoapify search.")
            return@withContext emptyList()
        }

        val speedMultiplier = speedKmh / DEFAULT_SPEED_REFERENCE_BASELINE
        var currentRadius = (radiusM * speedMultiplier).toInt().coerceAtLeast(200)
            .coerceAtMost(config.maxRadiusM)

        for (attempt in 0 until config.maxRadiusRetries) {
            Log.i(TAG, "Searching Geoapify: $lat, $lng radius=$currentRadius (Attempt ${attempt + 1})")

            val url = "${BASE_URL}?categories=${categories.joinToString(",")}&filter=circle:$lng,$lat,$currentRadius&limit=${config.maxResults * 2}&apiKey=${config.geoapifyApiKey}"

            try {
                val rawPlaces = executeGeoapifyQuery(url)
                if (rawPlaces.isNotEmpty()) {
                    val processedPlaces = processor.processPlaces(rawPlaces, lat, lng)
                    if (processedPlaces.isNotEmpty()) {
                        return@withContext processedPlaces
                    }
                }

                currentRadius = (currentRadius * 1.5).toInt().coerceAtMost(config.maxRadiusM)
                if (attempt < config.maxRadiusRetries - 1) delay((config.radiusRetryDelay * 1000).toLong())

            } catch (e: Exception) {
                Log.e(TAG, "Error fetching from Geoapify", e)
                break // Stop on error
            }
        }

        return@withContext emptyList()
    }

    private suspend fun executeGeoapifyQuery(url: String): List<Map<String, Any>> {
        val request = Request.Builder().url(url).build()
        val response = okHttpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            Log.e(TAG, "Geoapify request failed: ${response.code}")
            return emptyList()
        }

        val body = response.body?.string() ?: return emptyList()
        val jsonResponse = json.decodeFromString<JsonObject>(body)
        val features = jsonResponse["features"]?.jsonArray ?: return emptyList()

        return features.mapNotNull { feature -> parseGeoapifyFeature(feature.jsonObject) }
    }

    private fun parseGeoapifyFeature(feature: JsonObject): Map<String, Any>? {
        try {
            val properties = feature["properties"]?.jsonObject ?: return null
            val geometry = feature["geometry"]?.jsonObject ?: return null
            val coordinates = geometry["coordinates"]?.jsonArray ?: return null

            val lng = coordinates[0].jsonPrimitive.double
            val lat = coordinates[1].jsonPrimitive.double
            
            val tags = mutableMapOf<String, String>()
            properties["name"]?.jsonPrimitive?.content?.let { tags["name"] = it }
            properties["website"]?.jsonPrimitive?.content?.let { tags["website"] = it }
            
            val rawTags = properties["datasource"]?.jsonObject?.get("raw")?.jsonObject
            if (rawTags != null) {
                for ((key, value) in rawTags.entries) {
                    tags[key] = value.jsonPrimitive.content
                }
            }

            return mutableMapOf(
                "type" to "node",
                "id" to (properties["place_id"]?.jsonPrimitive?.content?.hashCode()?.toLong() ?: 0L),
                "lat" to lat,
                "lon" to lng,
                "tags" to HashMap(tags)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Geoapify feature", e)
            return null
        }
    }

    private fun buildCategoriesList(): List<String> {
        val categories = mutableSetOf<String>()
        categories.add("entertainment")
        categories.add("tourism.attraction")
        categories.add("tourism.sights")
        categories.add("leisure.park")
        categories.add("populated_place")

        return categories.toList()
    }
}
