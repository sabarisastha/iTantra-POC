package com.itantra.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.itantra.iTantraApplication
import com.itantra.tts.IndicParlerTtsEngine

/**
 * Debug-only ADB-triggered TTS benchmark receiver.
 *
 * Usage:
 *   adb shell am broadcast -a com.itantra.action.TTS_BENCHMARK \
 *       --es lang "hi" \
 *       --es text "हम आपात स्थिति में हैं" \
 *       --ei runs 5 \
 *       -n com.itantra/com.itantra.debug.TtsBenchmarkReceiver
 *
 * Watch output:
 *   adb logcat -s TTS_ISO:I
 */
class TtsBenchmarkReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {

        val app = context.applicationContext as? iTantraApplication
        if (app == null) {
            Log.e("TTS_ISO", "ABORT — Application not iTantraApplication")
            return
        }

        val engine = app.ttsEngine
        if (engine !is IndicParlerTtsEngine) {
            Log.e("TTS_ISO", "ABORT — ttsEngine is ${engine::class.simpleName}, not IndicParlerTtsEngine")
            return
        }

        val lang = intent.getStringExtra("lang") ?: "hi"
        val text = intent.getStringExtra("text") ?: "हम आपात स्थिति में हैं"
        val runs = intent.getIntExtra("runs", 5)
        val threads = if (intent.hasExtra("threads")) intent.getIntExtra("threads", 4) else null

        if (intent.action == "com.itantra.action.TTS_PREWARM") {
            Log.i("TTS_ISO", "Triggering engine.prewarm($lang)")
            engine.prewarm(lang)
            return
        }

        if (intent.action == "com.itantra.action.TTS_SPEAK") {
            Log.i("TTS_ISO", "Triggering engine.speak(\"$text\", lang=$lang)")
            engine.speak(
                com.itantra.stt.RecognizedSentence(
                    text = text,
                    languageCode = lang,
                    isAlert = false,
                    timestampMs = System.currentTimeMillis(),
                    sequenceId = 1,
                    receivedTimestampMs = System.currentTimeMillis()
                )
            )
            return
        }

        Log.i("TTS_ISO", "BroadcastReceiver triggered: lang=$lang runs=$runs threads=$threads")

        engine.runIsolatedBenchmark(
            lang = lang,
            text = text,
            runs = runs,
            threads = threads
        )
    }
}
