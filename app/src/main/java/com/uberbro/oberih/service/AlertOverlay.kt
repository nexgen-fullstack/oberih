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

    private fun scheduleAutoHide(ms: Long) {
        handler.removeCallbacks(autoHide)
        handler.removeCallbacks(deferredHide)
        handler.postDelayed(autoHide, ms)
    }

    private fun ensureView(): SignalView {
        view?.let { return it }
        val v = SignalView(ctx)
        val lp = WindowManager.LayoutParams(
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
            title = "Oberih signal"
        }
        wm.addView(v, lp)
        view = v
        return v
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
