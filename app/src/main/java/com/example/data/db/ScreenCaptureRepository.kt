package com.example.data.db

import com.example.data.engine.StitchedResult
import com.example.data.engine.TextStitcherEngine
import com.example.data.model.ScreenDump
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

class ScreenCaptureRepository(
    private val dao: ScreenCaptureDao,
    private val conversationDao: ConversationDao? = null
) {
    val allCaptures: Flow<List<ScreenCaptureEntity>> = dao.getAllCaptures()

    private val _activeBranch = MutableStateFlow("main")
    val activeBranch: StateFlow<String> = _activeBranch.asStateFlow()

    val allBranches: Flow<List<ConversationBranchEntity>> =
        conversationDao?.getAllBranches() ?: emptyFlow()

    fun getTurnsForBranch(branchName: String): Flow<List<ConversationTurnEntity>> {
        return conversationDao?.getTurnsForBranch(branchName) ?: emptyFlow()
    }

    suspend fun getCaptureById(id: Long): ScreenCaptureEntity? = dao.getCaptureById(id)

    suspend fun getLatestCapture(): ScreenCaptureEntity? = dao.getLatestCapture()

    suspend fun saveCapture(dump: ScreenDump): Long {
        val entity = ScreenCaptureEntity(
            timestamp = dump.timestamp,
            appPackage = dump.packageName,
            appName = dump.appName,
            windowTitle = dump.windowTitle,
            captureType = dump.captureType,
            scrollPasses = dump.scrollPasses,
            extractedTextCount = dump.extractedTexts.size,
            summaryText = dump.summary,
            jsonPayload = dump.toFormattedJsonString(2)
        )
        return dao.insertCapture(entity)
    }

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun clearAll() = dao.clearAll()

    // -------------------------------------------------------------------------
    // CONVERSATION BRANCH & TURN (GIT-STYLE) MANAGEMENT
    // -------------------------------------------------------------------------

    fun setActiveBranch(branchName: String) {
        val cleanName = branchName.trim().ifBlank { "main" }
        _activeBranch.value = cleanName
    }

    suspend fun ensureBranchExists(name: String, appName: String = "Application", appPackage: String = ""): ConversationBranchEntity {
        val clean = name.trim().ifBlank { "main" }
        val existing = conversationDao?.getBranchByName(clean)
        if (existing != null) return existing

        val newBranch = ConversationBranchEntity(
            branchName = clean,
            appName = appName,
            appPackage = appPackage,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            totalTurns = 0,
            notes = "Branche créée pour $appName"
        )
        conversationDao?.insertBranch(newBranch)
        return newBranch
    }

    suspend fun createBranch(name: String, appName: String = "Application", appPackage: String = ""): ConversationBranchEntity {
        return ensureBranchExists(name, appName, appPackage)
    }

    suspend fun deleteBranch(name: String) {
        conversationDao?.deleteTurnsForBranch(name)
        conversationDao?.deleteBranch(name)
        if (_activeBranch.value == name) {
            _activeBranch.value = "main"
        }
    }

    /**
     * Appends a new conversation turn (commit) with deduplicated stitched text
     * and explicit beginning/end markers to the specified branch.
     */
    suspend fun appendTurnToBranch(
        branchName: String,
        stitchedResult: StitchedResult,
        role: String = "assistant",
        scrollPassCount: Int = 1,
        appName: String = "",
        appPackage: String = ""
    ): ConversationTurnEntity? {
        if (conversationDao == null) return null

        val currentBranch = ensureBranchExists(branchName, appName, appPackage)
        val lastTurn = conversationDao.getLastTurnForBranch(currentBranch.branchName)
        val nextIndex = (lastTurn?.turnIndex ?: 0) + 1
        val turnCommitId = TextStitcherEngine.generateTurnCommitId()

        val turn = ConversationTurnEntity(
            turnId = turnCommitId,
            branchName = currentBranch.branchName,
            parentTurnId = lastTurn?.turnId,
            turnIndex = nextIndex,
            role = role,
            stitchedText = stitchedResult.fullText,
            startSnippet = stitchedResult.startSnippet,
            endSnippet = stitchedResult.endSnippet,
            scrollPassCount = scrollPassCount,
            wordCount = stitchedResult.wordCount,
            charCount = stitchedResult.charCount,
            timestamp = System.currentTimeMillis(),
            appName = appName,
            appPackage = appPackage
        )

        conversationDao.insertTurn(turn)

        // Update branch metadata
        conversationDao.updateBranch(
            currentBranch.copy(
                updatedAt = System.currentTimeMillis(),
                totalTurns = nextIndex,
                appName = if (appName.isNotBlank()) appName else currentBranch.appName,
                appPackage = if (appPackage.isNotBlank()) appPackage else currentBranch.appPackage
            )
        )

        return turn
    }

    suspend fun getBranchTurnsList(branchName: String): List<ConversationTurnEntity> {
        return conversationDao?.getTurnsListForBranch(branchName) ?: emptyList()
    }

    suspend fun getLastTurnForBranch(branchName: String): ConversationTurnEntity? {
        return conversationDao?.getLastTurnForBranch(branchName)
    }

    suspend fun exportBranchMarkdown(branchName: String): String {
        val turns = getBranchTurnsList(branchName)
        val branch = conversationDao?.getBranchByName(branchName)
        val app = branch?.appName ?: "Android App"
        return TextStitcherEngine.exportToMarkdown(branchName, app, turns)
    }

    suspend fun deleteTurn(id: Long) {
        conversationDao?.deleteTurnById(id)
    }
}
