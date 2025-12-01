# Tour Guide Android Libraries

Kotlin Android library modules for location-based tour guide content generation. Ported from Python implementation.

## Modules

### location-explorer

Core library for place discovery and content generation:
- Overpass API integration for place search
- Google Gemini AI for content generation
- Android TTS for audio narration
- Smart filtering and ranking

### tour-guide-client

High-level client library that integrates with Android Location Services:
- Automatic content generation based on location updates
- Distance threshold management
- Destination summaries
- Dynamic content length adjustment

## Quick Start

### 1. Clone/Import Project

Open the project in Android Studio.

### 2. Configure API Key

Set your Gemini API key in your app's code:

```kotlin
LocationExplorerConfig.initialize("YOUR_GEMINI_API_KEY")
```

Or use BuildConfig:

```kotlin
// In app/build.gradle.kts
android {
    buildTypes {
        debug {
            buildConfigField("String", "GEMINI_API_KEY", "\"${project.findProperty("GEMINI_API_KEY") ?: ""}\"")
        }
    }
}

// In local.properties
GEMINI_API_KEY=your_api_key_here
```

### 3. Add to Your App

In your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":location-explorer"))
    implementation(project(":tour-guide-client"))
}
```

### 4. Use in Your App

See individual module README files for detailed usage:
- [location-explorer/README.md](location-explorer/README.md)
- [tour-guide-client/README.md](tour-guide-client/README.md)

## Project Structure

```
TourGuide/
├── build.gradle.kts              # Root build file
├── settings.gradle.kts           # Module settings
├── gradle.properties             # Gradle configuration
├── location-explorer/            # Library module 1
│   ├── build.gradle.kts
│   └── src/main/java/com/tourguide/locationexplorer/
│       ├── config/
│       ├── models/
│       └── services/
└── tour-guide-client/            # Library module 2
    ├── build.gradle.kts
    └── src/main/java/com/tourguide/tourguideclient/
        ├── config/
        ├── models/
        └── TourGuideClient.kt
```

## Requirements

- Android Studio Hedgehog or later
- minSdk: 24 (Android 7.0)
- targetSdk: 33 (Android 13)
- compileSdk: 34 (Android 14)
- Kotlin: 1.9.20+
- Java: 17

## Dependencies

### location-explorer
- Retrofit 2.9.0
- OkHttp 4.12.0
- Kotlinx Coroutines 1.7.3
- Kotlinx Serialization 1.6.0
- Google Generative AI SDK 0.2.2

### tour-guide-client
- location-explorer (project dependency)
- Kotlinx Coroutines 1.7.3
- Google Play Services Location 21.0.1

## Getting a Gemini API Key

1. Visit https://aistudio.google.com/app/apikey
2. Sign in with your Google account
3. Click "Create API key"
4. Copy the key (starts with `AIza...`)
5. No credit card required!

## Porting Notes

This project is a port from Python to Kotlin. Key changes:

- **Async/Await**: Python `async def` → Kotlin `suspend fun`
- **HTTP Client**: `requests` → Retrofit + OkHttp
- **TTS**: `edge-tts`/`gtts` → Android `TextToSpeech`
- **Location**: REST API polling → Android Location Services
- **Configuration**: `.env` files → `LocationExplorerConfig` object
- **Data Models**: Pydantic → Kotlinx Serialization

## License

See LICENSE file for details.


