package com.tourguide.locationexplorer.config

import android.util.Log
import com.tourguide.locationexplorer.BuildConfig

/**
 * Configuration settings for Location Explorer.
 * Ported from location_explorer/config.py
 */
object LocationExplorerConfig {
    private const val TAG = "LocationExplorerConfig"

    // App Behavior
    var blockPhoneUiWhenConnectedToCar: Boolean = false
    
    // Gemini API Configuration
    var geminiApiKey: String? = null
        set(value) {
            field = value
            Log.i(TAG, "GEMINI_API_KEY: ${if (value != null) "SET" else "NOT SET"}")
        }
    
    var geminiModel: String = "gemini-2.0-flash"
        set(value) {
            field = value
            Log.i(TAG, "GEMINI_MODEL: $value")
        }
    
    var geminiTemperature: Float = 0.7f
        set(value) {
            field = value
            Log.i(TAG, "GEMINI_TEMPERATURE: $value")
        }
    
    var geminiMaxTokens: Int = 200
        set(value) {
            field = value
            Log.i(TAG, "GEMINI_MAX_TOKENS: $value")
        }
    
    // API Configuration
    var defaultLanguage: String = "he"
//    var defaultLanguage: String = "en"
//    var defaultLanguage: String = "de"
    
    // Maximum sentences for OSM place summaries
    var defaultMaxSentences: Int = 7

    // Text-to-Speech (TTS) Configuration
    var ttsPitch: Float = 1.0f
    var ttsRate: Float = 1.1f

    // Prompt for summarizing a single place from OSM data
    val osmPlaceSystemPrompt: String = """
        You are a knowledgeable tour guide assistant.
        Write in a direct, informative style. Start directly with factual information.
        Do not use exclamations, conversational phrases like 'Ah' or 'Oh', or rhetorical questions.
        Vary your sentence structure and phrasing to avoid repetitive patterns.
        Mention the approximate distance (rounding to the nearest multiple of a power of ten), but vary how and where you present it. Over 999m translate to km.
        If relative_direction is provided in the place data, naturally incorporate it into your description using phrases like 'You can see this to your right', 'This is located ahead', 'This is behind you', or 'This is to your left'.
        Do not mention technical data like OSM ID, OSM type, etc.
    """.trimIndent()
    
    val osmPlaceUserPromptParts: List<String> = listOf(
        "Please provide me information interesting a traveller about the following place:",
        "{place_json}",
        "Limit your response to {max_sentences} sentences."
    )
    
    // Keywords that indicate lack of information (negation/lack indicators)
    val geminiLowInfoNegationKeywords: List<String> = listOf(
        "unavailable", "not available", "no information", "no details", "cannot", "unable"
    )
    
    // Keywords that indicate information/details context
    val geminiLowInfoContextKeywords: List<String> = listOf(
        "details", "information", "specific", "further", "characteristics"
    )
    
    // Keywords that indicate generic/vague content (lack of specific information)
    val geminiLowInfoGenericKeywords: List<String> = listOf(
        "may offer", "may reveal", "may provide", "could offer", "could reveal", "could provide",
        "further research", "consider", "hidden gems", "local favorites", "local customs", "local traditions"
    )
    
    // Keywords that indicate speculative/uncertainty language (guessing, not facts)
    val geminiLowInfoSpeculativeKeywords: List<String> = listOf(
        "likely", "could be", "might be", "suggests", "possibly", "perhaps", "probably",
        "appears to be", "seems to be"
    )
    
    // --- Places Service Configuration ---
    var useGeoapify: Boolean = true
    var geoapifyApiKey: String? = null

    // Overpass API Configuration
    var overpassApiUrl: String = "https://overpass-api.de/api/interpreter"
    var overpassTimeout: Int = 25
    
    // Places Filtering Configuration
    // Rank threshold: OSM place_rank (1-30, lower number = more specific/important place)
    var placeRankThreshold: Int = 30
    
    // Importance threshold: Nominatim importance score (0.0-1.0, higher = more important)
    var importanceThreshold: Float = 0.29f
    
    // Maximum number of places to return (after filtering)
    var maxResults: Int = 10
    
    // Default search radius in meters for searching places near a location
    var defaultQueryRadiusM: Int = 500
    
    // Maximum search radius in meters (for progressive expansion when no results found)
    var maxRadiusM: Int = 5000
    
    // Maximum number of total attempts when no results found (progressive radius expansion)
    var maxRadiusRetries: Int = 3
    
    // Delay in seconds between retry attempts when expanding radius
    var radiusRetryDelay: Float = 1.0f

    // Place Types to Search
    val tourismTypes: List<String> = listOf(
        "attraction", "museum", "monument", "memorial", "viewpoint", "gallery", "theme_park", "zoo", "park"
    )
    
    val amenityTypes: List<String> = listOf(
        "restaurant", "cafe", "bar", "pub", "cinema", "theatre", "place_of_worship"
    )
    
    val leisureTypes: List<String> = listOf(
        "park", "garden", "beach_resort", "water_park", "nature_reserve", "stadium"
    )
    
    val historicTypes: List<String> = listOf(
        "castle", "ruins", "archaeological_site", "fort", "manor"
    )
    
    val placeTypes: List<String> = listOf(
        "city", "state", "town", "village", "suburb", "square"
    )

    // Place History Configuration
    // TTL for place history in seconds (to avoid returning same places in subsequent queries)
    var placeHistoryTtl: Int = 86400 // 24 hours default
    var enablePlaceHistory: Boolean = true
    
    init {
        // Try to load API keys from BuildConfig if available
        try {
            val geminiKey = BuildConfig.GEMINI_API_KEY.toString()
            if (geminiKey.isNotBlank()) {
                geminiApiKey = geminiKey
            }
            val geoapifyKey = BuildConfig.GEOAPIFY_API_KEY.toString()
            if (geoapifyKey.isNotBlank()) {
                geoapifyApiKey = geoapifyKey
            }
        } catch (e: Throwable) {
            // BuildConfig might not be generated yet or not accessible
            Log.w(TAG, "Could not load API keys from BuildConfig")
        }
    }
    
    /**
     * Initialize configuration with API key.
     * This should be called before using any services.
     */
    fun initialize(apiKey: String) {
        geminiApiKey = apiKey
        validate()
    }
    
    /**
     * Validate configuration.
     * Throws IllegalArgumentException if required settings are missing.
     */
    fun validate() {
        if (geminiApiKey.isNullOrBlank()) {
            throw IllegalArgumentException(
                "GEMINI_API_KEY is required. " +
                "Get your free API key at: https://aistudio.google.com/app/apikey"
            )
        }
        Log.i(TAG, "Configuration validation passed")
    }
}
