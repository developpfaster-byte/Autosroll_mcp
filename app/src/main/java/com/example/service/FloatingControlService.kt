package com.example.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Backward compatibility wrapper. Floating overlay is safely managed
 * directly by ScreenReaderAccessibilityService to prevent foreground service
 * conflicts and ensure continuous accessibility uptime.
 */
class FloatingControlService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ScreenReaderAccessibilityService.instance?.showFloatingOverlay()
        stopSelf()
        return START_NOT_STICKY
    }
}
