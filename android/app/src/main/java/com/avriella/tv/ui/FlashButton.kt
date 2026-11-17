package com.avriella.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.avriella.tv.cam.Torch

/** Round flashlight button for the corner of the grown-up screens. Glows yellow while the light is on. */
class FlashButton(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val update: () -> Unit = { invalidate(); contentDescription = if (Torch.on) "Turn flashlight off" else "Turn flashlight on" }

    init {
        contentDescription = "Turn flashlight on"
        isClickable = true
        lift(context.dpf(2f))
        pressScale(0.9f)
        setOnClickListener {
            Torch.toggle(context)?.let { context.toast(it) }
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); Torch.listeners.add(update); invalidate() }
    override fun onDetachedFromWindow() { Torch.listeners.remove(update); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = minOf(w, h) / 2f
        val on = Torch.on
        // button
        p.style = Paint.Style.FILL
        p.color = if (on) 0xFFFFD84D.toInt() else Pal.CARD
        c.drawCircle(w / 2, h / 2, r - 1, p)
        if (!on) { p.style = Paint.Style.STROKE; p.strokeWidth = context.dpf(1f); p.color = Pal.LINE; c.drawCircle(w / 2, h / 2, r - 1, p) }
        // flashlight, pointing up: head (trapezoid) + body (rounded bar) + switch dot
        val u = r / 10f
        val cx = w / 2; val cy = h / 2 + u * 1.5f
        p.style = Paint.Style.FILL
        p.color = if (on) 0xFF5A3A00.toInt() else Pal.INK
        path.reset()
        path.moveTo(cx - 4.2f * u, cy - 4.2f * u); path.lineTo(cx + 4.2f * u, cy - 4.2f * u)
        path.lineTo(cx + 2.4f * u, cy - 1.2f * u); path.lineTo(cx - 2.4f * u, cy - 1.2f * u); path.close()
        c.drawPath(path, p)
        c.drawRoundRect(RectF(cx - 2.4f * u, cy - 1.6f * u, cx + 2.4f * u, cy + 5.4f * u), u, u, p)
        p.color = if (on) 0xFFFFD84D.toInt() else Pal.CARD
        c.drawCircle(cx, cy + 1.2f * u, 0.9f * u, p)
        // beam rays
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = 1.3f * u
        p.color = if (on) 0xFF5A3A00.toInt() else Pal.SUB
        c.drawLine(cx, cy - 5.8f * u, cx, cy - 7.6f * u, p)
        c.drawLine(cx - 3.4f * u, cy - 5.6f * u, cx - 4.8f * u, cy - 7f * u, p)
        c.drawLine(cx + 3.4f * u, cy - 5.6f * u, cx + 4.8f * u, cy - 7f * u, p)
    }
}
