package com.sukhansh.canonview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.min

/** Read-only replica of the camera's info display + focus peaking + 9:16 aspect guide. */
class HudView(ctx: Context) : View(ctx) {
    var mode = "-"
    var shutter = "-"
    var aperture = "-"
    var iso = "-"
    var extras = ""
    var battery = ""
    var shots = ""
    var compEv: Float? = null
    var isManual = false
    /** Exposure estimated from the live view picture (M mode only). */
    var estEv: Float? = null
    var hist: IntArray? = null

    /** Focus Peaking toggle and high-contrast edge coordinates. */
    var enableFocusPeaking = false
    var focusPoints: FloatArray? = null

    /** Aspect guide mode: 0 = Off, 1 = 9:16 Portrait (Reels/TikTok), 2 = 1:1 Square. */
    var aspectGuideMode = 0

    private val d = ctx.resources.displayMetrics.density
    private val topPad = 30 * d
    private val strip = Paint().apply { color = 0x99000000.toInt() }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 22 * d; typeface = Typeface.DEFAULT_BOLD
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFCCCCCC.toInt(); textSize = 13 * d }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f * d
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 1.5f * d }
    private val yellow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD60A.toInt() }
    private val cyan = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF40E0FF.toInt() }
    private val greenPeak = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00FF00.toInt(); strokeWidth = 3f * d
    }
    private val cropMask = Paint().apply { color = 0xB3000000.toInt() }
    private val cropGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD60A.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * d
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val histPaint = Paint().apply { color = 0xCCFFFFFF.toInt(); strokeWidth = 2f * d }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // ---- 9:16 / 1:1 Aspect Ratio Crop Guides ----
        if (aspectGuideMode == 1) { // 9:16 Portrait
            val targetW = h * (9f / 16f)
            val cropW = min(w, targetW)
            val left = (w - cropW) / 2f
            val right = left + cropW
            // Draw darkened side bars
            if (left > 0) {
                c.drawRect(0f, 0f, left, h, cropMask)
                c.drawRect(right, 0f, w, h, cropMask)
                c.drawRect(left, 0f, right, h, cropGuidePaint)
            } else {
                val targetH = w * (16f / 9f)
                val top = (h - targetH) / 2f
                val bottom = top + targetH
                c.drawRect(0f, 0f, w, top, cropMask)
                c.drawRect(0f, bottom, w, h, cropMask)
                c.drawRect(0f, top, w, bottom, cropGuidePaint)
            }
        } else if (aspectGuideMode == 2) { // 1:1 Square
            val side = min(w, h)
            val left = (w - side) / 2f
            val top = (h - side) / 2f
            c.drawRect(0f, 0f, left, h, cropMask)
            c.drawRect(left + side, 0f, w, h, cropMask)
            c.drawRect(left, 0f, left + side, top, cropMask)
            c.drawRect(left, top + side, left + side, h, cropMask)
            c.drawRect(left, top, left + side, top + side, cropGuidePaint)
        }

        // ---- Focus Peaking (Green Edge Overlay) ----
        if (enableFocusPeaking && focusPoints != null) {
            val pts = focusPoints!!
            c.drawPoints(pts, greenPeak)
        }

        // ---- top strip: mode, shutter, aperture, ISO | battery, shots ----
        c.drawRect(0f, topPad, w, topPad + 62 * d, strip)
        var x = 12 * d
        val y1 = topPad + 30 * d
        val mw = big.measureText(mode) + 16 * d
        c.drawRoundRect(RectF(x, y1 - 24 * d, x + mw, y1 + 7 * d), 6 * d, 6 * d, outline)
        c.drawText(mode, x + 8 * d, y1, big)
        x += mw + 16 * d
        for (s in listOf(shutter, aperture, "ISO $iso")) {
            c.drawText(s, x, y1, big)
            x += big.measureText(s) + 20 * d
        }
        val right = "$battery  [$shots]"
        c.drawText(right, w - 12 * d - small.measureText(right), y1, small)
        c.drawText(extras, 12 * d, topPad + 54 * d, small)

        // ---- histogram ----
        hist?.let { hs ->
            val hx = w - 140 * d
            val hy = topPad + 68 * d
            val hw = 128 * d
            val hh = 48 * d
            c.drawRect(hx, hy, hx + hw, hy + hh, strip)
            val mx = (hs.maxOrNull() ?: 1).coerceAtLeast(1)
            val bw = hw / hs.size
            for (i in hs.indices) {
                val bh = hh * hs[i] / mx
                val px = hx + i * bw + bw / 2
                c.drawLine(px, hy + hh, px, hy + hh - bh, histPaint)
            }
        }

        // ---- exposure scale ----
        val cx = w / 2
        val half = min(w * 0.30f, 240 * d)
        val sy = h - 130 * d
        c.drawRect(cx - half - 24 * d, sy - 34 * d, cx + half + 24 * d, sy + 62 * d, strip)
        c.drawLine(cx - half, sy, cx + half, sy, tick)
        for (i in -9..9) {
            val px = cx + (i / 3f) / 3f * half
            val len = if (i % 3 == 0) 12 * d else 6 * d
            c.drawLine(px, sy, px, sy + len, tick)
            if (i % 3 == 0) {
                val t = if (i == 0) "0" else "%+d".format(i / 3)
                c.drawText(t, px - small.measureText(t) / 2, sy + 30 * d, small)
            }
        }
        fun posOf(ev: Float) = cx + ev.coerceIn(-3f, 3f) / 3f * half
        val tag: String
        if (isManual) {
            tag = "exposure (estimated from picture)"
            estEv?.let { ev ->
                val p = posOf(ev)
                c.drawRect(p - 3 * d, sy - 20 * d, p + 3 * d, sy - 3 * d, cyan)
            }
        } else {
            tag = "exposure compensation"
            compEv?.let { ev ->
                val p = posOf(ev)
                val tri = Path().apply {
                    moveTo(p - 7 * d, sy - 20 * d); lineTo(p + 7 * d, sy - 20 * d); lineTo(p, sy - 4 * d); close()
                }
                c.drawPath(tri, yellow)
            }
        }
        c.drawText(tag, cx - small.measureText(tag) / 2, sy + 52 * d, small)
    }
}