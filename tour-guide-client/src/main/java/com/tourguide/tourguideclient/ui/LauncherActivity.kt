package com.tourguide.tourguideclient.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.car.app.connection.CarConnection

/**
 * A trampoline activity that decides whether to launch the phone UI or exit.
 * This is the new main entry point for the application.
 */
class LauncherActivity : AppCompatActivity() {

    private var isDecisionMade = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Observe the connection state to make a reliable decision.
        CarConnection(this).type.observe(this) { connectionState ->
            // This observer can be called multiple times. We only want to act once.
            if (isDecisionMade) {
                return@observe
            }

            if (connectionState == CarConnection.CONNECTION_TYPE_PROJECTION) {
                // Car is connected. Do not launch phone UI.
                isDecisionMade = true
                finish()
            } else {
                // Car is not connected. Launch the phone UI.
                isDecisionMade = true
                val intent = Intent(this, TourGuideTestActivity::class.java)
                startActivity(intent)
                finish()
            }
        }
    }
}
