package com.example.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Model Context Protocol (MCP) data structures conforming to the official MCP specification.
 */
object McpProtocol {
    const val PROTOCOL_VERSION = "2024-11-05"
    const val JSONRPC_VERSION = "2.0"
}

data class McpRequest(
    val jsonrpc: String = McpProtocol.JSONRPC_VERSION,
    val id: Any? = null,
    val method: String,
    val params: JSONObject = JSONObject()
) {
    companion object {
        fun fromJson(jsonStr: String): McpRequest {
            val json = JSONObject(jsonStr)
            return McpRequest(
                jsonrpc = json.optString("jsonrpc", McpProtocol.JSONRPC_VERSION),
                id = json.opt("id"),
                method = json.optString("method", ""),
                params = json.optJSONObject("params") ?: JSONObject()
            )
        }
    }
}

data class McpResponse(
    val jsonrpc: String = McpProtocol.JSONRPC_VERSION,
    val id: Any? = null,
    val result: Any? = null,
    val error: McpError? = null
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("jsonrpc", jsonrpc)
        if (id != null) {
            json.put("id", id)
        } else {
            json.put("id", JSONObject.NULL)
        }
        if (error != null) {
            json.put("error", error.toJson())
        } else if (result != null) {
            json.put("result", result)
        }
        return json
    }
}

data class McpError(
    val code: Int,
    val message: String,
    val data: Any? = null
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("code", code)
        json.put("message", message)
        if (data != null) json.put("data", data)
        return json
    }

    companion object {
        val PARSE_ERROR = McpError(-32700, "Parse error")
        val INVALID_REQUEST = McpError(-32600, "Invalid Request")
        val METHOD_NOT_FOUND = McpError(-32601, "Method not found")
        val INVALID_PARAMS = McpError(-32602, "Invalid params")
        val INTERNAL_ERROR = McpError(-32603, "Internal error")
        fun custom(code: Int, message: String, data: Any? = null) = McpError(code, message, data)
    }
}

data class McpToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JSONObject
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("name", name)
        json.put("description", description)
        json.put("inputSchema", inputSchema)
        return json
    }
}

data class McpResourceDefinition(
    val uri: String,
    val name: String,
    val description: String,
    val mimeType: String = "application/json"
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("uri", uri)
        json.put("name", name)
        json.put("description", description)
        json.put("mimeType", mimeType)
        return json
    }
}

data class McpPromptDefinition(
    val name: String,
    val description: String,
    val arguments: JSONArray = JSONArray()
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("name", name)
        json.put("description", description)
        json.put("arguments", arguments)
        return json
    }
}

data class McpLogEntry(
    val id: String,
    val timestamp: Long = System.currentTimeMillis(),
    val direction: LogDirection,
    val method: String,
    val content: String,
    val isSuccess: Boolean = true
)

enum class LogDirection {
    INCOMING, OUTGOING
}
