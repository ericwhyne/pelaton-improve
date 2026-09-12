package net.whyne.treadoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs

class OverlayService : Service() {
    companion object {
        const val TAG = "TreadOverlay"
        const val CHANNEL_ID = "tread_overlay"
        const val NOTIFICATION_ID = 4211
        const val PREFS = "overlay"
    }

    private enum class Mode { STOPWATCH, COUNTDOWN }

    private lateinit var windowManager: WindowManager
    private var overlayView: LinearLayout? = null
    private lateinit var speedText: TextView
    private lateinit var inclineText: TextView
    private lateinit var timerText: TextView
    private lateinit var timerLabel: TextView
    private lateinit var chevron: TextView
    private lateinit var pillRow: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var adjustRow: LinearLayout
    private lateinit var pillBg: GradientDrawable
    private lateinit var modeStopwatchBtn: TextView
    private lateinit var modeCountdownBtn: TextView
    private lateinit var startPauseBtn: TextView
    private lateinit var countdownBar: CountdownBar

    /** Thin draining progress bar for countdown mode: green → amber → red. */
    private inner class CountdownBar(ctx: Context) : View(ctx) {
        var fraction = 0f // remaining/set, 0..1
            set(v) { val c = v.coerceIn(0f, 1f); if (c != field) { field = c; invalidate() } }
        var overtime = false
            set(v) { if (v != field) { field = v; invalidate() } }
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#33FFFFFF")
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, trackPaint)
            val w = if (overtime) width.toFloat() else width * fraction
            if (w <= 0f) return
            fillPaint.color = when {
                overtime -> Color.parseColor("#FF5252")
                fraction <= 0.1f -> Color.parseColor("#FF5252")
                fraction <= 0.25f -> Color.parseColor("#FFB300")
                else -> Color.parseColor("#4CAF50")
            }
            canvas.drawRoundRect(0f, 0f, w, height.toFloat(), r, r, fillPaint)
        }
    }
    private var affernetClient: AffernetClient? = null

    // ---- Timer engine ----
    // elapsed = accumulatedMs (+ live segment while running). Stopwatch shows
    // elapsed; countdown shows countdownSetMs - elapsed (negative = overtime).
    private var mode = Mode.STOPWATCH
    private var timerRunning = false
    private var accumulatedMs = 0L
    private var startedAtElapsed = 0L
    private var countdownSetMs = 0L
    private var alerted = false

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateTimerText()
            handler.postDelayed(this, 250)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mode = if (prefs.getString("mode", "STOPWATCH") == "COUNTDOWN") Mode.COUNTDOWN else Mode.STOPWATCH
        countdownSetMs = prefs.getLong("countdownMs", 10 * 60_000L)
        addOverlay()
        refreshModeUi()
        handler.post(tick)
        affernetClient = AffernetClient(this,
            onMetrics = { speed, incline ->
                handler.post {
                    speedText.text = String.format(Locale.US, "%.1f", speed)
                    inclineText.text = String.format(Locale.US, "%.1f%%", incline)
                }
            },
            onStatus = { status ->
                Log.i(TAG, "metrics status: $status")
            }
        ).also { it.start() }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        affernetClient?.stop()
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Tread Overlay", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Tread Overlay running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun makeText(sizeSp: Float, bold: Boolean = true): TextView =
        TextView(this).apply {
            textSize = sizeSp
            setTextColor(Color.WHITE)
            if (bold) setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
        }

    private fun makeLabel(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(Color.parseColor("#99FFFFFF"))
            gravity = Gravity.CENTER
        }

    /** Rounded touch-friendly button for the control panel. */
    private fun makeButton(label: String, onTap: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 15f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minWidth = dp(64f)
            minHeight = dp(44f)
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(Color.parseColor("#33FFFFFF"))
            }
            setOnClickListener { onTap() }
        }

    private fun setButtonActive(btn: TextView, active: Boolean) {
        (btn.background as GradientDrawable).setColor(
            Color.parseColor(if (active) "#CC2E7D32" else "#33FFFFFF") // active = green
        )
    }

    private fun addOverlay() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ---- control panel (hidden until chevron tap), sits ABOVE the pill ----
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = dp(18f).toFloat()
                setColor(Color.parseColor("#D9000000")) // ~85% black
            }
        }
        val rowParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8f) }
        val btnGap = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(8f) }

        // mode row
        modeStopwatchBtn = makeButton("⏱ Stopwatch") { setMode(Mode.STOPWATCH) }
        modeCountdownBtn = makeButton("⏳ Countdown") { setMode(Mode.COUNTDOWN) }
        panel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(modeStopwatchBtn, btnGap)
            addView(modeCountdownBtn, btnGap)
        }, rowParams)

        // countdown adjust row (visible in countdown mode only)
        adjustRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(makeButton("+10m") { bumpCountdown(10 * 60_000L) }, btnGap)
            addView(makeButton("+5m") { bumpCountdown(5 * 60_000L) }, btnGap)
            addView(makeButton("+1m") { bumpCountdown(60_000L) }, btnGap)
            addView(makeButton("+30s") { bumpCountdown(30_000L) }, btnGap)
            addView(makeButton("Clear") { clearCountdown() }, btnGap)
        }
        panel.addView(adjustRow, rowParams)

        // action row
        startPauseBtn = makeButton("Start") { toggleTimer() }
        panel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(startPauseBtn, btnGap)
            addView(makeButton("Reset") { resetTimer() }, btnGap)
            addView(makeButton("✕") { setPanelVisible(false) }, btnGap)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // ---- pill row ----
        pillBg = GradientDrawable().apply {
            cornerRadius = dp(24f).toFloat()
            setColor(Color.parseColor("#B3000000")) // ~70% black
        }
        // pill = vertical: metrics row on top, countdown progress bar underneath
        pillRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(8f), dp(14f), dp(8f))
            background = pillBg
        }
        val metricsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun column(value: TextView, label: TextView): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(value)
                addView(label)
            }

        speedText = makeText(22f).apply { text = "--" }
        inclineText = makeText(22f).apply { text = "--" }
        timerText = makeText(22f).apply { text = "00:00" }
        timerLabel = makeLabel("TIME")
        chevron = TextView(this).apply {
            text = "▲"
            textSize = 16f
            setTextColor(Color.parseColor("#B3FFFFFF"))
            gravity = Gravity.CENTER
            setPadding(dp(10f), dp(8f), dp(6f), dp(8f))
        }

        val spacerParams = LinearLayout.LayoutParams(dp(22f), 1)
        metricsRow.addView(column(speedText, makeLabel("MPH")))
        metricsRow.addView(View(this), spacerParams)
        metricsRow.addView(column(inclineText, makeLabel("INCLINE")))
        metricsRow.addView(View(this), spacerParams)
        metricsRow.addView(column(timerText, timerLabel))
        metricsRow.addView(chevron)
        pillRow.addView(metricsRow)

        countdownBar = CountdownBar(this)
        pillRow.addView(countdownBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(5f)
        ).apply { topMargin = dp(6f); rightMargin = dp(4f) })

        val panelMargin = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8f) }
        root.addView(panel, panelMargin)
        root.addView(pillRow)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // anchor to the BOTTOM so the panel expands upward and the pill
            // never moves; x/y restored from last drag position
            gravity = Gravity.BOTTOM or Gravity.START
            x = prefs.getInt("posX", dp(24f))
            y = prefs.getInt("posY", dp(40f)) // distance up from bottom edge
        }

        // Drag + tap handling on the PILL only (panel buttons handle their own taps)
        pillRow.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f
            var downY = 0f
            var origX = 0
            var origY = 0
            var moved = false
            var downTime = 0L
            var longPressFired = false
            val longPressRunnable = Runnable {
                longPressFired = true
                resetTimer()
            }

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        origX = params.x
                        origY = params.y
                        moved = false
                        longPressFired = false
                        downTime = SystemClock.elapsedRealtime()
                        handler.postDelayed(longPressRunnable, 1200)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (abs(dx) > dp(6f) || abs(dy) > dp(6f)) {
                            moved = true
                            handler.removeCallbacks(longPressRunnable)
                            params.x = origX + dx.toInt()
                            params.y = origY - dy.toInt() // bottom gravity: y grows upward
                            windowManager.updateViewLayout(root, params)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        handler.removeCallbacks(longPressRunnable)
                        if (moved) {
                            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                .putInt("posX", params.x).putInt("posY", params.y).apply()
                        } else if (!longPressFired &&
                            SystemClock.elapsedRealtime() - downTime < 800
                        ) {
                            // tap on the chevron end opens/closes the panel;
                            // tap anywhere else = start/pause (muscle memory)
                            val chevronStart = pillRow.width - dp(44f)
                            if (event.x >= chevronStart) setPanelVisible(panel.visibility != View.VISIBLE)
                            else toggleTimer()
                        }
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(root, params)
        overlayView = root
    }

    // ---- panel / mode ----

    private fun setPanelVisible(visible: Boolean) {
        panel.visibility = if (visible) View.VISIBLE else View.GONE
        chevron.text = if (visible) "▼" else "▲"
    }

    private fun setMode(m: Mode) {
        if (mode != m) {
            mode = m
            timerRunning = false
            accumulatedMs = 0L
            alerted = false
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("mode", m.name).apply()
        }
        refreshModeUi()
    }

    private fun refreshModeUi() {
        setButtonActive(modeStopwatchBtn, mode == Mode.STOPWATCH)
        setButtonActive(modeCountdownBtn, mode == Mode.COUNTDOWN)
        adjustRow.visibility = if (mode == Mode.COUNTDOWN) View.VISIBLE else View.GONE
        countdownBar.visibility = if (mode == Mode.COUNTDOWN) View.VISIBLE else View.GONE
        timerLabel.text = if (mode == Mode.COUNTDOWN) "LEFT" else "TIME"
        updateTimerText()
    }

    private fun bumpCountdown(ms: Long) {
        countdownSetMs = (countdownSetMs + ms).coerceAtMost(10 * 3600_000L)
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("countdownMs", countdownSetMs).apply()
        // adjusting the target resets any finished/overtime state
        if (!timerRunning) { accumulatedMs = 0L; alerted = false }
        updateTimerText()
    }

    private fun clearCountdown() {
        countdownSetMs = 0L
        timerRunning = false
        accumulatedMs = 0L
        alerted = false
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("countdownMs", countdownSetMs).apply()
        updateTimerText()
    }

    // ---- timer core ----

    private fun toggleTimer() {
        if (timerRunning) {
            accumulatedMs += SystemClock.elapsedRealtime() - startedAtElapsed
            timerRunning = false
        } else {
            if (mode == Mode.COUNTDOWN && countdownSetMs <= 0L) {
                // nothing to count down; open panel so a time can be set
                setPanelVisible(true)
                return
            }
            startedAtElapsed = SystemClock.elapsedRealtime()
            timerRunning = true
            setPanelVisible(false) // unclutter once running
        }
        updateTimerText()
    }

    private fun resetTimer() {
        timerRunning = false
        accumulatedMs = 0L
        alerted = false
        updateTimerText()
    }

    private fun elapsedMs(): Long {
        var total = accumulatedMs
        if (timerRunning) total += SystemClock.elapsedRealtime() - startedAtElapsed
        return total
    }

    private fun fmt(ms: Long): String {
        val secs = ms / 1000
        return if (secs >= 3600) {
            String.format(Locale.US, "%d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60)
        } else {
            String.format(Locale.US, "%02d:%02d", secs / 60, secs % 60)
        }
    }

    private fun updateTimerText() {
        val text: String
        var color = if (timerRunning) Color.WHITE else Color.parseColor("#80FFFFFF")
        if (mode == Mode.COUNTDOWN) {
            val remaining = countdownSetMs - elapsedMs()
            countdownBar.overtime = countdownSetMs > 0L && remaining <= 0L
            countdownBar.fraction = if (countdownSetMs > 0L) remaining.toFloat() / countdownSetMs else 0f
            if (countdownSetMs == 0L || remaining > 0) {
                // no time set yet -> calm 00:00, never "overtime"
                text = if (countdownSetMs == 0L) "00:00" else fmt(remaining + 999) // ceil so it starts at the full set time
                if (timerLabel.text != "LEFT") timerLabel.text = "LEFT"
            } else {
                if (timerLabel.text != "OVER") timerLabel.text = "OVER"
                // overtime: keep counting up in red with a + prefix
                text = "+" + fmt(-remaining)
                color = Color.parseColor("#FF5252")
                if (!alerted && timerRunning) {
                    alerted = true
                    alertCountdownDone()
                }
            }
        } else {
            text = fmt(elapsedMs())
        }
        if (timerText.text != text) timerText.text = text
        timerText.setTextColor(color)
    }

    /** Countdown hit zero: flash the pill red and triple-beep. */
    private fun alertCountdownDone() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
            for (i in 0..2) {
                handler.postDelayed({ tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 300) }, i * 500L)
            }
            handler.postDelayed({ tg.release() }, 2500)
        } catch (t: Throwable) {
            Log.w(TAG, "beep failed: $t")
        }
        val red = Color.parseColor("#D9C62828")
        val normal = Color.parseColor("#B3000000")
        for (i in 0..7) {
            handler.postDelayed({ pillBg.setColor(if (i % 2 == 0) red else normal) }, i * 350L)
        }
    }
}
