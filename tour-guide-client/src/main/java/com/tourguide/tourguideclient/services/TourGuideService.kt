package com.tourguide.tourguideclient.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.tourguide.locationexplorer.config.LocationExplorerConfig
import com.tourguide.locationexplorer.services.AndroidTtsService
import com.tourguide.locationexplorer.services.GeminiService
import com.tourguide.locationexplorer.services.OverpassService
import com.tourguide.locationexplorer.services.TtsService
import com.tourguide.tourguideclient.TourGuideClient
import com.tourguide.tourguideclient.config.TourGuideClientConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Tour Guide Service - Foreground Service that drives the TourGuideClient.
 * Handles location updates, triggers content generation, plays audio, and broadcasts text updates.
 */
class TourGuideService : Service() {

    companion object {
        private const val TAG = "TourGuideService"
        private const val NOTIFICATION_CHANNEL_ID = "tour_guide_channel"
        private const val NOTIFICATION_ID = 12345
        
        // Broadcast Action
        const val ACTION_TOUR_GUIDE_CONTENT = "com.tourguide.tourguideclient.ACTION_CONTENT"
        const val EXTRA_CONTENT_TEXT = "extra_content_text"
    }

    private lateinit var tourGuideClient: TourGuideClient
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var ttsService: TtsService
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private var lastSpeedKmh: Float = 0f

    // Location Callback
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            for (location in locationResult.locations) {
                handleLocation(location)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Creating TourGuideService")

        // 1. Initialize Services
        val overpassService = OverpassService()
        val geminiService = GeminiService()
        
        ttsService = AndroidTtsService()
        ttsService.initialize(this) { success ->
            if (success) {
                Log.i(TAG, "TTS Service initialized successfully")
            } else {
                Log.e(TAG, "TTS Service initialization failed")
            }
        }

        tourGuideClient = TourGuideClient(
            overpassService = overpassService,
            geminiService = geminiService,
            ttsService = ttsService
        )

        // 2. Initialize Location Client
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // 3. Create Notification Channel
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Starting TourGuideService")

        // 1. Start Foreground
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        // 2. Start TourGuideClient
        tourGuideClient.start()

        // 3. Start Location Updates
        startLocationUpdates()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Destroying TourGuideService")
        
        // Cleanup
        stopLocationUpdates()
        tourGuideClient.stop()
        ttsService.shutdown()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // Not a bound service
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Location permissions missing, stopping service")
            stopSelf()
            return
        }

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            TourGuideClientConfig.locationPollingIntervalMs
        ).build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
        Log.i(TAG, "Location updates started")
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun handleLocation(location: Location) {
        serviceScope.launch {
            // Calculate Speed (if not available from GPS)
            val speed = if (location.hasSpeed()) {
                location.speed * 3.6f // Convert m/s to km/h
            } else {
                lastSpeedKmh // Fallback or calculated if we stored prev location
            }
            lastSpeedKmh = speed

            // Feed Client
            tourGuideClient.handleLocationUpdate(
                location = location,
                speedKmh = speed,
                headingDegrees = if (location.hasBearing()) location.bearing else null
            )

            // Check for new content
            val response = tourGuideClient.getContent()
            
            if (response.status == 1) { // Success
                Log.i(TAG, "New content generated. Audio items: ${response.audio.size}")
                
                // 1. Play Audio (Service responsibility)
                if (response.audio.isNotEmpty()) {
                     // Note: The TourGuideClient currently returns base64 strings.
                     // AndroidTtsService.speak() takes text.
                     //
                     // Issue: The Client generates audio via TtsService internally 
                     // and returns the base64. But here we want to PLAY it.
                     //
                     // Refined Approach:
                     // Since the Client ALREADY called ttsService.generateAudio(),
                     // we might have raw bytes if we changed TtsService. 
                     // But currently AndroidTtsService.generateAudio returns null/logs warning.
                     //
                     // Correction:
                     // Android TTS works by "speak()". It doesn't easily give bytes.
                     // TourGuideClient logic calls `ttsService?.generateAudio(summary)`.
                     // Inside `AndroidTtsService`, `generateAudio` is currently a stub returning null!
                     //
                     // FIX: We should simply use the TEXT content here to trigger `speak()`.
                     // We don't need to decode the base64 audio from the response because
                     // `AndroidTtsService` can just speak the text directly.
                     
                     response.content.forEach { textSummary ->
                         ttsService.speak(textSummary)
                     }
                }

                // 2. Broadcast Text (UI responsibility)
                if (response.content.isNotEmpty()) {
                    val broadcastIntent = Intent(ACTION_TOUR_GUIDE_CONTENT)
                    broadcastIntent.putStringArrayListExtra(EXTRA_CONTENT_TEXT, ArrayList(response.content))
                    // Using standard broadcast (LocalBroadcastManager is deprecated)
                    // Package restriction ensures security
                    broadcastIntent.setPackage(packageName)
                    sendBroadcast(broadcastIntent)
                }
            }
        }
    }

    private fun createNotificationChannel() {
        val name = "Tour Guide Service"
        val descriptionText = "Active tour guide location tracking"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        val notificationManager: NotificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        // PendingIntent for notification tap (optional, can launch main activity)
        // val pendingIntent: PendingIntent = ...

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Tour Guide Active")
            .setContentText("Monitoring location for tour highlights...")
            .setSmallIcon(android.R.drawable.ic_dialog_map) // Placeholder icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
