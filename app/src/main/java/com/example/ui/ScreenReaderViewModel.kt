package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ScreenReaderApp
import com.example.data.db.ScreenCaptureEntity
import com.example.data.model.McpLogEntry
import com.example.data.model.ScreenDump
import com.example.mcp.McpServerState
import com.example.service.FloatingControlService
import com.example.service.ScreenReaderAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ScreenReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ScreenReaderApp
    private val repository = app.repository
    private val mcpEngine = app.mcpEngine

    val isAccessibilityEnabled: StateFlow<Boolean> = ScreenReaderAccessibilityService.isServiceActive

    val recentCaptures: StateFlow<List<ScreenCaptureEntity>> = repository.allCaptures
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val mcpServerState: StateFlow<McpServerState> = mcpEngine.serverState
    val mcpLogs: StateFlow<List<McpLogEntry>> = mcpEngine.logs

    private val _isReading = MutableStateFlow(false)
    val isReading: StateFlow<Boolean> = _isReading.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _currentDump = MutableStateFlow<ScreenDump?>(null)
    val currentDump: StateFlow<ScreenDump?> = _currentDump.asStateFlow()

    private val _jsonRpcConsoleInput = MutableStateFlow(DEFAULT_TEST_JSON_RPC)
    val jsonRpcConsoleInput: StateFlow<String> = _jsonRpcConsoleInput.asStateFlow()

    private val _jsonRpcConsoleOutput = MutableStateFlow<String?>(null)
    val jsonRpcConsoleOutput: StateFlow<String?> = _jsonRpcConsoleOutput.asStateFlow()

    private val _selectedSnapshotForView = MutableStateFlow<ScreenCaptureEntity?>(null)
    val selectedSnapshotForView: StateFlow<ScreenCaptureEntity?> = _selectedSnapshotForView.asStateFlow()

    fun updateConsoleInput(newInput: String) {
        _jsonRpcConsoleInput.value = newInput
    }

    fun selectSnapshotForView(entity: ScreenCaptureEntity?) {
        _selectedSnapshotForView.value = entity
    }

    fun triggerReadScreen() {
        val service = ScreenReaderAccessibilityService.instance
        if (service == null) {
            _statusMessage.value = "Le service d'accessibilité n'est pas activé. Activez-le dans les paramètres."
            return
        }

        viewModelScope.launch {
            _isReading.value = true
            _statusMessage.value = "Lecture de l'écran en cours..."
            try {
                val dump = service.readCurrentScreen("single_read")
                _currentDump.value = dump
                repository.saveCapture(dump)
                _statusMessage.value = "✓ Écran lu : ${dump.extractedTexts.size} textes extraits et enregistrés en JSON !"
            } catch (e: Exception) {
                _statusMessage.value = "Erreur lors de la lecture : ${e.message}"
            } finally {
                _isReading.value = false
            }
        }
    }

    fun triggerScrollAndRead(scrollCount: Int = 3, delayMs: Long = 800) {
        val service = ScreenReaderAccessibilityService.instance
        if (service == null) {
            _statusMessage.value = "Le service d'accessibilité n'est pas activé."
            return
        }

        viewModelScope.launch {
            _isReading.value = true
            _statusMessage.value = "Défilement (scroll) et lecture en cours ($scrollCount passes)..."
            try {
                val dump = service.scrollAndRead(maxScrolls = scrollCount, delayMs = delayMs)
                _currentDump.value = dump
                repository.saveCapture(dump)
                _statusMessage.value = "✓ Défilement terminé (${dump.scrollPasses} passes) : ${dump.extractedTexts.size} textes consolidés en JSON !"
            } catch (e: Exception) {
                _statusMessage.value = "Erreur défilement : ${e.message}"
            } finally {
                _isReading.value = false
            }
        }
    }

    fun executeJsonRpcTest() {
        val input = _jsonRpcConsoleInput.value
        viewModelScope.launch {
            _jsonRpcConsoleOutput.value = "Exécution MCP en cours..."
            val result = mcpEngine.executeRawJsonRpc(input)
            _jsonRpcConsoleOutput.value = result
        }
    }

    fun loadPresetJsonRpc(presetType: String) {
        val preset = when (presetType) {
            "initialize" -> """{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {}
}"""
            "tools_list" -> """{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/list",
  "params": {}
}"""
            "read_screen" -> """{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "read_screen",
    "arguments": {
      "include_hierarchy": true
    }
  }
}"""
            "scroll_and_read" -> """{
  "jsonrpc": "2.0",
  "id": 4,
  "method": "tools/call",
  "params": {
    "name": "scroll_and_read",
    "arguments": {
      "scroll_count": 3,
      "delay_ms": 800
    }
  }
}"""
            "resources_list" -> """{
  "jsonrpc": "2.0",
  "id": 5,
  "method": "resources/list",
  "params": {}
}"""
            else -> DEFAULT_TEST_JSON_RPC
        }
        _jsonRpcConsoleInput.value = preset
    }

    fun deleteCapture(id: Long) {
        viewModelScope.launch {
            repository.deleteById(id)
            if (_selectedSnapshotForView.value?.id == id) {
                _selectedSnapshotForView.value = null
            }
        }
    }

    fun clearAllCaptures() {
        viewModelScope.launch {
            repository.clearAll()
            _selectedSnapshotForView.value = null
            _statusMessage.value = "Historique effacé"
        }
    }

    fun startMcpServer(port: Int = 8080) {
        mcpEngine.startServer(port)
    }

    fun stopMcpServer() {
        mcpEngine.stopServer()
    }

    fun clearMcpLogs() {
        mcpEngine.clearLogs()
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun toggleFloatingService(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Toast.makeText(context, "Autorisez la superposition d'écran pour activer le bouton flottant", Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(context, FloatingControlService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Toast.makeText(context, "Bouton flottant activé !", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Erreur lancement bouton: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareJson(context: Context, jsonContent: String, title: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, jsonContent)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(Intent.createChooser(shareIntent, "Partager le JSON").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }

    companion object {
        const val DEFAULT_TEST_JSON_RPC = """{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "scroll_and_read",
    "arguments": {
      "scroll_count": 3,
      "delay_ms": 800
    }
  }
}"""
    }
}
