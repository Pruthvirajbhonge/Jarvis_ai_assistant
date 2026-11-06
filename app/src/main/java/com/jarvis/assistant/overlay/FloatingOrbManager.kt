package com.jarvis.assistant.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.ImageView
import com.jarvis.assistant.R
import kotlin.math.abs

/** Draggable floating Arc Reactor (layout_floating_orb.xml). Tap = start listening. */
class FloatingOrbManager(
    private val context: Context,
    private val onTap: () -> Unit
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (view != null || !Settings.canDrawOverlays(context)) return

        val themed = ContextThemeWrapper(context, R.style.Theme_JarvisAssistant)
        val v = LayoutInflater.from(themed).inflate(R.layout.layout_floating_orb, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 320
        }

        v.findViewById<ImageView>(R.id.ivFloatingReactorRing).startAnimation(
            RotateAnimation(
                0f, 360f,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f
            ).apply {
                duration = 6000
                repeatCount = Animation.INFINITE
                interpolator = LinearInterpolator()
            }
        )

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        v.setOnClickListener { onTap() }
        v.setOnTouchListener { target, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    wm.updateViewLayout(target, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) target.performClick()
                    true
                }
                else -> false
            }
        }

        wm.addView(v, params)
        view = v
    }

    fun hide() {
        view?.let {
            try { wm.removeView(it) } catch (e: Exception) {}
        }
        view = null
    }
}
