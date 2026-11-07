package com.jarvis.assistant.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.random.Random

/**
 * Animated subtle background starfield & energy particles for J.A.R.V.I.S. Holographic UI.
 */
class ParticleBackgroundView : View {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    private class Particle(
        var x: Float,
        var y: Float,
        var radius: Float,
        var speedY: Float,
        var alpha: Int,
        var color: Int
    )

    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var particles = emptyList<Particle>()

    private val cyanColor = Color.parseColor("#00E5FF")
    private val blueColor = Color.parseColor("#1E88E5")
    private val magentaColor = Color.parseColor("#E040FB")

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            updateParticles()
            invalidate()
        }
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val colors = intArrayOf(cyanColor, blueColor, magentaColor)
        particles = List(35) {
            Particle(
                x = Random.nextFloat() * w,
                y = Random.nextFloat() * h,
                radius = 1.5f + Random.nextFloat() * 2.5f,
                speedY = -(0.2f + Random.nextFloat() * 0.6f),
                alpha = 40 + Random.nextInt(120),
                color = colors[Random.nextInt(colors.size)]
            )
        }
        if (!animator.isRunning) animator.start()
    }

    private fun updateParticles() {
        val h = height.toFloat()
        val w = width.toFloat()
        if (h <= 0 || w <= 0) return

        particles.forEach { p ->
            p.y += p.speedY
            if (p.y < 0) {
                p.y = h
                p.x = Random.nextFloat() * w
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        particles.forEach { p ->
            particlePaint.color = p.color
            particlePaint.alpha = p.alpha
            canvas.drawCircle(p.x, p.y, p.radius, particlePaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (width > 0 && !animator.isRunning) animator.start()
    }
}
