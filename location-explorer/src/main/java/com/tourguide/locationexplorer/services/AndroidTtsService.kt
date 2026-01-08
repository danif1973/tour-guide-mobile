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
import com.tourguide.locationexplorer.config.LocationExplorerConfig
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

    // Listener for audio focus changes
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent loss of focus. Stop speaking.
                Log.d(TAG, "Audio focus lost permanently. Stopping playback.")
                tts?.stop()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Temporary loss. Pause or stop.
                Log.d(TAG, "Audio focus lost transiently. Stopping playback.")
                tts?.stop()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // This case should not happen with our current focus request, but handle it just in case.
                Log.d(TAG, "Audio focus lost transiently (can duck).")
                tts?.stop()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Focus gained. We don't need to do anything here as speak() is called right after request.
                Log.d(TAG, "Audio focus gained.")
            }
        }
    }

    override fun initialize(context: Context, callback: (Boolean) -> Unit) {
        if (isInitialized && tts != null) {
            callback(true)
            return
        }

        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        try {
            pendingCallback = callback
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TextToSpeech", e)
            callback(false)
            pendingCallback = null
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Default to US initially
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "The specified language is not supported!")
            }

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            
            tts?.setAudioAttributes(audioAttributes)
            
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { abandonFocus() }
                override fun onError(utteranceId: String?) { abandonFocus() }
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
        if (!isInitialized || tts == null) return

        try {
            val locale = Locale.forLanguageTag(language)
            val result = tts?.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Language $language not supported for TTS.")
                // Optional: fall back to a default language if the requested one is not available
                // tts?.setLanguage(Locale.US)
            }
            
            tts?.setSpeechRate(LocationExplorerConfig.ttsRate)
            tts?.setPitch(LocationExplorerConfig.ttsPitch)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to configure TTS settings for language $language", e)
        }
        
        Log.i(TAG, "Speaking text in $language")
        speak(text, TextToSpeech.QUEUE_ADD)
    }
    
    override fun speak(text: String, queueMode: Int) {
        if (!isInitialized || tts == null) {
            return
        }

        requestFocus()

        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        
        val result = tts?.speak(text, queueMode, params, UTTERANCE_ID)
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "Error queuing text to speech")
            abandonFocus()
        }
    }
    
    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
                
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
            
            audioManager?.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                audioFocusListener, 
                AudioManager.STREAM_NOTIFICATION,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
    }
    
    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(audioFocusListener)
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
