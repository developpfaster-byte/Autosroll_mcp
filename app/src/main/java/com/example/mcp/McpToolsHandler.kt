package com.example.mcp

import com.example.data.db.ScreenCaptureRepository
import com.example.data.model.McpError
import com.example.data.model.McpPromptDefinition
import com.example.data.model.McpProtocol
import com.example.data.model.McpRequest
import com.example.data.model.McpResourceDefinition
import com.example.data.model.McpResponse
import com.example.data.model.McpToolDefinition
import com.example.data.model.ScreenDump
import com.example.service.ScreenReaderAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class McpToolsHandler(
    private val repository: ScreenCaptureRepository
) {

    fun getToolsList(): List<McpToolDefinition> {
        return listOf(
            McpToolDefinition(
                name = "read_screen",
                description = "Lit le contenu textuel et structurel de l'écran Android actuel via le service d'accessibilité et retourne un document JSON.",
                inputSchema = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("include_hierarchy", JSONObject().put("type", "boolean").put("description", "Inclure l'arbre UI complet (défaut: true)"))
                    )
            ),
            McpToolDefinition(
                name = "scroll_and_read",
                description = "Fait défiler l'écran (scroll) vers le bas plusieurs fois, lit et extrait tous les textes découverts, puis retourne un JSON complet consolidé.",
                inputSchema = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("scroll_count", JSONObject().put("type", "integer").put("description", "Nombre de scrolls à réaliser (1 à 10)").put("default", 3))
                            .put("delay_ms", JSONObject().put("type", "integer").put("description", "Délai en ms entre chaque scroll").put("default", 800))
                    )
            ),
            McpToolDefinition(
                name = "scroll_screen",
                description = "Fait défiler l'écran vers le bas ou vers le haut sur l'application active.",
                inputSchema = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("direction", JSONObject().put("type", "string").put("enum", JSONArray(listOf("down", "up"))).put("description", "Direction du scroll (down ou up)").put("default", "down"))
                    )
            ),
            McpToolDefinition(
                name = "click_element",
                description = "Clique sur un élément de l'écran identifié par son texte, son identifiant de vue ou son ID.",
                inputSchema = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("target", JSONObject().put("type", "string").put("description", "Texte ou identifiant de l'élément à cliquer"))
                    )
                    .put("required", JSONArray(listOf("target")))
            ),
            McpToolDefinition(
                name = "type_text",
                description = "Saisit du texte dans un champ de texte ciblé ou actuellement sélectionné.",
                inputSchema = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("target", JSONObject().put("type", "string").put("description", "Cible facultative (texte ou ID du champ)"))
                            .put("text", JSONObject().put("type", "string").put("description", "Texte à insérer"))
                    )
                    .put("required", JSONArray(listOf("text")))
            ),
            McpToolDefinition(
                name = "get_captured_json",
                description = "Récupère les données JSON de la dernière capture d'écran ou d'une capture spécifique enregistrée.",
                inputSchema = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("snapshot_id", JSONObject().put("type", "integer").put("description", "ID du snapshot (optionnel, prend le dernier par défaut)"))
                    )
            )
        )
    }

    fun getResourcesList(): List<McpResourceDefinition> {
        return listOf(
            McpResourceDefinition(
                uri = "screen://current",
                name = "Current Screen JSON",
                description = "JSON structuré du dernier écran capturé via le service d'accessibilité"
            ),
            McpResourceDefinition(
                uri = "screen://history",
                name = "Screen Capture History",
                description = "Historique des captures d'écran et des fichiers JSON enregistrés"
            )
        )
    }

    fun getPromptsList(): List<McpPromptDefinition> {
        return listOf(
            McpPromptDefinition(
                name = "analyze_screen",
                description = "Analyse les textes et la hiérarchie de l'écran Android capturé et extrait les points d'intérêt principaux"
            ),
            McpPromptDefinition(
                name = "summarize_scrolled_content",
                description = "Synthétise l'intégralité du contenu textuel récolté après un défilement complet de page"
            )
        )
    }

    suspend fun handleRequest(request: McpRequest): McpResponse = withContext(Dispatchers.IO) {
        val method = request.method
        val params = request.params

        when (method) {
            "initialize" -> {
                val result = JSONObject()
                result.put("protocolVersion", McpProtocol.PROTOCOL_VERSION)
                result.put(
                    "capabilities",
                    JSONObject()
                        .put("tools", JSONObject().put("listChanged", false))
                        .put("resources", JSONObject().put("subscribe", false).put("listChanged", false))
                        .put("prompts", JSONObject().put("listChanged", false))
                )
                result.put(
                    "serverInfo",
                    JSONObject()
                        .put("name", "Android-ScreenReader-MCP-Server")
                        .put("version", "1.0.0")
                )
                McpResponse(id = request.id, result = result)
            }

            "notifications/initialized", "initialized" -> {
                McpResponse(id = request.id, result = JSONObject().put("status", "ready"))
            }

            "ping" -> {
                McpResponse(id = request.id, result = JSONObject())
            }

            "tools/list" -> {
                val toolsArray = JSONArray()
                getToolsList().forEach { toolsArray.put(it.toJson()) }
                val result = JSONObject().put("tools", toolsArray)
                McpResponse(id = request.id, result = result)
            }

            "tools/call" -> {
                val toolName = params.optString("name")
                val arguments = params.optJSONObject("arguments") ?: JSONObject()
                executeTool(toolName, arguments, request.id)
            }

            "resources/list" -> {
                val resArray = JSONArray()
                getResourcesList().forEach { resArray.put(it.toJson()) }
                val result = JSONObject().put("resources", resArray)
                McpResponse(id = request.id, result = result)
            }

            "resources/read" -> {
                val uri = params.optString("uri")
                handleResourceRead(uri, request.id)
            }

            "prompts/list" -> {
                val promptsArray = JSONArray()
                getPromptsList().forEach { promptsArray.put(it.toJson()) }
                val result = JSONObject().put("prompts", promptsArray)
                McpResponse(id = request.id, result = result)
            }

            "prompts/get" -> {
                val name = params.optString("name")
                handlePromptGet(name, request.id)
            }

            else -> {
                McpResponse(id = request.id, error = McpError.METHOD_NOT_FOUND)
            }
        }
    }

    private suspend fun executeTool(name: String, args: JSONObject, requestId: Any?): McpResponse {
        val service = ScreenReaderAccessibilityService.instance

        when (name) {
            "read_screen" -> {
                if (service == null) {
                    return formatMcpToolError("Le service d'accessibilité n'est pas activé dans les paramètres Android.", requestId)
                }
                val dump = withContext(Dispatchers.Main) {
                    service.readCurrentScreen("mcp_tool_call")
                }
                repository.saveCapture(dump)

                return formatMcpToolSuccess(
                    textContent = "Écran capturé avec succès:\nApplication: ${dump.appName} (${dump.packageName})\nÉléments textuels: ${dump.extractedTexts.size}\nTotal nœuds: ${dump.totalNodes}",
                    jsonPayload = dump.toJson(),
                    requestId = requestId
                )
            }

            "scroll_and_read" -> {
                if (service == null) {
                    return formatMcpToolError("Le service d'accessibilité n'est pas activé dans les paramètres Android.", requestId)
                }
                val scrollCount = args.optInt("scroll_count", 3).coerceIn(1, 10)
                val delayMs = args.optLong("delay_ms", 800).coerceIn(300, 3000)

                val dump = withContext(Dispatchers.Main) {
                    service.scrollAndRead(maxScrolls = scrollCount, delayMs = delayMs)
                }
                repository.saveCapture(dump)

                return formatMcpToolSuccess(
                    textContent = "Défilement et lecture terminés (${dump.scrollPasses} passes).\nTextes uniques découverts: ${dump.extractedTexts.size}\nApplication: ${dump.appName}",
                    jsonPayload = dump.toJson(),
                    requestId = requestId
                )
            }

            "scroll_screen" -> {
                if (service == null) {
                    return formatMcpToolError("Le service d'accessibilité n'est pas activé.", requestId)
                }
                val direction = args.optString("direction", "down")
                val success = withContext(Dispatchers.Main) {
                    if (direction.equals("up", ignoreCase = true)) {
                        service.scrollUp()
                    } else {
                        service.scrollDown()
                    }
                }
                return formatMcpToolSuccess(
                    textContent = if (success) "Défilement $direction exécuté avec succès." else "Impossible de faire défiler l'écran (non supporté ou bloqué).",
                    jsonPayload = JSONObject().put("success", success).put("direction", direction),
                    requestId = requestId
                )
            }

            "click_element" -> {
                if (service == null) {
                    return formatMcpToolError("Le service d'accessibilité n'est pas activé.", requestId)
                }
                val target = args.optString("target")
                if (target.isBlank()) {
                    return formatMcpToolError("Le paramètre 'target' est obligatoire.", requestId)
                }
                val success = withContext(Dispatchers.Main) {
                    service.clickElement(target)
                }
                return formatMcpToolSuccess(
                    textContent = if (success) "Clic sur '$target' effectué avec succès." else "Élément '$target' introuvable ou non cliquable.",
                    jsonPayload = JSONObject().put("success", success).put("target", target),
                    requestId = requestId
                )
            }

            "type_text" -> {
                if (service == null) {
                    return formatMcpToolError("Le service d'accessibilité n'est pas activé.", requestId)
                }
                val target = args.optString("target", null)
                val text = args.optString("text")
                val success = withContext(Dispatchers.Main) {
                    service.typeText(target, text)
                }
                return formatMcpToolSuccess(
                    textContent = if (success) "Texte saisi avec succès." else "Impossible de trouver un champ éditable pour saisir le texte.",
                    jsonPayload = JSONObject().put("success", success).put("text", text),
                    requestId = requestId
                )
            }

            "get_captured_json" -> {
                val snapshotId = if (args.has("snapshot_id")) args.getLong("snapshot_id") else null
                val entity = if (snapshotId != null) {
                    repository.getCaptureById(snapshotId)
                } else {
                    repository.getLatestCapture()
                }

                if (entity == null) {
                    return formatMcpToolError("Aucune capture JSON trouvée dans l'historique.", requestId)
                }

                return formatMcpToolSuccess(
                    textContent = "JSON Snapshot ID #${entity.id} (${entity.appName} - ${entity.formattedTimestamp})",
                    jsonPayload = JSONObject(entity.jsonPayload),
                    requestId = requestId
                )
            }

            else -> {
                return McpResponse(id = requestId, error = McpError.custom(-32601, "Outil inconnu: $name"))
            }
        }
    }

    private suspend fun handleResourceRead(uri: String, requestId: Any?): McpResponse {
        when {
            uri == "screen://current" -> {
                val latest = repository.getLatestCapture()
                val contentJson = latest?.jsonPayload ?: ScreenDump().toFormattedJsonString()
                val result = JSONObject().put(
                    "contents",
                    JSONArray().put(
                        JSONObject()
                            .put("uri", uri)
                            .put("mimeType", "application/json")
                            .put("text", contentJson)
                    )
                )
                return McpResponse(id = requestId, result = result)
            }
            uri == "screen://history" -> {
                val latest = repository.getLatestCapture()
                val result = JSONObject().put(
                    "contents",
                    JSONArray().put(
                        JSONObject()
                            .put("uri", uri)
                            .put("mimeType", "application/json")
                            .put("text", latest?.jsonPayload ?: "[]")
                    )
                )
                return McpResponse(id = requestId, result = result)
            }
            else -> {
                return McpResponse(id = requestId, error = McpError.custom(-32002, "Ressource introuvable: $uri"))
            }
        }
    }

    private fun handlePromptGet(name: String, requestId: Any?): McpResponse {
        val messages = JSONArray()
        when (name) {
            "analyze_screen" -> {
                messages.put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONObject()
                                .put("type", "text")
                                .put("text", "Analyse le document JSON suivant de l'écran Android capturé et extrait : 1) Le titre et le contexte de l'application, 2) Les données clés ou messages affichés, 3) Les actions et boutons disponibles.")
                        )
                )
            }
            "summarize_scrolled_content" -> {
                messages.put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONObject()
                                .put("type", "text")
                                .put("text", "Fais un résumé complet et structuré de l'ensemble des textes lus lors du défilement de l'écran.")
                        )
                )
            }
            else -> {
                return McpResponse(id = requestId, error = McpError.custom(-32601, "Prompt introuvable: $name"))
            }
        }
        val result = JSONObject().put("description", "Prompt pour l'analyse d'écran").put("messages", messages)
        return McpResponse(id = requestId, result = result)
    }

    private fun formatMcpToolSuccess(textContent: String, jsonPayload: JSONObject, requestId: Any?): McpResponse {
        val contentArray = JSONArray()
        contentArray.put(
            JSONObject()
                .put("type", "text")
                .put("text", "$textContent\n\n```json\n${jsonPayload.toString(2)}\n```")
        )
        val result = JSONObject()
            .put("content", contentArray)
            .put("isError", false)
            .put("data", jsonPayload)

        return McpResponse(id = requestId, result = result)
    }

    private fun formatMcpToolError(errorMessage: String, requestId: Any?): McpResponse {
        val contentArray = JSONArray()
        contentArray.put(
            JSONObject()
                .put("type", "text")
                .put("text", "Erreur: $errorMessage")
        )
        val result = JSONObject()
            .put("content", contentArray)
            .put("isError", true)

        return McpResponse(id = requestId, result = result)
    }
}
