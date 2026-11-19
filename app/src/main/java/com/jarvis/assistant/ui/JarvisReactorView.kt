package com.jarvis.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import androidx.core.content.res.ResourcesCompat
import com.jarvis.assistant.speech.VoiceInteractionManager.Mode
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * J.A.R.V.I.S. reactor - Android port of the PC "Command HUD v4" reactor (jarvis_hud.html + jarvis_hud.css).
 *
 * Everything is drawn in the same 400 x 400 "viewBox" units as the SVG, then scaled to the view, so the
 * artwork matches the desktop HUD: bloom + light flares + vertical beam, orbit ring with 5 energy orbs,
 * fine tick rings, the segmented metallic ring (drag it with a finger to rotate), the two data-arc rings,
 * the neon main ring, and the core (title, spinning emblem, sound waves, microphone, status label).
 *
 * Public API is unchanged: State, setReactorState, setUiMode, setAudioRms, showRobotAvatar.
 *
 * State look (same as the HUD):  idle = calm cyan, listening = cyan + fast, processing = amber,
 * speaking = cyan + breathing, error = red.  Change [idleAccentColor] to 0xFFFF5364 if you want the idle
 * state red like the PC "inactive" state.
 */
class JarvisReactorView : View {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    enum class State { IDLE, LISTENING, PROCESSING, SPEAKING, SUCCESS, ERROR }

    var showRobotAvatar = false
    var idleAccentColor: Int = Color.parseColor("#37DCFF")

    private var state = State.IDLE
    private var audioRms = 0f

    // ---- timing profile per state (values taken from the HUD css) ---------------------------------------------
    private class Profile(
        val ringOpacity: Float, val pulseSec: Float, val spinSec: Float, val flowSec: Float,
        val waveSec: Float, val micSec: Float, val arcASec: Float, val arcBSec: Float, val barSec: Float
    )

    private val pBase = Profile(.72f, 3.2f, 30f, 6f, 1.6f, 1.8f, 24f, 17f, 3.4f)
    private val pListening = Profile(.95f, 1.8f, 8f, 4f, 1.05f, 1.35f, 9f, 7f, 1.6f)
    private val pProcessing = Profile(1f, 1.25f, 3.6f, 2.8f, 1.5f, 1.5f, 24f, 17f, 1.1f)
    private val pInactive = Profile(.58f, 4f, 18f, 15f, 4f, 4f, 24f, 17f, 3.4f)

    private fun profile(): Profile = when (state) {
        State.LISTENING, State.SPEAKING -> pListening
        State.PROCESSING -> pProcessing
        State.ERROR -> pInactive
        else -> pBase
    }

    private fun targetAccent(): Int = when (state) {
        State.PROCESSING -> C_AMBER
        State.ERROR -> C_RED
        State.SUCCESS -> C_GREEN
        State.IDLE -> idleAccentColor
        else -> C_CYAN
    }

    // ---- animation state ---------------------------------------------------------------------------------------
    private var accent = C_CYAN
    private var ringOpacity = .72f
    private var voiceLevel = 0f
    private var orbitDeg = 0f
    private var ticksDeg = 0f
    private var ticks2Deg = 0f
    private var arcADeg = 0f
    private var arcBDeg = 0f
    private var spinDeg = 0f
    private var flowDeg = 0f
    private var pulseP = 0f
    private var waveP = 0f
    private var micP = 0f
    private var barP = 0f
    private var breatheP = 0f
    private var linePhase = 0f
    private var manualRingDeg = 0f

    private var lastFrameNs = 0L
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val now = System.nanoTime()
            val dt = if (lastFrameNs == 0L) 0.016f else ((now - lastFrameNs) / 1_000_000_000f).coerceIn(0f, 0.1f)
            lastFrameNs = now
            step(dt)
            invalidate()
        }
    }

    // ---- paints ------------------------------------------------------------------------------------------------
    private val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#5B8FA3") }
    private val rect = RectF()
    private val tmpPath = Path()

    private val uiFont: Typeface = run {
        val id = resources.getIdentifier("orbitron", "font", context.packageName)
        val f = if (id != 0) ResourcesCompat.getFont(context, id) else null
        f ?: Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    // ---- shaders (in viewBox units, built once) ----------------------------------------------------------------
    private val bloomShader = RadialGradient(
        200f, 200f, 262f,
        intArrayOf(argb(0f, 26, 140, 255), argb(.22f, 26, 140, 255), argb(.10f, 11, 95, 208), argb(0f, 11, 95, 208)),
        floatArrayOf(0f, .52f, .72f, 1f), Shader.TileMode.CLAMP
    )
    private val flareShader = LinearGradient(
        -300f, 0f, 8f, 0f,
        intArrayOf(argb(0f, 31, 155, 255), argb(.75f, 70, 200, 255), argb(.95f, 154, 234, 255)),
        floatArrayOf(0f, .75f, 1f), Shader.TileMode.CLAMP
    )
    private val beamShader = LinearGradient(
        0f, -70f, 0f, 470f,
        intArrayOf(
            argb(0f, 95, 214, 255), argb(.95f, 143, 232, 255), argb(.25f, 61, 184, 255),
            argb(.25f, 61, 184, 255), argb(.95f, 143, 232, 255), argb(0f, 95, 214, 255)
        ),
        floatArrayOf(0f, .12f, .22f, .78f, .88f, 1f), Shader.TileMode.CLAMP
    )
    private val orbitGradShader = LinearGradient(6f, 6f, 394f, 394f, Color.parseColor("#6FE3FF"), Color.parseColor("#1A7FE8"), Shader.TileMode.CLAMP)
    private val plateOutShader = LinearGradient(
        45f, 45f, 355f, 355f,
        intArrayOf(Color.parseColor("#B9CDD8"), Color.parseColor("#6F8796"), Color.parseColor("#2A3A46")),
        floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP
    )
    private val plateInShader = LinearGradient(355f, 45f, 45f, 355f, Color.parseColor("#5F7888"), Color.parseColor("#16232D"), Shader.TileMode.CLAMP)
    private val barFillShader = LinearGradient(
        0f, 54f, 0f, 346f,
        intArrayOf(Color.parseColor("#7FE6FF"), Color.parseColor("#27B8FF"), Color.parseColor("#0A78E0")),
        floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP
    )
    private val coreFillShader = RadialGradient(
        200f, 179.4f, 120.4f,
        intArrayOf(Color.parseColor("#06304A"), Color.parseColor("#031C2E"), Color.parseColor("#010A12")),
        floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP
    )
    private val haloBig = orbHalo(18.7f)
    private val haloSmall = orbHalo(11.6f)
    private val coreBig = orbCore(4.9f)
    private val coreSmall = orbCore(3.1f)

    private fun orbHalo(r: Float) = RadialGradient(
        0f, 0f, r,
        intArrayOf(argb(.65f, 88, 212, 255), argb(.25f, 28, 155, 255), argb(0f, 28, 155, 255)),
        floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP
    )

    private fun orbCore(r: Float) = RadialGradient(
        0f, 0f, r,
        intArrayOf(Color.WHITE, Color.parseColor("#8FE6FF"), Color.parseColor("#2AA9FF")),
        floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP
    )

    // ---- geometry (viewBox units, built once) ------------------------------------------------------------------
    private val barItems = (0 until 12).map { k -> (-90f + 30f * k) to (if (k % 3 == 0) 19f else 12.4f) }
    private val hotItems = (0 until 12).map { k -> (-90f + 30f * k) to (if (k % 3 == 0) 14.6f else 8f) }
    private val plateItems: List<Pair<Float, Float>> = (0 until 4).flatMap { q ->
        listOf((-73.35f + 90f * q) to 10.9f, (-45f + 90f * q) to 14.2f, (-16.65f + 90f * q) to 10.9f)
    }

    private val barBloomPath = sectors(barItems, 148f, 124f)
    private val barPath = sectors(barItems, 146f, 126f)
    private val barHotPath = sectors(hotItems, 139.5f, 132.5f)
    private val plateOutPath = sectors(plateItems, 151f, 135f)
    private val plateInPath = sectors(plateItems.map { it.first to it.second - 1.6f }, 134f, 119f)
    private val slitPath = sectors(plateItems.map { it.first to it.second * .48f }, 143f, 141f)
    private val slitDimPath = sectors(plateItems.map { it.first to it.second * .61f }, 126.6f, 125f)

    private val ticksPath = tickPath(179f, 216, 3f)
    private val ticks2Path = tickPath(165f, 120, 1.75f)
    private val texPath1 = dotPath(74f, 4f)
    private val texPath2 = dotPath(66f, 4f)

    private val orbitHotPath = Path().apply {
        val o = RectF(6f, 6f, 394f, 394f)
        addArc(o, 0f, 70f); addArc(o, 100f, 38f)
    }
    private val arcAPath = Path().apply {
        val o = RectF(92f, 92f, 308f, 308f) // r = 108
        val d = listOf(0f to 10f, 12f to 14.5f, 16.5f to 24.5f, 26.5f to 28f)
        for (rep in 0 until 12) for ((a, b) in d) addArc(o, rep * 30f + a, b - a)
    }
    private val arcBPath = Path().apply {
        val o = RectF(99f, 99f, 301f, 301f) // r = 101
        val d = listOf(0f to 62f, 70f to 94f, 102f to 154f, 162f to 170f)
        for (rep in 0 until 2) for ((a, b) in d) addArc(o, rep * 180f + a, b - a)
    }
    private val crossPath = Path().apply {
        moveTo(200f, 100f); lineTo(200f, 82f); moveTo(300f, 200f); lineTo(318f, 200f)
        moveTo(200f, 300f); lineTo(200f, 318f); moveTo(100f, 200f); lineTo(82f, 200f)
    }
    private val emblemPath = Path().apply {
        val o = RectF(176f, 184f, 224f, 232f) // r = 24 around (200, 208)
        for (i in 0 until 4) addArc(o, i * 90f, 81.8f)
    }
    private val waveL1 = Path().apply { moveTo(170f, 193f); quadTo(163f, 208f, 170f, 223f) }
    private val waveL2 = Path().apply { moveTo(161f, 186f); quadTo(150f, 208f, 161f, 230f) }
    private val waveR1 = Path().apply { moveTo(230f, 193f); quadTo(237f, 208f, 230f, 223f) }
    private val waveR2 = Path().apply { moveTo(239f, 186f); quadTo(250f, 208f, 239f, 230f) }
    private val micGrillPath = Path().apply {
        moveTo(197.5f, 199f); lineTo(202.5f, 199f); moveTo(197.5f, 203f); lineTo(202.5f, 203f)
        moveTo(197.5f, 207f); lineTo(202.5f, 207f)
    }
    private val micStandPath = Path().apply {
        moveTo(189f, 207f); lineTo(189f, 209f)
        arcTo(RectF(189f, 198f, 211f, 220f), 180f, -180f, false)
        lineTo(211f, 207f)
        moveTo(200f, 220f); lineTo(200f, 226f); moveTo(194f, 226f); lineTo(206f, 226f)
    }
    private val robotPath = Path()

    // orbs on the orbit (x, y, big?)
    private val orbs = listOf(
        Triple(278.60f, 22.63f, true), Triple(32.67f, 101.83f, true), Triple(380.62f, 129.21f, false),
        Triple(380.62f, 270.79f, true), Triple(51.61f, 324.96f, true)
    )

    // layout in px
    private var s = 1f
    private var cx = 0f
    private var cy = 0f
    private var radiusPx = 0f
    private val density get() = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    // ---- public api --------------------------------------------------------------------------------------------
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
        audioRms = audioRms * 0.6f + rms.coerceIn(0f, 1f) * 0.4f
    }

    // ---- animation step ----------------------------------------------------------------------------------------
    private fun step(dt: Float) {
        val p = profile()
        orbitDeg = (orbitDeg + 4f * dt) % 360f
        ticksDeg = (ticksDeg - 4f * dt) % 360f
        ticks2Deg = (ticks2Deg + 4f * dt) % 360f
        arcADeg = (arcADeg + 360f / p.arcASec * dt) % 360f
        arcBDeg = (arcBDeg - 360f / p.arcBSec * dt) % 360f
        spinDeg = (spinDeg + 360f / p.spinSec * dt) % 360f
        flowDeg = (flowDeg + 360f / p.flowSec * dt) % 360f
        pulseP = (pulseP + dt / p.pulseSec) % 1f
        waveP = (waveP + dt / p.waveSec) % 1f
        micP = (micP + dt / p.micSec) % 1f
        barP = (barP + dt / p.barSec) % 1f
        breatheP = (breatheP + dt / 1.05f) % 1f
        linePhase = (linePhase + dt * 0.4f) % 1000f

        // colour cross-fade (the CSS uses a 420 ms transition)
        val f = (dt / 0.42f).coerceIn(0f, 1f)
        accent = lerpColor(accent, targetAccent(), f)
        ringOpacity += (p.ringOpacity - ringOpacity) * f

        val target = if (state == State.LISTENING || state == State.SPEAKING) audioRms else 0f
        val k = 1f - exp(-dt * (if (target > voiceLevel) 18f else 6f))
        voiceLevel += (target - voiceLevel) * k
        if (voiceLevel < 0.002f) voiceLevel = 0f
    }

    // ---- drawing -----------------------------------------------------------------------------------------------
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val footer = dp(36f)
        radiusPx = min(w / 2f * 0.88f, (h - footer) / 2.18f).coerceAtLeast(1f)
        cx = w / 2f
        cy = radiusPx + (h - footer - 2.18f * radiusPx) / 2f
        s = radiusPx / 198f

        drawChrome(canvas)

        canvas.save()
        canvas.translate(cx - 200f * s, cy - 200f * s)
        canvas.scale(s, s)
        if (state == State.SPEAKING) {
            val b = 1.006f - .006f * cos(2f * PI.toFloat() * breatheP)
            canvas.scale(b, b, 200f, 200f)
        }

        drawBackdrop(canvas)
        drawOrbitAndTicks(canvas)
        drawSegmentedRing(canvas)
        drawInnerRings(canvas)
        drawCore(canvas)
        canvas.restore()

        drawBottomWaveform(canvas)
    }

    private fun drawBackdrop(c: Canvas) {
        fp.shader = bloomShader
        c.drawCircle(200f, 200f, 262f, fp)
        fp.shader = null

        // light flares (left set, mirrored to the right)
        for (mirror in 0..1) {
            c.save()
            if (mirror == 1) c.scale(-1f, 1f, 200f, 200f)
            stroke(Color.WHITE, 1.6f, Paint.Cap.ROUND); sp.shader = flareShader
            tmpPath.reset(); tmpPath.moveTo(-300f, 212f); tmpPath.quadTo(-60f, 198f, 8f, 201f); c.drawPath(tmpPath, sp)
            stroke(ca(Color.WHITE, .6f), .9f, Paint.Cap.ROUND); sp.shader = flareShader
            tmpPath.reset(); tmpPath.moveTo(-300f, 186f); tmpPath.quadTo(-80f, 190f, 10f, 198f); c.drawPath(tmpPath, sp)
            stroke(ca(Color.WHITE, .45f), .7f, Paint.Cap.ROUND); sp.shader = flareShader
            tmpPath.reset(); tmpPath.moveTo(-300f, 238f); tmpPath.quadTo(-90f, 214f, 12f, 204f); c.drawPath(tmpPath, sp)
            sp.shader = null
            c.restore()
        }

        fp.shader = beamShader
        c.drawRect(199.3f, -70f, 200.7f, 470f, fp)
        fp.shader = null

        // voice-level glow (HUD: --voice-glow drop shadow)
        if (voiceLevel > 0.01f) {
            stroke(ca(accent, .30f * voiceLevel), 4f + voiceLevel * 16f)
            c.drawCircle(200f, 200f, 196f, sp)
        }
    }

    private fun drawOrbitAndTicks(c: Canvas) {
        // thin tilted ellipse
        c.save(); c.rotate(-8f, 200f, 200f)
        stroke(argb(.176f, 56, 170, 255), 1f)
        rect.set(10f, 18f, 392f, 380f); c.drawOval(rect, sp)
        c.restore()

        // orbit ring + 5 energy orbs (clockwise, 90 s)
        c.save(); c.rotate(orbitDeg, 200f, 200f)
        stroke(argb(.38f, 70, 180, 255), .9f); c.drawCircle(200f, 200f, 194f, sp)
        stroke(Color.WHITE, 1.6f, Paint.Cap.ROUND); sp.shader = orbitGradShader
        c.drawPath(orbitHotPath, sp); sp.shader = null
        for ((x, y, big) in orbs) {
            c.save(); c.translate(x, y)
            fp.shader = if (big) haloBig else haloSmall
            c.drawCircle(0f, 0f, if (big) 18.7f else 11.6f, fp); fp.shader = null
            stroke(argb(.45f, 63, 196, 255), .8f); c.drawCircle(0f, 0f, if (big) 11.2f else 6.9f, sp)
            fp.color = Color.parseColor("#031A2C"); c.drawCircle(0f, 0f, if (big) 8.1f else 5.0f, fp)
            stroke(Color.parseColor("#38C7FF"), 1.3f); c.drawCircle(0f, 0f, if (big) 8.1f else 5.0f, sp)
            fp.shader = if (big) coreBig else coreSmall
            c.drawCircle(0f, 0f, if (big) 4.9f else 3.1f, fp); fp.shader = null
            c.restore()
        }
        c.restore()

        // fine tick rings
        c.save(); c.rotate(ticksDeg, 200f, 200f)
        stroke(argb(.55f, 70, 190, 255), 1.04f); c.drawPath(ticksPath, sp)
        c.restore()
        stroke(argb(.224f, 56, 170, 255), 1f); c.drawCircle(200f, 200f, 171f, sp)
        c.save(); c.rotate(ticks2Deg, 200f, 200f)
        stroke(argb(.4f, 70, 190, 255), 1.11f); c.drawPath(ticks2Path, sp)
        c.restore()
    }

    private fun drawSegmentedRing(c: Canvas) {
        c.save(); c.rotate(manualRingDeg, 200f, 200f)

        stroke(Color.parseColor("#010A11"), 40f); c.drawCircle(200f, 200f, 135f, sp)
        stroke(argb(.55f, 90, 200, 255), 1.1f)
        c.drawCircle(200f, 200f, 154f, sp); c.drawCircle(200f, 200f, 116f, sp)

        // pulsing blue glow behind the bars (HUD: blurred copy of the bars)
        val a = .725f - .225f * cos(2f * PI.toFloat() * barP)
        fp.color = argb(.85f * a * .55f, 31, 155, 255); c.drawPath(barBloomPath, fp)
        stroke(argb(.16f * a, 31, 155, 255), 6f); sp.strokeJoin = Paint.Join.ROUND; c.drawPath(barBloomPath, sp)
        stroke(argb(.09f * a, 31, 155, 255), 11f); sp.strokeJoin = Paint.Join.ROUND; c.drawPath(barBloomPath, sp)

        // metal plates
        fp.shader = plateOutShader; c.drawPath(plateOutPath, fp)
        fp.shader = plateInShader; c.drawPath(plateInPath, fp)
        fp.shader = null
        stroke(argb(.5f, 170, 220, 240), .6f); sp.strokeJoin = Paint.Join.ROUND
        c.drawPath(plateOutPath, sp); c.drawPath(plateInPath, sp)
        fp.color = argb(.85f, 95, 216, 255); c.drawPath(slitPath, fp)
        fp.color = argb(.45f, 95, 216, 255); c.drawPath(slitDimPath, fp)

        // glowing bars
        fp.shader = barFillShader; c.drawPath(barPath, fp); fp.shader = null
        stroke(argb(.75f, 156, 234, 255), .7f); sp.strokeJoin = Paint.Join.ROUND; c.drawPath(barPath, sp)
        fp.color = argb(.8f, 233, 251, 255); c.drawPath(barHotPath, fp)

        c.restore()
        sp.strokeJoin = Paint.Join.MITER
    }

    private fun drawInnerRings(c: Canvas) {
        fp.color = argb(.82f, 1, 11, 20); c.drawCircle(200f, 200f, 117f, fp)
        stroke(argb(.256f, 56, 170, 255), 1f); c.drawCircle(200f, 200f, 114f, sp)

        // data arcs: clockwise outer, counter-clockwise inner (faster while listening)
        c.save(); c.rotate(arcADeg, 200f, 200f)
        stroke(argb(.9f, 60, 200, 255), 3.2f); c.drawPath(arcAPath, sp)
        c.restore()
        c.save(); c.rotate(arcBDeg, 200f, 200f)
        stroke(Color.parseColor("#7EE2FF"), 1.3f); c.drawPath(arcBPath, sp)
        c.restore()
        stroke(argb(.6f, 120, 220, 255), 1f); c.drawPath(crossPath, sp)

        // neon main ring (follows the state colour)
        stroke(argb(.3f, 30, 130, 255), 6f); c.drawCircle(200f, 200f, 99f, sp)
        stroke(ca(accent, .22f), 15f); c.drawCircle(200f, 200f, 93f, sp)
        stroke(ca(accent, .16f), 11f); c.drawCircle(200f, 200f, 93f, sp)
        stroke(accent, 4.6f); c.drawCircle(200f, 200f, 93f, sp)
        stroke(argb(.85f, 235, 252, 255), 1.1f); c.drawCircle(200f, 200f, 93f, sp)
    }

    private fun drawCore(c: Canvas) {
        val tau = 2f * PI.toFloat()

        fp.shader = coreFillShader; c.drawCircle(200f, 200f, 86f, fp); fp.shader = null
        stroke(argb(.6f, 60, 190, 255), 1.5f); c.drawCircle(200f, 200f, 86f, sp)
        stroke(ca(accent, .35f), 1f); c.drawCircle(200f, 200f, 83f, sp)
        stroke(argb(.12f, 60, 170, 255), .8f); c.drawPath(texPath1, sp); c.drawPath(texPath2, sp)

        // pulsing glow ring (the HUD's .core-ring element)
        val pulse = 1.005f - .02f * cos(tau * pulseP)
        val rr = 93f * pulse
        stroke(ca(accent, .05f * ringOpacity), 16f); c.drawCircle(200f, 200f, rr, sp)
        stroke(ca(accent, .10f * ringOpacity), 8f); c.drawCircle(200f, 200f, rr, sp)
        stroke(ca(accent, ringOpacity), 1f); c.drawCircle(200f, 200f, rr, sp)
        stroke(ca(accent, .06f * ringOpacity), 8f); c.drawCircle(200f, 200f, rr - 6f, sp)

        if (showRobotAvatar) {
            drawRobotFace(c, 100f)
            return
        }

        // title
        tp.typeface = uiFont; tp.textSize = 13f; tp.letterSpacing = .24f
        tp.style = Paint.Style.FILL_AND_STROKE; tp.strokeWidth = 2.4f; tp.color = argb(.14f, 80, 200, 255)
        c.drawText("J.A.R.V.I.S.", 200f, 165f, tp)
        tp.style = Paint.Style.FILL; tp.color = Color.parseColor("#E6FBFF")
        c.drawText("J.A.R.V.I.S.", 200f, 165f, tp)

        // emblem ring (spins + dashes flow)
        stroke(ca(accent, .16f), 4f); c.drawCircle(200f, 208f, 24f, sp)
        c.save(); c.rotate(spinDeg + flowDeg, 200f, 208f)
        stroke(accent, 1.3f); c.drawPath(emblemPath, sp)
        c.restore()

        // sound waves
        fun waveAlpha(delay: Float) = (.75f - .25f * cos(tau * (waveP + delay)))
        stroke(ca(accent, waveAlpha(0f)), 1.8f, Paint.Cap.ROUND); c.drawPath(waveL1, sp)
        stroke(ca(accent, waveAlpha(.219f)), 1.8f, Paint.Cap.ROUND); c.drawPath(waveL2, sp)
        stroke(ca(accent, waveAlpha(.5f)), 1.8f, Paint.Cap.ROUND); c.drawPath(waveR1, sp)
        stroke(ca(accent, waveAlpha(.719f)), 1.8f, Paint.Cap.ROUND); c.drawPath(waveR2, sp)

        // microphone (pulses; fills with voice level while listening)
        val m = 1.035f - .035f * cos(tau * micP)
        c.save(); c.scale(m, m, 200f, 204f)
        rect.set(194.5f, 194f, 205.5f, 214f)
        fp.color = argb(.12f + voiceLevel * .5f, 30, 150, 220); c.drawRoundRect(rect, 5.5f, 5.5f, fp)
        stroke(ca(accent, .2f), 5f); c.drawRoundRect(rect, 5.5f, 5.5f, sp)
        stroke(accent, 1.7f); c.drawRoundRect(rect, 5.5f, 5.5f, sp)
        c.restore()
        stroke(ca(accent, .65f), 1f, Paint.Cap.ROUND); c.drawPath(micGrillPath, sp)
        stroke(accent, 1.7f, Paint.Cap.ROUND); sp.strokeJoin = Paint.Join.ROUND; c.drawPath(micStandPath, sp)
        sp.strokeJoin = Paint.Join.MITER

        // status label
        tp.typeface = uiFont; tp.textSize = 8.6f; tp.letterSpacing = .22f; tp.style = Paint.Style.FILL
        tp.color = if (state == State.IDLE) Color.parseColor("#BFE6F3") else accent
        c.drawText(statusLabel(), 200f, 250f, tp)
    }

    private fun statusLabel() = when (state) {
        State.LISTENING -> "LISTENING..."
        State.PROCESSING -> "PROCESSING..."
        State.SPEAKING -> "SPEAKING..."
        State.ERROR -> "SYSTEM ERROR"
        State.SUCCESS -> "TASK COMPLETE"
        State.IDLE -> "SYSTEM ONLINE"
    }

    private fun drawRobotFace(c: Canvas, size: Float) {
        val x = 200f
        val y = 200f
        stroke(accent, 2.2f); sp.strokeJoin = Paint.Join.ROUND
        robotPath.reset()
        robotPath.moveTo(x - size * .5f, y - size * .3f)
        robotPath.lineTo(x - size * .3f, y - size * .7f)
        robotPath.lineTo(x + size * .3f, y - size * .7f)
        robotPath.lineTo(x + size * .5f, y - size * .3f)
        robotPath.lineTo(x + size * .45f, y + size * .3f)
        robotPath.lineTo(x + size * .25f, y + size * .7f)
        robotPath.lineTo(x - size * .25f, y + size * .7f)
        robotPath.lineTo(x - size * .45f, y + size * .3f)
        robotPath.close()
        c.drawPath(robotPath, sp)
        fp.color = accent
        rect.set(x - size * .35f, y - size * .15f, x - size * .10f, y - size * .05f); c.drawRoundRect(rect, 2f, 2f, fp)
        rect.set(x + size * .10f, y - size * .15f, x + size * .35f, y - size * .05f); c.drawRoundRect(rect, 2f, 2f, fp)
        c.drawLine(x - size * .2f, y + size * .25f, x + size * .2f, y + size * .25f, sp)
        c.drawLine(x - size * .15f, y + size * .40f, x + size * .15f, y + size * .40f, sp)
        sp.strokeJoin = Paint.Join.MITER
    }

    /** Four HUD corner brackets + the top-left meta text (same as .corner / .vp-meta in the HUD css). */
    private fun drawChrome(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val m = dp(10f)
        val l = dp(16f)
        sp.shader = null; sp.style = Paint.Style.STROKE; sp.strokeCap = Paint.Cap.BUTT
        sp.color = argb(.75f, 61, 148, 171); sp.strokeWidth = dp(1f)
        c.drawLine(m, m + l, m, m, sp); c.drawLine(m, m, m + l, m, sp)
        c.drawLine(w - m - l, m, w - m, m, sp); c.drawLine(w - m, m, w - m, m + l, sp)
        c.drawLine(m, h - m - l, m, h - m, sp); c.drawLine(m, h - m, m + l, h - m, sp)
        c.drawLine(w - m - l, h - m, w - m, h - m, sp); c.drawLine(w - m, h - m, w - m, h - m - l, sp)

        metaPaint.typeface = uiFont; metaPaint.textSize = dp(8.5f); metaPaint.letterSpacing = .1f
        val x = m + dp(14f)
        var y = m + dp(12f)
        for (line in arrayOf("CORE v3.0", "CONTEXT AWARE", "WINDOWS + ANDROID")) {
            c.drawText(line, x, y, metaPaint); y += dp(12f)
        }
    }

    /** Live mic audio-reactive vertical neon bars under the reactor (HUD style). */
    private fun drawBottomWaveform(c: Canvas) {
        val bottomY = cy + radiusPx * 1.18f
        val waveW = min(width * 0.82f, radiusPx * 2.1f)
        val startX = cx - waveW / 2f
        val activeMic = voiceLevel > 0.01f
        val live = state == State.LISTENING || state == State.SPEAKING || activeMic
        val wc = if (state == State.ERROR) C_RED else C_CYAN2

        val barCount = 37
        val halfCount = barCount / 2
        val spacing = waveW / (barCount + 1)

        // 1. Draw horizontal glowing center line
        sp.shader = null; sp.style = Paint.Style.STROKE; sp.strokeCap = Paint.Cap.ROUND
        sp.color = ca(wc, if (live) .35f + voiceLevel * .45f else .18f)
        sp.strokeWidth = dp(1.2f)
        c.drawLine(startX, bottomY, startX + waveW, bottomY, sp)

        // 2. Draw vertical symmetrical neon cyan bars (matching reference image)
        for (i in -halfCount..halfCount) {
            val x = cx + i * spacing
            val normDist = kotlin.math.abs(i.toFloat()) / halfCount
            val env = cos(normDist * (PI.toFloat() / 2f))

            val wave1 = sin(linePhase * 12f + i * 0.38f)
            val wave2 = cos(linePhase * 20f - i * 0.22f)
            val modulation = kotlin.math.abs(wave1 * 0.6f + wave2 * 0.4f)

            val hBar = if (live && voiceLevel > 0.005f) {
                dp(2.5f) + voiceLevel * dp(32f) * env * (0.25f + 0.75f * modulation)
            } else {
                dp(2.2f) * env
            }

            val barW = dp(2.2f)
            val halfH = hBar / 2f

            // Outer cyan glow for active bars
            if (voiceLevel > 0.03f && env > 0.2f) {
                val alphaVal = (voiceLevel * 0.45f * env).coerceIn(0f, 0.55f)
                sp.color = ca(wc, alphaVal)
                sp.strokeWidth = barW + dp(3.5f)
                c.drawLine(x, bottomY - halfH, x, bottomY + halfH, sp)
            }

            // Core rounded vertical bar
            fp.color = ca(if (live && voiceLevel > 0.02f) Color.WHITE else wc, if (live) .85f + voiceLevel * .15f else .35f)
            rect.set(x - barW / 2f, bottomY - halfH, x + barW / 2f, bottomY + halfH)
            c.drawRoundRect(rect, barW / 2f, barW / 2f, fp)
        }

        // Status text label
        tp.typeface = uiFont; tp.textSize = dp(8.5f); tp.letterSpacing = .18f; tp.style = Paint.Style.FILL
        tp.color = when (state) {
            State.ERROR -> C_RED
            State.PROCESSING -> C_AMBER
            State.LISTENING -> C_CYAN2
            else -> if (activeMic) C_CYAN2 else C_GREEN
        }
        c.drawText(
            when (state) {
                State.ERROR -> "MIC ERROR"
                State.PROCESSING -> "PROCESSING..."
                State.LISTENING -> "LISTENING..."
                else -> if (activeMic) "AUDIO DETECTED" else "MIC ACTIVE"
            },
            cx, bottomY + dp(22f), tp
        )
    }

    // ---- touch: drag the segmented ring to rotate it, tap anywhere = existing click listener -------------------
    private var dragging = false
    private var dragMoved = false
    private var dragStartPointer = 0f
    private var dragStartRing = 0f

    private fun pointerAngle(x: Float, y: Float) = (atan2(y - cy, x - cx) * 180f / PI.toFloat())

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val u = hypot(e.x - cx, e.y - cy) / s
                if (u in 112f..158f) {
                    dragging = true; dragMoved = false
                    dragStartPointer = pointerAngle(e.x, e.y); dragStartRing = manualRingDeg
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                var d = pointerAngle(e.x, e.y) - dragStartPointer
                if (d > 180f) d -= 360f else if (d < -180f) d += 360f
                if (!dragMoved && kotlin.math.abs(d) < 2.5f) return true
                dragMoved = true
                manualRingDeg = (dragStartRing + d + 360f) % 360f
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (e.actionMasked == MotionEvent.ACTION_UP && !dragMoved) performClick()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean = super.performClick()

    // ---- lifecycle: stop animating when the screen is not visible (saves battery) ----------------------------------
    private fun startAnim() {
        if (!animator.isRunning) { lastFrameNs = 0L; animator.start() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (windowVisibility == VISIBLE) startAnim()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) startAnim() else animator.cancel()
    }

    // ---- helpers -----------------------------------------------------------------------------------------------
    private fun stroke(color: Int, width: Float, cap: Paint.Cap = Paint.Cap.BUTT) {
        sp.shader = null
        sp.style = Paint.Style.STROKE
        sp.color = color
        sp.strokeWidth = width
        sp.strokeCap = cap
    }

    private fun sectors(items: List<Pair<Float, Float>>, rO: Float, rI: Float): Path {
        val p = Path()
        val ro = RectF(200f - rO, 200f - rO, 200f + rO, 200f + rO)
        val ri = RectF(200f - rI, 200f - rI, 200f + rI, 200f + rI)
        for ((center, span) in items) {
            val a0 = center - span / 2f
            p.arcTo(ro, a0, span, true)
            p.arcTo(ri, a0 + span, -span, false)
            p.close()
        }
        return p
    }

    private fun tickPath(r: Float, n: Int, half: Float): Path {
        val p = Path()
        for (i in 0 until n) {
            val a = (i * 2.0 * PI / n)
            val c = cos(a).toFloat()
            val sn = sin(a).toFloat()
            p.moveTo(200f + (r - half) * c, 200f + (r - half) * sn)
            p.lineTo(200f + (r + half) * c, 200f + (r + half) * sn)
        }
        return p
    }

    private fun dotPath(r: Float, period: Float): Path {
        val p = Path()
        val n = (2.0 * PI * r / period).toInt()
        for (i in 0 until n) {
            val a = (i * 2.0 * PI / n)
            val b = a + 1.0 / r
            p.moveTo(200f + r * cos(a).toFloat(), 200f + r * sin(a).toFloat())
            p.lineTo(200f + r * cos(b).toFloat(), 200f + r * sin(b).toFloat())
        }
        return p
    }

    companion object {
        private val C_CYAN = Color.parseColor("#37DCFF")
        private val C_CYAN2 = Color.parseColor("#22D3EE")
        private val C_AMBER = Color.parseColor("#FFC04A")
        private val C_RED = Color.parseColor("#FF5364")
        private val C_GREEN = Color.parseColor("#00F27A")

        private fun argb(a: Float, r: Int, g: Int, b: Int) = Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), r, g, b)
        private fun ca(color: Int, a: Float) = Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

        private fun lerpColor(from: Int, to: Int, t: Float): Int {
            val r = Color.red(from) + ((Color.red(to) - Color.red(from)) * t).toInt()
            val g = Color.green(from) + ((Color.green(to) - Color.green(from)) * t).toInt()
            val b = Color.blue(from) + ((Color.blue(to) - Color.blue(from)) * t).toInt()
            return Color.rgb(r, g, b)
        }
    }
}