package com.tourguide.locationexplorer.services

import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.GenerateContentResponse
import com.google.ai.client.generativeai.type.generationConfig
import com.tourguide.locationexplorer.config.LocationExplorerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.text.Regex

/**
 * Service for generating location summaries using Google Gemini API.
 * Ported from location_explorer/services/gemini_service.py
 */
class GeminiService(
    private val apiKey: String? = LocationExplorerConfig.geminiApiKey,
    private val config: LocationExplorerConfig = LocationExplorerConfig
) {
    companion object {
        private const val TAG = "GeminiService"
    }
    
    private val model: GenerativeModel
    private val json = Json { ignoreUnknownKeys = true }
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    init {
        if (apiKey.isNullOrBlank()) {
            throw IllegalArgumentException("GEMINI_API_KEY is required")
        }
        
        model = GenerativeModel(
            modelName = config.geminiModel,
            apiKey = apiKey,
            generationConfig = generationConfig {
                temperature = config.geminiTemperature
                maxOutputTokens = config.geminiMaxTokens
            }
        )
        
        Log.d(TAG, "Initialized Gemini service with model: ${config.geminiModel}")
    }
    
    /**
     * Reverse geocode coordinates to get address/location name.
     */
    suspend fun reverseGeocode(lat: Double, lng: Double): String = withContext(Dispatchers.IO) {
        try {
            val url = "https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lng&format=json&accept-language=en&addressdetails=1"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Location-Explorer/1.0")
                .addHeader("Accept", "application/json")
                .build()
            
            delay(100) // Rate limiting
            
            val response = okHttpClient.newCall(request).execute()
            
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext "Location at $lat, $lng (geocoding failed)"
                val data = json.decodeFromString<Map<String, Any>>(body)
                
                val locationName = data["display_name"] as? String
                if (locationName != null) {
                    Log.d(TAG, "Reverse geocoded to: $locationName")
                    return@withContext locationName
                } else {
                    Log.w(TAG, "No display_name found in reverse geocoding response")
                    return@withContext "Unknown location at $lat, $lng"
                }
            } else {
                Log.e(TAG, "Reverse geocoding request failed with status ${response.code}")
                return@withContext "Location at $lat, $lng (geocoding failed)"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Reverse geocoding error: ${e.message}", e)
            return@withContext "Location at $lat, $lng (geocoding error)"
        }
    }
    
    /**
     * Refine OSM place data to minimal identification fields.
     */
    private fun refineOsmData(place: Map<String, Any>): Map<String, Any> {
        val tags = (place["tags"] as? Map<String, String>) ?: emptyMap()
        
        val refined = mutableMapOf<String, Any>(
            "name" to (tags["name:en"] ?: tags["name"] ?: "Unnamed"),
            "osm_id" to (place["id"] ?: ""),
            "osm_type" to (place["type"] ?: "")
        )
        
        if (place.containsKey("distance_m")) {
            val distance = (place["distance_m"] as? Number)?.toDouble() ?: 0.0
            refined["distance_m"] = distance.toInt()
        }
        
        if (place.containsKey("relative_direction")) {
            refined["relative_direction"] = place["relative_direction"]!!
        }
        
        return refined
    }
    
    /**
     * Create structured prompt for summarizing a single OSM place.
     */
    private fun createOsmSummaryPrompt(
        refinedOsm: Map<String, Any>,
        maxSentences: Int = config.defaultMaxSentences
    ): String {
        // Build JSON string manually for simplicity
        val jsonParts = refinedOsm.map { (key, value) ->
            val jsonValue = when (value) {
                is String -> "\"$value\""
                is Number -> value.toString()
                is Boolean -> value.toString()
                else -> "\"${value.toString()}\""
            }
            "\"$key\": $jsonValue"
        }
        val placeJson = "{${jsonParts.joinToString(", ")}}"
        
        val promptParts = mutableListOf<String>()
        promptParts.add(config.osmPlaceSystemPrompt)
        promptParts.addAll(config.osmPlaceUserPromptParts.map { part ->
            part.replace("{place_json}", placeJson)
                .replace("{max_sentences}", maxSentences.toString())
        })
        
        return promptParts.joinToString("\n")
    }
    
    /**
     * Detect if content indicates insufficient information.
     */
    private fun detectLowInformationPhrase(content: String): String? {
        if (content.isEmpty()) return null
        
        val normalized = content.lowercase()
        
        // Check 1: Explicit negation + context keywords
        val matchedNegation = config.geminiLowInfoNegationKeywords.filter { 
            it.lowercase() in normalized 
        }
        val matchedContext = config.geminiLowInfoContextKeywords.filter { 
            it.lowercase() in normalized 
        }
        
        if (matchedNegation.isNotEmpty() && matchedContext.isNotEmpty()) {
            return "explicit_negation: ${matchedNegation[0]} + ${matchedContext[0]}"
        }
        
        // Check 2-4: Combined scoring approach
        val matchedGeneric = config.geminiLowInfoGenericKeywords.filter { 
            it.lowercase() in normalized 
        }
        val matchedSpeculative = config.geminiLowInfoSpeculativeKeywords.filter { 
            it.lowercase() in normalized 
        }
        
        val vaguePatterns = listOf(
            Regex("could be (a|an|some)"),
            Regex("might be (a|an|some)"),
            Regex("some other"),
            Regex("notable feature"),
            Regex("point of interest"),
            Regex("warrants further"),
            Regex("further investigation")
        )
        
        val matchedPatterns = vaguePatterns.filter { it.containsMatchIn(normalized) }
        
        var score = 0
        val indicators = mutableListOf<String>()
        
        if (matchedGeneric.isNotEmpty()) {
            score += matchedGeneric.size
            indicators.add("${matchedGeneric.size} generic")
        }
        
        if (matchedSpeculative.isNotEmpty()) {
            score += matchedSpeculative.size
            indicators.add("${matchedSpeculative.size} speculative")
        }
        
        if (matchedPatterns.isNotEmpty()) {
            score += matchedPatterns.size
            indicators.add("${matchedPatterns.size} vague patterns")
        }
        
        if (score >= 3) {
            return "low_information_content: score=$score (${indicators.joinToString(", ")})"
        }
        
        return null
    }
    
    /**
     * Apply small textual adjustments to improve narration clarity.
     */
    private fun fineTuneResponse(content: String): String {
        if (content.isEmpty()) return content
        
        // Replace compact distance units with readable words
        return content
            .replace(Regex("""(\d+)\s?m\b"""), "$1 meters")
            .replace(Regex("""(\d+)\s?km\b"""), "$1 kilometers")
    }
    
    /**
     * Generate a detailed summary for a single OSM place using Gemini API.
     */
    suspend fun generateOsmSummary(
        place: Map<String, Any>,
        language: String = "en",
        maxSentences: Int = config.defaultMaxSentences
    ): String? = withContext(Dispatchers.IO) {
        val tags = (place["tags"] as? Map<String, String>) ?: emptyMap()
        val placeName = tags["name"] ?: "Unknown"
        Log.d(TAG, "Generating OSM summary for place: $placeName")
        
        // Refine OSM data
        val refinedOsm = refineOsmData(place)
        
        // Create prompt
        val promptText = createOsmSummaryPrompt(refinedOsm, maxSentences)
        
        // Retry logic for 429 errors
        val maxRetries = 2
        var retryDelay = 5000L
        
        var response: GenerateContentResponse? = null
        
        for (attempt in 0..maxRetries) {
            try {
                response = model.generateContent(promptText)
                break // Success
            } catch (e: Exception) {
                val errorStr = e.message ?: ""
                if ("429" in errorStr || "Resource exhausted" in errorStr) {
                    if (attempt < maxRetries) {
                        Log.w(TAG, "Received 429 rate limit error (attempt ${attempt + 1}/${maxRetries + 1}). Waiting ${retryDelay}ms before retry...")
                        delay(retryDelay)
                        retryDelay += 2000
                        continue
                    } else {
                        Log.e(TAG, "Gemini API rate limit error after ${maxRetries + 1} attempts: $errorStr")
                        val name = tags["name:en"] ?: tags["name"] ?: "This place"
                        return@withContext "$name is a point of interest in the area."
                    }
                } else {
                    throw e
                }
            }
        }
        
        if (response == null) {
            Log.e(TAG, "Failed to generate content after retries")
            val name = tags["name:en"] ?: tags["name"] ?: "This place"
            return@withContext "$name is a point of interest in the area."
        }
        
        try {
            val text = response.text
            if (text.isNullOrEmpty()) {
                Log.e(TAG, "Gemini returned empty response")
                val name = tags["name:en"] ?: tags["name"] ?: "This place"
                return@withContext "$name is a point of interest in the area."
            }
            
            val content = fineTuneResponse(text.trim())
            val matchedPhrase = detectLowInformationPhrase(content)
            
            if (matchedPhrase != null) {
                Log.i(TAG, "low_information_summary_detected place_name=$placeName matched_phrase=$matchedPhrase")
                return@withContext null
            }
            
            Log.i(TAG, "Generated OSM summary successfully for $placeName: ${content.take(100)}...")
            return@withContext content
        } catch (e: Exception) {
            Log.e(TAG, "Gemini API error processing response: ${e.message}", e)
            val name = tags["name:en"] ?: tags["name"] ?: "This place"
            return@withContext "$name is a point of interest in the area."
        }
    }
}
