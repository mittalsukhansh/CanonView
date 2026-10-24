package com.sukhansh.canonview

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.round

data class IntervalPlan(
    val startDelayMs: Long,
    val intervalMs: Long,
    val endIntervalMs: Long = intervalMs,
    val count: Int,
    val shotsPerInterval: Int = 1,
    val bracketStepThirds: Int = 1,
    val totalEvRamp: Float = 0f,
    val startTimeClock: String? = null,
)

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

    /** Flag indicating no memory card present in camera. */
    @Volatile var noCard = false

    /** Intervalometer status. */
    @Volatile var intervalRunning = false
    @Volatile var intervalPaused = false
    @Volatile var intervalInfo = ""
    @Volatile var intervalSummaryAtEnd = ""

    private var currentPlan: IntervalPlan? = null
    private var startAt = 0L
    private var nextAt = 0L
    private var pauseStartedAt = 0L
    private var remainingTimeBeforePause = 0L
    private var doneShots = 0
    private var lateShots = 0
    private var failedFrames = 0
    private var consecutiveFailures = 0
    private var intervalStartTimeMs = 0L
    private var baseShutterCode = 0x68
    private var baseIsoCode = 0x0048
    private var evfTurnedOffBetweenShots = false

    /** Debug log ring buffer (last 25 entries). */
    val debugLogs = ConcurrentLinkedQueue<String>()

    fun dbg(msg: String) {
        Log.d("CanonView", msg)
        debugLogs.add(msg)
        while (debugLogs.size > 25) {
            debugLogs.poll()
        }
    }

    private var lastAeModeChangeTime = 0L
    private var lastFrameTime = System.currentTimeMillis()
    private var lastEvfRecoveryTime = 0L

    private sealed class Action {
        class Set(val prop: Int, val value: Int) : Action()
        class Focus(val x: Int, val y: Int) : Action()
        class DriveLens(val step: Int) : Action()
        class Zoom(val level: Int) : Action()
        object Shoot : Action()
        object Flash : Action()
        class StartInterval(val plan: IntervalPlan) : Action()
        object PauseInterval : Action()
        object ResumeInterval : Action()
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
    fun requestStartInterval(plan: IntervalPlan) { pending.add(Action.StartInterval(plan)) }
    fun requestPauseInterval() { pending.add(Action.PauseInterval) }
    fun requestResumeInterval() { pending.add(Action.ResumeInterval) }
    fun requestStopInterval() { pending.add(Action.StopInterval) }
    fun requestBracket(frames: Int, stepThirds: Int) { pending.add(Action.Bracket(frames, stepThirds)) }

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
        dbg("OpenSession -> 0x${open.code.toString(16)}")
        if ((open.code != RC_OK) && (open.code != RC_SESSION_ALREADY_OPEN))
            throw PtpException("OpenSession failed: 0x${open.code.toString(16)}")

        val rRm = ptp.transact(OC_EOS_SET_REMOTE_MODE, intArrayOf(1))
        dbg("SetRemoteMode -> 0x${rRm.code.toString(16)}")
        val rEm = ptp.transact(OC_EOS_SET_EVENT_MODE, intArrayOf(1))
        dbg("SetEventMode -> 0x${rEm.code.toString(16)}")

        repeat(4) { pollEvents(); Thread.sleep(50) }

        var dTries = 0
        while (!values.containsKey(0xD11C) && dTries < 10) {
            pollEvents()
            Thread.sleep(100)
            dTries++
        }
        val destVal = values[0xD11C]
        dbg("Capture dest (0xD11C) value=$destVal")
        if (destVal == 4) {
            val destOpts = options[0xD11C]
            val validOpt = destOpts?.firstOrNull { it != 4 } ?: 2
            if (destOpts != null && destOpts.all { it == 4 }) {
                noCard = true
                dbg("No memory card detected: capture dest only 4 (PC)")
            } else {
                val rDest = setPropRetry(0xD11C, validOpt)
                dbg("Changed capture dest from PC (4) to $validOpt res=0x${rDest.code.toString(16)}")
            }
        }

        val evf = setProp(PROP_EVF_MODE, 1)
        dbg("SetProp EvfMode -> 0x${evf.code.toString(16)}")
        if (evf.code != RC_OK && evf.code != RC_DEVICE_BUSY)
            throw PtpException("SetProp EvfMode failed: 0x${evf.code.toString(16)}")
        val rOut = setProp(PROP_EVF_OUTPUT_DEVICE, 2)
        dbg("SetProp EvfOutputDevice -> 0x${rOut.code.toString(16)}")
    }

    /** Runs queued actions (settings, focus, shutter, intervalometer, bracketing); returns a message for any that failed. */
    fun applyPending(): String? {
        var err: String? = null
        var ran = false

        if (System.currentTimeMillis() - lastAeModeChangeTime < 1500) {
            pollEvents()
            Thread.sleep(50)
        }

        while (true) {
            val a = pending.poll() ?: break
            ran = true
            when (a) {
                is Action.Set -> {
                    val r = setPropRetry(a.prop, a.value)
                    dbg("Action.Set prop=0x${a.prop.toString(16)} val=${a.value} res=0x${r.code.toString(16)}")
                    if (r.code != RC_OK) {
                        val propObj = Settings.tracked.firstOrNull { it.id == a.prop }
                        val propName = propObj?.name ?: "0x${a.prop.toString(16)}"
                        val currentModeVal = values[Settings.AE_MODE.id] ?: 0
                        val currentModeName = Settings.label(Settings.AE_MODE, currentModeVal)
                        err = "Set $propName failed 0x${r.code.toString(16)} in mode $currentModeName"
                    }
                }
                is Action.Focus -> {
                    val focusModeVal = values[Settings.FOCUS_MODE.id]
                    if (focusModeVal == 3) {
                        err = "Camera/lens is in manual focus: set the lens switch to AF"
                        dbg("AF requested while in MF mode")
                    } else {
                        // 2. Clear any stuck AF first
                        val c1 = ptp.transact(0x9160)
                        dbg("AF clear stuck AfCancel code=0x${c1.code.toString(16)}")
                        pollEvents()
                        Thread.sleep(300)
                        pollEvents()

                        // 3. ZoomPosition
                        val z = ptp.transact(OC_EOS_ZOOM_POSITION, intArrayOf(a.x, a.y))
                        dbg("AF ZoomPosition x=${a.x} y=${a.y} code=0x${z.code.toString(16)}")
                        Thread.sleep(150)
                        pollEvents()

                        // 4. DoAf
                        var f = ptp.transact(OC_EOS_DO_AF)
                        var afTries = 0
                        while (f.code == RC_DEVICE_BUSY && afTries < 5) {
                            Thread.sleep(300)
                            pollEvents()
                            f = ptp.transact(OC_EOS_DO_AF)
                            afTries++
                        }
                        dbg("AF DoAf code=0x${f.code.toString(16)} (tries=$afTries)")

                        var halfCode = RC_OK
                        if (f.code != RC_OK) {
                            // 5. Mirror-down AF fallback
                            dbg("DoAf failed (0x${f.code.toString(16)}), starting mirror-down AF fallback...")
                            val rEvfOff = setProp(PROP_EVF_OUTPUT_DEVICE, 0)
                            dbg("AF mirror-down EVF off res=0x${rEvfOff.code.toString(16)}")
                            repeat(7) {
                                Thread.sleep(100)
                                pollEvents()
                            }

                            val halfRes = ptp.transact(OC_EOS_REMOTE_RELEASE_ON, intArrayOf(1, 0))
                            halfCode = halfRes.code
                            dbg("AF mirror-down half-press code=0x${halfCode.toString(16)}")
                            repeat(25) {
                                Thread.sleep(100)
                                pollEvents()
                            }

                            val rOff = ptp.transact(OC_EOS_REMOTE_RELEASE_OFF, intArrayOf(1))
                            val rCancel = ptp.transact(0x9160) // AfCancel
                            dbg("AF mirror-down releaseOff=0x${rOff.code.toString(16)} afCancel=0x${rCancel.code.toString(16)}")
                            pollEvents()

                            val rEvf1 = setPropRetry(PROP_EVF_MODE, 1)
                            val rEvf2 = setPropRetry(PROP_EVF_OUTPUT_DEVICE, 2)
                            dbg("AF mirror-down EVF restore mode=0x${rEvf1.code.toString(16)} dev=0x${rEvf2.code.toString(16)}")
                        } else {
                            // 6. After successful DoAf
                            repeat(15) {
                                Thread.sleep(100)
                                pollEvents()
                            }
                            val c2 = ptp.transact(0x9160) // AfCancel
                            dbg("AF post-success AfCancel code=0x${c2.code.toString(16)}")
                            pollEvents()
                        }

                        if (f.code != RC_OK && halfCode != RC_OK) {
                            err = "AF failed: afCancel=0x${c1.code.toString(16)} zoomPos=0x${z.code.toString(16)} doAf=0x${f.code.toString(16)} half=0x${halfCode.toString(16)}"
                        }
                    }
                }
                is Action.DriveLens -> {
                    val r = ptp.transact(OC_EOS_DRIVE_LENS, intArrayOf(a.step))
                    dbg("drive lens step=0x${a.step.toString(16)} code=0x${r.code.toString(16)}")
                    if (r.code != RC_OK) err = "Lens drive failed: 0x${r.code.toString(16)}"
                }
                is Action.Zoom -> {
                    val r = ptp.transact(0x9158, intArrayOf(a.level))
                    dbg("evf zoom level=${a.level} code=0x${r.code.toString(16)}")
                    if (r.code != RC_OK) err = "Zoom failed: 0x${r.code.toString(16)}"
                }
                Action.Shoot -> shoot(af = true)?.let { err = it }
                Action.Flash -> {
                    val r = ptp.transact(OC_EOS_POPUP_FLASH)
                    dbg("popup flash: 0x${r.code.toString(16)}")
                    if (r.code != RC_OK) err = "Flash failed: 0x${r.code.toString(16)} (use P/Av/Tv/M mode)"
                }
                is Action.StartInterval -> {
                    currentPlan = a.plan
                    intervalRunning = true
                    intervalPaused = false
                    intervalSummaryAtEnd = ""
                    doneShots = 0
                    lateShots = 0
                    failedFrames = 0
                    consecutiveFailures = 0
                    evfTurnedOffBetweenShots = false
                    intervalStartTimeMs = System.currentTimeMillis()
                    startAt = intervalStartTimeMs + a.plan.startDelayMs
                    nextAt = startAt
                    baseShutterCode = values[Settings.SHUTTER.id] ?: 0x68
                    baseIsoCode = values[Settings.ISO.id] ?: 0x0048
                    dbg("StartInterval plan: delayMs=${a.plan.startDelayMs} intervalMs=${a.plan.intervalMs} endIntervalMs=${a.plan.endIntervalMs} count=${a.plan.count} shotsPerInt=${a.plan.shotsPerInterval} evRamp=${a.plan.totalEvRamp}")
                }
                Action.PauseInterval -> {
                    if (intervalRunning && !intervalPaused) {
                        intervalPaused = true
                        pauseStartedAt = System.currentTimeMillis()
                        remainingTimeBeforePause = (nextAt - pauseStartedAt).coerceAtLeast(0L)
                        dbg("PauseInterval: remainingTimeBeforePause=${remainingTimeBeforePause}ms")
                    }
                }
                Action.ResumeInterval -> {
                    if (intervalRunning && intervalPaused) {
                        intervalPaused = false
                        nextAt = System.currentTimeMillis() + remainingTimeBeforePause
                        dbg("ResumeInterval: nextAt in ${remainingTimeBeforePause}ms")
                    }
                }
                Action.StopInterval -> {
                    if (evfTurnedOffBetweenShots) {
                        setPropRetry(PROP_EVF_MODE, 1)
                        setPropRetry(PROP_EVF_OUTPUT_DEVICE, 2)
                        evfTurnedOffBetweenShots = false
                    }
                    intervalRunning = false
                    intervalPaused = false
                    intervalInfo = ""
                    currentPlan = null
                    dbg("StopInterval requested")
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

    private fun formatHms(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun applyExposureRamp(plan: IntervalPlan, shotIdx: Int, currIntervalMs: Long) {
        if (plan.count <= 1 || plan.totalEvRamp == 0f || values[Settings.AE_MODE.id] != 3) return
        val progress = shotIdx.toFloat() / (plan.count - 1).toFloat()
        val targetEighths = round(progress * (plan.totalEvRamp * 8f)).toInt()

        val shutterOpts = options[Settings.SHUTTER.id] ?: emptyList()
        val isoOpts = options[Settings.ISO.id] ?: emptyList()

        var targetShutterCode = baseShutterCode
        var targetIsoCode = baseIsoCode

        if (targetEighths > 0) { // Brightening (sunset)
            // Lengthen shutter first
            val maxExpSec = (currIntervalMs / 1000.0) - 3.0
            val desiredShutterCode = baseShutterCode - targetEighths
            val bestShutter = shutterOpts.asSequence().filter { code ->
                val expSec = if (code <= 0x04) 0.0 else (1.0 / 60.0) * Math.pow(2.0, (0x68 - code) / 8.0)
                expSec <= maxExpSec
            }.minByOrNull { abs(it - desiredShutterCode) } ?: baseShutterCode

            targetShutterCode = bestShutter
            val shutterEighthsUsed = baseShutterCode - targetShutterCode
            val remainingIsoEighths = targetEighths - shutterEighthsUsed
            if (remainingIsoEighths > 0) {
                val desiredIso = baseIsoCode + remainingIsoEighths
                targetIsoCode = isoOpts.minByOrNull { abs(it - desiredIso) } ?: baseIsoCode
            }
        } else if (targetEighths < 0) { // Darkening (sunrise)
            // Lower ISO first
            val desiredIso = baseIsoCode + targetEighths
            val bestIso = isoOpts.minByOrNull { abs(it - desiredIso) } ?: baseIsoCode
            targetIsoCode = bestIso
            val isoEighthsUsed = targetIsoCode - baseIsoCode
            val remainingShutterEighths = targetEighths - isoEighthsUsed
            if (remainingShutterEighths < 0) {
                val desiredShutter = baseShutterCode - remainingShutterEighths
                targetShutterCode = shutterOpts.minByOrNull { abs(it - desiredShutter) } ?: baseShutterCode
            }
        }

        val rS = setPropRetry(Settings.SHUTTER.id, targetShutterCode)
        val rI = setPropRetry(Settings.ISO.id, targetIsoCode)
        dbg("ExposureRamp shot ${shotIdx + 1}/${plan.count}: targetEighths=$targetEighths shutter=0x${targetShutterCode.toString(16)} (res=0x${rS.code.toString(16)}) iso=0x${targetIsoCode.toString(16)} (res=0x${rI.code.toString(16)})")
    }

    private fun tickInterval(): String? {
        if (!intervalRunning) return null
        val plan = currentPlan ?: run {
            intervalRunning = false
            return null
        }
        val now = System.currentTimeMillis()

        val shotsLeftCam = values[Settings.SHOTS.id] ?: -1
        val battVal = values[Settings.BATTERY.id] ?: -1
        val totalCountStr = if (plan.count > 0) plan.count.toString() else "inf"

        if (intervalPaused) {
            intervalInfo = "PAUSED  ·  Shot $doneShots/$totalCountStr"
            return null
        }

        // Safety stops checked before each frame
        if (noCard) {
            intervalRunning = false
            intervalInfo = ""
            dbg("Interval stopped: No memory card")
            return "No memory card"
        }
        if (shotsLeftCam == 0) {
            intervalRunning = false
            intervalInfo = ""
            dbg("Interval stopped: Card full")
            return "Card full"
        }
        if (battVal == 0) {
            intervalRunning = false
            intervalInfo = ""
            dbg("Interval stopped: Battery low")
            return "Battery low"
        }

        if (now < startAt) {
            val startInSec = ((startAt - now + 999) / 1000).coerceAtLeast(1)
            intervalInfo = "Starts in $startInSec s"
            return null
        }

        // Calculate current interval for interval ramp
        val currIntervalMs = if (plan.count > 1 && plan.endIntervalMs != plan.intervalMs) {
            val prog = (doneShots).toFloat() / (plan.count - 1).toFloat()
            (plan.intervalMs + prog * (plan.endIntervalMs - plan.intervalMs)).toLong().coerceAtLeast(1000L)
        } else {
            plan.intervalMs
        }

        // Requirement e: Live View Off between shots for intervals >= 10s
        if (currIntervalMs >= 10_000L && evfTurnedOffBetweenShots && (nextAt - now <= 2000L)) {
            dbg("Interval: waking EVF 2s before next shot")
            setPropRetry(PROP_EVF_MODE, 1)
            setPropRetry(PROP_EVF_OUTPUT_DEVICE, 2)
            evfTurnedOffBetweenShots = false
        }

        if (now < nextAt) {
            val nextInSec = ((nextAt - now + 999) / 1000).coerceAtLeast(0)
            val remShots = if (plan.count > 0) (plan.count - doneShots).coerceAtLeast(0) else 0
            val remSec = if (plan.count > 0) (remShots * (currIntervalMs / 1000)) else 0L
            val leftHms = if (plan.count > 0) formatHms(remSec) else "inf"
            val battLabel = Settings.label(Settings.BATTERY, battVal)
            val camLeftStr = if (shotsLeftCam >= 0) "$shotsLeftCam left" else "? left"

            intervalInfo = "Shot $doneShots/$totalCountStr  ·  next in ${nextInSec}s  ·  left $leftHms  ·  camera $camLeftStr  ·  batt $battLabel"
            return null
        }

        // Spacing is start-to-start
        val t0 = System.currentTimeMillis()
        nextAt = t0 + currIntervalMs
        doneShots++

        // Requirement c: Exposure ramp before shot
        applyExposureRamp(plan, doneShots - 1, currIntervalMs)

        dbg("Interval shot $doneShots/$totalCountStr starting...")

        var err: String? = if (plan.shotsPerInterval > 1) {
            doBracket(plan.shotsPerInterval, plan.bracketStepThirds)
        } else {
            shoot(af = false)
        }

        if (err != null) {
            var retryOk = false
            for (rTry in 1..3) {
                Thread.sleep(1000)
                pollEvents()
                dbg("Interval shot retry $rTry/3...")
                val rErr = if (plan.shotsPerInterval > 1) doBracket(plan.shotsPerInterval, plan.bracketStepThirds) else shoot(af = false)
                if (rErr == null) {
                    retryOk = true
                    err = null
                    break
                } else {
                    err = rErr
                }
            }
            if (!retryOk) {
                failedFrames++
                consecutiveFailures++
                dbg("Interval shot $doneShots failed 3 retries: $err")
                if (consecutiveFailures >= 3) {
                    intervalRunning = false
                    intervalInfo = ""
                    val msg = "Interval stopped: 3 consecutive failed shots ($err)"
                    dbg(msg)
                    return msg
                }
            } else {
                consecutiveFailures = 0
            }
        } else {
            consecutiveFailures = 0
        }

        val shotDuration = System.currentTimeMillis() - t0
        if (shotDuration > currIntervalMs) {
            nextAt = System.currentTimeMillis()
            lateShots++
            dbg("Interval shot $doneShots took $shotDuration ms (> interval $currIntervalMs ms), marked late")
        }

        // Requirement e: Turn off EVF between shots if interval >= 10s
        if (currIntervalMs >= 10_000L && intervalRunning) {
            dbg("Interval: turning EVF output off between shots")
            setProp(PROP_EVF_OUTPUT_DEVICE, 0)
            evfTurnedOffBetweenShots = true
        }

        if (plan.count in 1..doneShots) {
            if (evfTurnedOffBetweenShots) {
                setPropRetry(PROP_EVF_MODE, 1)
                setPropRetry(PROP_EVF_OUTPUT_DEVICE, 2)
                evfTurnedOffBetweenShots = false
            }
            val totalElapsedSec = (System.currentTimeMillis() - intervalStartTimeMs) / 1000
            intervalSummaryAtEnd = "Done: $doneShots shots in ${formatHms(totalElapsedSec)}, $lateShots late, $failedFrames error"
            intervalRunning = false
            intervalInfo = ""
            dbg("Interval finished: $intervalSummaryAtEnd")
        } else {
            val nextInSec = ((nextAt - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
            val remShots = if (plan.count > 0) (plan.count - doneShots).coerceAtLeast(0) else 0
            val remSec = if (plan.count > 0) (remShots * (currIntervalMs / 1000)) else 0L
            val leftHms = if (plan.count > 0) formatHms(remSec) else "inf"
            val battLabel = Settings.label(Settings.BATTERY, battVal)
            val camLeftStr = if (shotsLeftCam >= 0) "$shotsLeftCam left" else "? left"

            intervalInfo = "Shot $doneShots/$totalCountStr  ·  next in ${nextInSec}s  ·  left $leftHms  ·  camera $camLeftStr  ·  batt $battLabel"
        }

        return err
    }

    private fun doBracket(frames: Int, stepThirds: Int): String? {
        val aeMode = values[Settings.AE_MODE.id] ?: -1
        var err: String? = null
        dbg("Start Bracket frames=$frames stepThirds=$stepThirds aeMode=$aeMode")

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
                    val snappedCode = shutterOpts.minByOrNull { abs(it - targetCode) } ?: targetCode

                    dbg("Bracket shutter step k=$k targetCode=$targetCode snappedCode=$snappedCode")
                    val rSet = setPropRetry(Settings.SHUTTER.id, snappedCode)
                    if (rSet.code != RC_OK) {
                        return "Bracket: camera refused shutter change (0x${rSet.code.toString(16)})"
                    }

                    var verified = false
                    repeat(5) {
                        pollEvents()
                        if (values[Settings.SHUTTER.id] == snappedCode) {
                            verified = true
                            return@repeat
                        }
                        Thread.sleep(100)
                    }
                    val currentVal = values[Settings.SHUTTER.id] ?: 0
                    dbg("Bracket set shutter result: res=0x${rSet.code.toString(16)} currentVal=$currentVal verified=$verified")
                    if (!verified) {
                        val stillLabel = Settings.label(Settings.SHUTTER, currentVal)
                        return "Bracket: value did not change (still $stillLabel)"
                    }

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
                    val snappedCode = expOpts.minByOrNull { abs(it.toByte().toInt() - targetSigned) } ?: baseCode

                    dbg("Bracket expComp step k=$k targetSigned=$targetSigned snappedCode=$snappedCode")
                    val rSet = setPropRetry(Settings.EXP_COMP.id, snappedCode)
                    if (rSet.code != RC_OK) {
                        return "Bracket: camera refused exp comp change (0x${rSet.code.toString(16)})"
                    }

                    var verified = false
                    repeat(5) {
                        pollEvents()
                        if (values[Settings.EXP_COMP.id] == snappedCode) {
                            verified = true
                            return@repeat
                        }
                        Thread.sleep(100)
                    }
                    val currentVal = values[Settings.EXP_COMP.id] ?: 0
                    dbg("Bracket set expComp result: res=0x${rSet.code.toString(16)} currentVal=$currentVal verified=$verified")
                    if (!verified) {
                        val stillLabel = Settings.label(Settings.EXP_COMP, currentVal)
                        return "Bracket: value did not change (still $stillLabel)"
                    }

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
        if (noCard) {
            dbg("shoot() rejected: noCard is true")
            return "No memory card: camera would only save to PC"
        }
        pollEvents()
        var err: String? = null
        if (af) {
            val half = ptp.transact(OC_EOS_REMOTE_RELEASE_ON, intArrayOf(1, 0), timeoutMs = 10_000)
            dbg("shoot(af=true) half-press: 0x${half.code.toString(16)}")
            pollEvents()
            val full = ptp.transact(OC_EOS_REMOTE_RELEASE_ON, intArrayOf(2, 0), timeoutMs = 10_000)
            dbg("shoot(af=true) full-press: 0x${full.code.toString(16)}")
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
            dbg("shoot(af=false) full-press no-AF: 0x${full.code.toString(16)}")
            if (full.code != RC_OK) {
                err = "Shutter failed: 0x${full.code.toString(16)}"
            }
            ptp.transact(OC_EOS_REMOTE_RELEASE_OFF, intArrayOf(3))
        }

        val shutterCode = values[Settings.SHUTTER.id] ?: 0x68
        val expSec = if (shutterCode <= 0x04) 0.0 else (1.0 / 60.0) * Math.pow(2.0, (0x68 - shutterCode) / 8.0)
        val sleepMs = ((expSec + 0.6) * 1000.0).toLong().coerceIn(400L, 120_000L)
        dbg("shoot sleeping $sleepMs ms for exposure")
        Thread.sleep(sleepMs)
        pollEvents()

        val rEvf1 = setPropRetry(PROP_EVF_MODE, 1)
        val rEvf2 = setPropRetry(PROP_EVF_OUTPUT_DEVICE, 2)
        dbg("shoot EVF restore res1=0x${rEvf1.code.toString(16)} res2=0x${rEvf2.code.toString(16)}")
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
                        val newV = bb.getInt(pos + 12) and p.mask
                        if (values[prop] != newV) {
                            values[prop] = newV
                            changed = true
                            if (prop == Settings.AE_MODE.id) {
                                lastAeModeChangeTime = System.currentTimeMillis()
                                dbg("AE_MODE changed to 0x${newV.toString(16)}")
                            } else if (prop == Settings.CAPTURE_DEST.id) {
                                dbg("CAPTURE_DEST changed to $newV")
                            }
                        }
                    }
                }
                EC_AVAIL_LIST_CHANGED -> if (size >= 20) {
                    val prop = bb.getInt(pos + 8)
                    val p = tracked[prop]
                    val type = bb.getInt(pos + 12)
                    val count = bb.getInt(pos + 16)
                    if (p != null && type == 3) {
                        if (count == 0) {
                            options.remove(prop)
                            changed = true
                            dbg("Options for prop=0x${prop.toString(16)} cleared (count=0)")
                        } else if (count in 1..512 && 20 + count * 4 <= size) {
                            options[prop] = List(count) { bb.getInt(pos + 20 + it * 4) and p.mask }
                            changed = true
                        }
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
            checkEvfRecovery(now)
            return null
        }
        if (r.code != RC_OK) {
            checkEvfRecovery(now)
            throw PtpException("GetViewFinder failed: 0x${r.code.toString(16)}")
        }
        val d = r.data
        if (d == null) {
            lastInfo = "OK but no data"
            checkEvfRecovery(now)
            return null
        }
        val jpg = extractJpeg(d)
        if (jpg != null) {
            lastViewFrame = jpg
            lastFrameTime = now
        } else {
            checkEvfRecovery(now)
        }
        lastInfo = if (jpg == null) "no JPEG in ${d.size} bytes" else ""
        return jpg
    }

    private fun checkEvfRecovery(now: Long) {
        if (now - lastFrameTime > 3000 && now - lastEvfRecoveryTime > 3000) {
            lastEvfRecoveryTime = now
            dbg("Live View lost >3s, running EVF recovery...")
            setPropRetry(PROP_EVF_MODE, 1)
            setPropRetry(PROP_EVF_OUTPUT_DEVICE, 2)
        }
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