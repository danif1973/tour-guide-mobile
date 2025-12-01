# Plan: TourGuideService Implementation

## Objective
- Convert the current passive `TourGuideClient` architecture into an active Android Background Service named `TourGuideService`. 
- The Service will act as a "Foreground Service" to ensure continuous operation while driving/walking.
- It will listen for active location updates and feed them into `TourGuideClient` to trigger content generation.

## Phase 1: Dependencies & Manifest

### 1.1 Add Location Dependencies
**File:** `tour-guide-client/build.gradle.kts`
Ensure `com.google.android.gms:play-services-location` is present (Already verified).

### 1.2 Update Android Manifest
**File:** `tour-guide-client/src/main/AndroidManifest.xml`
Declare the service and add necessary permissions.

**Permissions:**
```xml
<!-- Location -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<!-- Service -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<!-- Android 13+ Notifications -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

**Service Declaration:**
```xml
<service
    android:name=".services.TourGuideService"
    android:enabled="true"
    android:exported="false"
    android:foregroundServiceType="location" />
```

---

## Phase 2: Configuration Updates

**File:** `src/main/java/com/tourguide/tourguideclient/config/TourGuideClientConfig.kt`

Add configuration for the service behavior:
- `locationPollingIntervalMs`: Long = 5000 (Default 5 seconds)
- `minDistanceUpdateM`: Float = 0f (We want time-based mainly, but this can be tuned)

---

## Phase 3: Create TourGuideService

**File:** `src/main/java/com/tourguide/tourguideclient/services/TourGuideService.kt`

This class will act as the "Driver" for the `TourGuideClient`.

### 3.1 Class Structure
*   **Inherit:** `android.app.Service`
*   **Properties:**
    *   `tourGuideClient`: Instance of `TourGuideClient`.
    *   `fusedLocationClient`: Instance of `FusedLocationProviderClient`.
    *   `serviceScope`: A `CoroutineScope` (SupervisorJob + Dispatchers.IO).
    *   `locationCallback`: `LocationCallback` implementation.

### 3.2 Lifecycle Methods

**`onCreate()`**:
1.  **Initialize Dependencies**:
    *   Initialize `OverpassService` and `GeminiService` (using `LocationExplorerConfig`).
    *   Initialize `TtsService` (passing `this` context).
    *   Initialize `TourGuideClient` with these services.
2.  **Notification Channel**: Create a notification channel for the Foreground Service.
3.  **Location Client**: Initialize `FusedLocationProviderClient`.

**`onStartCommand(intent, flags, startId)`**:
1.  **Start Foreground**: Call `startForeground()` with a persistent notification.
2.  **Start Location Updates**:
    *   Build `LocationRequest` using `TourGuideClientConfig.locationPollingIntervalMs`.
    *   Call `requestLocationUpdates`.
3.  **Handle Actions**: (Optional) Support Intent actions like "STOP_SERVICE" or "SET_DESTINATION".

**`onDestroy()`**:
1.  **Cleanup**: Remove location updates (`removeLocationUpdates`).
2.  **Cancel Scope**: `serviceScope.cancel()`.
3.  **Stop Client**: Call `tourGuideClient.stop()`.

### 3.3 Location Handling
*   In `locationCallback.onLocationResult`:
    *   Extract the latest `Location`.
    *   Calculate `speed` (if not provided by GPS, calculate from prev location).
    *   Launch a coroutine: `tourGuideClient.handleLocationUpdate(location, speed)`.
    *   **Check for Content**: After update, call `tourGuideClient.getContent()`.
    *   **Output Handling**:
        *   **Audio**: If `content.audio` is present, the Service (via `TtsService` or `MediaPlayer`) should **play it immediately**. This ensures narration continues even if the app is backgrounded or the screen is off.
        *   **Text/UI**: Broadcast the text content (via `LocalBroadcastManager` or Intent) so the UI can display it if active.

### 3.4 Output Mechanism (Playback & Broadcast)
*   **Audio Playback**: The Service is responsible for queueing and playing the audio narration.
*   **Broadcast**: Define a constant `ACTION_TOUR_GUIDE_CONTENT`. Send an Intent containing the *text* content to update the UI.

---

## Phase 4: Logging & Error Handling
*   Ensure `Log.i` calls for service start/stop.
*   Handle permission missing errors gracefully (stop self if permission revoked).
