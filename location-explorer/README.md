# Location Explorer Library

Android library for discovering places of interest and generating location summaries using OpenStreetMap (Overpass API) and Google Gemini AI.

## Features

- **Place Discovery**: Search for nearby places using Overpass API
- **Content Generation**: Generate natural language summaries using Google Gemini AI
- **Text-to-Speech**: Android TTS integration for audio narration
- **Smart Filtering**: Filter and rank places by importance and relevance
- **Location History**: Avoid returning the same places in subsequent queries

## Setup

### 1. Add Dependency

In your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":location-explorer"))
}
```

### 2. Initialize Configuration

```kotlin
import com.tourguide.locationexplorer.config.LocationExplorerConfig

// Initialize with your Gemini API key
LocationExplorerConfig.initialize("YOUR_GEMINI_API_KEY")
```

### 3. Use Services

```kotlin
import com.tourguide.locationexplorer.services.OverpassService
import com.tourguide.locationexplorer.services.GeminiService
import com.tourguide.locationexplorer.services.AndroidTtsService

// Initialize services
val overpassService = OverpassService()
val geminiService = GeminiService()
val ttsService = AndroidTtsService()

// Initialize TTS
ttsService.initialize(context) { success ->
    if (success) {
        // TTS ready
    }
}

// Search for places
lifecycleScope.launch {
    val places = overpassService.searchPlacesByCoordinates(
        lat = 48.2082,
        lng = 16.3738,
        radiusM = 1000,
        speedKmh = 50f
    )
    
    // Generate summary for a place
    val placeMap = mapOf(
        "id" to places[0].osmId,
        "type" to places[0].osmType,
        "lat" to places[0].lat,
        "lon" to places[0].lng,
        "tags" to places[0].tags,
        "distance_m" to places[0].distanceM,
        "importance" to places[0].importance,
        "place_rank" to places[0].rank
    )
    
    val summary = geminiService.generateOsmSummary(placeMap, maxSentences = 7)
    
    // Speak the summary
    ttsService.speak(summary ?: "No summary available")
}
```

## Configuration

All configuration is available through `LocationExplorerConfig`:

- `geminiApiKey`: Google Gemini API key (required)
- `geminiModel`: Model name (default: "gemini-2.5-flash")
- `defaultQueryRadiusM`: Default search radius (default: 800m)
- `importanceThreshold`: Minimum importance score (default: 0.5)
- `maxResults`: Maximum places to return (default: 10)

## API Reference

### OverpassService

- `searchPlacesByCoordinates(lat, lng, radiusM, speedKmh)`: Search for places near coordinates
- `searchPlacesByName(locationName, radiusM)`: Search for places near a named location

### GeminiService

- `generateOsmSummary(place, maxSentences)`: Generate summary for a place
- `reverseGeocode(lat, lng)`: Get address/location name from coordinates

### TtsService

- `initialize(context, callback)`: Initialize TTS service
- `speak(text, language)`: Speak text using TTS
- `generateAudio(text, language)`: Generate audio bytes (may return null on Android)

## Requirements

- minSdk: 24 (Android 7.0)
- targetSdk: 33 (Android 13)
- Kotlin Coroutines
- Google Gemini API key

## License

See LICENSE file for details.


