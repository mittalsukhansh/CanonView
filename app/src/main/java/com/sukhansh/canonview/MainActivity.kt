package com.sukhansh.canonview

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round

class MainActivity : AppCompatActivity() {

    private val tag = "CanonView"
    private val permAction = "com.sukhansh.canonview.USB_PERMISSION"
    private val canonVendorId = 0x04A9

    private lateinit var usb: UsbManager
    private lateinit var image: ImageView
    private lateinit var status: TextView
    private lateinit var hud: HudView
    private lateinit var buttons: Map<Settings.Prop, Button>
    private lateinit var intervalBtn: Button

    // Photo Preview Overlay
    private lateinit var previewCard: FrameLayout
    private lateinit var previewImage: ImageView
    private var lastPreviewBitmap: Bitmap? = null
    private var autoHidePreviewRunnable: Runnable? = null

    // Full-Screen Photo Preview Overlay
    private lateinit var fullPreviewOverlay: FrameLayout
    private lateinit var fullPreviewImage: ImageView
    private var autoHideFullPreviewRunnable: Runnable? = null

    // Debug Log Overlay
    private lateinit var logOverlay: TextView
    private var showLogOverlay = false

    @Volatile private var running = false
    @Volatile private var session: CanonSession? = null
    private var worker: Thread? = null

    private var currentZoomIndex = 0
    private val zoomLevels = intArrayOf(1, 5, 10)

    // Software Countdown Timer (0 = Off, 2 = 2s, 5 = 5s, 10 = 10s)
    private var selectedTimerSec = 0
    private val timerOptions = intArrayOf(0, 2, 5, 10)
    private var currentTimerIndex = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isCountingDown = false

    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val dev: UsbDevice? =
                if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            if (granted && dev != null) startStream(dev) else setStatus("USB permission denied")
        }
    }

    private fun applyImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveMode()
        }
    }

    private fun openFullPreviewOverlay() {
        val bmp = lastPreviewBitmap ?: return
        fullPreviewImage.setImageBitmap(bmp)
        fullPreviewOverlay.visibility = View.VISIBLE

        autoHidePreviewRunnable?.let { mainHandler.removeCallbacks(it) }
        previewCard.visibility = View.GONE

        autoHideFullPreviewRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable { closeFullPreviewOverlay() }
        autoHideFullPreviewRunnable = runnable
        mainHandler.postDelayed(runnable, 10_000L)
    }

    private fun closeFullPreviewOverlay() {
        autoHideFullPreviewRunnable?.let { mainHandler.removeCallbacks(it) }
        fullPreviewOverlay.visibility = View.GONE
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersiveMode()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        usb = getSystemService(USB_SERVICE) as UsbManager

        val density = resources.displayMetrics.density

        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        hud = HudView(this)
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            setOnLongClickListener {
                showLogOverlay = !showLogOverlay
                logOverlay.visibility = if (showLogOverlay) View.VISIBLE else View.GONE
                Toast.makeText(this@MainActivity, if (showLogOverlay) "Log Overlay On" else "Log Overlay Off", Toast.LENGTH_SHORT).show()
                true
            }
        }

        logOverlay = TextView(this).apply {
            setTextColor(Color.GREEN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            setBackgroundColor(0x88000000.toInt())
            visibility = View.GONE
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
        }

        buttons = Settings.all.associateWith { p ->
            Button(this).apply {
                val btnText = "${p.name} -"
                text = btnText
                setOnClickListener { pick(p) }
            }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0x99000000.toInt())
            buttons.values.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        }

        // Tap on the picture = move focus frame there + autofocus
        image.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                val d = image.drawable
                val inv = Matrix()
                if (d != null && image.imageMatrix.invert(inv)) {
                    val pt = floatArrayOf(ev.x, ev.y)
                    inv.mapPoints(pt)
                    if (pt[0] in 0f..d.intrinsicWidth.toFloat() && pt[1] in 0f..d.intrinsicHeight.toFloat()) {
                        session?.requestFocus(pt[0].toInt(), pt[1].toInt())
                        Toast.makeText(this, "AF sent", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            true
        }

        // --- Side Tools Layout ---
        val shutter = Button(this).apply {
            val shootLabel = "SHOOT"
            text = shootLabel
            setOnClickListener {
                val s = session
                if (s == null) {
                    Toast.makeText(this@MainActivity, "Camera not connected", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (isCountingDown) return@setOnClickListener

                if (selectedTimerSec > 0) {
                    startCountdown(selectedTimerSec) { s.requestShoot() }
                } else {
                    s.requestShoot()
                    Toast.makeText(this@MainActivity, "Shooting...", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val timerBtn = Button(this).apply {
            text = "TIMER OFF"
            setOnClickListener {
                currentTimerIndex = (currentTimerIndex + 1) % timerOptions.size
                selectedTimerSec = timerOptions[currentTimerIndex]
                text = if (selectedTimerSec == 0) "TIMER OFF" else "TIMER ${selectedTimerSec}s"
            }
        }

        val flash = Button(this).apply {
            text = "FLASH"
            setOnClickListener { session?.requestFlash() ?: Toast.makeText(this@MainActivity, "Camera not connected", Toast.LENGTH_SHORT).show() }
        }

        val zoomBtn = Button(this).apply {
            text = "ZOOM 1x"
            setOnClickListener {
                currentZoomIndex = (currentZoomIndex + 1) % zoomLevels.size
                val lvl = zoomLevels[currentZoomIndex]
                text = "ZOOM ${lvl}x"
                session?.requestZoom(lvl)
            }
        }

        val peakBtn = Button(this).apply {
            text = "PEAK OFF"
            setOnClickListener {
                hud.enableFocusPeaking = !hud.enableFocusPeaking
                text = if (hud.enableFocusPeaking) "PEAK ON" else "PEAK OFF"
                hud.invalidate()
            }
        }

        val aspectBtn = Button(this).apply {
            text = "CROP OFF"
            setOnClickListener {
                hud.aspectGuideMode = (hud.aspectGuideMode + 1) % 3
                text = when (hud.aspectGuideMode) {
                    1 -> "CROP 9:16"
                    2 -> "CROP 1:1"
                    else -> "CROP OFF"
                }
                hud.invalidate()
            }
        }

        val gridBtn = Button(this).apply {
            text = "GRID OFF"
            textSize = 12f
            minHeight = 0
            setOnClickListener {
                hud.gridMode = (hud.gridMode + 1) % 3
                text = when (hud.gridMode) {
                    1 -> "GRID 3x3"
                    2 -> "GRID 3x3+"
                    else -> "GRID OFF"
                }
                hud.invalidate()
            }
        }

        intervalBtn = Button(this).apply {
            text = "INTERVAL"
            textSize = 12f
            minHeight = 0
            setOnClickListener {
                val s = session
                if (s == null) {
                    Toast.makeText(this@MainActivity, "Camera not connected", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!s.intervalRunning) {
                    showIntervalDialog(s)
                } else {
                    val options = if (s.intervalPaused) arrayOf("Resume", "Stop") else arrayOf("Pause", "Stop")
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Intervalometer")
                        .setItems(options) { _, which ->
                            if (s.intervalPaused) {
                                if (which == 0) s.requestResumeInterval() else s.requestStopInterval()
                            } else {
                                if (which == 0) s.requestPauseInterval() else s.requestStopInterval()
                            }
                        }
                        .show()
                }
            }
        }

        val bracketBtn = Button(this).apply {
            text = "BRACKET"
            textSize = 12f
            minHeight = 0
            setOnClickListener {
                val s = session
                if (s == null) {
                    Toast.makeText(this@MainActivity, "Camera not connected", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                showBracketDialog(s)
            }
        }

        // MF Lens Step Buttons: Near / Far
        val nearFarBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val n2 = Button(this@MainActivity).apply {
                text = "N<<"
                setOnClickListener { session?.requestDriveLens(0x0003) }
            }
            val n1 = Button(this@MainActivity).apply {
                text = "N<"
                setOnClickListener { session?.requestDriveLens(0x0001) }
            }
            val f1 = Button(this@MainActivity).apply {
                text = "F>"
                setOnClickListener { session?.requestDriveLens(0x8001) }
            }
            val f2 = Button(this@MainActivity).apply {
                text = "F>>"
                setOnClickListener { session?.requestDriveLens(0x8003) }
            }
            addView(n2, LinearLayout.LayoutParams(0, -2, 1f))
            addView(n1, LinearLayout.LayoutParams(0, -2, 1f))
            addView(f1, LinearLayout.LayoutParams(0, -2, 1f))
            addView(f2, LinearLayout.LayoutParams(0, -2, 1f))
        }

        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(shutter)
            addView(timerBtn)
            addView(flash)
            addView(zoomBtn)
            addView(peakBtn)
            addView(aspectBtn)
            addView(gridBtn)
            addView(intervalBtn)
            addView(bracketBtn)
            addView(nearFarBar)
        }

        val sideWidthPx = (130 * density).toInt()
        val sideMarginEndPx = (12 * density).toInt()
        val sideTopMarginPx = (32 * density).toInt()
        val fallbackBottomMarginPx = (64 * density).toInt()

        val sideScrollParams = FrameLayout.LayoutParams(
            sideWidthPx,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.END
        ).apply {
            topMargin = sideTopMarginPx
            bottomMargin = fallbackBottomMarginPx
            marginEnd = sideMarginEndPx
        }

        val sideScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(side)
        }

        bar.post {
            val barHeight = bar.height
            if (barHeight > 0) {
                val params = sideScroll.layoutParams as? FrameLayout.LayoutParams
                if (params != null) {
                    params.bottomMargin = barHeight
                    sideScroll.layoutParams = params
                }
            }
        }

        // --- Photo Preview Overlay ---
        previewImage = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val dismissBtn = Button(this).apply {
            text = "CLOSE"
            setOnClickListener {
                autoHidePreviewRunnable?.let { mainHandler.removeCallbacks(it) }
                previewCard.visibility = View.GONE
            }
        }
        val previewWidthPx = (240 * density).toInt()
        val previewHeightPx = (180 * density).toInt()
        val previewMarginStartPx = (16 * density).toInt()

        previewCard = FrameLayout(this).apply {
            setBackgroundColor(0xEE000000.toInt())
            visibility = View.GONE
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            addView(previewImage, FrameLayout.LayoutParams(-1, -1))
            addView(dismissBtn, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
            setOnClickListener {
                if (lastPreviewBitmap != null) {
                    openFullPreviewOverlay()
                }
            }
        }

        // --- Full-Screen Photo Preview Overlay ---
        fullPreviewImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { closeFullPreviewOverlay() }
        }
        val fullCloseBtn = Button(this).apply {
            text = "CLOSE"
            setOnClickListener { closeFullPreviewOverlay() }
        }
        val fullCloseMarginPx = (16 * density).toInt()

        fullPreviewOverlay = FrameLayout(this).apply {
            setBackgroundColor(0xF0000000.toInt())
            visibility = View.GONE
            addView(fullPreviewImage, FrameLayout.LayoutParams(-1, -1))
            addView(fullCloseBtn, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = fullCloseMarginPx
                marginEnd = fullCloseMarginPx
            })
        }

        val rootLayout = FrameLayout(this).apply {
            addView(image, FrameLayout.LayoutParams(-1, -1))
            addView(hud, FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
            addView(logOverlay, FrameLayout.LayoutParams((350 * density).toInt(), (200 * density).toInt(), Gravity.TOP or Gravity.START).apply {
                topMargin = (90 * density).toInt()
                marginStart = (12 * density).toInt()
            })
            addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            addView(sideScroll, sideScrollParams)
            addView(previewCard, FrameLayout.LayoutParams(previewWidthPx, previewHeightPx, Gravity.START or Gravity.CENTER_VERTICAL).apply {
                marginStart = previewMarginStartPx
                topMargin = (28 * density).toInt()
            })
            addView(fullPreviewOverlay, FrameLayout.LayoutParams(-1, -1))
        }

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            val cutout = insets.displayCutout
            val left = cutout?.safeInsetLeft ?: 0
            val top = cutout?.safeInsetTop ?: 0
            val right = cutout?.safeInsetRight ?: 0
            val bottom = cutout?.safeInsetBottom ?: 0
            rootLayout.setPadding(left, top, right, bottom)
            insets
        }

        setContentView(rootLayout)

        ContextCompat.registerReceiver(
            this, permReceiver, IntentFilter(permAction), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun showIntervalDialog(s: CanonSession) {
        val prefs = getSharedPreferences("CanonViewPrefs", MODE_PRIVATE)
        val density = resources.displayMetrics.density
        val padPx = (16 * density).toInt()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padPx, padPx / 2, padPx, padPx / 2)
        }

        val delayInput = EditText(this).apply {
            hint = "Start delay (s) or Clock HH:MM"
            setText(prefs.getString("interval_delay_str", "0"))
        }
        val intervalInput = EditText(this).apply {
            hint = "Start interval in seconds (default 10)"
            setText(prefs.getInt("interval_sec", 10).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val endIntervalInput = EditText(this).apply {
            hint = "End interval in seconds (optional ramp)"
            setText(prefs.getInt("interval_end_sec", 10).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val shotsInput = EditText(this).apply {
            hint = "Shots count (0 = until stopped)"
            setText(prefs.getInt("interval_shots", 0).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val shotsPerIntervalInput = EditText(this).apply {
            hint = "Shots per interval (1, 3, 5)"
            setText(prefs.getInt("interval_spi", 1).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val evRampInput = EditText(this).apply {
            hint = "Total EV Exposure Ramp (e.g. +6.0 for sunset, -4.0)"
            setText(prefs.getFloat("interval_ev_ramp", 0f).toString())
        }
        val clipLenInput = EditText(this).apply {
            hint = "Clip length in seconds (optional)"
            setText(prefs.getInt("interval_clip_len", 0).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val fpsInput = EditText(this).apply {
            hint = "FPS (24 / 25 / 30)"
            setText(prefs.getInt("interval_fps", 24).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        val summaryText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, padPx / 2, 0, padPx / 4)
        }

        val warningText = TextView(this).apply {
            setTextColor(Color.RED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, 0, 0, padPx / 2)
        }

        val scrollContainer = ScrollView(this).apply {
            addView(layout)
        }

        layout.addView(TextView(this).apply { text = "Start delay (seconds) OR Clock HH:MM:" })
        layout.addView(delayInput)
        layout.addView(TextView(this).apply { text = "Start Interval (seconds):" })
        layout.addView(intervalInput)
        layout.addView(TextView(this).apply { text = "End Interval (seconds, for ramp):" })
        layout.addView(endIntervalInput)
        layout.addView(TextView(this).apply { text = "Number of shots (0 = until stopped):" })
        layout.addView(shotsInput)
        layout.addView(TextView(this).apply { text = "Shots per interval (1, 3, 5):" })
        layout.addView(shotsPerIntervalInput)
        layout.addView(TextView(this).apply { text = "Total EV Exposure Ramp (+/- EV):" })
        layout.addView(evRampInput)
        layout.addView(TextView(this).apply { text = "Planner - Clip length (seconds, optional):" })
        layout.addView(clipLenInput)
        layout.addView(TextView(this).apply { text = "Planner - Clip FPS (24 / 25 / 30):" })
        layout.addView(fpsInput)
        layout.addView(summaryText)
        layout.addView(warningText)

        var dialog: AlertDialog? = null
        var isUpdatingText = false

        fun parseDelayMs(inputStr: String): Pair<Long, String?> {
            val trimmed = inputStr.trim()
            if (trimmed.contains(":")) {
                val parts = trimmed.split(":")
                if (parts.size == 2) {
                    val h = parts[0].toIntOrNull()
                    val m = parts[1].toIntOrNull()
                    if (h != null && m != null && h in 0..23 && m in 0..59) {
                        val cal = Calendar.getInstance()
                        cal.set(Calendar.HOUR_OF_DAY, h)
                        cal.set(Calendar.MINUTE, m)
                        cal.set(Calendar.SECOND, 0)
                        cal.set(Calendar.MILLISECOND, 0)
                        var targetMs = cal.timeInMillis
                        val nowMs = System.currentTimeMillis()
                        if (targetMs <= nowMs) {
                            targetMs += 86_400_000L // Next day
                        }
                        return Pair(targetMs - nowMs, "%02d:%02d".format(h, m))
                    }
                }
            }
            val sec = trimmed.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            return Pair(sec * 1000L, null)
        }

        fun updateSummaryAndWarnings() {
            if (isUpdatingText) return
            val delayStr = delayInput.text.toString()
            val (delayMs, clockStr) = parseDelayMs(delayStr)
            val delaySec = delayMs / 1000L

            val intervalSec = intervalInput.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 10
            val endIntervalSec = endIntervalInput.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: intervalSec
            var shotsVal = shotsInput.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
            val spiVal = shotsPerIntervalInput.text.toString().toIntOrNull()?.let { if (it in listOf(3, 5)) it else 1 } ?: 1
            val totalEvRampVal = evRampInput.text.toString().toFloatOrNull() ?: 0f

            val clipSec = clipLenInput.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
            var fpsVal = fpsInput.text.toString().toIntOrNull() ?: 24
            if (fpsVal !in listOf(24, 25, 30)) fpsVal = 24

            if (clipSec > 0) {
                val autoShots = clipSec * fpsVal
                if (shotsVal != autoShots) {
                    shotsVal = autoShots
                    isUpdatingText = true
                    shotsInput.setText(autoShots.toString())
                    isUpdatingText = false
                }
            }

            val shutterCode = s.values[Settings.SHUTTER.id] ?: 0x68
            val baseIsoCode = s.values[Settings.ISO.id] ?: 0x0048
            val exposureSec = if (shutterCode <= 0x04) 0.0 else (1.0 / 60.0) * Math.pow(2.0, (0x68 - shutterCode) / 8.0)
            val minInterval = Math.ceil(exposureSec + 3.0).toInt()

            val effectiveShots = if (shotsVal > 0) shotsVal else 0
            val avgIntervalSec = (intervalSec + endIntervalSec) / 2.0
            val shootTimeSec = delaySec + if (effectiveShots > 0) (effectiveShots - 1) * avgIntervalSec.toInt() + exposureSec.toInt() else 0
            val clipLenSec = if (fpsVal > 0 && effectiveShots > 0) effectiveShots / fpsVal else 0
            val finishTimeMs = System.currentTimeMillis() + shootTimeSec * 1000L
            val finishFormat = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(finishTimeMs))

            // Planned End Settings Calculation for Exposure Ramp
            var endShutterStr = Settings.label(Settings.SHUTTER, shutterCode)
            var endIsoStr = Settings.label(Settings.ISO, baseIsoCode)
            if (effectiveShots > 1 && totalEvRampVal != 0f && s.values[Settings.AE_MODE.id] == 3) {
                val targetEighths = round(totalEvRampVal * 8f).toInt()
                val shutterOpts = s.options[Settings.SHUTTER.id] ?: emptyList()
                val isoOpts = s.options[Settings.ISO.id] ?: emptyList()

                var endSCode = shutterCode
                var endICode = baseIsoCode

                if (targetEighths > 0) { // Brightening
                    val maxExpSec = (endIntervalSec.toDouble()) - 3.0
                    val desiredShutterCode = shutterCode - targetEighths
                    val bestShutter = shutterOpts.filter { code ->
                        val expSec = if (code <= 0x04) 0.0 else (1.0 / 60.0) * Math.pow(2.0, (0x68 - code) / 8.0)
                        expSec <= maxExpSec
                    }.minByOrNull { abs(it - desiredShutterCode) } ?: shutterCode

                    endSCode = bestShutter
                    val shutterEighthsUsed = shutterCode - endSCode
                    val remainingIsoEighths = targetEighths - shutterEighthsUsed
                    if (remainingIsoEighths > 0) {
                        val desiredIso = baseIsoCode + remainingIsoEighths
                        endICode = isoOpts.minByOrNull { abs(it - desiredIso) } ?: baseIsoCode
                    }
                } else if (targetEighths < 0) { // Darkening
                    val desiredIso = baseIsoCode + targetEighths
                    val bestIso = isoOpts.minByOrNull { abs(it - desiredIso) } ?: baseIsoCode
                    endICode = bestIso
                    val isoEighthsUsed = endICode - baseIsoCode
                    val remainingShutterEighths = targetEighths - isoEighthsUsed
                    if (remainingShutterEighths < 0) {
                        val desiredShutter = shutterCode - remainingShutterEighths
                        endSCode = shutterOpts.minByOrNull { abs(it - desiredShutter) } ?: shutterCode
                    }
                }

                endShutterStr = Settings.label(Settings.SHUTTER, endSCode)
                endIsoStr = Settings.label(Settings.ISO, endICode)
            }

            val shotsLeftCam = s.values[Settings.SHOTS.id] ?: -1
            val battVal = s.values[Settings.BATTERY.id] ?: -1
            val battLabel = Settings.label(Settings.BATTERY, battVal)
            val camLeftStr = if (shotsLeftCam >= 0) "$shotsLeftCam" else "?"

            fun fmtHms(sec: Long): String {
                val h = sec / 3600
                val m = (sec % 3600) / 60
                val sc = sec % 60
                return if (h > 0) "%d:%02d:%02d".format(h, m, sc) else "%d:%02d".format(m, sc)
            }

            val rampStr = if (totalEvRampVal != 0f) " | end $endShutterStr ISO $endIsoStr" else ""
            val clockStartStr = if (clockStr != null) " (start @ $clockStr)" else ""

            val spiStr = if (spiVal > 1) " ($spiVal shots/int)" else ""
            val summary = "$shotsVal shots$spiStr -> ${clipLenSec}s clip @ ${fpsVal}fps$clockStartStr | duration ${fmtHms(shootTimeSec)} | finishes ~$finishFormat$rampStr | camera $camLeftStr left | batt $battLabel"
            summaryText.text = summary

            val warnings = ArrayList<String>()
            var blocking = false

            if (intervalSec < minInterval) {
                warnings.add("Interval too short for shutter speed (min ${minInterval}s)")
                blocking = true
            }
            if (shotsLeftCam > 0 && shotsVal > shotsLeftCam) {
                warnings.add("Card has room for only $shotsLeftCam shots")
                blocking = true
            }
            if (s.values[Settings.AE_MODE.id] != 3) {
                warnings.add("Use M mode: Av/P exposure can flicker")
            }
            if (exposureSec >= 1.0) {
                warnings.add("Turn off Long exposure noise reduction in camera menu")
            }
            if (battVal == 0) {
                warnings.add("Battery low")
            }

            warningText.text = warnings.joinToString("\n")
            dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !blocking
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateSummaryAndWarnings()
            }
        }

        delayInput.addTextChangedListener(watcher)
        intervalInput.addTextChangedListener(watcher)
        endIntervalInput.addTextChangedListener(watcher)
        shotsInput.addTextChangedListener(watcher)
        shotsPerIntervalInput.addTextChangedListener(watcher)
        evRampInput.addTextChangedListener(watcher)
        clipLenInput.addTextChangedListener(watcher)
        fpsInput.addTextChangedListener(watcher)

        dialog = AlertDialog.Builder(this)
            .setTitle("Intervalometer")
            .setView(scrollContainer)
            .setPositiveButton("Start") { _, _ ->
                val delayStr = delayInput.text.toString()
                val (delayMs, clockStr) = parseDelayMs(delayStr)
                val intervalSec = intervalInput.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 10
                val endIntervalSec = endIntervalInput.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: intervalSec
                val shotsVal = shotsInput.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
                val spiVal = shotsPerIntervalInput.text.toString().toIntOrNull()?.let { if (it in listOf(3, 5)) it else 1 } ?: 1
                val totalEvRampVal = evRampInput.text.toString().toFloatOrNull() ?: 0f
                val clipSec = clipLenInput.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
                var fpsVal = fpsInput.text.toString().toIntOrNull() ?: 24
                if (fpsVal !in listOf(24, 25, 30)) fpsVal = 24

                prefs.edit()
                    .putString("interval_delay_str", delayStr)
                    .putInt("interval_sec", intervalSec)
                    .putInt("interval_end_sec", endIntervalSec)
                    .putInt("interval_shots", shotsVal)
                    .putInt("interval_spi", spiVal)
                    .putFloat("interval_ev_ramp", totalEvRampVal)
                    .putInt("interval_clip_len", clipSec)
                    .putInt("interval_fps", fpsVal)
                    .apply()

                s.requestStartInterval(
                    IntervalPlan(
                        startDelayMs = delayMs,
                        intervalMs = intervalSec * 1000L,
                        endIntervalMs = endIntervalSec * 1000L,
                        count = shotsVal,
                        shotsPerInterval = spiVal,
                        bracketStepThirds = 1,
                        totalEvRamp = totalEvRampVal,
                        startTimeClock = clockStr
                    )
                )
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            updateSummaryAndWarnings()
        }
        dialog.show()
    }

    private fun showBracketDialog(s: CanonSession) {
        val optionsText = arrayOf(
            "3 x 1/3 EV (-1/3, 0, +1/3)",
            "3 x 2/3 EV (-2/3, 0, +2/3)",
            "3 x 1 EV (-1, 0, +1)",
            "3 x 2 EV (-2, 0, +2)",
            "5 x 1/3 EV (-2/3 .. +2/3)",
            "5 x 2/3 EV (-1 1/3 .. +1 1/3)",
            "5 x 1 EV (-2 .. +2)",
            "5 x 2 EV (-4 .. +4)",
            "7 x 1/3 EV (-1 .. +1)",
            "7 x 2/3 EV (-2 .. +2)",
            "7 x 1 EV (-3 .. +3)"
        )
        val params = arrayOf(
            3 to 1,
            3 to 2,
            3 to 3,
            3 to 6,
            5 to 1,
            5 to 2,
            5 to 3,
            5 to 6,
            7 to 1,
            7 to 2,
            7 to 3
        )

        AlertDialog.Builder(this)
            .setTitle("Exposure Bracketing")
            .setItems(optionsText) { _, i ->
                val (f, st) = params[i]
                s.requestBracket(f, st)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startCountdown(seconds: Int, onFinish: () -> Unit) {
        isCountingDown = true
        var remaining = seconds
        val runnable = object : Runnable {
            override fun run() {
                if (remaining > 0) {
                    setStatus("📸 Timer: $remaining...")
                    remaining--
                    mainHandler.postDelayed(this, 1000)
                } else {
                    isCountingDown = false
                    setStatus("Shooting...")
                    onFinish()
                }
            }
        }
        mainHandler.post(runnable)
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
        val dev = usb.deviceList.values.firstOrNull { it.vendorId == canonVendorId }
        if (dev == null) { setStatus("Plug in the camera (USB-OTG), camera ON"); return }
        if (usb.hasPermission(dev)) {
            startStream(dev)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0, Intent(permAction).setPackage(packageName), flags)
            usb.requestPermission(dev, pi)
        }
    }

    override fun onPause() {
        super.onPause()
        closeFullPreviewOverlay()
        autoHidePreviewRunnable?.let { mainHandler.removeCallbacks(it) }
        running = false
        worker?.join(2500)
        worker = null
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(permReceiver)
    }

    private fun setStatus(s: String) = runOnUiThread { status.text = s }

    private fun refreshButtons() {
        val s = session ?: return
        buttons.forEach { (p, b) ->
            val v = s.values[p.id]
            b.text = if (v == null) "${p.name} -" else "${p.name}\n${Settings.label(p, v)}"
        }
        refreshHud()
    }

    private fun refreshHud() {
        val s = session ?: return
        fun t(p: Settings.Prop) = s.values[p.id]?.let { Settings.label(p, it) } ?: "-"
        hud.mode = t(Settings.AE_MODE)
        hud.shutter = t(Settings.SHUTTER)
        hud.aperture = t(Settings.APERTURE)
        hud.iso = t(Settings.ISO)
        hud.extras = listOf("WB ${t(Settings.WB)}", t(Settings.DRIVE), t(Settings.METERING),
            t(Settings.FOCUS_MODE), t(Settings.STYLE)).joinToString("  ·  ")
        hud.battery = t(Settings.BATTERY)
        hud.shots = t(Settings.SHOTS)
        hud.compEv = s.values[Settings.EXP_COMP.id]?.let { it.toByte().toInt() / 8f }
        hud.isManual = s.values[Settings.AE_MODE.id] == 3
        hud.invalidate()
    }

    /** Brightness stats and focus peaking edge points from live view picture. */
    private fun analyze(bmp: Bitmap): Triple<Float, IntArray, FloatArray?> {
        val width = bmp.width
        val height = bmp.height
        val totalPixels = width * height
        val pixels = IntArray(totalPixels)
        bmp.getPixels(pixels, 0, width, 0, 0, width, height)

        val hist = IntArray(64)
        var sumLin = 0.0
        var n = 0

        // Histogram and exposure sampling step
        val histStep = 8
        var y = 1
        while (y < height - 1) {
            var x = 1
            while (x < width - 1) {
                val c = pixels[y * width + x]
                val r = (c shr 16) and 255
                val g = (c shr 8) and 255
                val b = c and 255
                val l = (r * 54 + g * 183 + b * 19) shr 8

                hist[(l / 4).coerceAtMost(63)]++
                sumLin += (l / 255.0).pow(2.2)
                n++
                x += histStep
            }
            y += histStep
        }

        // Advanced High-Resolution Focus Peaking with Sobel Luminance Gradient
        val edgePoints = if (hud.enableFocusPeaking) ArrayList<Float>() else null
        if (hud.enableFocusPeaking) {
            val peakStep = 2
            val threshold = 36
            var py = 1
            while (py < height - 1) {
                var px = 1
                while (px < width - 1) {
                    val idx = py * width + px
                    val cL = pixels[idx - 1]
                    val cR = pixels[idx + 1]
                    val cU = pixels[idx - width]
                    val cD = pixels[idx + width]

                    val lL = (((cL shr 16) and 255) * 54 + ((cL shr 8) and 255) * 183 + (cL and 255) * 19) shr 8
                    val lR = (((cR shr 16) and 255) * 54 + ((cR shr 8) and 255) * 183 + (cR and 255) * 19) shr 8
                    val lU = (((cU shr 16) and 255) * 54 + ((cU shr 8) and 255) * 183 + (cU and 255) * 19) shr 8
                    val lD = (((cD shr 16) and 255) * 54 + ((cD shr 8) and 255) * 183 + (cD and 255) * 19) shr 8

                    val dx = abs(lR - lL)
                    val dy = abs(lD - lU)
                    val grad = dx + dy

                    if (grad > threshold) {
                        edgePoints?.add(px.toFloat())
                        edgePoints?.add(py.toFloat())
                    }
                    px += peakStep
                }
                py += peakStep
            }
        }

        val mean = (sumLin / n.coerceAtLeast(1)).coerceAtLeast(1e-6)
        val ev = log2(mean / 0.18).toFloat().coerceIn(-3f, 3f)
        val ptsArray = if (edgePoints != null && edgePoints.isNotEmpty()) edgePoints.toFloatArray() else null
        return Triple(ev, hist, ptsArray)
    }

    private fun pick(p: Settings.Prop) {
        val s = session ?: return
        val opts = s.options[p.id]
        if (opts.isNullOrEmpty()) {
            Toast.makeText(this, "${p.name} not adjustable in this mode", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = opts.map { Settings.label(p, it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setItems(labels) { _, i -> s.requestSet(p.id, opts[i]) }
            .show()
    }

    private fun startStream(dev: UsbDevice) {
        if (running) return
        running = true
        worker = Thread {
            val conn = usb.openDevice(dev)
            if (conn == null) { setStatus("openDevice failed"); running = false; return@Thread }
            var cs: CanonSession? = null
            try {
                val iface = (0 until dev.interfaceCount).asSequence()
                    .map { dev.getInterface(it) }
                    .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }
                    ?: throw PtpException("no PTP interface")
                if (!conn.claimInterface(iface, true)) throw PtpException("claimInterface failed")
                val eps = (0 until iface.endpointCount).map { iface.getEndpoint(it) }
                val bulkIn = eps.first { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }
                val bulkOut = eps.first { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }

                cs = CanonSession(PtpUsb(conn, bulkIn, bulkOut))
                cs.onPhotoCaptured = { photoBytes ->
                    val shotBmp = BitmapFactory.decodeByteArray(photoBytes, 0, photoBytes.size)
                    if (shotBmp != null) {
                        lastPreviewBitmap = shotBmp
                        runOnUiThread {
                            closeFullPreviewOverlay()

                            previewImage.setImageBitmap(shotBmp)
                            previewCard.visibility = View.VISIBLE

                            autoHidePreviewRunnable?.let { mainHandler.removeCallbacks(it) }
                            val runnable = Runnable {
                                previewCard.visibility = View.GONE
                            }
                            autoHidePreviewRunnable = runnable
                            mainHandler.postDelayed(runnable, 6000L)
                        }
                    }
                }
                setStatus("Starting session...")
                cs.start()
                session = cs

                var frames = 0
                var t0 = System.currentTimeMillis()
                while (running) {
                    cs.applyPending()?.let { msg ->
                        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
                    }
                    if (cs.changed) { cs.changed = false; runOnUiThread { refreshButtons() } }

                    val jpg = cs.nextFrame()
                    if (jpg == null) {
                        val n = System.currentTimeMillis()
                        if (n - t0 >= 1000) { setStatus("No frame: ${cs.lastInfo}"); t0 = n }
                        Thread.sleep(50); continue
                    }
                    val bmp = BitmapFactory.decodeByteArray(jpg, 0, jpg.size) ?: continue
                    val (estEv, hist, focusPoints) = analyze(bmp)
                    runOnUiThread {
                        image.setImageBitmap(bmp)
                        hud.picW = bmp.width
                        hud.picH = bmp.height
                        hud.estEv = estEv
                        hud.hist = hist
                        hud.focusPoints = focusPoints
                        hud.invalidate()
                        intervalBtn.text = when {
                            cs.intervalPaused -> "PAUSED"
                            cs.intervalRunning -> "STOP"
                            else -> "INTERVAL"
                        }
                        if (showLogOverlay) {
                            logOverlay.text = cs.debugLogs.joinToString("\n")
                        }
                    }
                    frames++
                    val now = System.currentTimeMillis()
                    if (now - t0 >= 1000) {
                        val intervalStr = if (cs.intervalInfo.isNotEmpty()) "  ·  ${cs.intervalInfo}"
                        else if (cs.intervalSummaryAtEnd.isNotEmpty()) "  ·  ${cs.intervalSummaryAtEnd}"
                        else ""
                        setStatus("Live  $frames fps  ${bmp.width}x${bmp.height}$intervalStr")
                        frames = 0; t0 = now
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "stream error", e)
                setStatus("Error: ${e.message}")
            } finally {
                session = null
                cs?.stop()
                conn.close()
                running = false
            }
        }.also { it.start() }
    }
}