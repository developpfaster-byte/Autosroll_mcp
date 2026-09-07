package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.ScreenReaderApp
import com.example.data.model.InteractiveElement
import com.example.data.model.NodeBounds
import com.example.data.model.ScreenDump
import com.example.data.model.UiNode
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class ScreenReaderAccessibilityService : AccessibilityService() {

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled coroutine exception in AccessibilityService: ${throwable.message}", throwable)
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + exceptionHandler)

    private var windowManager: WindowManager? = null
    private var floatingOverlayView: View? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    private var isScrollActionRunning = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceActive.value = true
        Log.d(TAG, "ScreenReaderAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            event?.packageName?.let {
                val pkg = it.toString()
                if (pkg != packageName) {
                    _lastActivePackage.value = pkg
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling accessibility event", e)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "ScreenReaderAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        hideFloatingOverlay()
        if (instance == this) {
            instance = null
        }
        _isServiceActive.value = false
        Log.d(TAG, "ScreenReaderAccessibilityService destroyed")
    }

    /**
     * Resolves the active application root node, safely bypassing our own floating overlay.
     */
    fun getActiveAppRootNode(): AccessibilityNodeInfo? {
        val directRoot = rootInActiveWindow
        if (directRoot != null && directRoot.packageName?.toString() != packageName) {
            return directRoot
        }

        val windowList = try {
            windows
        } catch (e: Exception) {
            emptyList()
        }

        for (win in windowList) {
            if (win.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                val winRoot = win.root
                if (winRoot != null && winRoot.packageName?.toString() != packageName) {
                    return winRoot
                }
            }
        }

        return directRoot ?: windowList.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root
    }

    /**
     * Reads and parses the current screen view hierarchy into a ScreenDump.
     */
    fun readCurrentScreen(captureType: String = "single_read"): ScreenDump {
        val rootNode = getActiveAppRootNode()
        val pkg = rootNode?.packageName?.toString() ?: _lastActivePackage.value.ifBlank { packageName }
        val appName = getAppLabel(pkg)
        val windowTitle = rootNode?.let { findWindowTitle(it) } ?: appName

        if (rootNode == null) {
            return ScreenDump(
                timestamp = System.currentTimeMillis(),
                packageName = pkg,
                appName = appName,
                windowTitle = windowTitle,
                scrollPasses = 1,
                totalNodes = 0,
                extractedTexts = listOf("Aucun contenu d'application détecté au premier plan. Ouvrez l'application à lire."),
                captureType = captureType
            )
        }

        var nodeCounter = 0
        fun parseNode(node: AccessibilityNodeInfo, path: String): UiNode {
            nodeCounter++
            val rect = Rect()
            node.getBoundsInScreen(rect)

            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            val viewId = node.viewIdResourceName
            val className = node.className?.toString() ?: ""

            val childList = mutableListOf<UiNode>()
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null }
                if (child != null) {
                    childList.add(parseNode(child, "$path/$i"))
                }
            }

            return UiNode(
                id = path,
                className = className,
                packageName = node.packageName?.toString() ?: pkg,
                text = text,
                contentDescription = desc,
                viewIdResourceName = viewId,
                bounds = NodeBounds(rect.left, rect.top, rect.right, rect.bottom),
                isClickable = node.isClickable,
                isScrollable = node.isScrollable,
                isEditable = node.isEditable,
                isCheckable = node.isCheckable,
                isChecked = node.isChecked,
                isFocused = node.isFocused,
                isHeading = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) node.isHeading else false,
                children = childList
            )
        }

        val parsedRoot = parseNode(rootNode, "root")
        val texts = mutableListOf<String>()
        parsedRoot.collectAllTexts(texts)
        val interactive = mutableListOf<InteractiveElement>()
        parsedRoot.collectInteractiveElements(interactive)

        val dump = ScreenDump(
            timestamp = System.currentTimeMillis(),
            packageName = pkg,
            appName = appName,
            windowTitle = windowTitle,
            scrollPasses = 1,
            totalNodes = nodeCounter,
            extractedTexts = texts.distinct(),
            interactiveElements = interactive,
            rootNode = parsedRoot,
            captureType = captureType
        )
        _lastCapturedDump.value = dump
        return dump
    }

    /**
     * Performs automated scrolling and reads screen content at each step.
     * Compiles an aggregated ScreenDump containing all newly discovered text.
     */
    suspend fun scrollAndRead(
        maxScrolls: Int = 3,
        delayMs: Long = 750,
        onProgress: ((currentPass: Int, maxPass: Int, newFound: Int) -> Unit)? = null
    ): ScreenDump {
        val aggregatedTexts = mutableListOf<String>()
        var lastCaptured: ScreenDump = readCurrentScreen("scroll_and_read")
        aggregatedTexts.addAll(lastCaptured.extractedTexts)

        var completedPasses = 1
        onProgress?.invoke(1, maxScrolls, aggregatedTexts.size)

        for (i in 1..maxScrolls) {
            delay(250) // Small rest to avoid gesture conflict
            val scrolled = scrollDown()
            delay(delayMs)

            val currentDump = readCurrentScreen("scroll_and_read")
            val newTexts = currentDump.extractedTexts.filterNot { aggregatedTexts.contains(it) }

            if (newTexts.isEmpty() && !scrolled) {
                // End of content reached or reached non-scrollable area
                break
            }

            aggregatedTexts.addAll(newTexts)
            lastCaptured = currentDump
            completedPasses++
            onProgress?.invoke(completedPasses, maxScrolls, aggregatedTexts.size)
        }

        val resultDump = lastCaptured.copy(
            scrollPasses = completedPasses,
            extractedTexts = aggregatedTexts.distinct(),
            captureType = "scroll_and_read"
        )
        _lastCapturedDump.value = resultDump
        return resultDump
    }

    /**
     * Scrolls down using accessibility action or gesture.
     */
    suspend fun scrollDown(): Boolean {
        try {
            val root = getActiveAppRootNode()
            if (root != null) {
                val scrollableNode = findScrollableNode(root)
                if (scrollableNode != null) {
                    val success = scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    if (success) return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_SCROLL_FORWARD failed, falling back to swipe gesture", e)
        }
        return performSwipeGesture(isScrollDown = true)
    }

    /**
     * Scrolls up using accessibility action or gesture.
     */
    suspend fun scrollUp(): Boolean {
        try {
            val root = getActiveAppRootNode()
            if (root != null) {
                val scrollableNode = findScrollableNode(root)
                if (scrollableNode != null) {
                    val success = scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    if (success) return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_SCROLL_BACKWARD failed, falling back to swipe gesture", e)
        }
        return performSwipeGesture(isScrollDown = false)
    }

    /**
     * Performs a tap or click on an element by query.
     */
    fun clickElement(target: String): Boolean {
        val root = getActiveAppRootNode() ?: return false
        val node = findNodeMatching(root, target)
        if (node != null) {
            var curr: AccessibilityNodeInfo? = node
            while (curr != null) {
                if (curr.isClickable) {
                    return curr.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                curr = curr.parent
            }
            val rect = Rect()
            node.getBoundsInScreen(rect)
            return performTapGesture(rect.centerX().toFloat(), rect.centerY().toFloat())
        }
        return false
    }

    /**
     * Types text into a targeted element or focused editable.
     */
    fun typeText(target: String?, textToType: String): Boolean {
        val root = getActiveAppRootNode() ?: return false
        val node = if (!target.isNullOrBlank()) {
            findNodeMatching(root, target)
        } else {
            findFocusedEditable(root)
        }

        if (node != null && node.isEditable) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
            }
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
        return false
    }

    // =========================================================================
    // FLOATING OVERLAY MANAGEMENT (Crash-proof, stays active across all apps)
    // =========================================================================

    fun toggleFloatingOverlay() {
        if (_isFloatingOverlayVisible.value) {
            hideFloatingOverlay()
        } else {
            showFloatingOverlay()
        }
    }

    fun showFloatingOverlay() {
        if (floatingOverlayView != null) return

        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val density = resources.displayMetrics.density
            fun dpToPx(dp: Int): Int = (dp * density).toInt()

            val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dpToPx(16)
                y = dpToPx(180)
            }
            overlayLayoutParams = params

            // Container
            val container = FrameLayout(this)

            // Main Pill
            val pill = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val bg = GradientDrawable().apply {
                    setColor(0xF00F172A.toInt()) // High-opacity deep slate
                    cornerRadius = dpToPx(24).toFloat()
                    setStroke(dpToPx(1), 0xFF00E5FF.toInt()) // Modern Cyan border
                }
                background = bg
                setPadding(dpToPx(6), dpToPx(4), dpToPx(8), dpToPx(4))
                elevation = dpToPx(8).toFloat()
            }

            // Drag Handle
            val dragHandle = ImageView(this).apply {
                setImageResource(android.R.drawable.ic_menu_sort_by_size)
                setColorFilter(0xFF94A3B8.toInt())
                setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6))
                layoutParams = LinearLayout.LayoutParams(dpToPx(28), dpToPx(38))
                contentDescription = "Déplacer le widget"
            }

            // Single Read Button
            val readBtn = TextView(this).apply {
                text = "👁 Lire"
                textSize = 12f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                val b = GradientDrawable().apply {
                    setColor(0xFF1E293B.toInt())
                    cornerRadius = dpToPx(14).toFloat()
                }
                background = b
                setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dpToPx(36)
                ).apply {
                    marginEnd = dpToPx(6)
                }
            }

            // Main SCROLLER & LIRE Button
            val scrollReadBtn = TextView(this).apply {
                text = "▼ Scroller & Lire"
                textSize = 12f
                setTextColor(Color.BLACK)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                val b = GradientDrawable().apply {
                    setColor(0xFF00E5FF.toInt()) // Cyan highlight
                    cornerRadius = dpToPx(14).toFloat()
                }
                background = b
                setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dpToPx(36)
                )
            }

            // Close Button (✕)
            val closeBtn = TextView(this).apply {
                text = "✕"
                textSize = 14f
                setTextColor(0xFF94A3B8.toInt())
                gravity = Gravity.CENTER
                setPadding(dpToPx(8), dpToPx(4), dpToPx(6), dpToPx(4))
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(28),
                    dpToPx(36)
                )
            }

            pill.addView(dragHandle)
            pill.addView(readBtn)
            pill.addView(scrollReadBtn)
            pill.addView(closeBtn)
            container.addView(pill)

            // Touch dragging logic
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f

            dragHandle.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = overlayLayoutParams?.x ?: 0
                        initialY = overlayLayoutParams?.y ?: 0
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        overlayLayoutParams?.let { lp ->
                            lp.x = initialX + (event.rawX - initialTouchX).toInt()
                            lp.y = initialY + (event.rawY - initialTouchY).toInt()
                            floatingOverlayView?.let { v ->
                                windowManager?.updateViewLayout(v, lp)
                            }
                        }
                        true
                    }
                    else -> false
                }
            }

            // Click: Single Read
            readBtn.setOnClickListener {
                if (isScrollActionRunning) return@setOnClickListener
                serviceScope.launch {
                    try {
                        readBtn.text = "⏳ ..."
                        val dump = readCurrentScreen("floating_read")
                        val app = application as? ScreenReaderApp
                        app?.repository?.saveCapture(dump)
                        Toast.makeText(
                            this@ScreenReaderAccessibilityService,
                            "✓ ${dump.extractedTexts.size} textes capturés en JSON (${dump.appName})",
                            Toast.LENGTH_SHORT
                        ).show()
                        readBtn.text = "✓ OK"
                        delay(1500)
                        readBtn.text = "👁 Lire"
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in floating read", e)
                        readBtn.text = "👁 Lire"
                    }
                }
            }

            // Click: Scroller & Lire (Continuer de scroller & extraire)
            scrollReadBtn.setOnClickListener {
                if (isScrollActionRunning) return@setOnClickListener
                isScrollActionRunning = true

                serviceScope.launch {
                    try {
                        scrollReadBtn.text = "⏳ Scroll..."
                        Toast.makeText(
                            this@ScreenReaderAccessibilityService,
                            "Défilement & capture automatique en cours...",
                            Toast.LENGTH_SHORT
                        ).show()

                        val dump = scrollAndRead(
                            maxScrolls = 3,
                            delayMs = 750,
                            onProgress = { currentPass, maxPass, count ->
                                scrollReadBtn.text = "Scroll $currentPass/$maxPass ($count)"
                            }
                        )

                        val app = application as? ScreenReaderApp
                        app?.repository?.saveCapture(dump)

                        Toast.makeText(
                            this@ScreenReaderAccessibilityService,
                            "✓ ${dump.extractedTexts.size} textes extraits et enregistrés en JSON !",
                            Toast.LENGTH_LONG
                        ).show()

                        scrollReadBtn.text = "✓ ${dump.extractedTexts.size} textes"
                        delay(2500)
                        scrollReadBtn.text = "▼ Scroller & Lire"
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during scroll and read from overlay", e)
                        Toast.makeText(
                            this@ScreenReaderAccessibilityService,
                            "Erreur lors du défilement: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                        scrollReadBtn.text = "▼ Scroller & Lire"
                    } finally {
                        isScrollActionRunning = false
                    }
                }
            }

            // Click: Close
            closeBtn.setOnClickListener {
                hideFloatingOverlay()
            }

            windowManager?.addView(container, params)
            floatingOverlayView = container
            _isFloatingOverlayVisible.value = true
            Toast.makeText(this, "Bouton flottant activé. Déplacez-vous sur une autre app !", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach floating overlay", e)
            Toast.makeText(this, "Erreur affichage bouton: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun hideFloatingOverlay() {
        try {
            floatingOverlayView?.let {
                windowManager?.removeView(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error removing floating overlay", e)
        } finally {
            floatingOverlayView = null
            _isFloatingOverlayVisible.value = false
        }
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
            val found = findScrollableNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun findNodeMatching(node: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val lowerQuery = query.lowercase().trim()
        val text = node.text?.toString()?.lowercase()
        val desc = node.contentDescription?.toString()?.lowercase()
        val viewId = node.viewIdResourceName?.lowercase()

        if (text?.contains(lowerQuery) == true ||
            desc?.contains(lowerQuery) == true ||
            viewId?.contains(lowerQuery) == true
        ) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
            val found = findNodeMatching(child, query)
            if (found != null) return found
        }
        return null
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && (node.isFocused || node.isAccessibilityFocused)) return node
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
            val found = findFocusedEditable(child)
            if (found != null) return found
        }
        return null
    }

    private fun findWindowTitle(root: AccessibilityNodeInfo): String? {
        for (i in 0 until root.childCount) {
            val child = try { root.getChild(i) } catch (e: Exception) { null } ?: continue
            val text = child.text?.toString()
            if (!text.isNullOrBlank() && text.length < 50) {
                return text
            }
        }
        return null
    }

    private fun getAppLabel(pkg: String): String {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            pkg.substringAfterLast('.')
        }
    }

    private suspend fun performSwipeGesture(isScrollDown: Boolean): Boolean = suspendCancellableCoroutine { cont ->
        try {
            val displayMetrics: DisplayMetrics = resources.displayMetrics
            val width = displayMetrics.widthPixels.toFloat()
            val height = displayMetrics.heightPixels.toFloat()

            // Keep swipe safely inside the central 50% of the screen
            val startX = width * 0.5f
            val startY = if (isScrollDown) height * 0.72f else height * 0.28f
            val endX = width * 0.5f
            val endY = if (isScrollDown) height * 0.28f else height * 0.72f

            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
                .build()

            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)

            if (!dispatched && cont.isActive) {
                cont.resume(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error performing swipe gesture", e)
            if (cont.isActive) cont.resume(false)
        }
    }

    private fun performTapGesture(x: Float, y: Float): Boolean {
        return try {
            val path = Path().apply {
                moveTo(x, y)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error performing tap gesture", e)
            false
        }
    }

    companion object {
        private const val TAG = "ScreenReaderService"

        var instance: ScreenReaderAccessibilityService? = null
            private set

        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

        private val _isFloatingOverlayVisible = MutableStateFlow(false)
        val isFloatingOverlayVisible: StateFlow<Boolean> = _isFloatingOverlayVisible.asStateFlow()

        private val _lastActivePackage = MutableStateFlow("")
        val lastActivePackage: StateFlow<String> = _lastActivePackage.asStateFlow()

        private val _lastCapturedDump = MutableStateFlow<ScreenDump?>(null)
        val lastCapturedDump: StateFlow<ScreenDump?> = _lastCapturedDump.asStateFlow()
    }
}
