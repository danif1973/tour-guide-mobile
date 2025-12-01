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

/**
 * Android TextToSpeech implementation.
 */
class AndroidTtsService(
    private val config: LocationExplorerConfig = LocationExplorerConfig
) : TtsService {
    companion object {
        private const val TAG = "AndroidTtsService"
    }
    
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    
    override fun initialize(context: Context, callback: (Boolean) -> Unit) {
        if (tts != null) {
            callback(isInitialized)
            return
        }
        
        tts = TextToSpeech(context) { status ->
            isInitialized = status == TextToSpeech.SUCCESS
            if (isInitialized) {
                val locale = when (config.ttsLanguage) {
                    "en" -> Locale.US
                    "en-GB" -> Locale.UK
                    else -> Locale.getDefault()
                }
                val result = tts?.setLanguage(locale) ?: TextToSpeech.LANG_MISSING_DATA
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Language not supported: ${config.ttsLanguage}")
                }
            }
            callback(isInitialized)
        }
    }
    
    override suspend fun generateAudio(text: String, language: String): ByteArray? {
        // Android TextToSpeech doesn't directly export audio bytes.
        // This would require using MediaRecorder or a third-party library.
        // For now, return null to indicate it's not supported.
        Log.w(TAG, "generateAudio() is not fully supported on Android TTS. Use speak() instead.")
        return null
    }
    
    override fun speak(text: String, language: String) {
        if (!isInitialized || tts == null) {
            Log.w(TAG, "TTS not initialized, cannot speak")
            return
        }
        
        if (text.isEmpty()) {
            Log.w(TAG, "TTS generation skipped: empty text")
            return
        }
        
        // Set language if different
        if (language != config.ttsLanguage) {
            val locale = when (language) {
                "en" -> Locale.US
                "en-GB" -> Locale.UK
                else -> Locale.getDefault()
            }
            tts?.setLanguage(locale)
        }
        
        // Parse rate, pitch, volume from config (simplified)
        val rate = parseRate(config.ttsRate)
        val pitch = parsePitch(config.ttsPitch)
        
        tts?.setSpeechRate(rate)
        tts?.setPitch(pitch)
        
        val result = tts?.speak(text, TextToSpeech.QUEUE_ADD, null, null)
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "Error speaking text")
        }
    }
    
    override fun stop() {
        tts?.stop()
    }
    
    override fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
    
    /**
     * Parse rate string (e.g., "+10%", "-20%") to float.
     * Default is 1.0, range is 0.0 to 2.0.
     */
    private fun parseRate(rateStr: String): Float {
        if (rateStr.isEmpty()) return 1.0f
        
        return try {
            val percentage = rateStr.replace("%", "").replace("+", "").toFloat() / 100f
            1.0f + percentage
        } catch (e: Exception) {
            1.0f
        }.coerceIn(0.0f, 2.0f)
    }
    
    /**
     * Parse pitch string (e.g., "-10Hz", "+5Hz") to float.
     * Default is 1.0, range is 0.0 to 2.0.
     */
    private fun parsePitch(pitchStr: String): Float {
        if (pitchStr.isEmpty()) return 1.0f
        
        return try {
            val hz = pitchStr.replace("Hz", "").replace("+", "").toFloat()
            // Convert Hz change to pitch multiplier (simplified)
            1.0f + (hz / 50f)
        } catch (e: Exception) {
            1.0f
        }.coerceIn(0.0f, 2.0f)
    }
}


