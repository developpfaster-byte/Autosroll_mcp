package com.example.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ScreenReaderApp
import com.example.data.db.ConversationBranchEntity
import com.example.data.db.ConversationTurnEntity
import com.example.data.db.ScreenCaptureEntity
import com.example.data.model.McpLogEntry
import com.example.data.model.ScreenDump
import com.example.mcp.McpServerState
import com.example.service.FloatingControlService
import com.example.service.ScreenReaderAccessibilityService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ScreenReaderApp
    private val repository = app.repository
    private val mcpEngine = app.mcpEngine

    val isAccessibilityEnabled: StateFlow<Boolean> = ScreenReaderAccessibilityService.isServiceActive
    val isFloatingOverlayVisible: StateFlow<Boolean> = ScreenReaderAccessibilityService.isFloatingOverlayVisible

    val recentCaptures: StateFlow<List<ScreenCaptureEntity>> = repository.allCaptures
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allBranches: StateFlow<List<ConversationBranchEntity>> = repository.allBranches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeBranchName: StateFlow<String> = repository.activeBranch

    val activeBranchTurns: StateFlow<List<ConversationTurnEntity>> = repository.activeBranch
        .flatMapLatest { branch -> repository.getTurnsForBranch(branch) }
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

    init {
        // Automatically sync dumps captured from the floating overlay button into the home screen
        viewModelScope.launch {
            ScreenReaderAccessibilityService.lastCapturedDump.collect { dump ->
                if (dump != null) {
                    _currentDump.value = dump
                }
            }
        }

        // On initial startup, hydrate with the latest capture if available
        viewModelScope.launch {
            if (_currentDump.value == null) {
                val latest = ScreenReaderAccessibilityService.lastCapturedDump.value
                if (latest != null) {
                    _currentDump.value = latest
                } else {
                    val dbLatest = repository.getLatestCapture()
                    if (dbLatest != null && dbLatest.jsonPayload.isNotBlank()) {
                        _currentDump.value = ScreenDump.fromJson(dbLatest.jsonPayload)
                    }
                }
            }
        }
    }

    fun updateConsoleInput(newInput: String) {
        _jsonRpcConsoleInput.value = newInput
    }

    fun selectSnapshotForView(entity: ScreenCaptureEntity?) {
        if (entity == null) {
            _selectedSnapshotForView.value = null
            return
        }

        val formattedPayload = formatJsonIfPossible(entity.jsonPayload, entity)
        _selectedSnapshotForView.value = entity.copy(jsonPayload = formattedPayload)

        // If entity had an empty payload in memory, load full record from database
        if (entity.jsonPayload.isBlank()) {
            viewModelScope.launch {
                val full = repository.getCaptureById(entity.id)
                if (full != null && full.jsonPayload.isNotBlank()) {
                    _selectedSnapshotForView.value = full.copy(
                        jsonPayload = formatJsonIfPossible(full.jsonPayload, full)
                    )
                }
            }
        }
    }

    fun copyCaptureJson(entity: ScreenCaptureEntity, context: Context) {
        viewModelScope.launch {
            var raw = entity.jsonPayload
            if (raw.isBlank()) {
                val full = repository.getCaptureById(entity.id)
                raw = full?.jsonPayload ?: ""
            }
            val formatted = formatJsonIfPossible(raw, entity)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Screen Dump JSON #${entity.id}", formatted))
            Toast.makeText(context, "✓ JSON copié (${entity.extractedTextCount} textes) !", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareCaptureJson(entity: ScreenCaptureEntity, context: Context) {
        viewModelScope.launch {
            var raw = entity.jsonPayload
            if (raw.isBlank()) {
                val full = repository.getCaptureById(entity.id)
                raw = full?.jsonPayload ?: ""
            }
            val formatted = formatJsonIfPossible(raw, entity)
            shareJson(context, formatted, "Screen Dump JSON #${entity.id} - ${entity.appName}")
        }
    }

    fun formatJsonIfPossible(rawJson: String, fallbackEntity: ScreenCaptureEntity? = null): String {
        val trimmed = rawJson.trim()
        if (trimmed.isNotBlank()) {
            try {
                if (trimmed.startsWith("{")) {
                    return JSONObject(trimmed).toString(2)
                } else if (trimmed.startsWith("[")) {
                    return JSONArray(trimmed).toString(2)
                }
            } catch (e: Exception) {
                return rawJson
            }
        }

        // Reconstruct valid MCP JSON if payload was stored blank
        if (fallbackEntity != null) {
            val json = JSONObject()
            json.put("protocol", "MCP-Screen-Dump-1.0")
            json.put("id", fallbackEntity.id)
            json.put("timestamp", fallbackEntity.timestamp)
            json.put("formattedTime", fallbackEntity.formattedTimestamp)
            json.put("packageName", fallbackEntity.appPackage)
            json.put("appName", fallbackEntity.appName)
            json.put("windowTitle", fallbackEntity.windowTitle)
            json.put("captureType", fallbackEntity.captureType)
            json.put("scrollPasses", fallbackEntity.scrollPasses)
            json.put("extractedTextCount", fallbackEntity.extractedTextCount)
            val texts = JSONArray()
            fallbackEntity.summaryText.split(" • ").filter { it.isNotBlank() }.forEach { texts.put(it) }
            json.put("extractedTexts", texts)
            return json.toString(2)
        }

        return "{}"
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

    fun triggerScrollAndStitchTurn(maxScrolls: Int = 80, delayMs: Long = 750, role: String = "auto") {
        val service = ScreenReaderAccessibilityService.instance
        if (service == null) {
            _statusMessage.value = "Le service d'accessibilité n'est pas activé."
            return
        }

        viewModelScope.launch {
            _isReading.value = true
            _statusMessage.value = "Défilement dynamique avec détection automatique de la fin du message..."
            try {
                val currentBranch = repository.activeBranch.value
                val result = service.scrollAndStitchConversationTurn(
                    maxScrolls = maxScrolls,
                    delayMs = delayMs,
                    branchName = currentBranch,
                    role = role,
                    onProgress = { current, words, statusMsg ->
                        _statusMessage.value = "Page $current ($words mots) • $statusMsg"
                    }
                )
                val turn = result.second
                _currentDump.value = result.first
                val continuity = if (turn?.parentTurnId != null) "Suite du Tour #${turn.turnIndex - 1}" else "Tour #${turn?.turnIndex ?: 1}"
                _statusMessage.value = "✓ $continuity cousu avec succès (${turn?.wordCount ?: 0} mots, ${turn?.scrollPassCount}p, fin détectée) dans 🌿 $currentBranch !"
            } catch (e: Exception) {
                _statusMessage.value = "Erreur lors de la couture : ${e.message}"
            } finally {
                _isReading.value = false
            }
        }
    }

    fun switchBranch(name: String) {
        repository.setActiveBranch(name)
        _statusMessage.value = "Branche active : 🌿 $name"
    }

    fun createBranch(name: String, appName: String = "Application") {
        viewModelScope.launch {
            val branch = repository.createBranch(name, appName)
            repository.setActiveBranch(branch.branchName)
            _statusMessage.value = "✓ Nouvelle branche créée : 🌿 ${branch.branchName}"
        }
    }

    fun deleteBranch(name: String) {
        viewModelScope.launch {
            repository.deleteBranch(name)
            _statusMessage.value = "Branche '$name' supprimée"
        }
    }

    fun deleteTurn(id: Long) {
        viewModelScope.launch {
            repository.deleteTurn(id)
        }
    }

    suspend fun exportBranchMarkdown(branchName: String): String {
        return repository.exportBranchMarkdown(branchName)
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
        val service = ScreenReaderAccessibilityService.instance
        if (service == null) {
            val isConfigured = ScreenReaderAccessibilityService.isConfiguredInSettings(context)
            if (isConfigured) {
                Toast.makeText(
                    context,
                    "Service en attente de reconnexion. Veuillez désactiver puis réactiver le service dans les Paramètres.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    context,
                    "Veuillez d'abord activer le service d'accessibilité dans les paramètres Android.",
                    Toast.LENGTH_LONG
                ).show()
            }
            openAccessibilitySettings(context)
            return
        }

        service.toggleFloatingOverlay()
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
