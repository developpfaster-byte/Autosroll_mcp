package com.example.data.engine

import java.util.UUID

object TextStitcherEngine {

    private val CHAT_FOOTER_PATTERNS = listOf(
        "envoyez un message", "envoyer un message", "message...", "ask anything",
        "ask follow-up", "chatgpt can make mistakes", "chatgpt peut faire des erreurs",
        "claude may produce inaccurate", "claude peut faire des erreurs",
        "recherche sur le web", "web search", "ajouter une pièce jointe", "mauvaise réponse",
        "bonne réponse", "copier", "régénérer", "regenerate response", "share", "partager"
    )

    /**
     * Stitches multiple scroll passes of text lines together by removing
     * overlapping phrases between consecutive screen captures, and discarding
     * text from the previous turn if [previousTurnEndAnchor] is supplied.
     */
    fun stitchScrollPasses(
        passes: List<List<String>>,
        previousTurnEndAnchor: String? = null
    ): StitchedResult {
        if (passes.isEmpty()) {
            return StitchedResult("", "", "", 0, 0, emptyList(), "Aucun contenu")
        }

        val aggregatedLines = mutableListOf<String>()

        for ((index, pass) in passes.withIndex()) {
            var cleanedPass = pass
                .map { it.trim() }
                .filter { it.isNotBlank() }

            // If first pass and we have a previous turn's end anchor, slice off older turn content!
            if (index == 0 && !previousTurnEndAnchor.isNullOrBlank()) {
                cleanedPass = sliceAfterPreviousTurnEnd(cleanedPass, previousTurnEndAnchor)
            }

            if (cleanedPass.isEmpty()) continue

            if (aggregatedLines.isEmpty()) {
                aggregatedLines.addAll(cleanedPass)
            } else {
                // Find overlap between end of aggregatedLines and beginning of cleanedPass
                val overlapIndex = findOverlapIndex(aggregatedLines, cleanedPass)
                if (overlapIndex < cleanedPass.size) {
                    for (i in overlapIndex until cleanedPass.size) {
                        aggregatedLines.add(cleanedPass[i])
                    }
                }
            }
        }

        // Clean any bottom chat input / footer noise from the tail
        val finalLines = removeChatFooterArtifacts(aggregatedLines)

        val fullText = finalLines.joinToString("\n\n")
        val words = fullText.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
        val chars = fullText.length

        val startSnippet = if (finalLines.isNotEmpty()) {
            finalLines.first().take(140)
        } else {
            "Début non détecté"
        }

        val endSnippet = if (finalLines.isNotEmpty()) {
            finalLines.last().takeLast(140)
        } else {
            "Fin non détectée"
        }

        return StitchedResult(
            fullText = fullText,
            startSnippet = startSnippet,
            endSnippet = endSnippet,
            wordCount = words,
            charCount = chars,
            stitchedLines = finalLines,
            endReason = "Fin de page détectée automatiquement"
        )
    }

    /**
     * Slices off any text at the start of a new pass that belonged to the previous turn,
     * ensuring the new turn starts EXACTLY where the continuation begins ("Qui commence ici").
     */
    fun sliceAfterPreviousTurnEnd(lines: List<String>, previousEndSnippet: String): List<String> {
        if (lines.isEmpty() || previousEndSnippet.isBlank()) return lines

        val normTarget = previousEndSnippet.lowercase().replace("\\s+".toRegex(), " ").trim()
        val targetWords = normTarget.split(" ").filter { it.length > 3 }.takeLast(8)

        var lastMatchingIndex = -1
        for (i in lines.indices) {
            val lineNorm = lines[i].lowercase().replace("\\s+".toRegex(), " ").trim()
            if (lineNorm.contains(normTarget) || normTarget.contains(lineNorm) || linesMatch(lines[i], previousEndSnippet)) {
                lastMatchingIndex = i
            } else if (targetWords.isNotEmpty() && targetWords.all { lineNorm.contains(it) }) {
                lastMatchingIndex = i
            }
        }

        return if (lastMatchingIndex in 0 until lines.size - 1) {
            lines.subList(lastMatchingIndex + 1, lines.size)
        } else if (lastMatchingIndex == lines.size - 1) {
            emptyList()
        } else {
            lines
        }
    }

    /**
     * Removes bottom UI artifacts (e.g. "Envoyez un message", "ChatGPT can make mistakes...")
     * that sit at the bottom of AI chat apps so they don't pollute the stitched response.
     */
    private fun removeChatFooterArtifacts(lines: List<String>): List<String> {
        val result = lines.toMutableList()
        while (result.isNotEmpty()) {
            val last = result.last().lowercase().trim()
            val isFooter = CHAT_FOOTER_PATTERNS.any { pattern ->
                last.contains(pattern) || last == pattern
            }
            if (isFooter && result.size > 1) {
                result.removeAt(result.size - 1)
            } else {
                break
            }
        }
        return result
    }

    /**
     * Detects if the screen has reached the true end of the page/response.
     * Returns Pair(isEnd, reason)
     */
    fun isEndOfPageReached(
        scrolledSuccessfully: Boolean,
        newUniqueCount: Int,
        stagnantCount: Int,
        screenTexts: List<String>
    ): Pair<Boolean, String> {
        // Condition 1: Scroll failed to move and no new text appeared
        if (!scrolledSuccessfully && newUniqueCount == 0) {
            return Pair(true, "Fin de la liste atteinte (défilement impossible)")
        }

        // Condition 2: Stagnant content over consecutive scrolls (bottom of page reached)
        if (stagnantCount >= 2 && newUniqueCount == 0) {
            return Pair(true, "Contenu stabilisé au bas de page (aucun nouveau texte)")
        }

        // Condition 3: Bottom chat input container detected with no room left to scroll
        val lastLines = screenTexts.takeLast(3).map { it.lowercase().trim() }
        val hasBottomChatBar = lastLines.any { text ->
            CHAT_FOOTER_PATTERNS.any { pattern -> text.contains(pattern) }
        }
        if (hasBottomChatBar && (!scrolledSuccessfully || newUniqueCount == 0)) {
            return Pair(true, "Pied de conversation et champ de saisie atteints")
        }

        return Pair(false, "Défilement en cours")
    }

    /**
     * Finds the index in [newLines] after which the text is truly new (not repeated).
     */
    private fun findOverlapIndex(existingLines: List<String>, newLines: List<String>): Int {
        val maxLookback = minOf(15, existingLines.size)
        val tail = existingLines.takeLast(maxLookback)

        // Try largest overlap first down to 1
        for (overlapLen in minOf(tail.size, newLines.size) downTo 1) {
            var isMatch = true
            for (k in 0 until overlapLen) {
                val existingItem = tail[tail.size - overlapLen + k]
                val newItem = newLines[k]
                if (!linesMatch(existingItem, newItem)) {
                    isMatch = false
                    break
                }
            }
            if (isMatch) {
                return overlapLen
            }
        }

        // Fallback: check if the first item of newLines already exists near the tail
        val firstNew = newLines.firstOrNull() ?: return 0
        for (i in tail.indices.reversed()) {
            if (linesMatch(tail[i], firstNew)) {
                // Approximate overlap found
                return 1
            }
        }

        return 0
    }

    private fun linesMatch(a: String, b: String): Boolean {
        if (a == b) return true
        val normA = a.lowercase().replace("\\s+".toRegex(), " ").trim()
        val normB = b.lowercase().replace("\\s+".toRegex(), " ").trim()
        if (normA == normB) return true
        // If one line is substring of another with length > 20
        if (normA.length > 20 && normB.length > 20) {
            if (normA.contains(normB) || normB.contains(normA)) return true
        }
        return false
    }

    /**
     * Infers whether a block is a user question or an assistant answer.
     */
    fun inferRole(text: String, lineCount: Int): String {
        val lower = text.lowercase().take(200)
        if (lower.startsWith("vous :") || lower.startsWith("moi :") || lower.startsWith("user:") || lower.startsWith("q:")) {
            return "user"
        }
        if (lower.startsWith("chatgpt") || lower.startsWith("claude") || lower.startsWith("assistant:") || lower.startsWith("ia :") || lower.startsWith("bot:")) {
            return "assistant"
        }
        // If very short and ends with '?'
        if (text.trim().endsWith("?") && text.length < 300) {
            return "user"
        }
        // If long multi-line explanation with paragraphs
        if (lineCount > 5 || text.length > 500) {
            return "assistant"
        }
        return "assistant"
    }

    /**
     * Generates a short Git-like commit hash.
     */
    fun generateTurnCommitId(): String {
        return UUID.randomUUID().toString().replace("-", "").take(7)
    }

    /**
     * Exports a full branch history into a clean Git-like Markdown document.
     */
    fun exportToMarkdown(branchName: String, appName: String, turns: List<com.example.data.db.ConversationTurnEntity>): String {
        val sb = StringBuilder()
        sb.appendLine("# 🌿 Branche de Conversation : $branchName")
        sb.appendLine("> Application source : $appName")
        sb.appendLine("> Total de tours/commits : ${turns.size}")
        sb.appendLine("> Date d'export : ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        turns.forEach { turn ->
            val roleIcon = if (turn.role == "user") "👤 Question Utilisateur" else "🤖 Réponse IA"
            val parentInfo = turn.parentTurnId?.let { " (parent: `$it`)" } ?: " (racine)"
            sb.appendLine("### Commit `[${turn.shortTurnId}]`$parentInfo — Tour #${turn.turnIndex} : $roleIcon")
            sb.appendLine("- **Début du texte** : `${turn.startSnippet}`")
            sb.appendLine("- **Fin du texte** : `${turn.endSnippet}`")
            sb.appendLine("- **Statistiques** : ${turn.scrollPassCount} pages scrollées • ${turn.wordCount} mots • ${turn.charCount} caractères")
            sb.appendLine("- **Horodatage** : ${turn.formattedTimestamp}")
            sb.appendLine()
            sb.appendLine(turn.stitchedText)
            sb.appendLine()
            sb.appendLine("---")
            sb.appendLine()
        }

        return sb.toString()
    }
}

data class StitchedResult(
    val fullText: String,
    val startSnippet: String,
    val endSnippet: String,
    val wordCount: Int,
    val charCount: Int,
    val stitchedLines: List<String>,
    val endReason: String = "Fin de page détectée automatiquement"
)
