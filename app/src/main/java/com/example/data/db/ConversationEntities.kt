package com.example.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Entity(
    tableName = "conversation_branches",
    indices = [Index(value = ["branchName"], unique = true)]
)
data class ConversationBranchEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val branchName: String,
    val appName: String = "Application",
    val appPackage: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val totalTurns: Int = 0,
    val notes: String = ""
) {
    val formattedUpdatedAt: String
        get() = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(updatedAt))
}

@Entity(
    tableName = "conversation_turns",
    indices = [
        Index(value = ["turnId"], unique = true),
        Index(value = ["branchName"]),
        Index(value = ["parentTurnId"])
    ]
)
data class ConversationTurnEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val turnId: String,               // Unique Git-like commit hash / ID (e.g. c1a8f9)
    val branchName: String,           // Name of branch (e.g. "main", "chatgpt-ia")
    val parentTurnId: String? = null, // Parent commit ID (null if root)
    val turnIndex: Int,               // 1, 2, 3...
    val role: String,                 // "user", "assistant", "system"
    val stitchedText: String,         // Deduplicated continuous stitched text across all scroll passes
    val startSnippet: String,         // Beginning of text (marker début)
    val endSnippet: String,           // Ending of text (marker fin)
    val scrollPassCount: Int,         // Number of pages/scrolls captured (e.g. 25)
    val wordCount: Int,
    val charCount: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val appName: String = "",
    val appPackage: String = ""
) {
    val formattedTimestamp: String
        get() = SimpleDateFormat("HH:mm:ss (dd/MM)", Locale.getDefault()).format(Date(timestamp))

    val shortTurnId: String
        get() = if (turnId.length > 7) turnId.take(7) else turnId
}
