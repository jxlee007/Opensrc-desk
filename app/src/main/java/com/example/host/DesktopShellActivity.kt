package com.example.host

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.R
import com.example.model.InstalledAppInfo
import com.example.model.VirtualDisplayConfig
import com.example.protocol.TouchSample
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Secondary-display Desktop Home / Launcher Activity rendered directly inside Display 1's Activity stack.
 * Replacing the prior full-screen Presentation window ensures native Android apps launched onto Display 1
 * appear ON TOP of this desktop shell instead of being covered by an overlay Presentation window.
 */
class DesktopShellActivity : Activity(), DesktopShellBridge.AttachedShell {

    private lateinit var rootContainer: FrameLayout
    private lateinit var desktopInfoText: TextView
    private lateinit var statusChipText: TextView
    private lateinit var shizukuHintText: TextView
    private lateinit var clockText: TextView
    private lateinit var cursorView: CursorReticleView

    private lateinit var appDrawerOverlay: LinearLayout
    private lateinit var appSearchInput: EditText
    private lateinit var appDrawerGrid: GridLayout
    private var availableApps: List<InstalledAppInfo> = emptyList()

    private lateinit var webWorkspaceContainer: LinearLayout
    private lateinit var webUrlInput: EditText
    private lateinit var webTabChipRow: LinearLayout
    private lateinit var webViewSlot: FrameLayout
    private var activeWebView: WebView? = null
    private var isWebLayerVisible = false
    private var activeTabIndex = 0
    private val tabUrls = mutableListOf(
        "https://www.google.com",
        "https://m.youtube.com",
        "https://docs.google.com"
    )
    private val tabBundles = arrayOfNulls<Bundle>(3)

    private var diagnosticOnly = false
    private var expectedDisplayId = -1
    private var config: VirtualDisplayConfig = DesktopShellBridge.currentConfig
    private var modeLabel: String = DesktopShellBridge.currentModeLabel

    @Volatile
    private var cachedTargetBounds: VerificationTargetBounds? = null
    private var cursorNormX = 0.5f
    private var cursorNormY = 0.5f
    private var lastTouchCoords = "Touch Ready"
    private var downTimeMs = 0L

    private val uiHandler = Handler(Looper.getMainLooper())
    private var clockTickRunning = false
    private val clockTickRunnable = object : Runnable {
        override fun run() {
            if (!clockTickRunning || !::rootContainer.isInitialized) return
            if (::clockText.isInitialized) {
                clockText.text = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            }
            rootContainer.invalidate()
            uiHandler.postDelayed(this, CLOCK_TICK_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        expectedDisplayId = intent?.getIntExtra(EXTRA_EXPECTED_DISPLAY_ID, -1) ?: -1
        diagnosticOnly = intent?.getBooleanExtra(EXTRA_DIAGNOSTIC_ONLY, false) == true

        val actualDisplayId = currentDisplayId()
        if (!DesktopShellBridge.isExpectedDisplayMatch(expectedDisplayId, actualDisplayId)) {
            DesktopShellBridge.reportDisplayMismatch(expectedDisplayId, actualDisplayId)
            finish()
            return
        }

        config = DesktopShellBridge.currentConfig
        modeLabel = DesktopShellBridge.currentModeLabel

        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0B0F17"))
        }

        if (!diagnosticOnly) {
            buildDesktopShellViews()
        } else {
            cursorView = CursorReticleView(this)
            rootContainer.addView(
                cursorView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        attachDiagnosticTarget(actualDisplayId ?: expectedDisplayId.coerceAtLeast(1))
        setContentView(rootContainer)

        if (!diagnosticOnly) {
            refreshMetadata()
        }

        DesktopShellBridge.attachShell(this, actualDisplayId ?: expectedDisplayId.coerceAtLeast(1))
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newExpected = intent?.getIntExtra(EXTRA_EXPECTED_DISPLAY_ID, expectedDisplayId) ?: expectedDisplayId
        val actual = currentDisplayId()
        if (!DesktopShellBridge.isExpectedDisplayMatch(newExpected, actual)) {
            DesktopShellBridge.reportDisplayMismatch(newExpected, actual)
            finish()
            return
        }
        expectedDisplayId = newExpected
        if (!diagnosticOnly && ::rootContainer.isInitialized) {
            refreshMetadata()
            rootContainer.invalidate()
        }
    }

    override fun onStart() {
        super.onStart()
        clockTickRunning = true
        uiHandler.removeCallbacks(clockTickRunnable)
        uiHandler.postDelayed(clockTickRunnable, CLOCK_TICK_INTERVAL_MS)
    }

    override fun onStop() {
        clockTickRunning = false
        uiHandler.removeCallbacks(clockTickRunnable)
        super.onStop()
    }

    override fun onDestroy() {
        clockTickRunning = false
        uiHandler.removeCallbacks(clockTickRunnable)
        activeWebView?.let { wv ->
            runCatching {
                wv.stopLoading()
                wv.onPause()
                wv.destroy()
            }
        }
        activeWebView = null
        DesktopShellBridge.detachShell(this)
        super.onDestroy()
    }

    @Deprecated("Handled cleanly for Display 1 desktop shell")
    override fun onBackPressed() {
        if (!diagnosticOnly && isWebLayerVisible && activeWebView?.canGoBack() == true) {
            activeWebView?.goBack()
            return
        }
        if (!diagnosticOnly && ::appDrawerOverlay.isInitialized && appDrawerOverlay.visibility == View.VISIBLE) {
            setDrawerVisible(false)
            return
        }
        // Keep Desktop Shell alive on Display 1 instead of closing Display 1's home task
    }

    private fun currentDisplayId(): Int? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display?.displayId
    } else {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay?.displayId
    }

    private fun buildDesktopShellViews() {
        val wallpaper = ImageView(this).apply {
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

        val contentColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 84)
        }
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val displayLabelId = currentDisplayId() ?: expectedDisplayId
        val titleView = TextView(this).apply {
            text = "EXTENDED DESKTOP // DISPLAY $displayLabelId"
            setTextColor(Color.parseColor("#22D3EE"))
            textSize = 17f
            typeface = Typeface.MONOSPACE
        }
        statusChipText = TextView(this).apply {
            text = "  •  $modeLabel"
            setTextColor(Color.parseColor("#10B981"))
            textSize = 14f
            typeface = Typeface.MONOSPACE
        }
        headerRow.addView(titleView)
        headerRow.addView(statusChipText)
        contentColumn.addView(headerRow)

        val inspectorCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC131A26"))
            setPadding(24, 20, 24, 20)
        }
        desktopInfoText = TextView(this).apply {
            setTextColor(Color.parseColor("#F1F5F9"))
            textSize = 14f
            typeface = Typeface.MONOSPACE
        }
        shizukuHintText = TextView(this).apply {
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, 8, 0, 0)
        }
        inspectorCard.addView(desktopInfoText)
        inspectorCard.addView(shizukuHintText)
        contentColumn.addView(
            inspectorCard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 20 }
        )

        rootContainer.addView(
            contentColumn,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        buildWebWorkspaceLayer()
        rootContainer.addView(
            webWorkspaceContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { bottomMargin = 64 }
        )

        buildAppDrawerOverlay()
        rootContainer.addView(
            appDrawerOverlay,
            FrameLayout.LayoutParams(600, 480, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = 20
                bottomMargin = 72
            }
        )

        val taskbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#F00B0F17"))
            setPadding(20, 8, 24, 8)
        }
        val appsBtn = Button(this).apply {
            text = "⊞ Apps"
            setTextColor(Color.parseColor("#0B0F17"))
            setBackgroundColor(Color.parseColor("#22D3EE"))
            textSize = 13f
            setOnClickListener { setDrawerVisible(appDrawerOverlay.visibility != View.VISIBLE) }
        }
        val webBtn = Button(this).apply {
            text = "🌐 Web Workspace"
            setTextColor(Color.parseColor("#F1F5F9"))
            setBackgroundColor(Color.parseColor("#1E293B"))
            textSize = 13f
            setOnClickListener { setWebLayerActive(!isWebLayerVisible) }
        }
        val spacer = View(this)
        clockText = TextView(this).apply {
            text = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            typeface = Typeface.MONOSPACE
        }
        taskbar.addView(appsBtn)
        taskbar.addView(
            webBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = 12 }
        )
        taskbar.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        taskbar.addView(clockText)

        rootContainer.addView(
            taskbar,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 64, Gravity.BOTTOM)
        )

        cursorView = CursorReticleView(this)
        rootContainer.addView(
            cursorView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun attachDiagnosticTarget(targetDisplayId: Int) {
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val targetSidePx = (72f * density).toInt()
        val diagTarget = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = false
            isFocusable = false
            addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                val w = (right - left).coerceAtLeast(0)
                val h = (bottom - top).coerceAtLeast(0)
                if (w > 0 && h > 0) {
                    cachedTargetBounds = VerificationTargetBounds(
                        displayId = targetDisplayId,
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
                    DesktopShellBridge.notifyVerificationTouchObserved(
                        ObservedTouchEvent(
                            displayId = targetDisplayId,
                            xPx = ev.x + v.left,
                            yPx = ev.y + v.top,
                            timestampMs = System.currentTimeMillis()
                        )
                    )
                }
                false
            }
        }
        rootContainer.addView(
            diagTarget,
            FrameLayout.LayoutParams(targetSidePx, targetSidePx, Gravity.CENTER)
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebWorkspaceLayer() {
        webWorkspaceContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            visibility = View.GONE
            alpha = 0f
        }

        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(12, 8, 12, 8)
        }
        val backBtn = Button(this).apply {
            text = "◀"
            setOnClickListener { if (activeWebView?.canGoBack() == true) activeWebView?.goBack() }
        }
        val reloadBtn = Button(this).apply {
            text = "↻"
            setOnClickListener { activeWebView?.reload() }
        }
        webUrlInput = EditText(this).apply {
            setText(tabUrls[0])
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "Enter URL or search…"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ ->
                navigateCurrentTab(text.toString())
                true
            }
        }
        val goBtn = Button(this).apply {
            text = "Go"
            setOnClickListener { navigateCurrentTab(webUrlInput.text.toString()) }
        }

        navBar.addView(backBtn)
        navBar.addView(reloadBtn)
        navBar.addView(webUrlInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        navBar.addView(goBtn)

        webTabChipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0B0F17"))
            setPadding(12, 4, 12, 4)
        }
        rebuildTabChips()

        webViewSlot = FrameLayout(this)
        webWorkspaceContainer.addView(navBar)
        webWorkspaceContainer.addView(webTabChipRow)
        webWorkspaceContainer.addView(
            webViewSlot,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
    }

    private fun rebuildTabChips() {
        webTabChipRow.removeAllViews()
        tabUrls.forEachIndexed { idx, url ->
            val chip = Button(this).apply {
                val label = url.removePrefix("https://").removePrefix("www.").take(18)
                text = "Tab ${idx + 1}: $label"
                textSize = 11f
                setTextColor(if (idx == activeTabIndex) Color.parseColor("#0B0F17") else Color.WHITE)
                setBackgroundColor(
                    if (idx == activeTabIndex) Color.parseColor("#22D3EE")
                    else Color.parseColor("#1E293B")
                )
                setOnClickListener { switchWebTab(idx) }
            }
            webTabChipRow.addView(chip)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureActiveWebView(): WebView {
        activeWebView?.let { return it }
        val wv = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.allowFileAccess = false
            settings.userAgentString =
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!url.isNullOrBlank()) {
                        tabUrls[activeTabIndex] = url
                        webUrlInput.setText(url)
                        rebuildTabChips()
                    }
                }
            }
            webChromeClient = WebChromeClient()
        }
        webViewSlot.removeAllViews()
        webViewSlot.addView(
            wv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        activeWebView = wv
        val saved = tabBundles[activeTabIndex]
        if (saved != null) {
            wv.restoreState(saved)
        } else {
            wv.loadUrl(tabUrls[activeTabIndex])
        }
        return wv
    }

    private fun switchWebTab(newIndex: Int) {
        if (newIndex == activeTabIndex || newIndex !in tabUrls.indices) return
        activeWebView?.let { wv ->
            val bundle = Bundle()
            wv.saveState(bundle)
            tabBundles[activeTabIndex] = bundle
            wv.stopLoading()
        }
        activeTabIndex = newIndex
        rebuildTabChips()
        val wv = ensureActiveWebView()
        val saved = tabBundles[newIndex]
        if (saved != null) {
            wv.restoreState(saved)
        } else {
            wv.loadUrl(tabUrls[newIndex])
        }
        webUrlInput.setText(tabUrls[newIndex])
    }

    private fun navigateCurrentTab(rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return
        val targetUrl = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else if (trimmed.contains('.') && !trimmed.contains(' ')) {
            "https://$trimmed"
        } else {
            "https://www.google.com/search?q=${trimmed.replace(" ", "+")}"
        }
        tabUrls[activeTabIndex] = targetUrl
        ensureActiveWebView().loadUrl(targetUrl)
    }

    private fun buildAppDrawerOverlay() {
        appDrawerOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5131A26"))
            setPadding(20, 16, 20, 16)
            visibility = View.GONE
        }
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val drawerHeader = TextView(this).apply {
            text = "ALL APPS (OPEN ON DISPLAY 1)"
            setTextColor(Color.parseColor("#22D3EE"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }
        val closeDrawerBtn = Button(this).apply {
            text = "✕"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setOnClickListener { setDrawerVisible(false) }
        }
        headerRow.addView(drawerHeader, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(closeDrawerBtn)
        appDrawerOverlay.addView(headerRow)

        appSearchInput = EditText(this).apply {
            hint = "Search installed apps…"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
            setBackgroundColor(Color.parseColor("#0B0F17"))
            setPadding(16, 12, 16, 12)
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    rebuildAppDrawerGrid()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        appDrawerOverlay.addView(
            appSearchInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        )

        val scroll = ScrollView(this)
        appDrawerGrid = GridLayout(this).apply {
            columnCount = 3
        }
        scroll.addView(appDrawerGrid)
        appDrawerOverlay.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { topMargin = 10 }
        )
    }

    private fun setDrawerVisible(visible: Boolean) {
        if (diagnosticOnly || !::appDrawerOverlay.isInitialized) return
        appDrawerOverlay.visibility = if (visible) View.VISIBLE else View.GONE
        DesktopShellBridge.notifyDrawerVisibilityChanged(visible)
        if (visible) {
            rebuildAppDrawerGrid()
        }
    }

    private fun rebuildAppDrawerGrid() {
        if (!::appDrawerGrid.isInitialized) return
        appDrawerGrid.removeAllViews()
        val query = if (::appSearchInput.isInitialized) appSearchInput.text?.toString().orEmpty() else ""
        val filtered = DesktopShellBridge.filterLaunchableApps(availableApps, query)
        filtered.forEach { app ->
            val btn = Button(this).apply {
                text = app.label.take(16)
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#1E293B"))
                setOnClickListener {
                    setDrawerVisible(false)
                    setWebLayerActive(false)
                    DesktopShellBridge.notifyAppLaunchedFromTaskbar(app.packageName)
                }
            }
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(4, 4, 4, 4)
            }
            appDrawerGrid.addView(btn, lp)
        }
    }

    override fun updateConfig(config: VirtualDisplayConfig, modeLabel: String) {
        this.config = config
        this.modeLabel = modeLabel
        uiHandler.post {
            if (!diagnosticOnly && ::desktopInfoText.isInitialized) {
                refreshMetadata()
            }
        }
    }

    override fun updateCursor(normX: Float, normY: Float, actionLabel: String) {
        cursorNormX = normX.coerceIn(0f, 1f)
        cursorNormY = normY.coerceIn(0f, 1f)
        lastTouchCoords =
            "$actionLabel (${(cursorNormX * config.targetWidth).toInt()}, ${(cursorNormY * config.targetHeight).toInt()})"
        uiHandler.post {
            if (::cursorView.isInitialized) {
                cursorView.setPosition(cursorNormX, cursorNormY)
            }
            if (!diagnosticOnly && ::desktopInfoText.isInitialized) {
                refreshMetadata()
            }
        }
    }

    override fun appendImeText(textInput: String?, keyCode: Int) {
        uiHandler.post {
            if (diagnosticOnly) return@post
            if (::appSearchInput.isInitialized && appDrawerOverlay.visibility == View.VISIBLE) {
                when {
                    keyCode == KeyEvent.KEYCODE_DEL && appSearchInput.text.isNotEmpty() -> {
                        appSearchInput.text.delete(appSearchInput.text.length - 1, appSearchInput.text.length)
                    }
                    !textInput.isNullOrEmpty() -> appSearchInput.append(textInput)
                }
                return@post
            }
            if (isWebLayerVisible && activeWebView != null) {
                when {
                    keyCode == KeyEvent.KEYCODE_ENTER -> {
                        if (webUrlInput.hasFocus()) {
                            navigateCurrentTab(webUrlInput.text.toString())
                        } else {
                            activeWebView?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                            activeWebView?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                        }
                    }
                    keyCode == KeyEvent.KEYCODE_DEL -> {
                        if (webUrlInput.hasFocus() && webUrlInput.text.isNotEmpty()) {
                            webUrlInput.text.delete(webUrlInput.text.length - 1, webUrlInput.text.length)
                        } else {
                            activeWebView?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                            activeWebView?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
                        }
                    }
                    !textInput.isNullOrEmpty() -> {
                        if (webUrlInput.hasFocus()) {
                            webUrlInput.append(textInput)
                        } else {
                            val escaped = textInput.replace("\\", "\\\\").replace("'", "\\'")
                            activeWebView?.evaluateJavascript(
                                "if(document.activeElement && 'value' in document.activeElement){document.activeElement.value+='$escaped';}",
                                null
                            )
                        }
                    }
                }
            }
        }
    }

    override fun injectInProcessTouch(action: Byte, normX: Float, normY: Float) {
        uiHandler.post {
            if (!::rootContainer.isInitialized) return@post
            val w = rootContainer.width.coerceAtLeast(config.targetWidth).toFloat()
            val h = rootContainer.height.coerceAtLeast(config.targetHeight).toFloat()
            val xPx = normX.coerceIn(0f, 1f) * w
            val yPx = normY.coerceIn(0f, 1f) * h
            val now = SystemClock.uptimeMillis()

            val motionAction = when (action) {
                TouchSample.ACTION_DOWN -> {
                    downTimeMs = now
                    MotionEvent.ACTION_DOWN
                }
                TouchSample.ACTION_UP -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val ev = MotionEvent.obtain(downTimeMs, now, motionAction, xPx, yPx, 0)
            try {
                rootContainer.dispatchTouchEvent(ev)
            } finally {
                ev.recycle()
            }
        }
    }

    override fun updateLaunchableApps(apps: List<InstalledAppInfo>) {
        availableApps = apps
        uiHandler.post {
            if (!diagnosticOnly && ::appDrawerGrid.isInitialized) {
                rebuildAppDrawerGrid()
            }
        }
    }

    override fun setWebLayerActive(active: Boolean) {
        uiHandler.post {
            if (diagnosticOnly || !::webWorkspaceContainer.isInitialized) return@post
            if (active == isWebLayerVisible &&
                webWorkspaceContainer.visibility == (if (active) View.VISIBLE else View.GONE)
            ) {
                return@post
            }
            isWebLayerVisible = active
            setDrawerVisible(false)
            if (active) {
                val wv = ensureActiveWebView()
                wv.onResume()
                wv.resumeTimers()
                webWorkspaceContainer.visibility = View.VISIBLE
                webWorkspaceContainer.animate().alpha(1f).setDuration(180L).start()
            } else {
                activeWebView?.let { wv ->
                    val bundle = Bundle()
                    wv.saveState(bundle)
                    tabBundles[activeTabIndex] = bundle
                    wv.onPause()
                    wv.pauseTimers()
                }
                webWorkspaceContainer.animate()
                    .alpha(0f)
                    .setDuration(180L)
                    .withEndAction { webWorkspaceContainer.visibility = View.GONE }
                    .start()
            }
            DesktopShellBridge.notifyWorkspaceLayerChanged(active)
        }
    }

    override fun trimWebMemoryToColdBundle() {
        uiHandler.post {
            val wv = activeWebView ?: return@post
            val bundle = Bundle()
            wv.saveState(bundle)
            tabBundles[activeTabIndex] = bundle
            if (!isWebLayerVisible) {
                webViewSlot.removeAllViews()
                runCatching {
                    wv.stopLoading()
                    wv.onPause()
                    wv.destroy()
                }
                activeWebView = null
            }
        }
    }

    override fun releaseWebResourcesAndFinish() {
        uiHandler.post {
            activeWebView?.let { wv ->
                runCatching {
                    wv.stopLoading()
                    wv.onPause()
                    wv.destroy()
                }
            }
            activeWebView = null
            finish()
        }
    }

    override fun getVerificationTargetBounds(): VerificationTargetBounds? = cachedTargetBounds

    private fun refreshMetadata() {
        val layoutMode = if (config.forceTabletLayout) "Tablet / Desktop" else "Phone Scaled"
        val wifiBand = if (config.is24GhzAdapted) "2.4 GHz CBR" else "5 GHz / High"
        desktopInfoText.text =
            "Resolution: ${config.targetWidth} x ${config.targetHeight} · ${config.targetDpi} dpi ($layoutMode)\n" +
                "Stream: ${config.bitrateMbps} Mbps ($wifiBand) · Input: $lastTouchCoords"
        statusChipText.text = "  •  $modeLabel"
        shizukuHintText.text = if (DesktopShellBridge.shizukuAuthorized) {
            "Shizuku Active: Apps launch above this shell in Freeform mode (windowingMode 5)."
        } else {
            "Shizuku Off: Using Web Workspace. Native apps open fullscreen over this shell without Shizuku."
        }
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
            canvas.drawCircle(cx, cy, 10f, fillPaint)
            canvas.drawCircle(cx, cy, 12f, ringPaint)
        }
    }

    companion object {
        const val CLOCK_TICK_INTERVAL_MS = 1_000L
        const val EXTRA_EXPECTED_DISPLAY_ID = "expected_display_id"
        const val EXTRA_DIAGNOSTIC_ONLY = "diagnostic_only"

        fun launchOnDisplay(
            context: Context,
            displayId: Int,
            diagnosticOnly: Boolean = false
        ): Result<Unit> {
            return runCatching {
                val intent = Intent(context, DesktopShellActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(EXTRA_EXPECTED_DISPLAY_ID, displayId)
                    putExtra(EXTRA_DIAGNOSTIC_ONLY, diagnosticOnly)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
                    context.startActivity(intent, options.toBundle())
                } else {
                    context.startActivity(intent)
                }
            }
        }
    }
}
