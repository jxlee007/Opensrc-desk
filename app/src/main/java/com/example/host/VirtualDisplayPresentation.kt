package com.example.host

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.R
import com.example.model.VirtualDisplayConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import android.annotation.SuppressLint
import android.view.MotionEvent

data class VerificationTargetBounds(
    val displayId: Int,
    val widthPx: Int,
    val heightPx: Int,
    val centerXPx: Float,
    val centerYPx: Float
)

class VirtualDisplayPresentation(
    context: Context,
    display: Display,
    private var config: VirtualDisplayConfig,
    private var modeLabel: String,
    private val onVerificationTouchObserved: ((ObservedTouchEvent) -> Unit)? = null
) : Presentation(context, display) {

    private lateinit var rootContainer: FrameLayout
    private lateinit var desktopInfoText: TextView
    private lateinit var notesBodyText: TextView
    private lateinit var statusChipText: TextView
    private lateinit var clockText: TextView
    private lateinit var cursorView: CursorReticleView
    private var verificationTargetView: View? = null
    @Volatile
    private var cachedTargetBounds: VerificationTargetBounds? = null

    private val notesBuilder = StringBuilder("Extended Desktop Scratchpad\n• Type from Host keyboard or tap shortcuts.")
    private var cursorNormX = 0.5f
    private var cursorNormY = 0.5f
    private var lastTouchCoords = "Touch Ready"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        rootContainer = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#0B0F17"))
        }

        val wallpaper = ImageView(context).apply {
            setImageResource(R.drawable.img_desktop_wallpaper)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.78f
        }
        rootContainer.addView(
            wallpaper,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val contentColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 32, 40, 88)
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(context).apply {
            text = "EXTENDED DESKTOP // DISPLAY 1"
            setTextColor(Color.parseColor("#22D3EE"))
            textSize = 18f
            typeface = Typeface.MONOSPACE
        }

        statusChipText = TextView(context).apply {
            text = "  •  $modeLabel"
            setTextColor(Color.parseColor("#10B981"))
            textSize = 14f
            typeface = Typeface.MONOSPACE
        }

        headerRow.addView(titleView)
        headerRow.addView(statusChipText)
        contentColumn.addView(headerRow)

        val cardsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 28, 0, 0)
        }

        // Left card: Display synthesis inspector
        val inspectorCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC131A26"))
            setPadding(28, 24, 28, 24)
        }
        val inspectorTitle = TextView(context).apply {
            text = "SYNTHESIZED DISPLAY PROFILE"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }
        desktopInfoText = TextView(context).apply {
            setTextColor(Color.parseColor("#F1F5F9"))
            textSize = 15f
            typeface = Typeface.MONOSPACE
            setPadding(0, 12, 0, 0)
        }
        inspectorCard.addView(inspectorTitle)
        inspectorCard.addView(desktopInfoText)

        // Right card: Interactive Host-IME Scratchpad
        val notesCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC1A2232"))
            setPadding(28, 24, 28, 24)
        }
        val notesTitle = TextView(context).apply {
            text = "LIVE IME & POINTER WORKSPACE"
            setTextColor(Color.parseColor("#22D3EE"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }
        notesBodyText = TextView(context).apply {
            text = notesBuilder.toString()
            setTextColor(Color.parseColor("#F1F5F9"))
            textSize = 15f
            setPadding(0, 12, 0, 0)
        }
        notesCard.addView(notesTitle)
        notesCard.addView(notesBodyText)

        cardsRow.addView(
            inspectorCard,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 20
            }
        )
        cardsRow.addView(
            notesCard,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
        )
        contentColumn.addView(cardsRow)

        rootContainer.addView(
            contentColumn,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Bottom Desktop Taskbar
        val taskbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#E60B0F17"))
            setPadding(28, 12, 28, 12)
        }
        val appLauncherBadge = TextView(context).apply {
            text = "⊞  WORKSPACE"
            setTextColor(Color.parseColor("#22D3EE"))
            textSize = 13f
            typeface = Typeface.MONOSPACE
        }
        val spacer = View(context)
        clockText = TextView(context).apply {
            text = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            typeface = Typeface.MONOSPACE
        }
        taskbar.addView(appLauncherBadge)
        taskbar.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        taskbar.addView(clockText)

        rootContainer.addView(
            taskbar,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 64, Gravity.BOTTOM)
        )

        cursorView = CursorReticleView(context)
        rootContainer.addView(
            cursorView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val targetSidePx = (72f * density).toInt()
        val diagTarget = View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = false
            isFocusable = false
            addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
                val w = (right - left).coerceAtLeast(0)
                val h = (bottom - top).coerceAtLeast(0)
                if (w > 0 && h > 0) {
                    cachedTargetBounds = VerificationTargetBounds(
                        displayId = display.displayId,
                        widthPx = w,
                        heightPx = h,
                        centerXPx = left + w / 2f,
                        centerYPx = top + h / 2f
                    )
                }
            }
            @SuppressLint("ClickableViewAccessibility")
            setOnTouchListener { v, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN ||
                    ev.actionMasked == MotionEvent.ACTION_MOVE ||
                    ev.actionMasked == MotionEvent.ACTION_UP
                ) {
                    onVerificationTouchObserved?.invoke(
                        ObservedTouchEvent(
                            displayId = display.displayId,
                            xPx = ev.x + v.left,
                            yPx = ev.y + v.top,
                            timestampMs = System.currentTimeMillis()
                        )
                    )
                }
                false
            }
        }
        verificationTargetView = diagTarget
        rootContainer.addView(
            diagTarget,
            FrameLayout.LayoutParams(targetSidePx, targetSidePx, Gravity.CENTER)
        )

        setContentView(rootContainer)
        refreshMetadata()
    }

    private var tickStarted = false
    private val clockTickRunnable = object : Runnable {
        override fun run() {
            if (!tickStarted || !::rootContainer.isInitialized) return
            clockText.text = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            rootContainer.invalidate()
            rootContainer.postDelayed(this, CLOCK_TICK_INTERVAL_MS)
        }
    }

    override fun onStart() {
        super.onStart()
        tickStarted = true
        if (::rootContainer.isInitialized) {
            rootContainer.removeCallbacks(clockTickRunnable)
            rootContainer.postDelayed(clockTickRunnable, CLOCK_TICK_INTERVAL_MS)
        }
    }

    override fun onStop() {
        tickStarted = false
        if (::rootContainer.isInitialized) {
            rootContainer.removeCallbacks(clockTickRunnable)
        }
        super.onStop()
    }

    fun getVerificationTargetBounds(): VerificationTargetBounds? = cachedTargetBounds

    fun updateConfig(newConfig: VirtualDisplayConfig, newModeLabel: String) {
        config = newConfig
        modeLabel = newModeLabel
        rootContainer.post { refreshMetadata() }
    }

    fun updateCursor(normX: Float, normY: Float, actionLabel: String) {
        cursorNormX = normX.coerceIn(0f, 1f)
        cursorNormY = normY.coerceIn(0f, 1f)
        lastTouchCoords = "$actionLabel (${(cursorNormX * config.targetWidth).toInt()}, ${(cursorNormY * config.targetHeight).toInt()})"
        rootContainer.post {
            cursorView.setPosition(cursorNormX, cursorNormY)
            refreshMetadata()
        }
    }

    fun appendImeText(textInput: String?, keyCode: Int) {
        rootContainer.post {
            when {
                keyCode == android.view.KeyEvent.KEYCODE_DEL -> {
                    if (notesBuilder.isNotEmpty()) {
                        notesBuilder.deleteCharAt(notesBuilder.length - 1)
                    }
                }
                keyCode == android.view.KeyEvent.KEYCODE_ENTER -> {
                    notesBuilder.append('\n')
                }
                !textInput.isNullOrEmpty() -> {
                    if (notesBuilder.length > 600) {
                        notesBuilder.delete(0, 150)
                    }
                    notesBuilder.append(textInput)
                }
            }
            notesBodyText.text = notesBuilder.toString()
        }
    }

    private fun refreshMetadata() {
        val layoutMode = if (config.forceTabletLayout) "Tablet / Desktop" else "Phone Scaled"
        val wifiBand = if (config.is24GhzAdapted) "2.4 GHz CBR" else "5 GHz / High"
        desktopInfoText.text =
            "Resolution: ${config.targetWidth} x ${config.targetHeight}\n" +
                "Density: ${config.targetDpi} dpi ($layoutMode)\n" +
                "Ceiling: ${config.resolutionCeiling.label}\n" +
                "Bitrate: ${config.bitrateMbps} Mbps ($wifiBand)\n" +
                "Input: $lastTouchCoords"
        statusChipText.text = "  •  $modeLabel"
        clockText.text = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    }

    companion object {
        const val CLOCK_TICK_INTERVAL_MS = 1_000L
    }

    private class CursorReticleView(context: Context) : View(context) {
        private var normX = 0.5f
        private var normY = 0.5f

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#22D3EE")
            style = Paint.Style.FILL
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        fun setPosition(nx: Float, ny: Float) {
            normX = nx
            normY = ny
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = normX * width
            val cy = normY * height
            canvas.drawCircle(cx, cy, 12f, fillPaint)
            canvas.drawCircle(cx, cy, 14f, ringPaint)
        }
    }
}
