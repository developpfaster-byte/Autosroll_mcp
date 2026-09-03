package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.ScreenReaderApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FloatingControlService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Settings.canDrawOverlays(this)) {
            setupFloatingView()
        }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun setupFloatingView() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 200
        }

        val container = FrameLayout(this)
        val density = resources.displayMetrics.density
        fun dpToPx(dp: Int): Int = (dp * density).toInt()

        val rootLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val bgDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xEE1E293B.toInt()) // Slate-900 with high opacity
                cornerRadius = dpToPx(28).toFloat()
                setStroke(dpToPx(2), 0xFF00E5FF.toInt()) // Cyan highlight border
            }
            background = bgDrawable
            setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
            elevation = dpToPx(8).toFloat()
        }

        // Drag handle / Move indicator
        val dragHandle = android.widget.ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_sort_by_size)
            setColorFilter(0xFF94A3B8.toInt())
            setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6))
            layoutParams = android.widget.LinearLayout.LayoutParams(dpToPx(32), dpToPx(48))
            contentDescription = "Déplacer le widget flottant"
        }

        // Button 1: Quick Read Screen Only (Single pass)
        val readOnlyButton = android.widget.TextView(this).apply {
            text = "Lire"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            val btnBg = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF334155.toInt())
                cornerRadius = dpToPx(16).toFloat()
            }
            background = btnBg
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                dpToPx(40)
            ).apply {
                marginEnd = dpToPx(6)
            }
        }

        // Button 2: Dedicated SCROLLER & LIRE (Automatic scrolling + Consolidated capture)
        val scrollAndReadButton = android.widget.TextView(this).apply {
            text = "▼ Scroller & Lire"
            textSize = 12f
            setTextColor(0xFF000000.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val btnBg = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF00E5FF.toInt()) // High contrast Cyan
                cornerRadius = dpToPx(16).toFloat()
            }
            background = btnBg
            setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(8))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                dpToPx(40)
            )
        }

        rootLayout.addView(dragHandle)
        rootLayout.addView(readOnlyButton)
        rootLayout.addView(scrollAndReadButton)
        container.addView(rootLayout)
        floatingView = container

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isMoving = false

        // Dragging handler on the drag handle
        dragHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isMoving = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                        isMoving = true
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager?.updateViewLayout(container, params)
                    true
                }
                else -> false
            }
        }

        // Quick Read listener
        readOnlyButton.setOnClickListener {
            triggerQuickRead(maxScrolls = 0)
        }

        // Scroller & Lire listener (Automated multi-pass scroll & read)
        scrollAndReadButton.setOnClickListener {
            triggerQuickRead(maxScrolls = 3)
        }

        try {
            windowManager?.addView(container, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun triggerQuickRead(maxScrolls: Int) {
        val accessibilityService = ScreenReaderAccessibilityService.instance
        if (accessibilityService == null) {
            Toast.makeText(this, "Service d'accessibilité inactif. Activez-le dans l'application.", Toast.LENGTH_SHORT).show()
            return
        }

        serviceScope.launch {
            if (maxScrolls > 0) {
                Toast.makeText(this@FloatingControlService, "Défilement & lecture de l'écran en cours ($maxScrolls passes)...", Toast.LENGTH_SHORT).show()
                val dump = accessibilityService.scrollAndRead(maxScrolls = maxScrolls, delayMs = 700)
                val app = application as? ScreenReaderApp
                app?.repository?.saveCapture(dump)
                Toast.makeText(
                    this@FloatingControlService,
                    "✓ ${dump.extractedTexts.size} textes capturés et enregistrés en JSON !",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this@FloatingControlService, "Lecture de l'écran en cours...", Toast.LENGTH_SHORT).show()
                val dump = accessibilityService.readCurrentScreen(captureType = "floating_read")
                val app = application as? ScreenReaderApp
                app?.repository?.saveCapture(dump)
                Toast.makeText(
                    this@FloatingControlService,
                    "✓ ${dump.extractedTexts.size} textes capturés en JSON",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MCP Screen Reader Actif")
            .setContentText("Le bouton flottant et le serveur MCP sont prêts")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MCP Screen Reader Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (floatingView != null) {
            windowManager?.removeView(floatingView)
            floatingView = null
        }
    }

    companion object {
        const val CHANNEL_ID = "mcp_floating_channel"
        const val NOTIFICATION_ID = 1010
    }
}
