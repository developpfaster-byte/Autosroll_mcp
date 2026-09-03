package com.example.mcp

import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter
import android.util.Log
import com.example.data.model.LogDirection
import com.example.data.model.McpError
import com.example.data.model.McpLogEntry
import com.example.data.model.McpRequest
import com.example.data.model.McpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

class McpServerEngine(
    private val context: Context,
    private val toolsHandler: McpToolsHandler
) {
    private val serverScope = CoroutineScope(Dispatchers.IO + Job())
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null

    private val _serverState = MutableStateFlow(McpServerState())
    val serverState: StateFlow<McpServerState> = _serverState.asStateFlow()

    private val _logs = MutableStateFlow<List<McpLogEntry>>(emptyList())
    val logs: StateFlow<List<McpLogEntry>> = _logs.asStateFlow()

    fun startServer(port: Int = 8080) {
        if (_serverState.value.isRunning) return

        serverJob = serverScope.launch {
            try {
                serverSocket = ServerSocket(port)
                val hostIp = getLocalIpAddress()
                _serverState.value = McpServerState(
                    isRunning = true,
                    port = port,
                    host = hostIp,
                    endpointUrl = "http://$hostIp:$port/mcp",
                    sseUrl = "http://$hostIp:$port/sse"
                )
                addLog(LogDirection.INCOMING, "SERVER_START", "Serveur MCP démarré sur port $port")

                while (isActive && serverSocket != null && !serverSocket!!.isClosed) {
                    try {
                        val clientSocket = serverSocket!!.accept()
                        launch {
                            handleClientConnection(clientSocket)
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.w(TAG, "Accept error", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start MCP server", e)
                _serverState.value = _serverState.value.copy(
                    isRunning = false,
                    lastError = e.localizedMessage ?: "Port déjà utilisé ou erreur réseau"
                )
                addLog(LogDirection.INCOMING, "SERVER_ERROR", "Erreur: ${e.message}", isSuccess = false)
            }
        }
    }

    fun stopServer() {
        try {
            serverSocket?.close()
            serverSocket = null
            serverJob?.cancel()
            serverJob = null
            _serverState.value = _serverState.value.copy(isRunning = false)
            addLog(LogDirection.INCOMING, "SERVER_STOP", "Serveur MCP arrêté")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
    }

    private suspend fun handleClientConnection(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output: OutputStream = socket.getOutputStream()
            val writer = PrintWriter(output)

            val requestLine = reader.readLine() ?: return@withContext
            val parts = requestLine.split(" ")
            if (parts.size < 2) return@withContext
            val method = parts[0]
            val path = parts[1]

            // Read HTTP headers
            val headers = mutableMapOf<String, String>()
            var line: String?
            var contentLength = 0
            while (reader.readLine().also { line = it } != null && line!!.isNotBlank()) {
                val headerParts = line!!.split(":", limit = 2)
                if (headerParts.size == 2) {
                    val key = headerParts[0].trim().lowercase()
                    val value = headerParts[1].trim()
                    headers[key] = value
                    if (key == "content-length") {
                        contentLength = value.toIntOrNull() ?: 0
                    }
                }
            }

            if (method == "OPTIONS") {
                // CORS preflight
                sendHttpResponse(writer, output, 200, "OK", "text/plain", "")
                return@withContext
            }

            if (method == "GET" && (path == "/" || path.startsWith("/info"))) {
                val infoHtml = generateDashboardHtml()
                sendHttpResponse(writer, output, 200, "OK", "text/html; charset=UTF-8", infoHtml)
                return@withContext
            }

            if (method == "GET" && path.startsWith("/sse")) {
                handleSseConnection(writer, output)
                return@withContext
            }

            // Read POST payload
            val body = if (contentLength > 0) {
                val buffer = CharArray(contentLength)
                var bytesRead = 0
                while (bytesRead < contentLength) {
                    val read = reader.read(buffer, bytesRead, contentLength - bytesRead)
                    if (read == -1) break
                    bytesRead += read
                }
                String(buffer, 0, bytesRead)
            } else ""

            val responseJson = processJsonRpcString(body)
            sendHttpResponse(writer, output, 200, "OK", "application/json", responseJson)

        } catch (e: Exception) {
            Log.e(TAG, "Client handling exception", e)
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun handleSseConnection(writer: PrintWriter, output: OutputStream) {
        try {
            writer.print("HTTP/1.1 200 OK\r\n")
            writer.print("Content-Type: text/event-stream\r\n")
            writer.print("Cache-Control: no-cache\r\n")
            writer.print("Connection: keep-alive\r\n")
            writer.print("Access-Control-Allow-Origin: *\r\n")
            writer.print("\r\n")
            writer.flush()

            val endpoint = "/mcp"
            writer.print("event: endpoint\r\ndata: $endpoint\r\n\r\n")
            writer.flush()
        } catch (e: Exception) {
            Log.w(TAG, "SSE error", e)
        }
    }

    private fun sendHttpResponse(
        writer: PrintWriter,
        output: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: String
    ) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        writer.print("HTTP/1.1 $statusCode $statusText\r\n")
        writer.print("Content-Type: $contentType\r\n")
        writer.print("Content-Length: ${bodyBytes.size}\r\n")
        writer.print("Access-Control-Allow-Origin: *\r\n")
        writer.print("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        writer.print("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
        writer.print("Connection: close\r\n")
        writer.print("\r\n")
        writer.flush()
        if (bodyBytes.isNotEmpty()) {
            output.write(bodyBytes)
            output.flush()
        }
    }

    /**
     * Executes a raw JSON-RPC string (used both by the network server and internal app tester)
     */
    suspend fun executeRawJsonRpc(rawJson: String): String {
        return processJsonRpcString(rawJson)
    }

    private suspend fun processJsonRpcString(body: String): String {
        if (body.isBlank()) {
            val errResponse = McpResponse(id = null, error = McpError.INVALID_REQUEST)
            return errResponse.toJson().toString()
        }

        return try {
            val request = McpRequest.fromJson(body)
            addLog(LogDirection.INCOMING, request.method, body.take(400))

            val response = toolsHandler.handleRequest(request)
            val respStr = response.toJson().toString()

            _serverState.value = _serverState.value.copy(
                totalRequestsHandled = _serverState.value.totalRequestsHandled + 1
            )
            addLog(LogDirection.OUTGOING, request.method, respStr.take(400), response.error == null)

            respStr
        } catch (e: Exception) {
            val errResponse = McpResponse(id = null, error = McpError.PARSE_ERROR)
            val respStr = errResponse.toJson().toString()
            addLog(LogDirection.OUTGOING, "ERROR", "Erreur de syntaxe JSON-RPC: ${e.message}", false)
            respStr
        }
    }

    private fun addLog(direction: LogDirection, method: String, content: String, isSuccess: Boolean = true) {
        val entry = McpLogEntry(
            id = UUID.randomUUID().toString(),
            direction = direction,
            method = method,
            content = content,
            isSuccess = isSuccess
        )
        val currentList = _logs.value.toMutableList()
        if (currentList.size > 50) {
            currentList.removeAt(currentList.lastIndex)
        }
        currentList.add(0, entry)
        _logs.value = currentList
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    private fun getLocalIpAddress(): String {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiIp = wifiManager?.connectionInfo?.ipAddress
            if (wifiIp != null && wifiIp != 0) {
                @Suppress("DEPRECATION")
                return Formatter.formatIpAddress(wifiIp)
            }

            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addresses = intf.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
            "127.0.0.1"
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }

    private fun generateDashboardHtml(): String {
        val state = _serverState.value
        val tools = toolsHandler.getToolsList()
        val toolsListHtml = tools.joinToString("") {
            "<li><strong><code>${it.name}</code></strong>: ${it.description}</li>"
        }

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <title>Android MCP Screen Reader</title>
                <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 40px; background: #0F172A; color: #F8FAFC; line-height: 1.6; }
                    .card { background: #1E293B; border-radius: 12px; padding: 24px; margin-bottom: 20px; border: 1px solid #334155; }
                    h1 { color: #38BDF8; margin-top: 0; }
                    h2 { color: #818CF8; }
                    code { background: #0284C7; color: #FFF; padding: 2px 6px; border-radius: 4px; font-size: 0.9em; }
                    pre { background: #020617; padding: 16px; border-radius: 8px; overflow-x: auto; color: #A5F3FC; }
                    .status-badge { display: inline-block; padding: 4px 12px; border-radius: 20px; background: #10B981; color: #FFF; font-weight: bold; font-size: 0.85em; }
                </style>
            </head>
            <body>
                <div class="card">
                    <h1>📱 Android MCP Screen Reader Server</h1>
                    <p><span class="status-badge">ACTIF</span> • Protocole Model Context Protocol v2024-11-05</p>
                    <p>Endpoint MCP JSON-RPC : <code>${state.endpointUrl}</code></p>
                    <p>Endpoint SSE : <code>${state.sseUrl}</code></p>
                    <p>Requêtes traitées : <strong>${state.totalRequestsHandled}</strong></p>
                </div>
                <div class="card">
                    <h2>🛠️ Outils MCP Disponibles</h2>
                    <ul>$toolsListHtml</ul>
                </div>
                <div class="card">
                    <h2>⚙️ Configuration pour Client MCP (ex: Claude Desktop / AI Studio)</h2>
                    <pre>{
  "mcpServers": {
    "android-screen-reader": {
      "url": "${state.endpointUrl}"
    }
  }
}</pre>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    companion object {
        private const val TAG = "McpServerEngine"
    }
}

data class McpServerState(
    val isRunning: Boolean = false,
    val port: Int = 8080,
    val host: String = "127.0.0.1",
    val endpointUrl: String = "http://127.0.0.1:8080/mcp",
    val sseUrl: String = "http://127.0.0.1:8080/sse",
    val totalRequestsHandled: Int = 0,
    val lastError: String? = null
)
