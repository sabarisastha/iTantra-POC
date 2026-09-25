package com.itantra.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.itantra.stt.RecognizedSentence
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "AndroidTtsEngine"

/**
 * Android system native TextToSpeech engine wrapper.
 * Guarantees immediate, audible playback across all devices and OEM ROMs.
 */
class AndroidTtsEngine(private val context: Context) : TextToSpeechEngine {

    private var tts: TextToSpeech? = null
    @Volatile private var initialized = false
    private val pendingUtterances = mutableListOf<RecognizedSentence>()
    private val alertPrevVolumes = ConcurrentHashMap<String, Int>()
    private val historyMap = ConcurrentHashMap<Int, RecognizedSentence>()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile private var alertFocusRequest: AudioFocusRequest? = null
    @Volatile private var normalFocusRequest: AudioFocusRequest? = null

    init {
        initTts()
    }

    private fun initTts() {
        if (tts != null && initialized) return
        Log.d(TAG, "Initializing Android System TextToSpeech engine...")
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                initialized = true
                tts?.setOnUtteranceProgressListener(utteranceListener)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    tts?.setAudioAttributes(attrs)
                }

                Log.d(TAG, "Android TextToSpeech successfully initialized")
                
                val toProcess: List<RecognizedSentence>
                synchronized(pendingUtterances) {
                    toProcess = pendingUtterances.toList()
                    pendingUtterances.clear()
                }
                Log.d(TAG, "Flushing ${toProcess.size} pending TTS utterances")
                toProcess.forEach { speak(it) }
            } else {
                Log.e(TAG, "Android TextToSpeech init failed: status=$status")
            }
        }
    }

    override fun prewarm(languageCode: String) {
        // System TTS handles language loading on demand
    }

    override fun replay(sequenceId: Int): Boolean {
        val sentence = historyMap[sequenceId] ?: return false
        Log.d(TAG, "Replaying sequenceId=$sequenceId via Android System TTS")
        speak(sentence)
        return true
    }

    override fun speak(sentence: RecognizedSentence) {
        if (sentence.text.isBlank()) return
        if (sentence.sequenceId > 0) {
            historyMap[sentence.sequenceId] = sentence
        }
        
        Log.d(TAG, "speak() requested: text='${sentence.text}', lang='${sentence.languageCode}', alert=${sentence.isAlert}, initialized=$initialized")
        if (!initialized || tts == null) {
            synchronized(pendingUtterances) {
                pendingUtterances.add(sentence)
            }
            Log.d(TAG, "TTS not ready yet; queued utterance: ${sentence.text}")
            initTts()
            return
        }

        val uid = UUID.randomUUID().toString()
        val locale = localeFor(sentence.languageCode)
        var result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Locale $locale not supported on this device — trying English fallback")
            result = tts?.setLanguage(Locale.ENGLISH)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                result = tts?.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Defaulting to device default locale: ${Locale.getDefault()}")
                    tts?.setLanguage(Locale.getDefault())
                }
            }
        }

        if (sentence.isAlert) {
            speakAlert(sentence.text, uid)
        } else {
            speakNormal(sentence.text, uid)
        }
    }

    /** Stops current audio playback on disconnect without destroying TextToSpeech engine */
    fun stop() {
        Log.d(TAG, "stop() called — stopping speech playback and releasing focus")
        releaseAlertFocus()
        releaseNormalFocus()
        runCatching { tts?.stop() }
        synchronized(pendingUtterances) {
            pendingUtterances.clear()
        }
    }

    override fun shutdown() {
        Log.d(TAG, "shutdown() called — destroying TextToSpeech engine")
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        initialized = false
    }

    private fun speakNormal(text: String, uid: String) {
        Log.d(TAG, "speakNormal: '$text' (uid=$uid)")
        requestNormalFocus()
        ensureStreamMusicAudible()

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        // Use QUEUE_FLUSH to immediately interrupt and play incoming PTT speech without queue stalling
        val res = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, uid)
        Log.d(TAG, "tts.speak (normal) returned: $res")
    }

    private fun speakAlert(text: String, uid: String) {
        Log.d(TAG, "speakAlert: '$text' (uid=$uid) at MAX volume")
        requestAlertFocus()
        val stream = AudioManager.STREAM_ALARM
        val prevVol = audioManager.getStreamVolume(stream)
        alertPrevVolumes[uid] = prevVol
        
        audioManager.setStreamVolume(
            stream,
            audioManager.getStreamMaxVolume(stream),
            0,
        )
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, stream)
        }
        val res = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, uid)
        Log.d(TAG, "tts.speak (alert) returned: $res")
    }

    private fun ensureStreamMusicAudible() {
        try {
            val stream = AudioManager.STREAM_MUSIC
            val currentVol = audioManager.getStreamVolume(stream)
            val maxVol = audioManager.getStreamMaxVolume(stream)
            if (currentVol == 0) {
                val targetVol = maxOf(1, (maxVol * 0.75f).toInt())
                audioManager.setStreamVolume(stream, targetVol, 0)
                Log.i(TAG, "STREAM_MUSIC volume was muted (0); boosted to $targetVol/$maxVol for PTT speech")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check/adjust STREAM_MUSIC volume: ${e.message}")
        }
    }

    private fun requestNormalFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { /* hold focus */ }
                .build()
            audioManager.requestAudioFocus(req)
            normalFocusRequest = req
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
        }
    }

    private fun releaseNormalFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            normalFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            normalFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun requestAlertFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { /* hold focus; no-op */ }
                .build()
            audioManager.requestAudioFocus(req)
            alertFocusRequest = req
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_ALARM,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            )
        }
    }

    private fun releaseAlertFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            alertFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            alertFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(uid: String?) {
            Log.d(TAG, "TTS utterance started: uid=$uid")
        }
        
        override fun onDone(uid: String?) {
            Log.d(TAG, "TTS utterance completed: uid=$uid")
            restoreVolume(uid)
            releaseAlertFocus()
            releaseNormalFocus()
        }
        
        @Deprecated("Deprecated in Java")
        override fun onError(uid: String?) {
            Log.e(TAG, "TTS utterance error: uid=$uid")
            restoreVolume(uid)
            releaseAlertFocus()
            releaseNormalFocus()
        }
        
        override fun onError(uid: String?, errorCode: Int) {
            Log.e(TAG, "TTS utterance error on $uid, code=$errorCode")
            restoreVolume(uid)
            releaseAlertFocus()
            releaseNormalFocus()
        }
        
        private fun restoreVolume(uid: String?) {
            if (uid != null) {
                alertPrevVolumes.remove(uid)?.let { prevVol ->
                    try {
                        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, prevVol, 0)
                        Log.d(TAG, "Restored STREAM_ALARM volume to $prevVol")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to restore volume", e)
                    }
                }
            }
        }
    }

    private fun localeFor(code: String): Locale = when (code) {
        "hi" -> Locale("hi", "IN")
        "gu" -> Locale("gu", "IN")
        "mr" -> Locale("mr", "IN")
        "kn" -> Locale("kn", "IN")
        "ml" -> Locale("ml", "IN")
        "ta" -> Locale("ta", "IN")
        "te" -> Locale("te", "IN")
        "or" -> Locale("or", "IN")
        "bn" -> Locale("bn", "IN")
        "en" -> Locale.ENGLISH
        else -> Locale.ENGLISH
    }
}
