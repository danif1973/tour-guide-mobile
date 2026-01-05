package com.tourguide.locationexplorer.services

import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.GenerateContentResponse
import com.google.ai.client.generativeai.type.SerializationException
import com.google.ai.client.generativeai.type.ServerException
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.tourguide.locationexplorer.config.LocationExplorerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
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
            systemInstruction = content { text(config.osmPlaceSystemPrompt) },
            generationConfig = generationConfig {
                temperature = config.geminiTemperature
                maxOutputTokens = config.geminiMaxTokens
            }
        )
        
        Log.d(TAG, "Initialized Gemini service with model: ${config.geminiModel}")
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
        language: String,
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
        promptParts.addAll(config.osmPlaceUserPromptParts.map { part ->
            part.replace("{place_json}", placeJson)
                .replace("{max_sentences}", maxSentences.toString())
        })

        // Add the language instruction if it's not the default English
        if (language.lowercase() != "en") {
            try {
                val languageName = Locale.forLanguageTag(language).displayLanguage
                if (languageName.isNotBlank()) {
                    promptParts.add("Please provide the response in $languageName.")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not determine display name for language code: $language")
            }
        }
        
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
        maxSentences: Int = config.defaultMaxSentences
    ): String? = withContext(Dispatchers.IO) {
        val tags = (place["tags"] as? Map<String, String>) ?: emptyMap()
        val placeName = tags["name"] ?: "Unknown"
        val language = config.defaultLanguage // Use language from config
        
        Log.d(TAG, "Generating OSM summary for place: $placeName in language: $language")
        
        // Refine OSM data
        val refinedOsm = refineOsmData(place)
        
        // Create prompt with language instruction
        val promptText = createOsmSummaryPrompt(refinedOsm, language, maxSentences)
        Log.d(TAG, "Prompt text: $promptText")
        
        var response: GenerateContentResponse? = null
        
        try {
            response = model.generateContent(promptText)
            val text = response.text
            
            if (text.isNullOrEmpty()) {
                val finishReason = response.candidates.firstOrNull()?.finishReason?.name ?: "UNKNOWN"
                Log.w(TAG, "Gemini returned empty response for $placeName. Reason: $finishReason")
                return@withContext null
            }
            
            val content = fineTuneResponse(text.trim())
            val matchedPhrase = detectLowInformationPhrase(content)
            
            if (matchedPhrase != null) {
                Log.i(TAG, "low_information_summary_detected place_name=$placeName matched_phrase=$matchedPhrase")
                return@withContext null
            }
            
            Log.i(TAG, "Generated OSM summary successfully for $placeName: ${content.take(100)}...")
            return@withContext content
            
        } catch (e: SerializationException) {
            val finishReason = response?.candidates?.firstOrNull()?.finishReason?.name ?: "NO_CANDIDATES"
            val promptFeedback = response?.promptFeedback?.toString() ?: "NO_PROMPT_FEEDBACK"
            Log.e(TAG, "Gemini summary failed for $placeName due to SerializationException. Finish reason: $finishReason. Prompt Feedback: $promptFeedback", e)
            throw(e)
        } catch (e: ServerException) {
            Log.e(TAG, "Gemini summary failed for $placeName due to a server error (e.g. API key issue).", e)
            throw(e)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini summary failed for $placeName with a generic error.")
            throw(e)
        }
    }
}
