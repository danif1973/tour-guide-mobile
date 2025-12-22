package com.tourguide.tourguideclient.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.tourguide.locationexplorer.config.LocationExplorerConfig
import com.tourguide.locationexplorer.services.AndroidTtsService
import com.tourguide.locationexplorer.services.GeminiService
import com.tourguide.locationexplorer.services.GeoapifyService
import com.tourguide.locationexplorer.services.OverpassService
import com.tourguide.locationexplorer.services.PlacesService
import com.tourguide.locationexplorer.services.TtsService
import com.tourguide.tourguideclient.TourGuideClient
import com.tourguide.tourguideclient.config.TourGuideClientConfig
import com.tourguide.tourguideclient.utils.broadcastDebug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

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
        
        // Manual Location Action
        const val ACTION_SIMULATE_LOCATION = "com.tourguide.tourguideclient.SIMULATE_LOCATION"
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LNG = "extra_lng"
    }

    private lateinit var tourGuideClient: TourGuideClient
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var ttsService: TtsService
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private var lastSpeedKmh: Float = 0f
    private val isProcessingSimulation = AtomicBoolean(false)
    
    // Manual location receiver
    private val simulationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SIMULATE_LOCATION) {
                val lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
                val lng = intent.getDoubleExtra(EXTRA_LNG, 0.0)
                
                if (lat != 0.0 && lng != 0.0) {
                    Log.i(TAG, "Received manual location simulation: $lat, $lng")
                    broadcastDebug("TourGuideService", "Simulating location: $lat, $lng")
                    
                    val location = Location("manual_simulation").apply {
                        latitude = lat
                        longitude = lng
                        time = System.currentTimeMillis()
                        accuracy = 10.0f
                        // Simulate movement speed if needed, or leave 0
                        speed = 50.0f / 3.6f // Simulate ~50km/h for testing
                        bearing = 0.0f
                    }
                    isProcessingSimulation.set(true)
                    handleLocation(location, isSimulation = true)
                }
            }
        }
    }

    // Location Callback
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            if (isProcessingSimulation.get()) {
                Log.d(TAG, "Skipping GPS update due to active simulation")
                return
            }
            for (location in locationResult.locations) {
                handleLocation(location)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        broadcastDebug("TourGuideService", "Creating TourGuideService")
        Log.i(TAG, "Creating TourGuideService")

        // --- Initialize Services ---
        val geminiService = GeminiService()
        ttsService = AndroidTtsService()

        // Select PlacesService implementation based on config
        val placesService: PlacesService = if (LocationExplorerConfig.useGeoapify) {
            Log.i(TAG, "Using GeoapifyService for places.")
            GeoapifyService()
        } else {
            Log.i(TAG, "Using OverpassService for places.")
            OverpassService()
        }
        
        ttsService.initialize(this) { success ->
            if (success) {
                Log.i(TAG, "TTS Service initialized successfully")
                broadcastDebug("TourGuideService", "TTS Service initialized")
            } else {
                Log.e(TAG, "TTS Service initialization failed")
                broadcastDebug("TourGuideService", "TTS Service initialization failed")
            }
        }

        tourGuideClient = TourGuideClient(
            context = this,
            placesService = placesService,
            geminiService = geminiService,
            ttsService = ttsService
        )

        // 2. Initialize Location Client
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // 3. Create Notification Channel
        createNotificationChannel()
        
        // 4. Register Simulation Receiver
        val filter = IntentFilter(ACTION_SIMULATE_LOCATION)
        ContextCompat.registerReceiver(this, simulationReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Starting TourGuideService")
        broadcastDebug("TourGuideService", "Starting TourGuideService")

        // 1. Start Foreground
        val notification = createNotification()
        
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            broadcastDebug("TourGuideService", "Failed to start foreground: ${e.message}")
        }

        startLocationUpdates()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Destroying TourGuideService")
        broadcastDebug("TourGuideService", "Destroying TourGuideService")
        
        // Cleanup
        try {
            unregisterReceiver(simulationReceiver)
        } catch (e: Exception) {
            // Ignore
        }
        stopLocationUpdates()
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
            broadcastDebug("TourGuideService", "Location permissions missing")
            stopSelf()
            return
        }

        // Immediately request the last known location for a quick start
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                Log.i(TAG, "Got last known location: ${location.latitude}, ${location.longitude}")
                broadcastDebug("TourGuideService", "Got last known location: ${location.latitude}, ${location.longitude}")
                handleLocation(location)
            }
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
        Log.i(TAG, "Periodic location updates started")
        broadcastDebug("TourGuideService", "Periodic location updates started")
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun handleLocation(location: Location, isSimulation: Boolean = false) {
        serviceScope.launch {
            try {
                val speed = if (location.hasSpeed()) {
                    location.speed * 3.6f // Convert m/s to km/h
                } else {
                    lastSpeedKmh
                }
                lastSpeedKmh = speed
                tourGuideClient.handleLocationUpdate(
                    location = location,
                    speedKmh = speed,
                    headingDegrees = if (location.hasBearing()) location.bearing else null
                )

                val response = tourGuideClient.getContent()

                if (response.status == 1) { // Success
                    Log.i(TAG, "New content generated. Content items: ${response.content.size}")
                    broadcastDebug("TourGuideService", "New content generated. Content items: ${response.content.size}")

                    // Play audio for the new content in the correct order
                    response.content.forEachIndexed { index, text ->
                        ttsService.speak(
                            text,
                            if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                        )
                    }

                    if (response.content.isNotEmpty()) {
                        val broadcastIntent = Intent(ACTION_TOUR_GUIDE_CONTENT)
                        broadcastIntent.putStringArrayListExtra(EXTRA_CONTENT_TEXT, ArrayList(response.content))
                        broadcastIntent.setPackage(packageName)
                        sendBroadcast(broadcastIntent)
                    }
                }
            } finally {
                if (isSimulation) {
                    // It's critical to reset the client's state BEFORE allowing GPS updates again.
                    tourGuideClient.resetLocationState()
                    isProcessingSimulation.set(false)
                    Log.d(TAG, "Simulation processing complete, resuming GPS")
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Tour Guide Active")
            .setContentText("Monitoring location for tour highlights...")
            .setSmallIcon(android.R.drawable.ic_dialog_map) // Placeholder icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
