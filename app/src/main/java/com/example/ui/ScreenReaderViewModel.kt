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
            Toast.makeText(
                context,
                "Veuillez d'abord activer le service d'accessibilité dans les paramètres Android.",
                Toast.LENGTH_LONG
            ).show()
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
