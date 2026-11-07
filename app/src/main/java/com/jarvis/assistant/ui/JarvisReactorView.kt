package com.jarvis.assistant.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.jarvis.assistant.speech.VoiceInteractionManager.Mode
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Iron Man Arc Reactor 3.0 & Holographic AI Avatar Canvas View.
 *
 * Features:
 * 1. Corner HUD brackets & top-left system info.
 * 2. Outer orbital ring with 4 glowing satellite node spheres.
 * 3. 72-tick mark concentric indexing track.
 * 4. 16 Metallic segmented blocks with inset glowing cyan neon lenses.
 * 5. Concentric inner cyan energy rings with radial glow.
 * 6. Central Core with "J.A.R.V.I.S." header, vector microphone, "(( 🎙️ ))" sound waves,
 *    and state text ("SYSTEM ONLINE", "LISTENING...", "PROCESSING...").
 * 7. Holographic AI Robot Avatar mode (Screen 3).
 * 8. Live bottom mic active sine wave visualizer.
 */
class JarvisReactorView : View {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    enum class State {
        IDLE, LISTENING, PROCESSING, SPEAKING, SUCCESS, ERROR
    }

    private var state = State.IDLE
    private var audioRms: Float = 0f // 0.0 to 1.0
    var showRobotAvatar = false

    // Angles & Animations
    private var outerNodeAngle = 0f
    private var metallicRingAngle = 0f
    private var scannerAngle = 0f
    private var wavePhase = 0f
    private var pulseRatio = 1.0f

    // Colors matching the reference image (Sky Blue Holographic)
    private val cyanColor = Color.parseColor("#00E5FF")
    private val cyanBrightColor = Color.parseColor("#18FFFF")
    private val skyBlueColor = Color.parseColor("#4DD0E1")
    private val metallicDarkColor = Color.parseColor("#1A293D")
    private val metallicSilverColor = Color.parseColor("#4A6B8F")
    private val coreBgColor = Color.parseColor("#040A14")
    private val greenColor = Color.parseColor("#4DD0E1") // Holographic Sky Blue replacement
    private val redColor = Color.parseColor("#FF5252")
    private val magentaColor = Color.parseColor("#E040FB")
    private val hudTextColor = Color.parseColor("#80D8FF")

    // Paints
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudTextColor
        textSize = 22f
        isFakeBoldText = true
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        strokeCap = Paint.Cap.ROUND
    }

    // Rectangles & Paths
    private val rectMetallicOuter = RectF()
    private val rectMetallicInner = RectF()
    private val rectLensOuter = RectF()
    private val rectLensInner = RectF()
    private val rectInnerRings = RectF()
    private val rectCore = RectF()
    private val wavePath = Path()
    private val robotPath = Path()

    // Animation Loop
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            updateAnimation()
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

    private fun updateAnimation() {
        val speed = when (state) {
            State.PROCESSING -> 3.0f
            State.LISTENING -> 1.8f
            State.SPEAKING -> 2.0f
            else -> 1.0f
        }

        outerNodeAngle = (outerNodeAngle + 0.4f * speed) % 360f
        metallicRingAngle = (metallicRingAngle - 0.6f * speed) % 360f
        scannerAngle = (scannerAngle + 2.0f * speed) % 360f
        wavePhase = (wavePhase + 0.12f * speed) % (2f * Math.PI.toFloat())

        pulseRatio = if (state == State.LISTENING || state == State.SPEAKING) {
            0.94f + 0.12f * sin(System.currentTimeMillis() / 140.0).toFloat() + audioRms * 0.2f
        } else {
            0.97f + 0.05f * sin(System.currentTimeMillis() / 500.0).toFloat()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f * 0.88f

        val activeColor = when (state) {
            State.ERROR -> redColor
            State.SUCCESS -> greenColor
            State.SPEAKING -> magentaColor
            else -> cyanColor
        }

        // 1. HUD Brackets & System Info
        drawHudElements(canvas, radius)

        // 2. Central Glow Background
        drawCentralGlow(canvas, cx, cy, radius, activeColor)

        if (showRobotAvatar) {
            // Draw Holographic Robot AI Face (Screen 3)
            drawInnerEnergyRings(canvas, cx, cy, radius, activeColor)
            drawHolographicRobotFace(canvas, cx, cy, radius * 0.55f, activeColor)
        } else {
            // Draw Standard Iron Man Arc Reactor 3.0
            drawCrosshairs(canvas, cx, cy, radius, activeColor)
            drawOuterOrbitNodes(canvas, cx, cy, radius, activeColor)
            drawTickMarkTrack(canvas, cx, cy, radius, activeColor)
            drawMetallicSegmentedRing(canvas, cx, cy, radius, activeColor)
            drawInnerEnergyRings(canvas, cx, cy, radius, activeColor)
            drawCentralCore(canvas, cx, cy, radius, activeColor)
        }

        // Live Mic Active Sine Wave Visualizer
        drawBottomWaveform(canvas, cx, cy, radius, activeColor)
    }

    private fun drawHudElements(canvas: Canvas, radius: Float) {
        val bracketSize = 24f
        val margin = 16f
        linePaint.color = Color.argb(120, Color.red(cyanColor), Color.green(cyanColor), Color.blue(cyanColor))
        linePaint.strokeWidth = 3f

        canvas.drawLine(margin, margin + bracketSize, margin, margin, linePaint)
        canvas.drawLine(margin, margin, margin + bracketSize, margin, linePaint)

        val h = height.toFloat()
        val w = width.toFloat()
        canvas.drawLine(margin, h - margin - bracketSize, margin, h - margin, linePaint)
        canvas.drawLine(margin, h - margin, margin + bracketSize, h - margin, linePaint)

        canvas.drawLine(w - margin - bracketSize, h - margin, w - margin, h - margin, linePaint)
        canvas.drawLine(w - margin, h - margin, w - margin, h - margin - bracketSize, linePaint)

        hudTextPaint.textSize = radius * 0.065f
        canvas.drawText("CORE v3.0", margin + 8f, margin + radius * 0.12f, hudTextPaint)
        canvas.drawText("CONTEXT AWARE", margin + 8f, margin + radius * 0.20f, hudTextPaint)
        canvas.drawText("WINDOWS + ANDROID", margin + 8f, margin + radius * 0.28f, hudTextPaint)
    }

    private fun drawCentralGlow(canvas: Canvas, cx: Float, cy: Float, radius: Float, activeColor: Int) {
        val glowRadius = radius * pulseRatio * 0.95f
        fillPaint.shader = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(
                Color.argb((0.35f * 255).toInt(), Color.red(activeColor), Color.green(activeColor), Color.blue(activeColor)),
                Color.argb((0.10f * 255).toInt(), Color.red(activeColor), Color.green(activeColor), Color.blue(activeColor)),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowRadius, fillPaint)
    }

    private fun drawCrosshairs(canvas: Canvas, cx: Float, cy: Float, radius: Float, activeColor: Int) {
        linePaint.shader = null
        linePaint.color = Color.argb(70, Color.red(activeColor), Color.green(activeColor), Color.blue(activeColor))
        linePaint.strokeWidth = 2f

        canvas.drawLine(cx - radius * 1.18f, cy, cx - radius * 0.82f, cy, linePaint)
        canvas.drawLine(cx + radius * 0.82f, cy, cx + radius * 1.18f, cy, linePaint)
        canvas.drawLine(cx, cy - radius * 1.18f, cx, cy - radius * 0.82f, linePaint)
        canvas.drawLine(cx, cy + radius * 0.82f, cx, cy + radius * 1.18f, linePaint)
    }

    private fun drawOuterOrbitNodes(canvas: Canvas, cx: Float, cy: Float, radius: Float, activeColor: Int) {
        val orbitR = radius * 0.98f

        linePaint.shader = null
        linePaint.color = Color.argb(100, Color.red(activeColor), Color.green(activeColor), Color.blue(activeColor))
        linePaint.strokeWidth = 2.5f
        canvas.drawCircle(cx, cy, orbitR, linePaint)

        val nodeAngles = floatArrayOf(0f, 90f, 180f, 270f)
        for (a in nodeAngles) {
            val angleRad = Math.toRadians((a + outerNodeAngle).toDouble())
            val nx = cx + (orbitR * cos(angleRad)).toFloat()
            val ny = cy + (orbitR * sin(angleRad)).toFloat()

            fillPaint.shader = null
            fillPaint.color = activeColor
            fillPaint.alpha = 255
            canvas.drawCircle(nx, ny, 7.5f, fillPaint)

            linePaint.color = Color.WHITE
            linePaint.strokeWidth = 2f
            canvas.drawCircle(nx, ny, 11f, linePaint)
        }
    }

    private fun drawTickMarkTrack(canvas: Canvas, cx: Float, cy: Float, radius: Float, activeColor: Int) {
        val trackR = radius * 0.90f
        val ticks = 72
        linePaint.strokeWidth = 2f

        for (i in 0 until ticks) {
            val angleRad = Math.toRadians((i * (360f / ticks)).toDouble())
            val isMajor = i % 6 == 0
            val tickLen = if (isMajor) radius * 0.05f else radius * 0.025f

            val x1 = cx + ((trackR - tickLen) * cos(angleRad)).toFloat()
            val y1 = cy + ((trackR - tickLen) * sin(angleRad)).toFloat()
            val x2 = cx + (trackR * cos(angleRad)).toFloat()
            val y2 = cy + (trackR * sin(angleRad)).toFloat()

            linePaint.color = if (isMajor) activeColor else Color.argb(90, Color.red(activeColor), Color.green(activeColor), Color.blue(activeColor))
            canvas.drawLine(x1, y1, x2, y2, linePaint)
        }
    }

    private fun drawMetallicSegmentedRing(canvas: Canvas, cx: Float, cy: Float, radius: Float, activeColor: Int) {
        val outerR = radius * 0.82f
        val innerR = radius * 0.65f
        val segments = 16
        val sweepAngle = (360f / segments) * 0.74f
        val gapAngle = (360f / segments) * 0.26f

        rectMetallicOuter.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR)
        rectMetallicInner.set(cx - innerR, cy - innerR, cx + innerR, cy + innerR)

        val lensOuterR = outerR - (outerR - innerR) * 0.22f
        val lensInnerR = innerR + (outerR - innerR) * 0.22f
        rectLensOuter.set(cx - lensOuterR, cy - lensOuterR, cx + lensOuterR, cy + lensOuterR)
        rectLensInner.set(cx - lensInnerR, cy - lensInnerR, cx + lensInnerR, cy + lensInnerR)

        for (i in 0 until segments) {
            val startAngle = i * (sweepAngle + gapAngle) + metallicRingAngle

            fillPaint.shader = LinearGradient(
                cx, cy - outerR, cx, cy + outerR,
                metallicSilverColor, metallicDarkColor, Shader.TileMode.CLAMP
            )
            fillPaint.alpha = 240

            val segmentPath = Path().apply {
                arcTo(rectMetallicOuter, startAngle, sweepAngle, true)
                arcTo(rectMetallicInner, startAngle + sweepAngle, -sweepAngle, false)
                close()
            }
            canvas.drawPath(segmentPath, fillPaint)

            linePaint.shader = null
            linePaint.color = Color.argb(180, 80, 130, 180)
            linePaint.strokeWidth = 2f
            canvas.drawPath(segmentPath, linePaint)

            fillPaint.shader = null
            fillPaint.color = activeColor
            fillPaint.alpha = if (i % 2 == 0 || state == State.LISTENING || state == State.PROCESSING) 255 else 180

            val lensSweep = sweepAngle * 0.65f
            val lensStart = startAngle + (sweepAngle - lensSweep) / 2f
            val lensPath = Path().apply {
                arcTo(rectLensOuter, lensStart, lensSweep, true)
                arcTo(rectLensInner, lensStart + lensSweep, -lensSweep, false)
                close()
            }
            canvas.drawPath(lensPath, fillPaint)

            linePaint.color = Color.WHITE
            linePaint.strokeWidth = 1.5f
            canvas.drawPath(lensPath, linePaint)
        }
    }

    private fun drawInnerEnergyRings(canvas: Canvas, cx: Float, cy: Float, radius: Float, activeColor: Int) {
        val ring1 = radius * 0.60f
        val ring2 = radius * 0.54f

        linePaint.shader = null
        linePaint.color = activeColor
        linePaint.strokeWidth = 4f
        linePaint.alpha = 240
        canvas.drawCircle(cx, cy, ring1, linePaint)

        linePaint.color = cyanBrightColor
        linePaint.strokeWidth = 2.5f
        linePaint.alpha = 200
        canvas.drawCircle(cx, cy, ring2, linePaint)

        if (state == State.PROCESSING || state == State.LISTENING) {
            rectInnerRings.set(cx - ring1, cy - ring1, cx + ring1, cy + ring1)
            fillPaint.shader = SweepGradient(
                cx, cy,
                intArrayOf(Color.TRANSPARENT, Color.argb(90, Color.red(activeColor), Color.green(activeColor), Color.blue(activeColor))),
                floatArrayOf(0.7f, 1.0f)
            )
            canvas.drawCircle(cx, cy, ring1, fillPaint)
        }
    }

    private fun drawCentralCore(canvas: Canvas, cx: Float, cy: Float, radius: Float, activeColor: Int) {
        val coreR = radius * 0.48f * pulseRatio

        fillPaint.shader = null
        fillPaint.color = coreBgColor
        fillPaint.alpha = 250
        canvas.drawCircle(cx, cy, coreR, fillPaint)

        linePaint.shader = null
        linePaint.color = activeColor
        linePaint.strokeWidth = 4f
        linePaint.alpha = 255
        canvas.drawCircle(cx, cy, coreR, linePaint)

        rectCore.set(cx - coreR, cy - coreR, cx + coreR, cy + coreR)

        textPaint.color = Color.WHITE
        textPaint.textSize = coreR * 0.40f
        textPaint.letterSpacing = 0.30f
        canvas.drawText("J.A.R.V.I.S.", cx, cy - coreR * 0.40f, textPaint)

        val micH = coreR * 0.40f
        drawVectorMicWithWaves(canvas, cx, cy, micH, activeColor)

        val statusStr = when (state) {
            State.LISTENING -> "LISTENING..."
            State.PROCESSING -> "PROCESSING..."
            State.SPEAKING -> "SPEAKING..."
            State.ERROR -> "SYSTEM ERROR"
            State.SUCCESS -> "TASK COMPLETE"
            State.IDLE -> "SYSTEM ONLINE"
        }
        textPaint.color = activeColor
        textPaint.textSize = coreR * 0.20f
        textPaint.letterSpacing = 0.15f
        canvas.drawText(statusStr, cx, cy + coreR * 0.64f, textPaint)
    }

    private fun drawVectorMicWithWaves(canvas: Canvas, cx: Float, cy: Float, micHeight: Float, activeColor: Int) {
        val w = micHeight * 0.32f
        val h = micHeight * 0.52f

        linePaint.color = activeColor
        linePaint.strokeWidth = 4f
        fillPaint.color = activeColor

        rectLensOuter.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f - w / 2f)
        canvas.drawRoundRect(rectLensOuter, w / 2f, w / 2f, linePaint)

        if (state == State.LISTENING || state == State.SPEAKING) {
            fillPaint.alpha = (120 + audioRms * 135).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(rectLensOuter, w / 2f, w / 2f, fillPaint)
        }

        rectLensOuter.set(cx - w * 0.85f, cy - h * 0.15f, cx + w * 0.85f, cy + h * 0.42f)
        canvas.drawArc(rectLensOuter, 0f, 180f, false, linePaint)

        canvas.drawLine(cx, cy + h * 0.42f, cx, cy + h * 0.70f, linePaint)
        canvas.drawLine(cx - w * 0.6f, cy + h * 0.70f, cx + w * 0.6f, cy + h * 0.70f, linePaint)

        linePaint.strokeWidth = 3.5f
        val waveR1 = w * 1.5f
        val waveR2 = w * 2.2f

        rectLensOuter.set(cx - waveR1, cy - waveR1, cx + waveR1, cy + waveR1)
        canvas.drawArc(rectLensOuter, 135f, 90f, false, linePaint)
        canvas.drawArc(rectLensOuter, -45f, 90f, false, linePaint)

        rectLensOuter.set(cx - waveR2, cy - waveR2, cx + waveR2, cy + waveR2)
        canvas.drawArc(rectLensOuter, 140f, 80f, false, linePaint)
        canvas.drawArc(rectLensOuter, -40f, 80f, false, linePaint)
    }

    private fun drawHolographicRobotFace(canvas: Canvas, cx: Float, cy: Float, size: Float, activeColor: Int) {
        linePaint.color = activeColor
        linePaint.strokeWidth = 3.5f
        fillPaint.color = activeColor

        robotPath.reset()
        // Helmet Head Outline
        robotPath.moveTo(cx - size * 0.5f, cy - size * 0.3f)
        robotPath.lineTo(cx - size * 0.3f, cy - size * 0.7f)
        robotPath.lineTo(cx + size * 0.3f, cy - size * 0.7f)
        robotPath.lineTo(cx + size * 0.5f, cy - size * 0.3f)
        robotPath.lineTo(cx + size * 0.45f, cy + size * 0.3f)
        robotPath.lineTo(cx + size * 0.25f, cy + size * 0.7f)
        robotPath.lineTo(cx - size * 0.25f, cy + size * 0.7f)
        robotPath.lineTo(cx - size * 0.45f, cy + size * 0.3f)
        robotPath.close()

        canvas.drawPath(robotPath, linePaint)

        // Glowing Robot Eyes
        val eyeWidth = size * 0.25f
        val eyeHeight = size * 0.10f
        rectLensOuter.set(cx - size * 0.35f, cy - size * 0.15f, cx - size * 0.10f, cy - size * 0.05f)
        rectLensInner.set(cx + size * 0.10f, cy - size * 0.15f, cx + size * 0.35f, cy - size * 0.05f)

        fillPaint.alpha = 255
        canvas.drawRoundRect(rectLensOuter, 4f, 4f, fillPaint)
        canvas.drawRoundRect(rectLensInner, 4f, 4f, fillPaint)

        // Cheek & Mouth Circuit Lines
        canvas.drawLine(cx - size * 0.2f, cy + size * 0.25f, cx + size * 0.2f, cy + size * 0.25f, linePaint)
        canvas.drawLine(cx - size * 0.15f, cy + size * 0.40f, cx + size * 0.15f, cy + size * 0.40f, linePaint)
    }

    private fun drawBottomWaveform(canvas: Canvas, cx: Float, cy: Float, radius: Float, activeColor: Int) {
        val bottomY = cy + radius * 1.18f
        val waveWidth = radius * 1.5f
        val startX = cx - waveWidth / 2f

        wavePath.reset()
        wavePath.moveTo(startX, bottomY)

        val points = 50
        val amp = if (state == State.LISTENING || state == State.SPEAKING) 12f + audioRms * 28f else 4f
        for (i in 0..points) {
            val x = startX + (i.toFloat() / points) * waveWidth
            val y = bottomY + amp * sin(i * 0.35f + wavePhase)
            if (i == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
        }

        wavePaint.color = activeColor
        canvas.drawPath(wavePath, wavePaint)

        hudTextPaint.textSize = radius * 0.065f
        hudTextPaint.color = Color.parseColor("#5A8CB8")
        hudTextPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("MIC ACTIVE", cx, bottomY + 32f, hudTextPaint)
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
