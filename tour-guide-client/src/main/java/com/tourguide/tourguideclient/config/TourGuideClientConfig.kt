package com.tourguide.tourguideclient.config

/**
 * Configuration settings for the Tour Guide Client.
 * Ported from tour_guide_client/config.py
 */
object TourGuideClientConfig {
    // Content generation settings
    // meters - distance threshold for triggering new content generation
    var contentGenerationDistanceThresholdM: Int = 500
    
    // seconds - estimated time for content generation (used for future position prediction)
    var contentGenerationEstimatedTimeS: Float = 20.0f
    
    // Speed reference settings
    // Reference speed for speed-based calculations (baseline multiplier = 1.0)
    var speedReferenceBaseline: Float = 50.0f
    
    // Destination summary settings
    // Maximum sentences for destination summaries
    var destinationMaxSentences: Int = 10
    
    // Service settings
    // Milliseconds - interval for requesting location updates
    var locationPollingIntervalMs: Long = 5000
    
    // Client identification
    const val CLIENT_NAME = "tour-guide-client"
    const val CLIENT_VERSION = "1.0.0"
}
