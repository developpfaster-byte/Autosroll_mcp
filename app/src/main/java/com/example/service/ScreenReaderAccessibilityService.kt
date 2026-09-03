package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.model.InteractiveElement
import com.example.data.model.NodeBounds
import com.example.data.model.ScreenDump
import com.example.data.model.UiNode
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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceActive.value = true
        Log.d(TAG, "ScreenReaderAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Track active window / package changes if needed
        event?.packageName?.let {
            _lastActivePackage.value = it.toString()
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "ScreenReaderAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        _isServiceActive.value = false
        Log.d(TAG, "ScreenReaderAccessibilityService destroyed")
    }

    /**
     * Reads and parses the current screen view hierarchy into a ScreenDump.
     */
    fun readCurrentScreen(captureType: String = "single_read"): ScreenDump {
        val rootNode = rootInActiveWindow
        val packageName = rootNode?.packageName?.toString() ?: _lastActivePackage.value
        val appName = getAppLabel(packageName)
        val windowTitle = rootNode?.let { findWindowTitle(it) } ?: appName

        if (rootNode == null) {
            return ScreenDump(
                timestamp = System.currentTimeMillis(),
                packageName = packageName,
                appName = appName,
                windowTitle = windowTitle,
                scrollPasses = 1,
                totalNodes = 0,
                extractedTexts = listOf("No active window hierarchy available. Ensure screen is unlocked."),
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
                val child = node.getChild(i)
                if (child != null) {
                    childList.add(parseNode(child, "$path/$i"))
                }
            }

            return UiNode(
                id = path,
                className = className,
                packageName = node.packageName?.toString() ?: packageName,
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
            packageName = packageName,
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
     * Compiles an aggregated ScreenDump containing all discovered text.
     */
    suspend fun scrollAndRead(maxScrolls: Int = 3, delayMs: Long = 800): ScreenDump {
        val aggregatedTexts = mutableListOf<String>()
        var lastCaptured: ScreenDump = readCurrentScreen("scroll_and_read")
        aggregatedTexts.addAll(lastCaptured.extractedTexts)

        var completedPasses = 1
        for (i in 1..maxScrolls) {
            val scrolled = scrollDown()
            delay(delayMs)

            val currentDump = readCurrentScreen("scroll_and_read")
            val newTexts = currentDump.extractedTexts.filterNot { aggregatedTexts.contains(it) }

            if (newTexts.isEmpty() && !scrolled) {
                // End of content reached or cannot scroll further
                break
            }

            aggregatedTexts.addAll(newTexts)
            lastCaptured = currentDump
            completedPasses++
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
        val root = rootInActiveWindow
        if (root != null) {
            val scrollableNode = findScrollableNode(root)
            if (scrollableNode != null) {
                val success = scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                if (success) return true
            }
        }
        // Fallback to gesture swipe up (scrolls down)
        return performSwipeGesture(isScrollDown = true)
    }

    /**
     * Scrolls up using accessibility action or gesture.
     */
    suspend fun scrollUp(): Boolean {
        val root = rootInActiveWindow
        if (root != null) {
            val scrollableNode = findScrollableNode(root)
            if (scrollableNode != null) {
                val success = scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                if (success) return true
            }
        }
        // Fallback to gesture swipe down (scrolls up)
        return performSwipeGesture(isScrollDown = false)
    }

    /**
     * Performs a tap or click on an element by ID, Text, or ViewId.
     */
    fun clickElement(target: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeMatching(root, target)
        if (node != null) {
            var curr: AccessibilityNodeInfo? = node
            while (curr != null) {
                if (curr.isClickable) {
                    return curr.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                curr = curr.parent
            }
            // If not clickable directly, click center of bounds via gesture
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
        val root = rootInActiveWindow ?: return false
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

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
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
            val child = node.getChild(i) ?: continue
            val found = findNodeMatching(child, query)
            if (found != null) return found
        }
        return null
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && (node.isFocused || node.isAccessibilityFocused)) return node
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedEditable(child)
            if (found != null) return found
        }
        return null
    }

    private fun findWindowTitle(root: AccessibilityNodeInfo): String? {
        // Try to find header or first meaningful text
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val text = child.text?.toString()
            if (!text.isNullOrBlank() && text.length < 50) {
                return text
            }
        }
        return null
    }

    private fun getAppLabel(packageName: String): String {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast('.')
        }
    }

    private suspend fun performSwipeGesture(isScrollDown: Boolean): Boolean = suspendCancellableCoroutine { cont ->
        val displayMetrics: DisplayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()

        val startX = width / 2f
        val startY = if (isScrollDown) height * 0.75f else height * 0.25f
        val endX = width / 2f
        val endY = if (isScrollDown) height * 0.25f else height * 0.75f

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 400))
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
    }

    private fun performTapGesture(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        return dispatchGesture(gesture, null, null)
    }

    companion object {
        private const val TAG = "ScreenReaderService"

        var instance: ScreenReaderAccessibilityService? = null
            private set

        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

        private val _lastActivePackage = MutableStateFlow("")
        val lastActivePackage: StateFlow<String> = _lastActivePackage.asStateFlow()

        private val _lastCapturedDump = MutableStateFlow<ScreenDump?>(null)
        val lastCapturedDump: StateFlow<ScreenDump?> = _lastCapturedDump.asStateFlow()
    }
}
