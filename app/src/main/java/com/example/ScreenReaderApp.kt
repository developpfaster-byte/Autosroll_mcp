package com.example

import android.app.Application
import com.example.data.db.AppDatabase
import com.example.data.db.ScreenCaptureRepository
import com.example.mcp.McpServerEngine
import com.example.mcp.McpToolsHandler

class ScreenReaderApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var repository: ScreenCaptureRepository
        private set

    lateinit var toolsHandler: McpToolsHandler
        private set

    lateinit var mcpEngine: McpServerEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = AppDatabase.getDatabase(this)
        repository = ScreenCaptureRepository(database.screenCaptureDao())
        toolsHandler = McpToolsHandler(repository)
        mcpEngine = McpServerEngine(this, toolsHandler)
        
        // Auto start MCP server on default port 8080
        mcpEngine.startServer(8080)
    }

    companion object {
        lateinit var instance: ScreenReaderApp
            private set
    }
}
