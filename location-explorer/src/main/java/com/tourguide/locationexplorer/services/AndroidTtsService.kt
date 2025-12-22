package com.tourguide.locationexplorer.services

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class AndroidTtsService : TtsService, TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingCallback: ((Boolean) -> Unit)? = null

    companion object {
        private const val TAG = "AndroidTtsService"
    }

    override fun initialize(context: Context, callback: (Boolean) -> Unit) {
        try {
            pendingCallback = callback
            tts = TextToSpeech(context, this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TextToSpeech", e)
            callback(false)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "The specified language is not supported!")
                isInitialized = false
            } else {
                // Set audio attributes for navigation to enable ducking
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts?.setAudioAttributes(audioAttributes)
                isInitialized = true
                Log.i(TAG, "TTS Initialized with Navigation Guidance attributes")
            }
        } else {
            Log.e(TAG, "TTS Initialization failed!")
            isInitialized = false
        }
        pendingCallback?.invoke(isInitialized)
        pendingCallback = null
    }

    override fun speak(text: String, language: String) {
        speak(text, TextToSpeech.QUEUE_ADD)
    }
    
    override fun speak(text: String, queueMode: Int) {
        Log.i(TAG, "speak request: ${text.take(30)}... (Queue: $queueMode)")
        
        if (!isInitialized || tts == null) {
            Log.w(TAG, "TTS not initialized, skipping speak request.")
            return
        }

        val params = Bundle()
        // Explicitly set volume to max
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        
        // Use STREAM_MUSIC as fallback. AudioAttributes (set in onInit) should still 
        // dictate the USAGE (Navigation), but this helps ensure routing to main speakers 
        // on some Android Auto implementations where Navigation stream might be muted.
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)

        val result = tts?.speak(text, queueMode, params, null)
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "Error queuing text to speech")
        }
    }

    override suspend fun generateAudio(text: String, language: String): ByteArray? {
        // Android TTS doesn't support generating raw audio bytes easily, 
        // so we return null. The caller should handle playback via speak().
        return null
    }

    override fun stop() {
        if (isInitialized && tts != null) {
            tts?.stop()
        }
    }

    override fun shutdown() {
        if (isInitialized && tts != null) {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isInitialized = false
        }
    }
}
