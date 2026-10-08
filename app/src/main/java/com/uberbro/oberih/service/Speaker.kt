package com.uberbro.oberih.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.uberbro.oberih.data.DistanceUnit
import com.uberbro.oberih.data.Level
import com.uberbro.oberih.data.VoiceLang
import com.uberbro.oberih.hazard.HazardAlert
import com.uberbro.oberih.hazard.HazardType
import com.uberbro.oberih.hazard.Stage
import com.uberbro.oberih.offer.Profit
import com.uberbro.oberih.offer.Verdict
import java.util.Locale
import kotlin.math.roundToInt

/** Тексти голосових підказок. Короткі, щоб не відволікати від дороги. */
object Phrases {
    fun levelPhrase(level: Level, lang: VoiceLang): String = when (lang) {
        VoiceLang.UK -> when (level) {
            Level.RED -> "Червона зона. Заборонено."
            Level.ORANGE -> "Нічна небезпечна зона. Заборонено."
            Level.YELLOW -> "Жовта зона. Не рекомендуємо."
            Level.GREEN -> "Зелена зона. Дозволено."
            Level.UNKNOWN -> "Немає даних про район."
        }
        VoiceLang.EN -> when (level) {
            Level.RED -> "Red zone. Forbidden."
            Level.ORANGE -> "Night danger zone. Forbidden."
            Level.YELLOW -> "Yellow zone. Not recommended."
            Level.GREEN -> "Green zone. Allowed."
            Level.UNKNOWN -> "No data for this area."
        }
    }

    fun dollarsUk(n: Int): String {
        val a = kotlin.math.abs(n)
        val word = when {
            a % 10 == 1 && a % 100 != 11 -> "долар"
            a % 10 in 2..4 && a % 100 !in 12..14 -> "долари"
            else -> "доларів"
        }
        return "$n $word"
    }

    private fun plural(n: Int, one: String, few: String, many: String): String {
        val a = kotlin.math.abs(n)
        return when {
            a % 10 == 1 && a % 100 != 11 -> one
            a % 10 in 2..4 && a % 100 !in 12..14 -> few
            else -> many
        }
    }

    /** «за 400 метрів», «за кілометр», «за 2 кілометри»; англійською — милі/фути. */
    fun distance(meters: Double, lang: VoiceLang, unit: DistanceUnit): String {
        if (lang == VoiceLang.EN || unit == DistanceUnit.MI) {
            val miles = meters / 1609.34
            return when (lang) {
                VoiceLang.EN -> when {
                    miles < 0.15 -> "in ${roundTo(meters / 0.3048, 100)} feet"
                    miles < 0.95 -> "in ${"%.1f".format(java.util.Locale.US, miles)} miles"
                    else -> { val n = miles.roundToInt().coerceAtLeast(1); "in $n mile${if (n == 1) "" else "s"}" }
                }
                VoiceLang.UK -> when {
                    miles < 0.15 -> "за ${roundTo(meters / 0.3048, 100)} футів"
                    miles < 0.75 -> "за пів милі"
                    else -> { val n = miles.roundToInt().coerceAtLeast(1); "за $n ${plural(n, "милю", "милі", "миль")}" }
                }
            }
        }
        return when {
            meters < 950 -> "за ${roundTo(meters, if (meters < 300) 50 else 100)} метрів"
            meters < 1_500 -> "за кілометр"
            else -> { val n = (meters / 1000).roundToInt(); "за $n ${plural(n, "кілометр", "кілометри", "кілометрів")}" }
        }
    }

    /** Коротко для плашки: «1,8 км», «400 м», «1.1 mi», «500 ft». */
    fun shortDistance(meters: Double, unit: DistanceUnit): String = when (unit) {
        DistanceUnit.KM -> if (meters < 950) "${roundTo(meters, 50)} м" else "%.1f км".format(java.util.Locale("uk"), meters / 1000)
        DistanceUnit.MI -> if (meters < 240) "${roundTo(meters / 0.3048, 50)} ft" else "%.1f mi".format(java.util.Locale.US, meters / 1609.34)
    }

    private fun roundTo(v: Double, step: Int): Int = ((v / step).roundToInt() * step).coerceAtLeast(step)

    fun hazard(a: HazardAlert, lang: VoiceLang, unit: DistanceUnit): String {
        val d = a.meters?.let { " " + distance(it, lang, unit) } ?: ""
        return when (lang) {
            VoiceLang.UK -> {
                val what = when (a.type) {
                    HazardType.POLICE -> "поліція"
                    HazardType.CRASH -> "аварія"
                    HazardType.CLOSURE -> "перекрита дорога"
                    HazardType.HAZARD -> "небезпека на дорозі"
                }
                if (a.stage == Stage.FAR) "Увага! Попереду $what$d."
                else "${what.replaceFirstChar { it.uppercase() }}$d. Будь уважний."
            }
            VoiceLang.EN -> {
                val what = when (a.type) {
                    HazardType.POLICE -> "police"
                    HazardType.CRASH -> "crash"
                    HazardType.CLOSURE -> "road closed"
                    HazardType.HAZARD -> "road hazard"
                }
                if (a.stage == Stage.FAR) "Attention! $what ahead$d." else "${what.replaceFirstChar { it.uppercase() }}$d."
            }
        }
    }

    fun full(v: Verdict, lang: VoiceLang, speakProfit: Boolean): String {
        val sb = StringBuilder(levelPhrase(v.level, lang))
        val ph = v.netPerHour
        if (speakProfit && ph != null) {
            val n = ph.roundToInt()
            sb.append(' ')
            when (lang) {
                VoiceLang.UK -> {
                    if (n <= 0) sb.append("Збиткова поїздка.")
                    else {
                        sb.append(dollarsUk(n)).append(" за годину")
                        when (v.profit) {
                            Profit.GOOD -> sb.append(", вигідно.")
                            Profit.BAD -> sb.append(", невигідно.")
                            else -> sb.append('.')
                        }
                    }
                }
                VoiceLang.EN -> {
                    if (n <= 0) sb.append("Losing money.")
                    else {
                        sb.append("$n dollars per hour")
                        when (v.profit) {
                            Profit.GOOD -> sb.append(", good.")
                            Profit.BAD -> sb.append(", poor.")
                            else -> sb.append('.')
                        }
                    }
                }
            }
        }
        return sb.toString()
    }
}

/**
 * Голос. Під час підказки звук Waze/музики тимчасово стишується (audio focus «duck»), а потім повертається.
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private val ctx = context.applicationContext
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .build()
    private var tts: TextToSpeech? = TextToSpeech(ctx, this)
    private var ready = false
    private var pending: (() -> Unit)? = null

    /** null — ще невідомо; true — українського голосу на телефоні немає. */
    var ukrainianMissing: Boolean? = null
        private set

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        ready = true
        t.setAudioAttributes(attrs)
        t.setSpeechRate(1.05f)
        ukrainianMissing = t.isLanguageAvailable(UK) < TextToSpeech.LANG_AVAILABLE
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { audio.abandonAudioFocusRequest(focus) }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { audio.abandonAudioFocusRequest(focus) }
        })
        pending?.let { pending = null; it() }
    }

    /** Повертає мову, якою реально говоримо (якщо української немає — англійська). */
    fun effectiveLang(wanted: VoiceLang): VoiceLang =
        if (wanted == VoiceLang.UK && ukrainianMissing == true) VoiceLang.EN else wanted

    fun speak(text: String, lang: VoiceLang) {
        val t = tts ?: return
        if (!ready) { pending = { speak(text, lang) }; return }
        t.language = if (lang == VoiceLang.UK && ukrainianMissing != true) UK else Locale.US
        audio.requestAudioFocus(focus)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "oberih-${System.currentTimeMillis()}")
    }

    fun speakVerdict(v: Verdict, wanted: VoiceLang, speakProfit: Boolean) {
        if (!ready) { pending = { speakVerdict(v, wanted, speakProfit) }; return }
        val lang = effectiveLang(wanted)
        speak(Phrases.full(v, lang, speakProfit), lang)
    }

    fun stop() { tts?.stop(); audio.abandonAudioFocusRequest(focus) }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }

    companion object {
        val UK: Locale = Locale("uk", "UA")
    }
}
