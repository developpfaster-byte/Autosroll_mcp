package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Entity(tableName = "screen_captures")
data class ScreenCaptureEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val appPackage: String,
    val appName: String,
    val windowTitle: String,
    val captureType: String, // "single_read", "scroll_and_read", "mcp_tool_call"
    val scrollPasses: Int,
    val extractedTextCount: Int,
    val summaryText: String,
    val jsonPayload: String
) {
    val formattedTimestamp: String
        get() = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}
