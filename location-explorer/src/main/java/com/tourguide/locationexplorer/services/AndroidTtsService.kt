package com.tourguide.locationexplorer.services

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class AndroidTtsService : TtsService, TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    @Volatile private var isInitialized = false
    private var pendingCallback: ((Boolean) -> Unit)? = null
    
    // Audio Focus Management
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    companion object {
        private const val TAG = "AndroidTtsService"
        private const val UTTERANCE_ID = "tour_guide_utterance"
    }

    override fun initialize(context: Context, callback: (Boolean) -> Unit) {
        if (isInitialized && tts != null) {
            callback(true)
            return
        }

        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        try {
            pendingCallback = callback
            // Use applicationContext to avoid leaking Activity contexts
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TextToSpeech", e)
            callback(false)
            pendingCallback = null
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Default to US
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "The specified language is not supported!")
            }

            // Set audio attributes
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            
            tts?.setAudioAttributes(audioAttributes)
            
            // Set speech rate to normal
            tts?.setSpeechRate(1.1f)
            
            // Set listener to manage focus release
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    // Focus already requested before speak
                }

                override fun onDone(utteranceId: String?) {
                    abandonFocus()
                }

                override fun onError(utteranceId: String?) {
                    abandonFocus()
                }
            })
            
            isInitialized = true
            Log.i(TAG, "TTS Initialized with NAVIGATION_GUIDANCE")
        } else {
            Log.e(TAG, "TTS Initialization failed!")
            isInitialized = false
        }
        pendingCallback?.invoke(isInitialized)
        pendingCallback = null
    }

    override fun speak(text: String, language: String) {
        if (isInitialized && tts != null && language != "en") {
            try {
                val locale = Locale.forLanguageTag(language)
                tts?.setLanguage(locale)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set language: $language", e)
            }
        }
        Log.w(TAG, "Starting text narration in $language")
        speak(text, TextToSpeech.QUEUE_ADD)
    }
    
    override fun speak(text: String, queueMode: Int) {
        if (!isInitialized || tts == null) {
            return
        }

        // Request Audio Focus manually to ensure Car Audio "ducks" and plays our stream
        requestFocus()

        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        
        // Use UTTERANCE_ID to trigger the progress listener
        val result = tts?.speak(text, queueMode, params, UTTERANCE_ID)
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "Error queuing text to speech")
            abandonFocus() // Release if failed immediately
        }
    }
    
    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
                
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { /* Handle interruptions if needed */ }
                .build()
            
            audioManager?.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                null, 
                AudioManager.STREAM_MUSIC, // Stream type doesn't matter much for ducking on old API, but usage does
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }
    
    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(null)
        }
    }

    override suspend fun generateAudio(text: String, language: String): ByteArray? {
        return null
    }

    override fun stop() {
        tts?.stop()
        abandonFocus()
    }

    override fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        abandonFocus()
    }
}
