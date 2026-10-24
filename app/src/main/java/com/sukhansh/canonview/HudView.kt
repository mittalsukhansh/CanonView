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

/** Read-only replica of the camera's info display + focus peaking + 9:16 aspect guide + 3x3 grid overlay. */
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

    /** Live view image pixel dimensions for scaling peaking/guides to FIT_CENTER. */
    var picW = 0
    var picH = 0

    /** Focus Peaking toggle and high-contrast edge coordinates. */
    var enableFocusPeaking = false
    var focusPoints: FloatArray? = null

    /** Aspect guide mode: 0 = Off, 1 = 9:16 Portrait (Reels/TikTok), 2 = 1:1 Square. */
    var aspectGuideMode = 0

    /** Composition grid mode: 0 = Off, 1 = Rule of Thirds, 2 = Thirds + Center Cross. */
    var gridMode = 0

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
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f * d
    }
    private val histPaint = Paint().apply { color = 0xCCFFFFFF.toInt(); strokeWidth = 2f * d }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // ---- 9:16 / 1:1 Aspect Ratio Crop Guides mapped to picture rectangle ----
        if (aspectGuideMode != 0 && picW > 0 && picH > 0) {
            val sc = min(w / picW.toFloat(), h / picH.toFloat())
            val pLeft = (w - picW * sc) / 2f
            val pTop = (h - picH * sc) / 2f
            val pRight = pLeft + picW * sc
            val pBottom = pTop + picH * sc
            val pw = picW * sc
            val ph = picH * sc

            if (aspectGuideMode == 1) { // 9:16 Portrait
                val targetW = ph * (9f / 16f)
                val cropW = min(pw, targetW)
                val left = pLeft + (pw - cropW) / 2f
                val right = left + cropW
                if (left > pLeft) {
                    c.drawRect(pLeft, pTop, left, pBottom, cropMask)
                    c.drawRect(right, pTop, pRight, pBottom, cropMask)
                    c.drawRect(left, pTop, right, pBottom, cropGuidePaint)
                } else {
                    val targetH = pw * (16f / 9f)
                    val cropH = min(ph, targetH)
                    val top = pTop + (ph - cropH) / 2f
                    val bottom = top + cropH
                    c.drawRect(pLeft, pTop, pRight, top, cropMask)
                    c.drawRect(pLeft, bottom, pRight, pBottom, cropMask)
                    c.drawRect(pLeft, top, pRight, bottom, cropGuidePaint)
                }
            } else if (aspectGuideMode == 2) { // 1:1 Square
                val side = min(pw, ph)
                val left = pLeft + (pw - side) / 2f
                val top = pTop + (ph - side) / 2f
                c.drawRect(pLeft, pTop, left, pBottom, cropMask)
                c.drawRect(left + side, pTop, pRight, pBottom, cropMask)
                c.drawRect(left, pTop, left + side, top, cropMask)
                c.drawRect(left, top + side, left + side, pBottom, cropMask)
                c.drawRect(left, top, left + side, top + side, cropGuidePaint)
            }
        }

        // ---- Composition Grid mapped to picture rectangle ----
        if (gridMode != 0 && picW > 0 && picH > 0) {
            val sc = min(w / picW.toFloat(), h / picH.toFloat())
            val pLeft = (w - picW * sc) / 2f
            val pTop = (h - picH * sc) / 2f
            val pw = picW * sc
            val ph = picH * sc

            val x1 = pLeft + pw / 3f
            val x2 = pLeft + pw * 2f / 3f
            val y1Grid = pTop + ph / 3f
            val y2Grid = pTop + ph * 2f / 3f

            c.drawLine(x1, pTop, x1, pTop + ph, gridPaint)
            c.drawLine(x2, pTop, x2, pTop + ph, gridPaint)
            c.drawLine(pLeft, y1Grid, pLeft + pw, y1Grid, gridPaint)
            c.drawLine(pLeft, y2Grid, pLeft + pw, y2Grid, gridPaint)

            if (gridMode == 2) { // Thirds + Center Cross
                val cx = pLeft + pw / 2f
                val cy = pTop + ph / 2f
                val arm = 12 * d
                c.drawLine(cx - arm, cy, cx + arm, cy, gridPaint)
                c.drawLine(cx, cy - arm, cx, cy + arm, gridPaint)
            }
        }

        // ---- Focus Peaking (Green Edge Overlay mapped to picture rectangle) ----
        if (enableFocusPeaking && focusPoints != null && picW > 0 && picH > 0) {
            val sc = min(w / picW.toFloat(), h / picH.toFloat())
            val left = (w - picW * sc) / 2f
            val top = (h - picH * sc) / 2f
            c.save()
            c.translate(left, top)
            c.scale(sc, sc)
            greenPeak.strokeWidth = 3f * d / sc
            c.drawPoints(focusPoints!!, greenPeak)
            c.restore()
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