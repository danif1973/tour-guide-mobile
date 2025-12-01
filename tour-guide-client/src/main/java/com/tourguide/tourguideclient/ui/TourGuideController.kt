package com.tourguide.tourguideclient.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.tourguide.tourguideclient.services.TourGuideService

/**
 * Controller for managing the TourGuideService and listening for content updates.
 * Acts as a bridge between the UI (Activity/Fragment) and the background service.
 */
class TourGuideController(private val context: Context) {

    companion object {
        private const val TAG = "TourGuideController"
    }

    // Callback for when new text content is received from the service
    private var onContentReceived: ((List<String>) -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TourGuideService.ACTION_TOUR_GUIDE_CONTENT) {
                val content = intent.getStringArrayListExtra(TourGuideService.EXTRA_CONTENT_TEXT)
                if (content != null) {
                    Log.d(TAG, "Received content update: ${content.size} items")
                    onContentReceived?.invoke(content)
                }
            }
        }
    }

    private var isReceiverRegistered = false

    /**
     * Start the TourGuideService in foreground mode.
     */
    fun startService() {
        val intent = Intent(context, TourGuideService::class.java)
        ContextCompat.startForegroundService(context, intent)
        Log.i(TAG, "Service start request sent")
    }

    /**
     * Stop the TourGuideService.
     */
    fun stopService() {
        val intent = Intent(context, TourGuideService::class.java)
        context.stopService(intent)
        Log.i(TAG, "Service stop request sent")
    }

    /**
     * Register for content updates.
     * @param callback Function to be called when new text content is available.
     */
    fun register(callback: (List<String>) -> Unit) {
        this.onContentReceived = callback
        
        if (!isReceiverRegistered) {
            val filter = IntentFilter(TourGuideService.ACTION_TOUR_GUIDE_CONTENT)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            
            isReceiverRegistered = true
            Log.d(TAG, "BroadcastReceiver registered")
        }
    }

    /**
     * Unregister the content listener. 
     * Should be called in onStop() or onDestroy() of the UI component.
     */
    fun unregister() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(receiver)
                isReceiverRegistered = false
                Log.d(TAG, "BroadcastReceiver unregistered")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Receiver already unregistered", e)
            }
        }
        this.onContentReceived = null
    }
}
