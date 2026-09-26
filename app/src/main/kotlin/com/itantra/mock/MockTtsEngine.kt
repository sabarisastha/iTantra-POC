package com.itantra.mock

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
import com.itantra.tts.TextToSpeechEngine
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "MockTtsEngine"

class MockTtsEngine(private val context: Context) : TextToSpeechEngine {

    private var tts: TextToSpeech? = null
    @Volatile private var initialized = false
    private val pendingUtterances = mutableListOf<RecognizedSentence>()
    private val alertPrevVolumes = ConcurrentHashMap<String, Int>()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile private var alertFocusRequest: AudioFocusRequest? = null

    init {
        initTts()
    }

    private fun initTts() {
        if (tts != null && initialized) return
        Log.d(TAG, "Initializing TextToSpeech engine...")
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                initialized = true
                tts?.setOnUtteranceProgressListener(utteranceListener)
                Log.d(TAG, "Android TextToSpeech successfully initialized")
                
                val toProcess: List<RecognizedSentence>
                synchronized(pendingUtterances) {
                    toProcess = pendingUtterances.toList()
                    pendingUtterances.clear()
                }
                Log.d(TAG, "Flushing ${toProcess.size} pending TTS utterances")
                toProcess.forEach { speak(it) }
            } else {
                Log.e(TAG, "TextToSpeech init failed: status=$status")
            }
        }
    }

    override fun speak(sentence: RecognizedSentence) {
        Log.d(TAG, "speak() requested: text='${sentence.text}', lang='${sentence.languageCode}', alert=${sentence.isAlert}, initialized=$initialized")
        if (!initialized || tts == null) {
            synchronized(pendingUtterances) {
                pendingUtterances.add(sentence)
            }
            Log.d(TAG, "TTS not ready yet; queued utterance: ${sentence.text}")
            initTts()
            return
        }
        val uid    = UUID.randomUUID().toString()
        val locale = localeFor(sentence.languageCode)
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Locale $locale not supported on this device — falling back to en")
            tts?.setLanguage(Locale.ENGLISH)
        }

        if (sentence.isAlert) {
            speakAlert(sentence.text, uid)
        } else {
            speakNormal(sentence.text, uid)
        }
    }

    /** Stops current audio playback on disconnect without destroying TextToSpeech engine */
    fun stop() {
        Log.d(TAG, "stop() called — stopping speech playback and releasing alert focus")
        releaseAlertFocus()
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
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        tts?.speak(text, TextToSpeech.QUEUE_ADD, params, uid)
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
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, uid)
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
        }
        
        @Deprecated("Deprecated in Java")
        override fun onError(uid: String?) {
            Log.e(TAG, "TTS utterance error: uid=$uid")
            restoreVolume(uid)
            releaseAlertFocus()
        }
        
        override fun onError(uid: String?, errorCode: Int) {
            Log.e(TAG, "TTS utterance error on $uid, code=$errorCode")
            restoreVolume(uid)
            releaseAlertFocus()
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
