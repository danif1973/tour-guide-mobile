package com.tourguide.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.tourguide.tourguideclient.services.TourGuideService

class MainScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private var lastMessage: String = "Waiting for interesting places..."
    private val messageHistory = mutableListOf<String>()

    private val contentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TourGuideService.ACTION_TOUR_GUIDE_CONTENT) {
                val texts = intent.getStringArrayListExtra(TourGuideService.EXTRA_CONTENT_TEXT)
                if (!texts.isNullOrEmpty()) {
                    // Update UI with the latest interesting fact
                    val latest = texts.first() // Take the first one for the summary
                    lastMessage = latest
                    messageHistory.add(0, latest)
                    if (messageHistory.size > 5) messageHistory.removeAt(messageHistory.lastIndex)
                    
                    invalidate() // Trigger redraw
                }
            }
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        val filter = IntentFilter(TourGuideService.ACTION_TOUR_GUIDE_CONTENT)
        ContextCompat.registerReceiver(carContext, contentReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        invalidate() // Refresh UI on start
    }

    override fun onStop(owner: LifecycleOwner) {
        try {
            carContext.unregisterReceiver(contentReceiver)
        } catch (e: Exception) {
            // Ignore if not registered
        }
    }

    override fun onGetTemplate(): Template {
        val paneBuilder = Pane.Builder()

        val isServiceRunning = TourGuideService.isRunning

        // Status Row
        paneBuilder.addRow(
            Row.Builder()
                .setTitle("Status")
                .addText(if (isServiceRunning) "Service Running" else "Service Stopped")
                .build()
        )

        // Content Row(s)
        if (messageHistory.isEmpty()) {
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle("Latest Update")
                    .addText(lastMessage)
                    .build()
            )
        } else {
            messageHistory.forEachIndexed { index, msg ->
                paneBuilder.addRow(
                    Row.Builder()
                        .setTitle(if (index == 0) "Latest" else "History")
                        .addText(msg.take(100) + if (msg.length > 100) "..." else "")
                        .build()
                )
            }
        }

        // Action Button (Toggle Service)
        val serviceAction = if (isServiceRunning) {
            Action.Builder()
                .setTitle("Stop Tour")
                .setBackgroundColor(CarColor.RED)
                .setOnClickListener {
                    stopService()
                }
                .build()
        } else {
            Action.Builder()
                .setTitle("Start Tour")
                .setBackgroundColor(CarColor.GREEN)
                .setOnClickListener {
                    checkPermissionsAndStartService()
                }
                .build()
        }
        
        // Add action to the Pane
        paneBuilder.addAction(serviceAction)

        val header = androidx.car.app.model.Header.Builder()
            .setStartHeaderAction(Action.APP_ICON)
            .setTitle("Tour Guide")
            .build()

        return PaneTemplate.Builder(paneBuilder.build())
            .setHeader(header)
            .build()
    }

    private fun checkPermissionsAndStartService() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            carContext.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startService()
        } else {
            CarToast.makeText(carContext, "Requesting permissions on phone...", CarToast.LENGTH_LONG).show()
            carContext.requestPermissions(missingPermissions) { granted, rejected ->
                if (granted.contains(Manifest.permission.ACCESS_FINE_LOCATION) || 
                    granted.contains(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    startService()
                } else {
                    CarToast.makeText(carContext, "Location permission required!", CarToast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startService() {
        val intent = Intent(carContext, TourGuideService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            carContext.startForegroundService(intent)
        } else {
            carContext.startService(intent)
        }
        invalidate()
    }

    private fun stopService() {
        val intent = Intent(carContext, TourGuideService::class.java)
        carContext.stopService(intent)
        invalidate()
    }
}
