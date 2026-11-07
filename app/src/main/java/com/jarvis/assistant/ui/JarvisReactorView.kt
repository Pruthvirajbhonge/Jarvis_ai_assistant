package com.jarvis.assistant.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.jarvis.assistant.speech.VoiceInteractionManager.Mode
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Advanced Holographic J.A.R.V.I.S. Arc Reactor Canvas View 2.0.
 *
 * Features:
 * 1. Outer orbital ring with rotating tick marks & orbital nodes.
 * 2. Segmented 3D metallic ring with dynamic rotation.
 * 3. Inner circular scanner with rotating laser radar arcs.
 * 4. Animated pulsing cyan/magenta energy rings.
 * 5. Central metallic microphone icon drawn natively on Canvas.
 * 6. Floating orbital particle engine.
 * 7. Radial light crosshairs & target reticles.
 * 8. Audio-reactive visualizer radiating around the core.
 * 9. Full state-based animation engine (IDLE, LISTENING, PROCESSING, SPEAKING, SUCCESS, ERROR).
 */
class JarvisReactorView : View {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    enum class State {
        IDLE, LISTENING, PROCESSING, SPEAKING, SUCCESS, ERROR
    }

    private var state = State.IDLE
    private var audioRms: Float = 0f // Normalized 0.0 to 1.0

    // Animation angles and progression values
    private var outerRingAngle = 0f
    private var metallicRingAngle = 0f
    private var scannerAngle = 0f
    private var pulseRadiusRatio = 1f
    private var rippleProgress = 0f

    // Colors
    private val cyanColor = Color.parseColor("#00E5FF")
    private val magentaColor = Color.parseColor("#E040FB")
    private val greenColor = Color.parseColor("#00E676")
    private val redColor = Color.parseColor("#FF5252")
    private val darkNavyColor = Color.parseColor("#060D1E")
    private val metallicBaseColor = Color.parseColor("#1E3A5E")
    private val metallicHighlightColor = Color.parseColor("#4070A0")

    // Pre-allocated Paints (NO allocations inside onDraw)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val metallicSegmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val metallicBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val scannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val waveformPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
    }
    private val micPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }
    private val micFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // Pre-allocated RectF & Paths
    private val rectOuter = RectF()
    private val rectMetallicOuter = RectF()
    private val rectMetallicInner = RectF()
    private val rectInner = RectF()
    private val rectCenterCore = RectF()

    // Floating Particles
    private class OrbitalParticle(
        var angle: Float,
        var distanceRatio: Float,
        var speed: Float,
        var radius: Float,
        var alpha: Int
    )

    private val particles = List(18) {
        OrbitalParticle(
            angle = Random.nextFloat() * 360f,
            distanceRatio = 0.42f + Random.nextFloat() * 0.48f,
            speed = (0.3f + Random.nextFloat() * 0.7f) * if (Random.nextBoolean()) 1f else -1f,
            radius = 2.5f + Random.nextFloat() * 3.5f,
            alpha = (100 + Random.nextInt(155))
        )
    }

    // Main Animation Loop
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            updateAnimationState()
            invalidate()
        }
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        animator.start()
    }

    fun setReactorState(newState: State) {
        if (state != newState) {
            state = newState
            invalidate()
        }
    }

    fun setUiMode(mode: Mode, busy: Boolean, errorMsg: String? = null) {
        val targetState = when {
            !errorMsg.isNullOrBlank() -> State.ERROR
            busy -> State.PROCESSING
            mode == Mode.COMMAND -> State.LISTENING
            else -> State.IDLE
        }
        setReactorState(targetState)
    }

    fun setAudioRms(rms: Float) {
        audioRms = audioRms * 0.6f + (rms.coerceIn(0f, 1f)) * 0.4f
        invalidate()
    }

    private fun updateAnimationState() {
        val speedMultiplier = when (state) {
            State.PROCESSING -> 3.5f
            State.LISTENING -> 1.8f
            State.SPEAKING -> 2.0f
            else -> 1.0f
        }

        outerRingAngle = (outerRingAngle + 0.5f * speedMultiplier) % 360f
        metallicRingAngle = (metallicRingAngle - 0.8f * speedMultiplier) % 360f
        scannerAngle = (scannerAngle + 2.5f * speedMultiplier) % 360f

        rippleProgress = (rippleProgress + 0.02f * speedMultiplier) % 1.0f

        val pulseFactor = if (state == State.LISTENING || state == State.SPEAKING) {
            0.92f + 0.15f * sin(System.currentTimeMillis() / 150.0).toFloat() + audioRms * 0.25f
        } else {
            0.96f + 0.06f * sin(System.currentTimeMillis() / 600.0).toFloat()
        }
        pulseRadiusRatio = pulseFactor

        particles.forEach { p ->
            p.angle = (p.angle + p.speed * speedMultiplier) % 360f
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        val maxRadius = min(w, h) / 2f * 0.92f

        rectOuter.set(cx - maxRadius, cy - maxRadius, cx + maxRadius, cy + maxRadius)
        rectMetallicOuter.set(cx - maxRadius * 0.84f, cy - maxRadius * 0.84f, cx + maxRadius * 0.84f, cy + maxRadius * 0.84f)
        rectMetallicInner.set(cx - maxRadius * 0.68f, cy - maxRadius * 0.68f, cx + maxRadius * 0.68f, cy + maxRadius * 0.68f)
        rectInner.set(cx - maxRadius * 0.55f, cy - maxRadius * 0.55f, cx + maxRadius * 0.55f, cy + maxRadius * 0.55f)
        rectCenterCore.set(cx - maxRadius * 0.38f, cy - maxRadius * 0.38f, cx + maxRadius * 0.38f, cy + maxRadius * 0.38f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = min(width, height) / 2f * 0.90f

        val activePrimaryColor = when (state) {
            State.ERROR -> redColor
            State.SUCCESS -> greenColor
            State.SPEAKING -> magentaColor
            else -> cyanColor
        }

        drawCentralGlow(canvas, cx, cy, baseRadius, activePrimaryColor)
        drawCrosshairs(canvas, cx, cy, baseRadius, activePrimaryColor)
        drawOrbitalParticles(canvas, cx, cy, baseRadius, activePrimaryColor)
        drawAudioVisualizer(canvas, cx, cy, baseRadius, activePrimaryColor)
        drawOuterOrbitalRing(canvas, cx, cy, baseRadius, activePrimaryColor)
        drawMetallicSegmentedRing(canvas, cx, cy, baseRadius, activePrimaryColor)
        drawInnerScanner(canvas, cx, cy, baseRadius, activePrimaryColor)
        drawEnergyRipples(canvas, cx, cy, baseRadius, activePrimaryColor)
        drawCentralCore(canvas, cx, cy, baseRadius, activePrimaryColor)
    }

    private fun drawCentralGlow(canvas: Canvas, cx: Float, cy: Float, radius: Float, activeColor: Int) {
        val glowRadius = radius * pulseRadiusRatio
        val fillAlpha = when (state) {
            State.LISTENING -> 0.28f + audioRms * 0.3f
            State.PROCESSING -> 0.35f
            State.SPEAKING -> 0.30f
            State.ERROR -> 0.35f
            else -> 0.15f
        }
        fillGlowPaint.shader = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(
                Color.argb((fillAlpha * 255).toInt(), Color.red(activeColor), Color.green(activeColor), Color.blue(activeColor)),
                Color.argb((fillAlpha * 0.4f * 255).toInt(), Color.red(activeColor), Color.green(activeColor), Color.blue(activeColor)),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowRadius, fillGlowPaint)
    }

    private fun drawCrosshairs(canvas: Canvas, cx: Float, cy: Float, radius: Float, activeColor: Int) {
        tickPaint.color = Color.argb(40, Color.red(activeColor), Color.green(activeColor), Color.blue(activeColor))
        tickPaint.strokeWidth = 1.5f

        canvas.drawLine(cx - radius * 1.12f, cy, cx - radius * 0.88f, cy, tickPaint)
        canvas.drawLine(cx + radius * 0.88f, cy, cx + radius * 1.12f, cy, tickPaint)
        canvas.drawLine(cx, cy - radius * 1.12f, cx, cy - radius * 0.88f, tickPaint)
        canvas.drawLine(cx, cy + radius * 0.88f, cx, cy + radius * 1.12f, tickPaint)

        tickPaint.color = Color.argb(70, Color.red(activeColor), Color.green(activeColor), Color.blue(activeColor))
        val dotDistance = radius * 1.02f
        val angles = floatArrayOf(45f, 135f, 225f, 315f)
        for (a in angles) {
            val rad = Math.toRadians(a.toDouble())
            val dx = cx + (dotDistance * cos(rad)).toFloat()
            val dy = cy + (dotDistance * sin(rad)).toFloat()
            canvas.drawCircle(dx, dy, 3f, tickPaint)
        }
    }

    private fun drawOrbitalParticles(canvas: Canvas, cx: Float, cy: Float, radius: Float, activeColor: Int) {
        particles.forEach { p ->
            val dist = radius * p.distanceRatio
            val rad = Math.toRadians((p.angle + outerRingAngle * 0.5f).toDouble())
            val px = cx + (dist * cos(rad)).toFloat()
            val py = cy + (dist * sin(rad)).toFloat()

            particlePaint.color = activeColor
            particlePaint.alpha = (p.alpha * (if (state == State.LISTENING) 1.2f else 0.8f)).toInt().coerceIn(0, 255)
            canvas.drawCircle(px, py, p.radius, particlePaint)
        }
    }

    private fun drawAudioVisualizer(canvas: Canvas, cx: Float, cy: Float, radius: Float, activeColor: Int) {
        val bars = 24
        val visualizerRadius = radius * 0.86f
        val baseBarLength = radius * 0.08f
        val currentRms = if (state == State.LISTENING || state == State.SPEAKING) max(audioRms, 0.15f) else 0.05f

        waveformPaint.color = activeColor
        waveformPaint.strokeWidth = 3.5f

        for (i in 0 until bars) {
            val angleDeg = (i * (360f / bars) + scannerAngle * 0.2f) % 360f
            val rad = Math.toRadians(angleDeg.toDouble())

            val randomFactor = (sin(i * 1.5 + System.currentTimeMillis() / 80.0) + 1.0) / 2.0
            val barLen = baseBarLength * (1.0f + (currentRms * 3.5f * randomFactor).toFloat())

            val x1 = cx + ((visualizerRadius) * cos(rad)).toFloat()
            val y1 = cy + ((visualizerRadius) * sin(rad)).toFloat()
            val x2 = cx + ((visualizerRadius + barLen) * cos(rad)).toFloat()
            val y2 = cy + ((visualizerRadius + barLen) * sin(rad)).toFloat()

            waveformPaint.alpha = (60 + (currentRms * 195 * randomFactor)).toInt().coerceIn(0, 255)
            canvas.drawLine(x1, y1, x2, y2, waveformPaint)
        }
    }

    private fun drawOuterOrbitalRing(canvas: Canvas, cx: Float, cy: Float, radius: Float, activeColor: Int) {
        val outerR = radius * 0.94f

        ringPaint.color = Color.argb(80, Color.red(activeColor), Color.green(activeColor), Color.blue(activeColor))
        ringPaint.strokeWidth = 2f
        canvas.drawCircle(cx, cy, outerR, ringPaint)

        val totalTicks = 36
        tickPaint.strokeWidth = 2f
        for (i in 0 until totalTicks) {
            val angleDeg = (i * (360f / totalTicks) + outerRingAngle) % 360f
            val rad = Math.toRadians(angleDeg.toDouble())

            val isMajor = i % 6 == 0
            val tickLength = if (isMajor) radius * 0.06f else radius * 0.03f
            val rInner = outerR - tickLength

            val x1 = cx + (rInner * cos(rad)).toFloat()
            val y1 = cy + (rInner * sin(rad)).toFloat()
            val x2 = cx + (outerR * cos(rad)).toFloat()
            val y2 = cy + (outerR * sin(rad)).toFloat()

            tickPaint.color = if (isMajor) activeColor else Color.argb(100, Color.red(activeColor), Color.green(activeColor), Color.blue(activeColor))
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }
    }

    private fun drawMetallicSegmentedRing(canvas: Canvas, cx: Float, cy: Float, radius: Float, activeColor: Int) {
        val outerRadius = radius * 0.82f
        val innerRadius = radius * 0.68f
        val segments = 12
        val sweepAngle = (360f / segments) * 0.72f
        val gapAngle = (360f / segments) * 0.28f

        rectMetallicOuter.set(cx - outerRadius, cy - outerRadius, cx + outerRadius, cy + outerRadius)
        rectMetallicInner.set(cx - innerRadius, cy - innerRadius, cx + innerRadius, cy + innerRadius)

        for (i in 0 until segments) {
            val startAngle = i * (sweepAngle + gapAngle) + metallicRingAngle
            val isGlowing = (i % 3 == 0 && state == State.PROCESSING) || (i % 2 == 0 && state == State.LISTENING)

            metallicSegmentPaint.shader = LinearGradient(
                cx, cy - outerRadius, cx, cy + outerRadius,
                if (isGlowing) activeColor else metallicBaseColor,
                if (isGlowing) activeColor else metallicHighlightColor,
                Shader.TileMode.CLAMP
            )
            metallicSegmentPaint.alpha = if (isGlowing) 230 else 180

            val path = android.graphics.Path().apply {
                arcTo(rectMetallicOuter, startAngle, sweepAngle, true)
                arcTo(rectMetallicInner, startAngle + sweepAngle, -sweepAngle, false)
                close()
            }
            canvas.drawPath(path, metallicSegmentPaint)

            metallicBorderPaint.color = if (isGlowing) activeColor else cyanColor
            metallicBorderPaint.alpha = if (isGlowing) 255 else 90
            metallicBorderPaint.strokeWidth = 2f
            canvas.drawPath(path, metallicBorderPaint)
        }
    }

    private fun drawInnerScanner(canvas: Canvas, cx: Float, cy: Float, radius: Float, activeColor: Int) {
        val scannerRadius = radius * 0.62f
        rectInner.set(cx - scannerRadius, cy - scannerRadius, cx + scannerRadius, cy + scannerRadius)

        glowPaint.color = activeColor
        glowPaint.strokeWidth = 3f
        glowPaint.alpha = 180
        canvas.drawArc(rectInner, scannerAngle, 120f, false, glowPaint)

        glowPaint.color = if (state == State.SPEAKING) cyanColor else magentaColor
        glowPaint.strokeWidth = 2f
        glowPaint.alpha = 140
        canvas.drawArc(rectInner, -scannerAngle * 1.3f, 80f, false, glowPaint)

        if (state == State.PROCESSING || state == State.LISTENING) {
            scannerPaint.shader = SweepGradient(
                cx, cy,
                intArrayOf(Color.TRANSPARENT, Color.argb(80, Color.red(activeColor), Color.green(activeColor), Color.blue(activeColor))),
                floatArrayOf(0.7f, 1.0f)
            )
            canvas.drawCircle(cx, cy, scannerRadius, scannerPaint)
        }
    }

    private fun drawEnergyRipples(canvas: Canvas, cx: Float, cy: Float, radius: Float, activeColor: Int) {
        if (state == State.LISTENING || state == State.SPEAKING || state == State.SUCCESS) {
            val rMax = radius * 0.95f
            val rCurrent = radius * 0.45f + (rMax - radius * 0.45f) * rippleProgress
            val alpha = ((1.0f - rippleProgress) * 200).toInt().coerceIn(0, 255)

            ringPaint.color = activeColor
            ringPaint.alpha = alpha
            ringPaint.strokeWidth = 3f * (1.0f - rippleProgress)
            canvas.drawCircle(cx, cy, rCurrent, ringPaint)
        }
    }

    private fun drawCentralCore(canvas: Canvas, cx: Float, cy: Float, radius: Float, activeColor: Int) {
        val coreRadius = radius * 0.38f * pulseRadiusRatio

        fillGlowPaint.shader = null
        fillGlowPaint.color = darkNavyColor
        fillGlowPaint.alpha = 240
        canvas.drawCircle(cx, cy, coreRadius, fillGlowPaint)

        glowPaint.shader = null
        glowPaint.color = activeColor
        glowPaint.strokeWidth = 4f
        glowPaint.alpha = 255
        canvas.drawCircle(cx, cy, coreRadius, glowPaint)

        drawMicIcon(canvas, cx, cy, coreRadius * 0.55f, activeColor)
    }

    private fun drawMicIcon(canvas: Canvas, cx: Float, cy: Float, micHeight: Float, activeColor: Int) {
        val w = micHeight * 0.42f
        val h = micHeight * 0.65f

        micPaint.color = activeColor
        micPaint.strokeWidth = 4f

        rectCenterCore.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f - w / 2f)
        canvas.drawRoundRect(rectCenterCore, w / 2f, w / 2f, micPaint)

        if (state == State.LISTENING || state == State.SPEAKING) {
            micFillPaint.color = activeColor
            micFillPaint.alpha = (100 + audioRms * 155).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(rectCenterCore, w / 2f, w / 2f, micFillPaint)
        }

        rectCenterCore.set(cx - w * 0.9f, cy - h * 0.2f, cx + w * 0.9f, cy + h * 0.45f)
        canvas.drawArc(rectCenterCore, 0f, 180f, false, micPaint)

        canvas.drawLine(cx, cy + h * 0.45f, cx, cy + h * 0.75f, micPaint)
        canvas.drawLine(cx - w * 0.6f, cy + h * 0.75f, cx + w * 0.6f, cy + h * 0.75f, micPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isRunning) animator.start()
    }
}
