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
import android.provider.Settings
import android.text.TextUtils
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
import com.example.data.db.ConversationTurnEntity
import com.example.data.engine.StitchedResult
import com.example.data.engine.TextStitcherEngine
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
        fun parseNode(node: AccessibilityNodeInfo, path: String, depth: Int = 0): UiNode {
            nodeCounter++
            val rect = Rect()
            node.getBoundsInScreen(rect)

            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            val viewId = node.viewIdResourceName
            val className = node.className?.toString() ?: ""

            val childList = mutableListOf<UiNode>()
            if (depth < 30) {
                for (i in 0 until node.childCount) {
                    val child = try { node.getChild(i) } catch (e: Exception) { null }
                    if (child != null) {
                        childList.add(parseNode(child, "$path/$i", depth + 1))
                    }
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
     * Continuous multi-pass scrolling that dynamically detects the true end of the page/response
     * (rather than relying on a hardcoded count), stitches text without duplicating overlapping phrases,
     * slices off content from previous turns to maintain conversation continuity, and commits the turn to the branch.
     */
    suspend fun scrollAndStitchConversationTurn(
        maxScrolls: Int = 80, // Safe ceiling; stops dynamically as soon as page end is reached
        delayMs: Long = 750,
        branchName: String = "",
        role: String = "auto",
        onProgress: ((currentPass: Int, wordCount: Int, statusMessage: String) -> Unit)? = null
    ): Pair<ScreenDump, ConversationTurnEntity?> {
        val app = application as? ScreenReaderApp
        val targetBranch = branchName.ifBlank { app?.repository?.activeBranch?.value ?: "main" }

        // 1. Retrieve the previous turn to preserve continuity ("suite de la conversation")
        val previousTurn = app?.repository?.getLastTurnForBranch(targetBranch)
        val previousEndAnchor = previousTurn?.endSnippet

        val passes = mutableListOf<List<String>>()

        // 2. Initial capture pass
        val initialDump = readCurrentScreen("conversation_turn")
        passes.add(initialDump.extractedTexts)
        var lastCaptured = initialDump
        var completedPasses = 1

        // 3. Initial stitch with previous turn anchor slicing
        var currentStitched = TextStitcherEngine.stitchScrollPasses(
            passes = passes,
            previousTurnEndAnchor = previousEndAnchor
        )

        val initStatus = if (previousTurn != null) {
            "Suite du Tour #${previousTurn.turnIndex} (Début détecté)"
        } else {
            "Début de conversation détecté"
        }
        onProgress?.invoke(1, currentStitched.wordCount, initStatus)

        var stagnantCount = 0
        var terminationReason = "Fin de page détectée automatiquement"

        // 4. Dynamic scroll loop until true end of content is reached
        for (i in 1..maxScrolls) {
            delay(200)
            val scrolled = scrollDown()
            delay(delayMs)

            val passDump = readCurrentScreen("conversation_turn")
            val newUnique = passDump.extractedTexts.filterNot { currentStitched.stitchedLines.contains(it) }

            if (newUnique.isEmpty()) {
                stagnantCount++
            } else {
                stagnantCount = 0
            }

            // Dynamically check if true end of page/response is reached
            val (isEnd, reason) = TextStitcherEngine.isEndOfPageReached(
                scrolledSuccessfully = scrolled,
                newUniqueCount = newUnique.size,
                stagnantCount = stagnantCount,
                screenTexts = passDump.extractedTexts
            )

            if (newUnique.isNotEmpty()) {
                passes.add(passDump.extractedTexts)
                currentStitched = TextStitcherEngine.stitchScrollPasses(
                    passes = passes,
                    previousTurnEndAnchor = previousEndAnchor
                )
                lastCaptured = passDump
                completedPasses++
            }

            if (isEnd) {
                terminationReason = reason
                onProgress?.invoke(completedPasses, currentStitched.wordCount, "✓ $reason")
                break
            } else {
                onProgress?.invoke(completedPasses, currentStitched.wordCount, "Page $completedPasses ($stagnantCount statique)")
            }
        }

        val inferredRole = if (role == "auto") {
            TextStitcherEngine.inferRole(currentStitched.fullText, currentStitched.stitchedLines.size)
        } else {
            role
        }

        val finalStitched = currentStitched.copy(endReason = terminationReason)

        val turnEntity = app?.repository?.appendTurnToBranch(
            branchName = targetBranch,
            stitchedResult = finalStitched,
            role = inferredRole,
            scrollPassCount = completedPasses,
            appName = lastCaptured.appName,
            appPackage = lastCaptured.packageName
        )

        val dumpResult = lastCaptured.copy(
            scrollPasses = completedPasses,
            extractedTexts = finalStitched.stitchedLines,
            captureType = "conversation_turn"
        )
        try {
            app?.repository?.saveCapture(dumpResult)
        } catch (e: Throwable) {
            Log.e(TAG, "Error saving dump capture", e)
        }
        _lastCapturedDump.value = dumpResult

        return Pair(dumpResult, turnEntity)
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
        // Clean up any existing overlay view first to prevent 'view already added' errors
        hideFloatingOverlay()

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

            // Branch indicator badge
            val app = application as? ScreenReaderApp
            val activeBranch = app?.repository?.activeBranch?.value ?: "main"
            val branchBadge = TextView(this).apply {
                text = "🌿 $activeBranch"
                textSize = 10f
                setTextColor(0xFF38BDF8.toInt())
                gravity = Gravity.CENTER
                val b = GradientDrawable().apply {
                    setColor(0x330284C7.toInt())
                    cornerRadius = dpToPx(10).toFloat()
                }
                background = b
                setPadding(dpToPx(6), dpToPx(3), dpToPx(6), dpToPx(3))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dpToPx(28)
                ).apply {
                    marginEnd = dpToPx(4)
                }
            }

            // Single Read Button
            val readBtn = TextView(this).apply {
                text = "👁"
                textSize = 12f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                val b = GradientDrawable().apply {
                    setColor(0xFF1E293B.toInt())
                    cornerRadius = dpToPx(14).toFloat()
                }
                background = b
                setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dpToPx(36)
                ).apply {
                    marginEnd = dpToPx(4)
                }
                contentDescription = "Lire l'écran visible"
            }

            // New Turn Marker Button
            val newTurnBtn = TextView(this).apply {
                text = "+ Tour"
                textSize = 11f
                setTextColor(0xFFF8FAFC.toInt())
                gravity = Gravity.CENTER
                val b = GradientDrawable().apply {
                    setColor(0xFF334155.toInt())
                    cornerRadius = dpToPx(14).toFloat()
                }
                background = b
                setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dpToPx(36)
                ).apply {
                    marginEnd = dpToPx(4)
                }
                contentDescription = "Marquer le début d'une nouvelle question/réponse"
            }

            // Main SCROLLER & COUDRE Button (Dynamic page-end detection)
            val scrollReadBtn = TextView(this).apply {
                text = "▼ Défilement Auto-Fin"
                textSize = 12f
                setTextColor(Color.BLACK)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                val b = GradientDrawable().apply {
                    setColor(0xFF00E5FF.toInt()) // Cyan highlight
                    cornerRadius = dpToPx(14).toFloat()
                }
                background = b
                setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dpToPx(36)
                )
                contentDescription = "Défilement continu avec détection automatique de la fin du message et couture sans doublon"
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
            pill.addView(branchBadge)
            pill.addView(readBtn)
            pill.addView(newTurnBtn)
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
                        readBtn.text = "⏳"
                        val dump = readCurrentScreen("floating_read")
                        app?.repository?.saveCapture(dump)
                        Toast.makeText(
                            this@ScreenReaderAccessibilityService,
                            "✓ ${dump.extractedTexts.size} textes capturés (${dump.appName})",
                            Toast.LENGTH_SHORT
                        ).show()
                        readBtn.text = "✓"
                        delay(1200)
                        readBtn.text = "👁"
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in floating read", e)
                        readBtn.text = "👁"
                    }
                }
            }

            // Click: New Turn Marker
            newTurnBtn.setOnClickListener {
                Toast.makeText(
                    this@ScreenReaderAccessibilityService,
                    "🌿 Prêt pour une nouvelle question/réponse. Les prochains défilements créeront un nouveau tour.",
                    Toast.LENGTH_SHORT
                ).show()
            }

            // Click: Scroller & Coudre (Dynamic detection of end of page)
            scrollReadBtn.setOnClickListener {
                if (isScrollActionRunning) return@setOnClickListener
                isScrollActionRunning = true

                serviceScope.launch {
                    try {
                        scrollReadBtn.text = "Détection..."
                        val currentBranch = app?.repository?.activeBranch?.value ?: "main"
                        branchBadge.text = "🌿 $currentBranch"

                        Toast.makeText(
                            this@ScreenReaderAccessibilityService,
                            "Défilement dynamique : recherche de la fin du message et couture sans doublons...",
                            Toast.LENGTH_SHORT
                        ).show()

                        val result = scrollAndStitchConversationTurn(
                            maxScrolls = 80,
                            delayMs = 750,
                            branchName = currentBranch,
                            role = "auto",
                            onProgress = { currentPass, wordCount, statusMsg ->
                                scrollReadBtn.text = "P$currentPass ($wordCount m)"
                            }
                        )

                        val turn = result.second
                        val dump = result.first

                        val continuityText = if (turn?.parentTurnId != null) "Suite T#${turn.turnIndex - 1}" else "T#1"
                        Toast.makeText(
                            this@ScreenReaderAccessibilityService,
                            "✓ $continuityText cousu : ${turn?.wordCount ?: 0} mots (${dump.scrollPasses}p, fin détectée) dans 🌿 $currentBranch !",
                            Toast.LENGTH_LONG
                        ).show()

                        scrollReadBtn.text = "✓ T#${turn?.turnIndex} (${turn?.wordCount}m)"
                        delay(2800)
                        scrollReadBtn.text = "▼ Défilement Auto-Fin"
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during scroll and stitch from overlay", e)
                        Toast.makeText(
                            this@ScreenReaderAccessibilityService,
                            "Erreur lors du défilement: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                        scrollReadBtn.text = "▼ Défilement Auto-Fin"
                    } finally {
                        isScrollActionRunning = false
                    }
                }
            }

            // Click: Close
            closeBtn.setOnClickListener {
                hideFloatingOverlay()
            }

            try {
                windowManager?.addView(container, params)
            } catch (e: Exception) {
                Log.w(TAG, "Failed adding with TYPE_ACCESSIBILITY_OVERLAY: ${e.message}, trying fallback", e)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Settings.canDrawOverlays(this)) {
                    params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    windowManager?.addView(container, params)
                } else {
                    throw e
                }
            }
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
            floatingOverlayView?.let { view ->
                try {
                    windowManager?.removeViewImmediate(view)
                } catch (e: Exception) {
                    windowManager?.removeView(view)
                }
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

        /**
         * Checks if this service is enabled in Android Accessibility settings,
         * helping distinguish between an unconfigured service vs a service awaiting restart.
         */
        fun isConfiguredInSettings(context: Context): Boolean {
            return try {
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                val colonSplitter = TextUtils.SimpleStringSplitter(':')
                colonSplitter.setString(enabledServices)
                while (colonSplitter.hasNext()) {
                    val componentName = colonSplitter.next()
                    if (componentName.contains(context.packageName, ignoreCase = true) &&
                        componentName.contains("ScreenReaderAccessibilityService", ignoreCase = true)
                    ) {
                        return true
                    }
                }
                false
            } catch (e: Exception) {
                false
            }
        }
    }
}
