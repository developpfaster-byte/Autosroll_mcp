package com.example.data.db

import com.example.data.model.ScreenDump
import kotlinx.coroutines.flow.Flow

class ScreenCaptureRepository(private val dao: ScreenCaptureDao) {
    val allCaptures: Flow<List<ScreenCaptureEntity>> = dao.getAllCaptures()

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
}
