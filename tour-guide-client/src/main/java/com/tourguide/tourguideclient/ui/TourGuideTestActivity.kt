package com.tourguide.tourguideclient.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.tourguide.tourguideclient.services.TourGuideService

class TourGuideTestActivity : AppCompatActivity() { // Must inherit from AppCompatActivity for Material components

    private lateinit var controller: TourGuideController
    private lateinit var logTextView: TextView
    private lateinit var contentTextView: TextView
    private lateinit var toggleSwitch: SwitchMaterial

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        const val ACTION_DEBUG_LOG = "com.tourguide.debug.LOG"
    }

    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_DEBUG_LOG) {
                val tag = intent.getStringExtra("tag") ?: "Unknown"
                val message = intent.getStringExtra("message") ?: ""
                runOnUiThread {
                    appendLog("[$tag] $message")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = TourGuideController(this)

        // --- UI SETUP ---
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setPadding(32, 32, 32, 32)
        }

        val buttonsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        toggleSwitch = SwitchMaterial(this).apply {
            text = ""
        }
        
        val setLocationButton = Button(this).apply {
            text = "Set Loc"
            textSize = 12f
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(30, 15, 30, 15)
            setOnClickListener { showLocationDialog() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = 32
            }
        }
        buttonsLayout.addView(toggleSwitch)
        buttonsLayout.addView(setLocationButton)
        rootLayout.addView(buttonsLayout)

        // --- CONTENT TEXT VIEW ---
        val contentScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f) // Takes up half the space
            setBackgroundColor(Color.BLACK)
        }
        contentTextView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(16, 16, 16, 16)
            typeface = Typeface.MONOSPACE
        }
        contentScrollView.addView(contentTextView)
        rootLayout.addView(contentScrollView)

        // --- DIVIDER ---
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2).apply {
                topMargin = 8
                bottomMargin = 8
            }
            setBackgroundColor(Color.GRAY)
        }
        rootLayout.addView(divider)

        // --- LOG TEXT VIEW ---
        val logScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f) // Takes up the other half
            setBackgroundColor(Color.BLACK)
        }
        logTextView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.GREEN)
            setPadding(16, 16, 16, 16)
            typeface = Typeface.MONOSPACE
        }
        logScrollView.addView(logTextView)
        rootLayout.addView(logScrollView)

        setContentView(rootLayout)
        
        val filter = IntentFilter(ACTION_DEBUG_LOG)
        ContextCompat.registerReceiver(this, debugReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        
        appendLog("Activity created. Waiting for service to start...")
    }
    
    private fun showLocationDialog() {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }
        val latInput = EditText(context).apply {
            hint = "Latitude"
            setText("48.2082") // Default: Vienna
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val lngInput = EditText(context).apply {
            hint = "Longitude"
            setText("16.3738") // Default: Vienna
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        layout.addView(TextView(context).apply { text = "Latitude:" })
        layout.addView(latInput)
        layout.addView(TextView(context).apply { text = "Longitude:" })
        layout.addView(lngInput)

        AlertDialog.Builder(context)
            .setTitle("Set Location")
            .setView(layout)
            .setPositiveButton("Set") { _, _ ->
                try {
                    val lat = latInput.text.toString().toDouble()
                    val lng = lngInput.text.toString().toDouble()
                    controller.simulateLocation(lat, lng)
                    appendLog("-> Simulation request sent for: $lat, $lng")
                } catch (e: NumberFormatException) {
                    Toast.makeText(context, "Invalid coordinates", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        
        // Check service state and update switch
        val isRunning = isServiceRunning(TourGuideService::class.java)
        toggleSwitch.isChecked = isRunning
        
        // Set listener AFTER updating state to avoid triggering it
        toggleSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startServiceWithPermissions()
            } else {
                controller.stopService()
                appendLog("Service stop requested")
            }
        }

        controller.register { contentList ->
            runOnUiThread {
                contentTextView.text = ""
                // Removed .reversed() to fix ordering issue
                contentList.forEach { item ->
                    appendContent("• $item")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(debugReceiver)
        } catch (e: Exception) {
            // Ignore
        }
        controller.stopService()
    }

    override fun onStop() {
        super.onStop()
        controller.unregister()
        // Remove listener to avoid memory leaks or unwanted triggers if view is kept
        toggleSwitch.setOnCheckedChangeListener(null)
    }
    
    @Suppress("DEPRECATION")
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun startServiceWithPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            controller.startService()
            appendLog("Service start requested")
            // No need to set text on switch
        } else {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                controller.startService()
                appendLog("Permissions granted. Service starting...")
                toggleSwitch.isChecked = true // Ensure switch reflects state
            } else {
                Toast.makeText(this, "Permissions required to run Tour Guide", Toast.LENGTH_LONG).show()
                appendLog("Permissions denied.")
                toggleSwitch.isChecked = false
            }
        }
    }

    private fun appendLog(text: String) {
        logTextView.append("$text\n")
    }
    
    private fun appendContent(text: String) {
        contentTextView.append("$text\n\n")
    }
}
