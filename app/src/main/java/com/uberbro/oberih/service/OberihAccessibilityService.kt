package com.uberbro.oberih.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.uberbro.oberih.data.CrimeRepository
import com.uberbro.oberih.data.Journal
import com.uberbro.oberih.data.JournalEntry
import com.uberbro.oberih.data.Level
import com.uberbro.oberih.data.Prefs
import com.uberbro.oberih.data.ScreenSamples
import com.uberbro.oberih.offer.OfferAnalyzer
import com.uberbro.oberih.offer.OfferParser
import com.uberbro.oberih.offer.ParsedOffer
import com.uberbro.oberih.offer.Profit
import com.uberbro.oberih.offer.ScreenText
import com.uberbro.oberih.offer.Verdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Серце програми. Android показує цьому сервісу текст на екрані Uber Driver / Lyft Driver.
 * Коли з'являється нове замовлення — розпізнаємо його, рахуємо зону й вигідність,
 * блимаємо кольором і кажемо голосом. Сервіс НІЧОГО не натискає.
 */
class OberihAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "Oberih"
        const val UBER = "com.ubercab.driver"
        const val LYFT = "com.lyft.android.driver"
        const val DEMO_MARKER = "OBERIH_DEMO_OFFER"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        @Volatile
        var instance: OberihAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var overlay: AlertOverlay
    private lateinit var speaker: Speaker
    private lateinit var prefs: Prefs

    private var firstPendingAt = 0L
    private var lastSource: AccessibilityNodeInfo? = null
    private var currentSig: String? = null
    private var candidateSig: String? = null
    private var candidateAt = 0L
    private var lastOfferSeenAt = 0L
    private var analyzeJob: Job? = null
    private var lastSampleAt = 0L
    private val recent = LinkedHashMap<String, Pair<Long, Verdict?>>() // підпис → (коли аналізували, результат)

    private val scanRunnable = Runnable { scan() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        overlay = AlertOverlay(this)
        speaker = Speaker(this)
        instance = this
        _running.value = true
        scope.launch { CrimeRepository.load(this@OberihAccessibilityService) }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val pkg = e?.packageName?.toString() ?: return
        if (pkg != UBER && pkg != LYFT && pkg != packageName) return
        if (pkg != packageName) runCatching { lastSource = e.source }
        val now = SystemClock.uptimeMillis()
        if (firstPendingAt == 0L) firstPendingAt = now
        handler.removeCallbacks(scanRunnable)
        // Чекаємо, поки екран «заспокоїться», але не довше ~0,7 с від першої зміни.
        handler.postDelayed(scanRunnable, if (now - firstPendingAt > 700) 0 else 220)
    }

    private fun scan() {
        firstPendingAt = 0L
        val captured = try { collect() } catch (t: Throwable) { Log.w(TAG, "collect", t); return }
        val h = resources.displayMetrics.heightPixels
        var found: ParsedOffer? = null
        var foundText = ""
        for ((pkg, items) in captured) {
            if (pkg == packageName && items.none { it.text == DEMO_MARKER }) continue
            val app = when (pkg) { UBER -> "Uber"; LYFT -> "Lyft"; else -> "Демо" }
            val offer = OfferParser.parse(app, items.filter { it.text != DEMO_MARKER }, h)
            val text = items.joinToString("\n") { it.text }
            if (offer != null) { found = offer; foundText = text; break }
            if (pkg != packageName) maybeSample(app, false, text)
        }
        val now = SystemClock.uptimeMillis()
        if (found != null) {
            lastOfferSeenAt = now
            val sig = found.signature
            if (sig == currentSig) return
            // Картка замовлення ще «виїжджає» і адрес не видно — чекаємо пів секунди, щоб не аналізувати двічі.
            if (found.pickupText == null || found.dropoffText == null) {
                if (sig != candidateSig) { candidateSig = sig; candidateAt = now }
                val waited = now - candidateAt
                if (waited < 450) {
                    handler.removeCallbacks(scanRunnable)
                    handler.postDelayed(scanRunnable, 500 - waited)
                    return
                }
            }
            currentSig = sig
            val prev = recent[sig]
            val cached = prev?.second
            if (prev != null && now - prev.first < 120_000 && cached != null) {
                // Те саме замовлення прийшло знову — показуємо колір ще раз, але без повторного голосу.
                overlay.showVerdict(cached, prefs.flashMode)
            } else if (prev == null || now - prev.first >= 120_000) {
                onNewOffer(found, foundText)
            }
        } else if (currentSig != null && now - lastOfferSeenAt > 1_500) {
            currentSig = null
            overlay.hide()
        }
    }

    private fun maybeSample(app: String, recognized: Boolean, text: String) {
        if (!recognized && !OfferParser.looksLikeOfferCandidate(text)) return
        val now = SystemClock.uptimeMillis()
        if (!recognized && now - lastSampleAt < 3_000) return
        lastSampleAt = now
        scope.launch(Dispatchers.IO) { runCatching { ScreenSamples.add(applicationContext, app, recognized, text) } }
    }

    private fun onNewOffer(offer: ParsedOffer, rawText: String) {
        val sig = offer.signature
        recent[sig] = SystemClock.uptimeMillis() to null
        if (recent.size > 30) recent.remove(recent.keys.first())
        if (offer.app != "Демо") maybeSample(offer.app, true, rawText)
        overlay.showAnalyzing()
        analyzeJob?.cancel()
        analyzeJob = scope.launch {
            val v = withTimeoutOrNull(8_000) { OfferAnalyzer.analyze(applicationContext, offer) }
                ?: fallbackVerdict(offer)
            recent[sig] = SystemClock.uptimeMillis() to v
            present(v)
            withContext(Dispatchers.IO) {
                Journal.add(
                    applicationContext,
                    JournalEntry(System.currentTimeMillis(), offer.app, v.level, v.fare, v.netPerHour,
                        v.profit.word, offer.pickupText, offer.dropoffText, v.reason),
                )
            }
        }
    }

    private fun fallbackVerdict(o: ParsedOffer) = Verdict(
        Level.UNKNOWN, Level.UNKNOWN, Level.UNKNOWN, Level.UNKNOWN, null, null,
        com.uberbro.oberih.util.SunTimes.isNight(), o.fare, null, null, null, null, Profit.UNKNOWN,
        "не вдалося перевірити (немає інтернету?)",
    )

    private fun present(v: Verdict) {
        overlay.showVerdict(v, prefs.flashMode)
        if (prefs.voiceOn) speaker.speakVerdict(v, prefs.voiceLang, prefs.speakProfit)
        // Якщо замовлення вже зникло з екрана, поки ми рахували, — сигнал однаково видно кілька секунд.
        if (currentSig == null) overlay.hide(6_000)
    }

    /** Для кнопок «Перевірити сигнал» у програмі. */
    fun demo(level: Level, night: Boolean = false) {
        val v = Verdict(
            level, level, level, Level.GREEN, null, "Englewood", night, 18.5,
            if (level == Level.GREEN) 31.0 else 14.0, 1.6, 30.0, 11.0,
            if (level == Level.GREEN) Profit.GOOD else Profit.BAD,
            "тест сигналу",
        )
        present(v)
        overlay.hide(6_000)
    }

    fun speakTest(text: String) = speaker.speak(text, speaker.effectiveLang(prefs.voiceLang))

    val ukrainianVoiceMissing: Boolean? get() = speaker.ukrainianMissing

    // ---------- Читання екрана ----------

    private fun collect(): Map<String, List<ScreenText>> {
        val out = LinkedHashMap<String, MutableList<ScreenText>>()
        val roots = ArrayList<AccessibilityNodeInfo>()
        val seenWindows = HashSet<Int>()
        runCatching {
            for (w in windows) {
                val r = w.root ?: continue
                roots += r; seenWindows += w.id
            }
        }
        if (roots.isEmpty()) rootInActiveWindow?.let { roots += it }
        // Буває, що картка замовлення — окреме «плаваюче» вікно, якого немає в списку. Тоді йдемо від джерела події.
        lastSource?.let { src ->
            if (src.windowId !in seenWindows) {
                var n: AccessibilityNodeInfo? = src
                var guard = 0
                while (n?.parent != null && guard++ < 60) n = n.parent
                n?.let { roots += it }
            }
        }
        val rect = Rect()
        for (root in roots) {
            val pkg = root.packageName?.toString() ?: continue
            if (pkg != UBER && pkg != LYFT && pkg != packageName) continue
            val list = out.getOrPut(pkg) { mutableListOf() }
            walk(root, list, 0, rect)
        }
        return out
    }

    private fun walk(node: AccessibilityNodeInfo, out: MutableList<ScreenText>, depth: Int, rect: Rect) {
        if (depth > 50 || out.size > 1500) return
        if (!node.isVisibleToUser) return
        node.getBoundsInScreen(rect)
        val t = node.text?.toString()
        val d = node.contentDescription?.toString()
        if (!t.isNullOrBlank()) out += ScreenText(t, rect.top, rect.height())
        if (!d.isNullOrBlank() && d != t) out += ScreenText(d, rect.top, rect.height())
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            walk(c, out, depth + 1, rect)
        }
    }

    override fun onInterrupt() {
        if (::speaker.isInitialized) speaker.stop()
    }

    override fun onDestroy() {
        instance = null
        _running.value = false
        handler.removeCallbacksAndMessages(null)
        if (::overlay.isInitialized) overlay.hideNow()
        if (::speaker.isInitialized) speaker.shutdown()
        scope.cancel()
        super.onDestroy()
    }
}
