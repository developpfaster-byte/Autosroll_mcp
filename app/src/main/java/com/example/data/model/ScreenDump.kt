package com.example.data.model

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Represents a complete structured dump of a screen read session.
 */
data class ScreenDump(
    val timestamp: Long = System.currentTimeMillis(),
    val packageName: String = "",
    val appName: String = "",
    val windowTitle: String = "",
    val scrollPasses: Int = 1,
    val totalNodes: Int = 0,
    val extractedTexts: List<String> = emptyList(),
    val interactiveElements: List<InteractiveElement> = emptyList(),
    val rootNode: UiNode? = null,
    val captureType: String = "single_read" // "single_read", "scroll_and_read", "mcp_tool_call"
) {
    val formattedDate: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

    val summary: String
        get() = if (extractedTexts.isNotEmpty()) {
            extractedTexts.take(5).joinToString(" • ")
        } else {
            "No text captured"
        }

    fun toFormattedJsonString(indentSpaces: Int = 2): String {
        return toJson().toString(indentSpaces)
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("protocol", "MCP-Screen-Dump-1.0")
        json.put("timestamp", timestamp)
        json.put("formattedTime", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date(timestamp)))
        json.put("packageName", packageName)
        json.put("appName", appName)
        json.put("windowTitle", windowTitle)
        json.put("captureType", captureType)
        json.put("scrollPasses", scrollPasses)
        json.put("totalNodes", totalNodes)
        
        val textArray = JSONArray()
        extractedTexts.forEach { textArray.put(it) }
        json.put("extractedTexts", textArray)

        val elementsArray = JSONArray()
        interactiveElements.forEach { elementsArray.put(it.toJson()) }
        json.put("interactiveElements", elementsArray)

        if (rootNode != null) {
            json.put("uiHierarchy", rootNode.toJson())
        }

        return json
    }

    companion object {
        fun fromJson(jsonStr: String): ScreenDump? {
            return try {
                val json = JSONObject(jsonStr)
                val texts = mutableListOf<String>()
                val textArr = json.optJSONArray("extractedTexts")
                if (textArr != null) {
                    for (i in 0 until textArr.length()) {
                        texts.add(textArr.getString(i))
                    }
                }
                ScreenDump(
                    timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                    packageName = json.optString("packageName", ""),
                    appName = json.optString("appName", ""),
                    windowTitle = json.optString("windowTitle", ""),
                    scrollPasses = json.optInt("scrollPasses", 1),
                    totalNodes = json.optInt("totalNodes", 0),
                    extractedTexts = texts,
                    captureType = json.optString("captureType", "single_read")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
