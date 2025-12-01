package com.tourguide.tourguideclient.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * A simple test activity to demonstrate the TourGuideController and Service.
 * It creates a programmatic UI to avoid dependency on XML resources in this library module.
 */
class TourGuideTestActivity : Activity() {

    private lateinit var controller: TourGuideController
    private lateinit var logTextView: TextView
    private lateinit var statusTextView: TextView

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Controller
        controller = TourGuideController(this)

        // Create UI Programmatically
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(32, 32, 32, 32)
        }

        // Status Text
        statusTextView = TextView(this).apply {
            text = "Status: Stopped"
            textSize = 18f
            setPadding(0, 0, 0, 24)
        }
        rootLayout.addView(statusTextView)

        // Buttons Layout
        val buttonsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val startButton = Button(this).apply {
            text = "Start Service"
            setOnClickListener { startServiceWithPermissions() }
        }

        val stopButton = Button(this).apply {
            text = "Stop Service"
            setOnClickListener { 
                controller.stopService() 
                statusTextView.text = "Status: Stopped"
                appendLog("Service stop requested")
            }
        }

        buttonsLayout.addView(startButton)
        buttonsLayout.addView(stopButton)
        rootLayout.addView(buttonsLayout)

        // Log ScrollView
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(0, 24, 0, 0)
        }

        logTextView = TextView(this).apply {
            text = "Waiting for content...\n"
            textSize = 14f
        }
        scrollView.addView(logTextView)
        rootLayout.addView(scrollView)

        setContentView(rootLayout)
    }

    override fun onStart() {
        super.onStart()
        // Register for updates
        controller.register { contentList ->
            runOnUiThread {
                statusTextView.text = "Status: Active (Content Received)"
                contentList.forEach { item ->
                    appendLog("• $item")
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Unregister updates (but Service keeps running if started)
        controller.unregister()
    }

    private fun startServiceWithPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            controller.startService()
            statusTextView.text = "Status: Running"
            appendLog("Service start requested")
        } else {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                controller.startService()
                statusTextView.text = "Status: Running"
                appendLog("Permissions granted. Service starting...")
            } else {
                Toast.makeText(this, "Permissions required to run Tour Guide", Toast.LENGTH_LONG).show()
                appendLog("Permissions denied.")
            }
        }
    }

    private fun appendLog(text: String) {
        val currentText = logTextView.text.toString()
        logTextView.text = "$text\n\n$currentText"
    }
}
