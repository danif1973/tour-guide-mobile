package com.tourguide.locationexplorer.services

import android.util.Log
import com.tourguide.locationexplorer.config.LocationExplorerConfig
import com.tourguide.locationexplorer.models.PlaceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Service for searching nearby places using Geoapify Places API.
 * Implements PlacesService to be interchangeable with OverpassService.
 */
class GeoapifyService(
    private val config: LocationExplorerConfig = LocationExplorerConfig
) : PlacesService {

    companion object {
        private const val TAG = "GeoapifyService"
        private const val BASE_URL = "https://api.geoapify.com/v2/places"
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
        if (!config.useGeoapify) {
            Log.w(TAG, "GeoapifyService called but useGeoapify is false in config.")
            return@withContext emptyList()
        }

        val apiKey = config.geoapifyApiKey
        if (apiKey.isNullOrBlank()) {
            Log.e(TAG, "Geoapify API key is missing.")
            return@withContext emptyList()
        }

        // Map config types to Geoapify categories
        val categories = buildCategoriesList()
        if (categories.isEmpty()) {
            Log.w(TAG, "No categories configured for search.")
            return@withContext emptyList()
        }

        val categoriesStr = categories.joinToString(",")
        
        // Build URL
        val url = "$BASE_URL?categories=$categoriesStr&filter=circle:$lng,$lat,$radiusM&limit=${config.maxResults * 2}&apiKey=$apiKey"
        
        Log.i(TAG, "Searching Geoapify: $lat, $lng radius=$radiusM")

        try {
            val request = Request.Builder().url(url).build()
            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "Geoapify request failed: ${response.code}")
                return@withContext emptyList()
            }

            val body = response.body?.string() ?: return@withContext emptyList()
            val jsonResponse = json.decodeFromString<JsonObject>(body)
            val features = jsonResponse["features"]?.jsonArray ?: return@withContext emptyList()

            Log.i(TAG, "Geoapify returned ${features.size} features")

            val places = features.mapNotNull { feature ->
                parseGeoapifyFeature(feature as JsonObject)
            }

            // Reuse scoring logic concept (simplified/adapted)
            val scoredPlaces = places.map { place ->
                place.copy(importance = calculatePromiseScore(place))
            }
            
            // Sort by importance
            return@withContext scoredPlaces.sortedByDescending { it.importance }

        } catch (e: Exception) {
            Log.e(TAG, "Error fetching from Geoapify", e)
            return@withContext emptyList()
        }
    }

    private fun buildCategoriesList(): List<String> {
        // Mapping Overpass/Config types to Geoapify categories
        val categories = mutableListOf<String>()
        
        // Tourism
        if (config.tourismTypes.contains("attraction")) categories.add("tourism.attraction")
        if (config.tourismTypes.contains("museum")) categories.add("entertainment.museum")
        if (config.tourismTypes.contains("monument") || config.tourismTypes.contains("memorial")) categories.add("tourism.sights")
        
        // Amenity
        if (config.amenityTypes.contains("restaurant")) categories.add("catering.restaurant")
        if (config.amenityTypes.contains("cafe")) categories.add("catering.cafe")
        if (config.amenityTypes.contains("pub") || config.amenityTypes.contains("bar")) categories.add("catering.bar")
        
        // Nature/Leisure
        if (config.leisureTypes.contains("park")) categories.add("leisure.park")
        
        // Fallback/Defaults if detailed config is missing but general types are present
        if (categories.isEmpty()) {
            categories.add("tourism")
            categories.add("entertainment.culture")
            categories.add("catering")
        }
        
        return categories
    }

    private fun parseGeoapifyFeature(feature: JsonObject): PlaceInfo? {
        try {
            val properties = feature["properties"]?.jsonObject ?: return null
            val geometry = feature["geometry"]?.jsonObject
            val coordinates = geometry?.get("coordinates")?.jsonArray

            val lng = coordinates?.get(0)?.jsonPrimitive?.double ?: 0.0
            val lat = coordinates?.get(1)?.jsonPrimitive?.double ?: 0.0
            
            // Extract fields
            val name = properties["name"]?.jsonPrimitive?.content 
                ?: properties["name:en"]?.jsonPrimitive?.content 
                ?: "Unnamed Place"
                
            val categories = properties["categories"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            
            // Construct a "tags" map to mimic OSM tags for compatibility
            val tags = mutableMapOf<String, String>()
            tags["name"] = name
            
            // Map categories back to pseudo-OSM tags for the client consumption
            if (categories.any { it.startsWith("tourism") }) tags["tourism"] = "attraction"
            if (categories.any { it.contains("museum") }) tags["tourism"] = "museum"
            if (categories.any { it.contains("restaurant") }) tags["amenity"] = "restaurant"
            
            // Other metadata
            val address = properties["formatted"]?.jsonPrimitive?.content ?: ""
            if (address.isNotEmpty()) tags["address"] = address
            
            val placeId = properties["place_id"]?.jsonPrimitive?.content ?: ""
            
            // Attempt to extract numeric OSM ID if present in datasource
            val datasource = properties["datasource"]?.jsonObject
            val osmId = datasource?.get("raw")?.jsonObject?.get("osm_id")?.jsonPrimitive?.content?.toLongOrNull() 
                ?: placeId.hashCode().toLong() // Fallback ID

            return PlaceInfo(
                name = name,
                lat = lat,
                lng = lng,
                placeType = if (categories.isNotEmpty()) categories.first().split(".").first() else "unknown",
                category = if (categories.isNotEmpty()) categories.first() else "unknown",
                importance = 0f, // Calculated later
                rank = 30,
                distanceM = properties["distance"]?.jsonPrimitive?.content?.toFloatOrNull(),
                tags = tags,
                osmId = osmId,
                osmType = "node" // Simplified
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse feature", e)
            return null
        }
    }
    
    /**
     * Reusing the logic from OverpassService (adapted).
     */
    private fun calculatePromiseScore(place: PlaceInfo): Float {
        var score = 0.0f
        val tags = place.tags
        
        // Tourism
        if (tags["tourism"] == "attraction" || tags["tourism"] == "museum") score += 0.25f
        
        // Basic name check
        if (place.name != "Unnamed Place") score += 0.1f
        
        return min(score, 1.0f)
    }
}
