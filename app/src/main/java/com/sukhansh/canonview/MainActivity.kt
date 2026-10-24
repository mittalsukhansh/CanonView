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
import android.text.InputType
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
import androidx.core.graphics.get
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow

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
    private var autoHidePreviewRunnable: Runnable? = null

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
                if (s.intervalRunning) {
                    s.requestStopInterval()
                } else {
                    showIntervalDialog(s)
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
            })
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
        val density = resources.displayMetrics.density
        val padPx = (16 * density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padPx, padPx / 2, padPx, padPx / 2)
        }
        val intervalInput = EditText(this).apply {
            hint = "Interval in seconds (default 10)"
            setText("10")
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val countInput = EditText(this).apply {
            hint = "Shots count (0 = infinite)"
            setText("0")
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(TextView(this).apply { text = "Interval (seconds):" })
        layout.addView(intervalInput)
        layout.addView(TextView(this).apply { text = "Number of shots (0 = continuous):" })
        layout.addView(countInput)

        AlertDialog.Builder(this)
            .setTitle("Intervalometer")
            .setView(layout)
            .setPositiveButton("Start") { _, _ ->
                val sec = intervalInput.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 10
                val cnt = countInput.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
                s.requestStartInterval(sec, cnt)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBracketDialog(s: CanonSession) {
        val optionsText = arrayOf(
            "3 x 1/3 EV",
            "3 x 2/3 EV",
            "3 x 1 EV",
            "5 x 1/3 EV",
            "5 x 2/3 EV",
            "5 x 1 EV",
            "7 x 2/3 EV",
            "5 x 2 EV"
        )
        val params = arrayOf(
            3 to 1,
            3 to 2,
            3 to 3,
            5 to 1,
            5 to 2,
            5 to 3,
            7 to 2,
            5 to 6
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
        val hist = IntArray(64)
        var sumLin = 0.0
        var n = 0
        val step = 8
        val edgePoints = ArrayList<Float>()
        var y = 1
        while (y < bmp.height - 1) {
            var x = 1
            while (x < bmp.width - 1) {
                val c = bmp[x, y]
                val l = 0.2126 * ((c shr 16) and 255) + 0.7152 * ((c shr 8) and 255) + 0.0722 * (c and 255)
                hist[(l / 4).toInt().coerceAtMost(63)]++
                sumLin += (l / 255.0).pow(2.2)
                n++

                if (hud.enableFocusPeaking) {
                    val cRight = bmp[x + 1, y]
                    val lRight = 0.2126 * ((cRight shr 16) and 255) + 0.7152 * ((cRight shr 8) and 255) + 0.0722 * (cRight and 255)
                    val cDown = bmp[x, y + 1]
                    val lDown = 0.2126 * ((cDown shr 16) and 255) + 0.7152 * ((cDown shr 8) and 255) + 0.0722 * (cDown and 255)
                    val diff = abs(l - lRight) + abs(l - lDown)
                    if (diff > 45) {
                        edgePoints.add(x.toFloat())
                        edgePoints.add(y.toFloat())
                    }
                }
                x += step
            }
            y += step
        }
        val mean = (sumLin / n).coerceAtLeast(1e-6)
        val ev = log2(mean / 0.18).toFloat().coerceIn(-3f, 3f)
        val ptsArray = if (edgePoints.isNotEmpty()) edgePoints.toFloatArray() else null
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
                        runOnUiThread {
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
                        intervalBtn.text = if (cs.intervalRunning) "STOP" else "INTERVAL"
                        if (showLogOverlay) {
                            logOverlay.text = cs.debugLogs.joinToString("\n")
                        }
                    }
                    frames++
                    val now = System.currentTimeMillis()
                    if (now - t0 >= 1000) {
                        val intervalStr = if (cs.intervalInfo.isNotEmpty()) "  ·  ${cs.intervalInfo}" else ""
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