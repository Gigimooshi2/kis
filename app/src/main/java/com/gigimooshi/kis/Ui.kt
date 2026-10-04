package com.gigimooshi.kis

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.max

/** Colors from res/values(-night)/colors.xml, so light/dark mode follows the system. */
class Palette(ctx: Context) {
    val bg = ctx.getColor(R.color.bg)
    val surface = ctx.getColor(R.color.surface)
    val text = ctx.getColor(R.color.text)
    val text2 = ctx.getColor(R.color.text2)
    val accent = ctx.getColor(R.color.accent)
    val accentSoft = ctx.getColor(R.color.accent_soft)
    val onAccent = ctx.getColor(R.color.on_accent)
    val neg = ctx.getColor(R.color.neg)
    val negSoft = ctx.getColor(R.color.neg_soft)
    val warn = ctx.getColor(R.color.warn)
    val track = ctx.getColor(R.color.track)
    val info = ctx.getColor(R.color.info)
    val infoSoft = ctx.getColor(R.color.info_soft)
    val ripple = ctx.getColor(R.color.ripple)

    companion object {
        /** Avatar colors for merchants, picked by name hash. */
        val AVATARS = intArrayOf(
            0xFFE57373.toInt(), 0xFFF06292.toInt(), 0xFFBA68C8.toInt(), 0xFF7986CB.toInt(),
            0xFF4FA3E0.toInt(), 0xFF4DB6AC.toInt(), 0xFFE0A030.toInt(), 0xFFA1887F.toInt(),
        )
    }
}

/** Rounded progress bar with an animated fill. */
class BarView(ctx: Context) : View(ctx) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shown = 0f
    private var animator: ValueAnimator? = null

    fun setColors(trackColor: Int, fillColor: Int) {
        track.color = trackColor
        fill.color = fillColor
        invalidate()
    }

    fun setRatio(ratio: Float) {
        val target = ratio.coerceIn(0f, 1f)
        if (target == shown) return
        animator?.cancel()
        if (!isLaidOut) {
            shown = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(shown, target).apply {
            duration = 500
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                shown = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2
        canvas.drawRoundRect(0f, 0f, w, h, r, r, track)
        if (shown > 0f) canvas.drawRoundRect(0f, 0f, max(h, w * shown), h, r, r, fill)
    }
}
