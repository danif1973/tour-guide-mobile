package com.tourguide.tourguideclient.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer
import com.tourguide.locationexplorer.config.LocationExplorerConfig

/**
 * A trampoline activity that decides whether to launch the phone UI or exit.
 * This is the new main entry point for the application.
 */
class LauncherActivity : Activity() {

    private var isDecisionMade = false
    private lateinit var carConnection: CarConnection
    private val connectionObserver = Observer<Int> { connectionState ->
        // This observer can be called multiple times. We only want to act once.
        if (isDecisionMade) {
            return@Observer
        }

        // Check both the connection state and the configuration flag
        if (LocationExplorerConfig.blockPhoneUiWhenConnectedToCar &&
            connectionState == CarConnection.CONNECTION_TYPE_PROJECTION) {
            // Car is connected and blocking is enabled. Do not launch phone UI.
            isDecisionMade = true
            finish()
        } else {
            // Car is not connected, or blocking is disabled. Launch the phone UI.
            isDecisionMade = true
            val intent = Intent(this, TourGuideActivity::class.java)
            startActivity(intent)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        carConnection = CarConnection(this)
        // usage of observeForever allows us to use standard Activity (fixing theme crash)
        // while still observing the LiveData updates (fixing race condition).
        carConnection.type.observeForever(connectionObserver)
    }

    override fun onDestroy() {
        super.onDestroy()
        // clean up to prevent memory leaks
        if (::carConnection.isInitialized) {
            carConnection.type.removeObserver(connectionObserver)
        }
    }
}
