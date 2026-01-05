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
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.LongMessageTemplate
import androidx.car.app.model.Template
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.tourguide.tourguideclient.services.TourGuideService

class MainScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private val messageHistory = mutableListOf<String>()

    private val contentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TourGuideService.ACTION_TOUR_GUIDE_CONTENT) {
                val texts = intent.getStringArrayListExtra(TourGuideService.EXTRA_CONTENT_TEXT)
                if (!texts.isNullOrEmpty()) {
                    // Add new messages to the top of the history
                    messageHistory.addAll(0, texts)
                    while (messageHistory.size > 50) { // Keep a long history for scrolling
                        messageHistory.removeAt(messageHistory.lastIndex)
                    }
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
        invalidate()
    }

    override fun onStop(owner: LifecycleOwner) {
        try {
            carContext.unregisterReceiver(contentReceiver)
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun onGetTemplate(): Template {
        val isServiceRunning = TourGuideService.isRunning

        val message = if (messageHistory.isEmpty()) {
            if (isServiceRunning) "Looking for interesting locations..." else "Click 'Start' to start the Tour Guide."
        } else {
            // Join all history into a single block of text for the LongMessageTemplate
            messageHistory.joinToString("\n\n")
        }

        // Create the Start/Stop action
        val serviceAction = if (isServiceRunning) {
            Action.Builder()
                .setTitle("Stop")
                .setOnClickListener { stopService() }
                .build()
        } else {
            Action.Builder()
                .setTitle("Start")
                .setOnClickListener { checkPermissionsAndStartService() }
                .build()
        }

        // Place the action in an ActionStrip in the header, which is allowed while driving.
        val actionStrip = ActionStrip.Builder()
            .addAction(serviceAction)
            .build()

        return LongMessageTemplate.Builder(message)
            .setTitle("Tour Guide")
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(actionStrip)
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
            carContext.requestPermissions(missingPermissions) { granted, _ ->
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
