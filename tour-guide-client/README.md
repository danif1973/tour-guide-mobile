# Tour Guide Client Library

Android library that provides location-based tour guide content generation. Integrates with Android Location Services to automatically generate informative summaries about nearby places of interest.

## Features

- **Automatic Content Generation**: Generates content when you move a configurable distance threshold
- **Destination Summaries**: Generates detailed summaries for navigation destinations
- **Dynamic Content Length**: Adjusts summary length based on number of places and importance
- **Location-Aware**: Uses Android Location Services for real-time location tracking
- **Audio Support**: Optional TTS integration for audio narration

## Setup

### 1. Add Dependencies

In your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":location-explorer"))
    implementation(project(":tour-guide-client"))
    
    // Android Location Services
    implementation("com.google.android.gms:play-services-location:21.0.1")
}
```

### 2. Request Permissions

In your `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
```

### 3. Initialize Services

```kotlin
import com.tourguide.locationexplorer.config.LocationExplorerConfig
import com.tourguide.locationexplorer.services.*
import com.tourguide.tourguideclient.TourGuideClient

// Initialize Location Explorer config
LocationExplorerConfig.initialize("YOUR_GEMINI_API_KEY")

// Create services
val overpassService = OverpassService()
val geminiService = GeminiService()
val ttsService = AndroidTtsService()

// Initialize TTS
ttsService.initialize(context) { success ->
    if (success) {
        // TTS ready
    }
}

// Create client
val tourGuideClient = TourGuideClient(
    overpassService = overpassService,
    geminiService = geminiService,
    ttsService = ttsService
)

// Start client
tourGuideClient.start()
```

### 4. Integrate with Location Services

```kotlin
import com.google.android.gms.location.*

class MainActivity : AppCompatActivity() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var tourGuideClient: TourGuideClient
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        // ... initialize tourGuideClient ...
        
        requestLocationUpdates()
    }
    
    private fun requestLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
            .setMinUpdateIntervalMillis(2000)
            .build()
        
        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    // Get speed and heading if available
                    val speed = location.speed * 3.6f // Convert m/s to km/h
                    val heading = location.bearing
                    
                    // Handle location update
                    lifecycleScope.launch {
                        tourGuideClient.handleLocationUpdate(
                            location = location,
                            speedKmh = speed,
                            headingDegrees = heading
                        )
                        
                        // Check for new content
                        val content = tourGuideClient.getContent()
                        if (content.status == 1) {
                            // Display content
                            displayContent(content.content)
                            
                            // Play audio if available
                            content.audio.forEach { audioBase64 ->
                                // Decode and play audio
                            }
                        }
                    }
                }
            }
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        tourGuideClient.stop()
    }
}
```

### 5. Set Destination (Optional)

```kotlin
// When navigation starts
val destination = Location("").apply {
    latitude = 48.2082
    longitude = 16.3738
}

tourGuideClient.setDestination(destination, "Vienna State Opera")
```

## Configuration

Configure via `TourGuideClientConfig`:

- `contentGenerationDistanceThresholdM`: Distance threshold for content generation (default: 500m)
- `contentGenerationEstimatedTimeS`: Estimated time for content generation (default: 20s)
- `speedReferenceBaseline`: Reference speed for calculations (default: 50.0 km/h)
- `destinationMaxSentences`: Max sentences for destination summaries (default: 10)

## API Reference

### TourGuideClient

- `start()`: Start the client
- `stop()`: Stop the client
- `handleLocationUpdate(location, speedKmh, headingDegrees)`: Handle location update from Android Location Services
- `setDestination(location, name)`: Set destination for navigation
- `getContent()`: Get current content (returns `ContentResponse`)

### ContentResponse

- `status`: 0 (no content) or 1 (new content available)
- `content`: List of summary strings
- `audio`: List of base64-encoded audio strings (optional)

## How It Works

1. **Location Updates**: Client receives location updates from Android Location Services
2. **Distance Check**: When you move beyond the distance threshold, content generation is triggered
3. **Place Discovery**: Overpass API searches for nearby places of interest
4. **Content Generation**: Gemini AI generates informative summaries for each place
5. **Audio Generation**: Optional TTS converts summaries to audio
6. **Content Delivery**: Content is available via `getContent()` method

## Requirements

- minSdk: 24 (Android 7.0)
- targetSdk: 33 (Android 13)
- Google Play Services Location
- Kotlin Coroutines
- Google Gemini API key

## License

See LICENSE file for details.


