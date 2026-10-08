package com.uberbro.oberih.service

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import com.uberbro.oberih.data.FlashMode
import com.uberbro.oberih.data.Level
import com.uberbro.oberih.hazard.HazardType
import com.uberbro.oberih.offer.Profit
import com.uberbro.oberih.offer.Verdict
import kotlin.math.roundToInt

/**
 * Кольоровий сигнал поверх усіх програм.
 * Вікно типу «accessibility overlay» ПРОПУСКАЄ ВСІ ДОТИКИ — кнопки Uber/Lyft під ним натискаються як звичайно.
 */
class AlertOverlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var view: SignalView? = null
    private var lights: LightsView? = null
    private val hideLights = Runnable { removeLights() }
    private var shownAt = 0L
    private val autoHide = Runnable { hideNow() }
    private val deferredHide = Runnable { hideNow() }

    fun showAnalyzing() {
        val v = ensureView()
        v.set(Color.parseColor("#78909C"), "ОБЕРІГ", "перевіряю зону…", false)
        v.frameAlpha = 0.6f
        v.fillAlpha = 0f
        v.invalidate()
        scheduleAutoHide(15_000)
    }

    fun showVerdict(verdict: Verdict, mode: FlashMode) {
        val v = ensureView()
        val line2 = buildList {
            verdict.netPerHour?.let { add("$${it.roundToInt()}/год") }
            if (verdict.profit != Profit.UNKNOWN) add(verdict.profit.word.lowercase())
            if (verdict.reason.isNotBlank()) add(verdict.reason)
        }.joinToString(" · ")
        v.set(verdict.level.colorInt, verdict.level.word, line2, verdict.level == Level.YELLOW)
        shownAt = SystemClock.uptimeMillis()
        v.flash(mode, if (verdict.isNight) 0.30f else 0.45f)
        scheduleAutoHide(25_000)
    }

    /** Ховає сигнал, але не раніше ніж через [minVisibleMs] після показу вердикту. */
    fun hide(minVisibleMs: Long = 4_000) {
        if (view == null) return
        val left = shownAt + minVisibleMs - SystemClock.uptimeMillis()
        handler.removeCallbacks(deferredHide)
        if (left > 0) handler.postDelayed(deferredHide, left) else hideNow()
    }

    fun hideNow() {
        handler.removeCallbacks(autoHide)
        handler.removeCallbacks(deferredHide)
        view?.let { v ->
            v.stop()
            runCatching { wm.removeView(v) }
        }
        view = null
    }

    val isShowing: Boolean get() = view != null

    /**
     * Мигалка: поліція — червоно-синя, аварія/небезпека — жовта. ~3 секунди миготіння
     * (не частіше 3 разів на секунду — безпечно для очей), потім плашка ще кілька секунд.
     */
    fun showLights(type: HazardType, subtitle: String, night: Boolean) {
        val v = lights ?: LightsView(ctx).also {
            runCatching { wm.addView(it, overlayParams("Oberih lights")) }.onFailure { return }
            lights = it
        }
        v.start(type, subtitle, if (night) 0.30f else 0.42f)
        handler.removeCallbacks(hideLights)
        handler.postDelayed(hideLights, 9_000)
    }

    private fun removeLights() {
        lights?.let { it.stop(); runCatching { wm.removeView(it) } }
        lights = null
    }

    fun hideAll() { hideNow(); handler.removeCallbacks(hideLights); removeLights() }

    private fun scheduleAutoHide(ms: Long) {
        handler.removeCallbacks(autoHide)
        handler.removeCallbacks(deferredHide)
        handler.postDelayed(autoHide, ms)
    }

    private fun ensureView(): SignalView {
        view?.let { return it }
        val v = SignalView(ctx)
        wm.addView(v, overlayParams("Oberih signal"))
        view = v
        return v
    }

    private fun overlayParams(name: String): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            title = name
        }
    }

    private class LightsView(ctx: Context) : View(ctx) {
        private val density = ctx.resources.displayMetrics.density
        private var type = HazardType.POLICE
        private var subtitle = ""
        private var peak = 0.4f
        private var phase = -1 // -1 — миготіння закінчилось
        private var animator: ValueAnimator? = null
        private val paint = Paint()
        private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 24 * density
        }
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16 * density }
        private val rect = RectF()

        fun start(t: HazardType, sub: String, p: Float) {
            stop()
            type = t; subtitle = sub; peak = p
            animator = ValueAnimator.ofInt(0, PHASES).apply {
                duration = PHASES * 400L
                interpolator = LinearInterpolator()
                addUpdateListener { val ph = it.animatedValue as Int; if (ph != phase) { phase = ph; invalidate() } }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) { phase = -1; invalidate() }
                })
                start()
            }
        }

        fun stop() { animator?.cancel(); animator = null }

        private val colorA get() = if (type == HazardType.POLICE) RED else AMBER
        private val colorB get() = if (type == HazardType.POLICE) BLUE else AMBER

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val stroke = 12 * density
            // Мигалка: ліва й права половини. Під червоною рамкою — синє поле, під синьою — червоне,
            // і кожні 0,4 с усе міняється місцями (не частіше 3 разів на секунду — безпечно для очей).
            val flashing = phase in 0 until PHASES
            val swap = flashing && phase % 2 == 1
            val leftFrame = if (swap) colorB else colorA
            val rightFrame = if (swap) colorA else colorB
            if (flashing) {
                val a = (peak * 255).toInt()
                if (type == HazardType.POLICE) {
                    paint.color = rightFrame; paint.alpha = a; canvas.drawRect(0f, 0f, w / 2, h, paint)
                    paint.color = leftFrame; paint.alpha = a; canvas.drawRect(w / 2, 0f, w, h, paint)
                } else if (!swap) {
                    paint.color = AMBER; paint.alpha = a; canvas.drawRect(0f, 0f, w, h, paint)
                }
            }
            // Рамка: кожна половина свого кольору (для аварії — жовта/біла по черзі).
            paint.alpha = 235
            val lf = if (type == HazardType.POLICE) leftFrame else if (swap) Color.WHITE else AMBER
            val rf = if (type == HazardType.POLICE) rightFrame else if (swap) Color.WHITE else AMBER
            paint.color = lf
            canvas.drawRect(0f, 0f, stroke, h, paint); canvas.drawRect(0f, 0f, w / 2, stroke, paint)
            canvas.drawRect(0f, h - stroke, w / 2, h, paint)
            paint.color = rf
            canvas.drawRect(w - stroke, 0f, w, h, paint); canvas.drawRect(w / 2, 0f, w, stroke, paint)
            canvas.drawRect(w / 2, h - stroke, w, h, paint)

            // Плашка на третині висоти — не закриває підказку повороту Waze угорі й кнопки внизу.
            val title = "🚨 ${type.title}"
            val pad = 16 * density
            val tw = maxOf(titlePaint.measureText(title), subPaint.measureText(subtitle)) + 2 * pad
            val ph = if (subtitle.isEmpty()) 48 * density else 72 * density
            val top = h * 0.30f
            rect.set((w - tw) / 2, top, (w + tw) / 2, top + ph)
            pill.color = if (type == HazardType.POLICE) Color.parseColor("#0D2A6B") else AMBER
            pill.alpha = 240
            canvas.drawRoundRect(rect, 20 * density, 20 * density, pill)
            val tc = if (type == HazardType.POLICE) Color.WHITE else Color.BLACK
            titlePaint.color = tc; subPaint.color = tc
            canvas.drawText(title, rect.centerX() - titlePaint.measureText(title) / 2, rect.top + 32 * density, titlePaint)
            if (subtitle.isNotEmpty()) canvas.drawText(subtitle, rect.centerX() - subPaint.measureText(subtitle) / 2, rect.top + 58 * density, subPaint)
        }

        companion object {
            /** 12 перемикань × 0,4 с ≈ 5 секунд мигалки. */
            const val PHASES = 12
            val RED = Color.parseColor("#E53935")
            val BLUE = Color.parseColor("#1E64FF")
            val AMBER = Color.parseColor("#FFB300")
        }
    }

    private class SignalView(ctx: Context) : View(ctx) {
        private val density = ctx.resources.displayMetrics.density
        private var color = Color.GRAY
        private var title = ""
        private var subtitle = ""
        private var darkText = false
        var fillAlpha = 0f
        var frameAlpha = 1f
        private var animator: ValueAnimator? = null

        private val fill = Paint()
        private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 22 * density
        }
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14 * density }
        private val rect = RectF()

        fun set(c: Int, t: String, s: String, dark: Boolean) {
            color = c; title = t; subtitle = s; darkText = dark
            invalidate()
        }

        /** 3 спалахи за ~1,6 с, потім лишається рамка. У режимі «рамка» — пульсує тільки рамка. */
        fun flash(mode: FlashMode, peak: Float) {
            stop()
            frameAlpha = 1f
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1_600
                interpolator = LinearInterpolator()
                addUpdateListener {
                    val f = it.animatedFraction
                    // Три «горби»: 0 → пік → низ → пік → низ → пік → 0
                    val wave = kotlin.math.abs(kotlin.math.sin(f * Math.PI * 3)).toFloat()
                    if (mode == FlashMode.FULL) {
                        fillAlpha = peak * wave
                    } else {
                        fillAlpha = 0f
                        frameAlpha = 0.35f + 0.65f * wave
                    }
                    invalidate()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        fillAlpha = 0f; frameAlpha = 1f; invalidate()
                    }
                })
                start()
            }
        }

        fun stop() { animator?.cancel(); animator = null }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (fillAlpha > 0.01f) {
                fill.color = color; fill.alpha = (fillAlpha * 255).toInt()
                canvas.drawRect(0f, 0f, w, h, fill)
            }
            val stroke = 10 * density
            frame.color = color; frame.alpha = (frameAlpha * 230).toInt(); frame.strokeWidth = stroke
            canvas.drawRect(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2, frame)

            // Плашка з текстом угорі (під рядком стану).
            val pad = 14 * density
            val maxW = w - 48 * density
            val sub = ellipsize(subtitle, subPaint, maxW - 2 * pad)
            val tw = maxOf(titlePaint.measureText(title), subPaint.measureText(sub)) + 2 * pad
            val ph = if (sub.isEmpty()) 44 * density else 66 * density
            val top = 34 * density
            rect.set((w - tw) / 2, top, (w + tw) / 2, top + ph)
            pill.color = color; pill.alpha = 235
            canvas.drawRoundRect(rect, 18 * density, 18 * density, pill)
            val tc = if (darkText) Color.BLACK else Color.WHITE
            titlePaint.color = tc; subPaint.color = tc
            canvas.drawText(title, rect.centerX() - titlePaint.measureText(title) / 2, rect.top + 30 * density, titlePaint)
            if (sub.isNotEmpty()) canvas.drawText(sub, rect.centerX() - subPaint.measureText(sub) / 2, rect.top + 54 * density, subPaint)
        }

        private fun ellipsize(s: String, p: Paint, max: Float): String {
            if (p.measureText(s) <= max) return s
            var e = s
            while (e.isNotEmpty() && p.measureText("$e…") > max) e = e.dropLast(1)
            return "$e…"
        }
    }
}
