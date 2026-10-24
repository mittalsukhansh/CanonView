package com.sukhansh.canonview

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.round

/**
 * Canon EOS PTP extension: remote mode, live view, settings read/write,
 * drive lens stepping, live view zoom, intervalometer, bracketing, and photo release callback.
 */
class CanonSession(private val ptp: PtpUsb) {

    companion object {
        const val OC_OPEN_SESSION = 0x1002
        const val OC_CLOSE_SESSION = 0x1003
        const val OC_EOS_SET_PROP = 0x9110
        const val OC_EOS_SET_REMOTE_MODE = 0x9114
        const val OC_EOS_SET_EVENT_MODE = 0x9115
        const val OC_EOS_GET_EVENT = 0x9116
        const val OC_EOS_KEEP_DEVICE_ON = 0x911D
        const val OC_EOS_GET_VIEWFINDER = 0x9153
        const val OC_EOS_REMOTE_RELEASE_ON = 0x9128   // params: (1=half|2=full, 0=AF|1=MF)
        const val OC_EOS_REMOTE_RELEASE_OFF = 0x9129  // param: 1=half|2=full
        const val OC_EOS_DO_AF = 0x9154
        const val OC_EOS_POPUP_FLASH = 0x9131          // no params
        const val OC_EOS_ZOOM_POSITION = 0x9159       // x, y: moves the live view frame
        const val OC_EOS_DRIVE_LENS = 0x9155          // step: 0x0001..0x0003 (near), 0x8001..0x8003 (far)

        const val PROP_EVF_OUTPUT_DEVICE = 0xD1B0
        const val PROP_EVF_MODE = 0xD1B1

        const val EC_PROP_VALUE_CHANGED = 0xC189
        const val EC_AVAIL_LIST_CHANGED = 0xC18A

        const val RC_OK = 0x2001
        const val RC_DEVICE_BUSY = 0x2019
        const val RC_SESSION_ALREADY_OPEN = 0x201E
        const val RC_NOT_READY_YET = 0xA102

        const val SEG_JPEG_A = 1
        const val SEG_JPEG_B = 11
    }

    /** Current value per property id (masked to the property's width). */
    val values = ConcurrentHashMap<Int, Int>()
    /** Allowed values per property id, as reported by the camera. */
    val options = ConcurrentHashMap<Int, List<Int>>()
    /** Set to true whenever values/options changed; the UI clears it. */
    @Volatile var changed = false
    /** Why the last live view request produced no picture (for the status line). */
    @Volatile var lastInfo = ""
    /** Callback triggered after shooting a single photo preview. */
    @Volatile var onPhotoCaptured: ((ByteArray) -> Unit)? = null

    /** Intervalometer status. */
    @Volatile var intervalRunning = false
    @Volatile var intervalInfo = ""
    private var intervalSeconds = 10
    private var intervalTargetCount = 0
    private var intervalShotIndex = 0
    private var nextIntervalAt = 0L

    private sealed class Action {
        class Set(val prop: Int, val value: Int) : Action()
        class Focus(val x: Int, val y: Int) : Action()
        class DriveLens(val step: Int) : Action()
        class Zoom(val level: Int) : Action()
        object Shoot : Action()
        object Flash : Action()
        class StartInterval(val seconds: Int, val count: Int) : Action()
        object StopInterval : Action()
        class Bracket(val frames: Int, val stepThirds: Int) : Action()
    }
    private val pending = ConcurrentLinkedQueue<Action>()
    private val tracked = Settings.tracked.associateBy { it.id }
    private var frameCount = 0
    private var lastKeepAlive = System.currentTimeMillis()
    @Volatile private var lastViewFrame: ByteArray? = null

    /** Called from the UI thread; applied on the worker thread between frames. */
    fun requestSet(prop: Int, value: Int) { pending.add(Action.Set(prop, value)) }
    /** x,y in live view image pixels. */
    fun requestFocus(x: Int, y: Int) { pending.add(Action.Focus(x, y)) }
    fun requestDriveLens(step: Int) { pending.add(Action.DriveLens(step)) }
    fun requestZoom(level: Int) { pending.add(Action.Zoom(level)) }
    fun requestShoot() { pending.add(Action.Shoot) }
    fun requestFlash() { pending.add(Action.Flash) }
    fun requestStartInterval(seconds: Int, count: Int) { pending.add(Action.StartInterval(seconds, count)) }
    fun requestStopInterval() { pending.add(Action.StopInterval) }
    fun requestBracket(frames: Int, stepThirds: Int) { pending.add(Action.Bracket(frames, stepThirds)) }

    private fun ok(r: PtpResult, what: String) {
        if (r.code != RC_OK) throw PtpException("$what failed: 0x${r.code.toString(16)}")
    }

    private fun setProp(prop: Int, value: Int): PtpResult {
        val b = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(12); b.putInt(prop); b.putInt(value)
        return ptp.transact(OC_EOS_SET_PROP, dataOut = b.array())
    }

    private fun setPropRetry(prop: Int, value: Int): PtpResult {
        var r = setProp(prop, value)
        var tries = 0
        while ((r.code == RC_DEVICE_BUSY) && (tries < 10)) {
            Thread.sleep(300)
            pollEvents()
            r = setProp(prop, value)
            tries++
        }
        return r
    }

    fun start() {
        val open = ptp.transact(OC_OPEN_SESSION, intArrayOf(1))
        if (open.code != RC_OK && open.code != RC_SESSION_ALREADY_OPEN)
            throw PtpException("OpenSession failed: 0x${open.code.toString(16)}")

        ok(ptp.transact(OC_EOS_SET_REMOTE_MODE, intArrayOf(1)), "SetRemoteMode")
        ok(ptp.transact(OC_EOS_SET_EVENT_MODE, intArrayOf(1)), "SetEventMode")

        repeat(4) { pollEvents(); Thread.sleep(50) }

        val evf = setProp(PROP_EVF_MODE, 1)
        if (evf.code != RC_OK && evf.code != RC_DEVICE_BUSY)
            throw PtpException("SetProp EvfMode failed: 0x${evf.code.toString(16)}")
        ok(setProp(PROP_EVF_OUTPUT_DEVICE, 2), "SetProp EvfOutputDevice")
    }

    /** Runs queued actions (settings, focus, shutter, intervalometer, bracketing); returns a message for any that failed. */
    fun applyPending(): String? {
        var err: String? = null
        var ran = false
        while (true) {
            val a = pending.poll() ?: break
            ran = true
            when (a) {
                is Action.Set -> {
                    val r = setProp(a.prop, a.value)
                    if (r.code != RC_OK) err = "Set failed: 0x${r.code.toString(16)} (check mode dial)"
                }
                is Action.Focus -> {
                    val z = ptp.transact(OC_EOS_ZOOM_POSITION, intArrayOf(a.x, a.y))
                    val f = ptp.transact(OC_EOS_DO_AF, timeoutMs = 8000)
                    Log.d("CanonView", "AF x=${a.x} y=${a.y} zoomPos=0x${z.code.toString(16)} doAf=0x${f.code.toString(16)}")
                    if (f.code != RC_OK) err = "AF failed: 0x${f.code.toString(16)}"
                }
                is Action.DriveLens -> {
                    val r = ptp.transact(OC_EOS_DRIVE_LENS, intArrayOf(a.step))
                    Log.d("CanonView", "drive lens step=0x${a.step.toString(16)} code=0x${r.code.toString(16)}")
                    if (r.code != RC_OK) err = "Lens drive failed: 0x${r.code.toString(16)}"
                }
                is Action.Zoom -> {
                    val r = ptp.transact(0x9158, intArrayOf(a.level))
                    Log.d("CanonView", "evf zoom level=${a.level} code=0x${r.code.toString(16)}")
                    if (r.code != RC_OK) err = "Zoom failed: 0x${r.code.toString(16)}"
                }
                Action.Shoot -> shoot(af = true)?.let { err = it }
                Action.Flash -> {
                    val r = ptp.transact(OC_EOS_POPUP_FLASH)
                    Log.d("CanonView", "popup flash: 0x${r.code.toString(16)}")
                    if (r.code != RC_OK) err = "Flash failed: 0x${r.code.toString(16)} (use P/Av/Tv/M mode)"
                }
                is Action.StartInterval -> {
                    intervalRunning = true
                    intervalSeconds = a.seconds
                    intervalTargetCount = a.count
                    intervalShotIndex = 0
                    nextIntervalAt = System.currentTimeMillis()
                }
                Action.StopInterval -> {
                    intervalRunning = false
                    intervalInfo = ""
                }
                is Action.Bracket -> {
                    doBracket(a.frames, a.stepThirds)?.let { err = it }
                }
            }
        }
        tickInterval()?.let { err = it }
        if (ran) pollEvents()
        return err
    }

    private fun tickInterval(): String? {
        if (!intervalRunning) return null
        val now = System.currentTimeMillis()
        if (now < nextIntervalAt) return null

        intervalShotIndex++
        intervalInfo = if (intervalTargetCount > 0) "Interval $intervalShotIndex/$intervalTargetCount" else "Interval $intervalShotIndex/inf"

        val err = shoot(af = false)
        if (err != null) {
            intervalRunning = false
            intervalInfo = ""
            return err
        }

        if (intervalTargetCount in 1..intervalShotIndex) {
            intervalRunning = false
            intervalInfo = ""
        } else {
            nextIntervalAt = now + (intervalSeconds * 1000L)
        }
        return null
    }

    private fun doBracket(frames: Int, stepThirds: Int): String? {
        val aeMode = values[Settings.AE_MODE.id] ?: -1
        var err: String? = null

        if (aeMode == 3) { // Manual Mode -> vary Shutter Speed
            val baseCode = values[Settings.SHUTTER.id] ?: 0
            val shutterOpts = options[Settings.SHUTTER.id]
            if (baseCode <= 0x04 || shutterOpts.isNullOrEmpty()) {
                return "Bulb mode or shutter options unavailable for bracketing"
            }
            try {
                val halfF = frames / 2
                for (k in -halfF..halfF) {
                    val offset8th = round(k * stepThirds * 8f / 3f).toInt()
                    val targetCode = baseCode - offset8th
                    val snappedCode = shutterOpts.minByOrNull { Math.abs(it - targetCode) } ?: targetCode
                    setPropRetry(Settings.SHUTTER.id, snappedCode)
                    pollEvents()
                    err = shoot(af = false)
                    if (err != null) break
                }
            } finally {
                setPropRetry(Settings.SHUTTER.id, baseCode)
                pollEvents()
            }
        } else if (aeMode in listOf(0, 1, 2)) { // P, Tv, Av Mode -> vary Exposure Comp
            val baseCode = values[Settings.EXP_COMP.id] ?: 0
            val baseSigned = baseCode.toByte().toInt()
            val expOpts = options[Settings.EXP_COMP.id]
            if (expOpts.isNullOrEmpty()) {
                return "Exposure comp options unavailable for bracketing"
            }
            try {
                val halfF = frames / 2
                for (k in -halfF..halfF) {
                    val offset8th = round(k * stepThirds * 8f / 3f).toInt()
                    val targetSigned = baseSigned + offset8th
                    val snappedCode = expOpts.minByOrNull { Math.abs(it.toByte().toInt() - targetSigned) } ?: baseCode
                    setPropRetry(Settings.EXP_COMP.id, snappedCode)
                    pollEvents()
                    err = shoot(af = false)
                    if (err != null) break
                }
            } finally {
                setPropRetry(Settings.EXP_COMP.id, baseCode)
                pollEvents()
            }
        } else {
            return "Bracketing needs P, Tv, Av or M mode"
        }
        return err
    }

    /** Shutter release: with AF (default, af = true) or without AF (af = false). Photo goes to the camera's card. */
    private fun shoot(af: Boolean = true): String? {
        pollEvents()
        var err: String? = null
        if (af) {
            val half = ptp.transact(OC_EOS_REMOTE_RELEASE_ON, intArrayOf(1, 0), timeoutMs = 10_000)
            Log.d("CanonView", "half-press: 0x${half.code.toString(16)}")
            pollEvents()
            val full = ptp.transact(OC_EOS_REMOTE_RELEASE_ON, intArrayOf(2, 0), timeoutMs = 10_000)
            Log.d("CanonView", "full-press: 0x${full.code.toString(16)}")
            if (full.code != RC_OK) {
                err = "Shutter failed: 0x${full.code.toString(16)} (half-press 0x${half.code.toString(16)})"
            } else {
                val driveVal = values[Settings.DRIVE.id] ?: 0
                val isBurst = driveVal in listOf(1, 4, 5, 7)
                if (!isBurst) {
                    lastViewFrame?.let { imgData -> onPhotoCaptured?.invoke(imgData) }
                }
            }
            ptp.transact(OC_EOS_REMOTE_RELEASE_OFF, intArrayOf(2))
            pollEvents()
            ptp.transact(OC_EOS_REMOTE_RELEASE_OFF, intArrayOf(1))
        } else {
            val full = ptp.transact(OC_EOS_REMOTE_RELEASE_ON, intArrayOf(3, 1), timeoutMs = 10_000)
            Log.d("CanonView", "full-press (no-AF): 0x${full.code.toString(16)}")
            if (full.code != RC_OK) {
                err = "Shutter failed: 0x${full.code.toString(16)}"
            }
            ptp.transact(OC_EOS_REMOTE_RELEASE_OFF, intArrayOf(3))
        }

        val shutterCode = values[Settings.SHUTTER.id] ?: 0x68
        val expSec = if (shutterCode <= 0x04) 0.0 else (1.0 / 60.0) * Math.pow(2.0, (0x68 - shutterCode) / 8.0)
        val sleepMs = ((expSec + 0.6) * 1000.0).toLong().coerceIn(400L, 120_000L)
        Thread.sleep(sleepMs)
        pollEvents()

        // The mirror drops for the exposure, so switch live view back on with retry.
        setPropRetry(PROP_EVF_MODE, 1)
        setPropRetry(PROP_EVF_OUTPUT_DEVICE, 2)
        return err
    }

    /** Drain the camera's event queue and parse property updates. */
    fun pollEvents() {
        val r = ptp.transact(OC_EOS_GET_EVENT)
        val d = r.data ?: return
        val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        while (pos + 8 <= d.size) {
            val size = bb.getInt(pos)
            val code = bb.getInt(pos + 4)
            if (size < 8 || pos + size > d.size) break
            when (code) {
                EC_PROP_VALUE_CHANGED -> if (size >= 16) {
                    val prop = bb.getInt(pos + 8)
                    val p = tracked[prop]
                    if (p != null) {
                        values[prop] = bb.getInt(pos + 12) and p.mask
                        changed = true
                    }
                }
                EC_AVAIL_LIST_CHANGED -> if (size >= 20) {
                    val prop = bb.getInt(pos + 8)
                    val p = tracked[prop]
                    val type = bb.getInt(pos + 12)
                    val count = bb.getInt(pos + 16)
                    if (p != null && type == 3 && count in 1..512 && 20 + count * 4 <= size) {
                        options[prop] = List(count) { bb.getInt(pos + 20 + it * 4) and p.mask }
                        changed = true
                    }
                }
            }
            pos += size
        }
    }

    /** Returns one live view JPEG, or null if the camera isn't ready yet. */
    fun nextFrame(): ByteArray? {
        if (++frameCount % 5 == 0) pollEvents()
        val now = System.currentTimeMillis()
        if (now - lastKeepAlive > 10_000) {
            ptp.transact(OC_EOS_KEEP_DEVICE_ON)
            lastKeepAlive = now
        }

        val r = ptp.transact(OC_EOS_GET_VIEWFINDER, intArrayOf(0x00200000, 0, 0), timeoutMs = 2000)
        if (r.code == RC_DEVICE_BUSY || r.code == RC_NOT_READY_YET) {
            lastInfo = "camera not ready (0x${r.code.toString(16)})"
            return null
        }
        if (r.code != RC_OK) throw PtpException("GetViewFinder failed: 0x${r.code.toString(16)}")
        val d = r.data
        if (d == null) { lastInfo = "OK but no data"; return null }
        val jpg = extractJpeg(d)
        if (jpg != null) { lastViewFrame = jpg }
        lastInfo = if (jpg == null) "no JPEG in ${d.size} bytes" else ""
        return jpg
    }

    fun stop() {
        runCatching { setProp(PROP_EVF_OUTPUT_DEVICE, 0) }
        runCatching { ptp.transact(OC_EOS_GET_EVENT) }
        runCatching { ptp.transact(OC_EOS_SET_REMOTE_MODE, intArrayOf(0)) }
        runCatching { ptp.transact(OC_CLOSE_SESSION) }
    }

    private fun extractJpeg(d: ByteArray): ByteArray? {
        val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        while (pos + 8 <= d.size) {
            val len = bb.getInt(pos).toLong() and 0xFFFFFFFFL
            val type = bb.getInt(pos + 4)
            if (len < 8 || pos + len > d.size) return null
            if (type == SEG_JPEG_A || type == SEG_JPEG_B) {
                return d.copyOfRange(pos + 8, (pos + len).toInt())
            }
            pos += len.toInt()
        }
        return null
    }
}