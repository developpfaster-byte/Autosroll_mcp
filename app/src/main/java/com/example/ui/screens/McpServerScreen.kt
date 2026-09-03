package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.LogDirection
import com.example.ui.ScreenReaderViewModel
import com.example.ui.theme.AccentError
import com.example.ui.theme.AccentSuccess
import com.example.ui.theme.CodeBackground
import com.example.ui.theme.PrimaryCyan
import com.example.ui.theme.SecondaryIndigo

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun McpServerScreen(
    viewModel: ScreenReaderViewModel,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val serverState by viewModel.mcpServerState.collectAsStateWithLifecycle()
    val logs by viewModel.mcpLogs.collectAsStateWithLifecycle()
    val consoleInput by viewModel.jsonRpcConsoleInput.collectAsStateWithLifecycle()
    val consoleOutput by viewModel.jsonRpcConsoleOutput.collectAsStateWithLifecycle()

    var customPort by remember { mutableStateOf(serverState.port.toString()) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
    ) {
        // Server Status Card
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(if (serverState.isRunning) AccentSuccess else AccentError)
                            )
                            Text(
                                text = if (serverState.isRunning) "Serveur MCP ACTIF" else "Serveur MCP ARRÊTÉ",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (serverState.isRunning) AccentSuccess else AccentError
                            )
                        }

                        Button(
                            onClick = {
                                if (serverState.isRunning) {
                                    viewModel.stopMcpServer()
                                } else {
                                    viewModel.startMcpServer(customPort.toIntOrNull() ?: 8080)
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (serverState.isRunning) AccentError else AccentSuccess
                            ),
                            modifier = Modifier.testTag("mcp_server_toggle_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PowerSettingsNew,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (serverState.isRunning) "Arrêter" else "Démarrer",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (serverState.isRunning) {
                        Surface(
                            color = CodeBackground,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Endpoint MCP (JSON-RPC) :",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = serverState.endpointUrl,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 13.sp,
                                            color = PrimaryCyan,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    IconButton(onClick = {
                                        clipboardManager.setText(AnnotatedString(serverState.endpointUrl))
                                    }) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copier URL", tint = PrimaryCyan)
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Endpoint SSE (Stream) :",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = serverState.sseUrl,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            color = SecondaryIndigo
                                        )
                                    }
                                    IconButton(onClick = {
                                        clipboardManager.setText(AnnotatedString(serverState.sseUrl))
                                    }) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copier SSE", tint = SecondaryIndigo)
                                    }
                                }
                            }
                        }

                        Text(
                            text = "⚡ Requêtes traitées : ${serverState.totalRequestsHandled} • Version MCP : 2024-11-05",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (serverState.lastError != null) {
                        Text(
                            text = "Dernière erreur : ${serverState.lastError}",
                            color = AccentError,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // MCP Client Config Generator Card
        item {
            val configJson = """{
  "mcpServers": {
    "android-screen-reader": {
      "url": "${serverState.endpointUrl}"
    }
  }
}"""
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Cable, contentDescription = null, tint = SecondaryIndigo)
                            Text(
                                text = "Configuration Client MCP",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        IconButton(onClick = {
                            clipboardManager.setText(AnnotatedString(configJson))
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copier Config")
                        }
                    }

                    Text(
                        text = "Collez cette configuration dans votre client MCP (Claude Desktop, AI Studio, etc.) pour contrôler et lire l'appareil Android à distance :",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(CodeBackground)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = configJson,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = Color(0xFFA5F3FC)
                        )
                    }
                }
            }
        }

        // Interactive MCP JSON-RPC Console & Tester
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Terminal, contentDescription = null, tint = PrimaryCyan)
                        Text(
                            text = "Console de Test MCP (JSON-RPC)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "Sélectionnez un preset ou saisissez une requête JSON-RPC 2.0 :",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Preset Chips
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = false,
                            onClick = { viewModel.loadPresetJsonRpc("initialize") },
                            label = { Text("initialize", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = false,
                            onClick = { viewModel.loadPresetJsonRpc("tools_list") },
                            label = { Text("tools/list", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = false,
                            onClick = { viewModel.loadPresetJsonRpc("scroll_and_read") },
                            label = { Text("scroll_and_read", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = false,
                            onClick = { viewModel.loadPresetJsonRpc("read_screen") },
                            label = { Text("read_screen", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = false,
                            onClick = { viewModel.loadPresetJsonRpc("resources_list") },
                            label = { Text("resources/list", fontSize = 11.sp) }
                        )
                    }

                    // Input Field
                    OutlinedTextField(
                        value = consoleInput,
                        onValueChange = { viewModel.updateConsoleInput(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .testTag("mcp_console_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = CodeBackground,
                            focusedContainerColor = CodeBackground
                        ),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFA5F3FC)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )

                    // Execute Button
                    Button(
                        onClick = { viewModel.executeJsonRpcTest() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("execute_mcp_test_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryCyan)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                        Spacer(Modifier.width(6.dp))
                        Text("Exécuter la Requête MCP", color = Color.Black, fontWeight = FontWeight.Bold)
                    }

                    // Output Area
                    if (consoleOutput != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Réponse MCP :",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                IconButton(onClick = {
                                    clipboardManager.setText(AnnotatedString(consoleOutput ?: ""))
                                }) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copier Réponse", modifier = Modifier.size(16.dp))
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(CodeBackground)
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = consoleOutput ?: "",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = Color(0xFF6EE7B7)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Live Logs Section
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Sensors, contentDescription = null, tint = PrimaryCyan)
                            Text(
                                text = "Journal des Échanges MCP (${logs.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (logs.isNotEmpty()) {
                            IconButton(onClick = { viewModel.clearMcpLogs() }) {
                                Icon(Icons.Default.Clear, contentDescription = "Effacer logs")
                            }
                        }
                    }

                    if (logs.isEmpty()) {
                        Text(
                            text = "Aucun échange enregistré pour le moment.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            logs.take(15).forEach { log ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(8.dp),
                                        verticalAlignment = Alignment.Top,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Surface(
                                            color = if (log.direction == LogDirection.INCOMING) PrimaryCyan else SecondaryIndigo,
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = if (log.direction == LogDirection.INCOMING) "IN" else "OUT",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.Black,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = log.method,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp,
                                                color = if (log.isSuccess) MaterialTheme.colorScheme.onSurface else AccentError
                                            )
                                            Text(
                                                text = log.content,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
