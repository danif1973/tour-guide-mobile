package com.tourguide.locationexplorer.services

import android.content.Context
import android.media.AudioAttributes
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
                // Set audio attributes for navigation
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts?.setAudioAttributes(audioAttributes)
                isInitialized = true
            }
        } else {
            Log.e(TAG, "TTS Initialization failed!")
            isInitialized = false
        }
        pendingCallback?.invoke(isInitialized)
        pendingCallback = null
    }

    override fun speak(text: String, language: String) {
        if (!isInitialized || tts == null) {
            Log.w(TAG, "TTS not initialized, skipping speak request.")
            return
        }

        val params = Bundle()
        // No extra params needed for now, but this is where you'd add them if required

        tts?.speak(text, TextToSpeech.QUEUE_ADD, params, null)
    }
    
    fun speak(text: String, queueMode: Int) {
        if (!isInitialized || tts == null) {
            Log.w(TAG, "TTS not initialized, skipping speak request.")
            return
        }
        val params = Bundle()
        tts?.speak(text, queueMode, params, null)
    }

    override suspend fun generateAudio(text: String, language: String): ByteArray? {
        Log.w(TAG, "generateAudio is not supported by AndroidTtsService and will do nothing.")
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
