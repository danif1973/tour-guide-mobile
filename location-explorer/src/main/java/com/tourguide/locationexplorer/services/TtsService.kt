package com.tourguide.locationexplorer.services

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.tourguide.locationexplorer.config.LocationExplorerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Text-to-Speech service interface.
 * Ported from location_explorer/services/edge_tts_service.py and tts_service.py
 */
interface TtsService {
    /**
     * Initialize the TTS service.
     * @param context Android context
     * @param callback Called with true if initialization succeeded, false otherwise
     */
    fun initialize(context: Context, callback: (Boolean) -> Unit)
    
    /**
     * Generate audio from text and return as byte array.
     * Note: Android TTS doesn't directly export audio bytes, so this may return null.
     * @param text Text to convert to speech
     * @param language Language code (default: "en")
     * @return Byte array of audio data, or null if not supported/available
     */
    suspend fun generateAudio(text: String, language: String = "en"): ByteArray?
    
    /**
     * Speak text using TTS.
     * @param text Text to speak
     * @param language Language code (default: "en")
     */
    fun speak(text: String, language: String = "en")
    
    /**
     * Stop speaking.
     */
    fun stop()
    
    /**
     * Shutdown the TTS service.
     */
    fun shutdown()
}
