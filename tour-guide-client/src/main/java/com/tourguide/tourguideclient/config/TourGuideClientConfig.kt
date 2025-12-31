package com.tourguide.tourguideclient.config

import android.util.Log

/**
 * Configuration settings for the Tour Guide Client.
 * Ported from tour_guide_client/config.py
 */
object TourGuideClientConfig {
    private const val TAG = "TourGuideClientConfig"

    // How often to poll for location updates (in milliseconds)
    var locationPollingIntervalMs: Long = 5000L
        set(value) {
            field = value
            Log.i(TAG, "locationPollingIntervalMs: $value")
        }

    // Minimum distance in meters to trigger content generation
    var contentGenerationDistanceThresholdM: Int = 500
        set(value) {
            field = value
            Log.i(TAG, "contentGenerationDistanceThresholdM: $value")
        }
    var contentGenerationDistanceMinThresholdM: Int = 100
        set(value) {
            field = value
            Log.i(TAG, "contentGenerationDistanceMinThresholdM: $value")
        }

    // Estimated time in seconds to look ahead for content generation based on speed
    var contentGenerationEstimatedTimeS: Float = 20.0f
        set(value) {
            field = value
            Log.i(TAG, "contentGenerationEstimatedTimeS: $value")
        }

    // Speed reference baseline for calculating dynamic distance threshold
    var speedReferenceBaseline: Float = 50.0f // km/h
        set(value) {
            field = value
            Log.i(TAG, "speedReferenceBaseline: $value")
        }
        
    // Maximum sentences for the destination summary
    var destinationMaxSentences: Int = 10
        set(value) {
            field = value
            Log.i(TAG, "destinationMaxSentences: $value")
        }

    // When true, skips calling the Gemini API and returns placeholder content.
    var skipGeminiContentGeneration: Boolean = false
        set(value) {
            field = value
            Log.i(TAG, "skipGeminiContentGeneration: $value")
        }
}
