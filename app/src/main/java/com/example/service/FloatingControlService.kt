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
        val button = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_search)
            setBackgroundResource(android.R.drawable.btn_default)
            contentDescription = "MCP Screen Reader Trigger"
            setPadding(24, 24, 24, 24)
        }
        container.addView(button)
        floatingView = container

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isMoving = false

        button.setOnTouchListener { _, event ->
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
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isMoving = true
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager?.updateViewLayout(container, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isMoving) {
                        triggerQuickRead()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(container, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun triggerQuickRead() {
        val accessibilityService = ScreenReaderAccessibilityService.instance
        if (accessibilityService == null) {
            Toast.makeText(this, "Service d'accessibilité inactif", Toast.LENGTH_SHORT).show()
            return
        }

        serviceScope.launch {
            Toast.makeText(this@FloatingControlService, "Lecture et scroll en cours...", Toast.LENGTH_SHORT).show()
            val dump = accessibilityService.scrollAndRead(maxScrolls = 2, delayMs = 600)
            val app = application as? ScreenReaderApp
            app?.repository?.saveCapture(dump)
            Toast.makeText(
                this@FloatingControlService,
                "✓ ${dump.extractedTexts.size} textes capturés en JSON",
                Toast.LENGTH_LONG
            ).show()
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
