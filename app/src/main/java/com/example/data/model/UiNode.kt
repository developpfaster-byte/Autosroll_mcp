package com.example.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents a single node from the Android Accessibility view tree.
 */
data class UiNode(
    val id: String = "",
    val className: String = "",
    val packageName: String = "",
    val text: String? = null,
    val contentDescription: String? = null,
    val viewIdResourceName: String? = null,
    val bounds: NodeBounds = NodeBounds(),
    val isClickable: Boolean = false,
    val isScrollable: Boolean = false,
    val isEditable: Boolean = false,
    val isCheckable: Boolean = false,
    val isChecked: Boolean = false,
    val isFocused: Boolean = false,
    val isHeading: Boolean = false,
    val children: List<UiNode> = emptyList()
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("className", className)
        if (text != null && text.isNotBlank()) json.put("text", text)
        if (contentDescription != null && contentDescription.isNotBlank()) json.put("contentDescription", contentDescription)
        if (viewIdResourceName != null && viewIdResourceName.isNotBlank()) json.put("viewId", viewIdResourceName)
        json.put("bounds", bounds.toJson())
        if (isClickable) json.put("isClickable", true)
        if (isScrollable) json.put("isScrollable", true)
        if (isEditable) json.put("isEditable", true)
        if (isCheckable) json.put("isCheckable", true)
        if (isChecked) json.put("isChecked", true)
        if (isFocused) json.put("isFocused", true)
        if (isHeading) json.put("isHeading", true)
        
        if (children.isNotEmpty()) {
            val childrenArray = JSONArray()
            children.forEach { childrenArray.put(it.toJson()) }
            json.put("children", childrenArray)
        }
        return json
    }

    /**
     * Collects all non-blank readable text recursively
     */
    fun collectAllTexts(destination: MutableList<String>) {
        if (!text.isNullOrBlank()) {
            destination.add(text.trim())
        } else if (!contentDescription.isNullOrBlank()) {
            destination.add(contentDescription.trim())
        }
        children.forEach { it.collectAllTexts(destination) }
    }

    /**
     * Collects interactive elements (clickable, editable, scrollable)
     */
    fun collectInteractiveElements(destination: MutableList<InteractiveElement>) {
        if (isClickable || isEditable || isScrollable) {
            val label = text ?: contentDescription ?: viewIdResourceName ?: className.substringAfterLast('.')
            destination.add(
                InteractiveElement(
                    id = id,
                    label = label,
                    type = when {
                        isEditable -> "EditText"
                        isClickable -> "Clickable"
                        isScrollable -> "Scrollable"
                        else -> "Element"
                    },
                    viewId = viewIdResourceName,
                    bounds = bounds,
                    isClickable = isClickable,
                    isEditable = isEditable,
                    isScrollable = isScrollable
                )
            )
        }
        children.forEach { it.collectInteractiveElements(destination) }
    }
}

data class NodeBounds(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("left", left)
        json.put("top", top)
        json.put("right", right)
        json.put("bottom", bottom)
        json.put("width", width)
        json.put("height", height)
        json.put("center", JSONObject().put("x", centerX).put("y", centerY))
        return json
    }
}

data class InteractiveElement(
    val id: String,
    val label: String,
    val type: String,
    val viewId: String?,
    val bounds: NodeBounds,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("label", label)
        json.put("type", type)
        if (viewId != null) json.put("viewId", viewId)
        json.put("bounds", bounds.toJson())
        json.put("isClickable", isClickable)
        json.put("isEditable", isEditable)
        json.put("isScrollable", isScrollable)
        return json
    }
}
