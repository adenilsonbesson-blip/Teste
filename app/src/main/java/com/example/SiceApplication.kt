package com.example

import android.app.Application
import com.example.data.SiceDatabase
import com.example.data.SiceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class SiceApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob())

    lateinit var database: SiceDatabase
        private set
    lateinit var repository: SiceRepository
        private set

    override fun onCreate() {
        super.onCreate()
        
        // Search and clean vestigial temporary files from uninstalled versions while preserving active database
        cleanLegacyTracesAndSanitizeStorage(this)

        database = SiceDatabase.getDatabase(this, applicationScope)
        repository = SiceRepository(database.siceDao())

        // Pre-warm database cache synchronously from local disk for zero-delay startup
        AndroidDBInterface.prewarm(this@SiceApplication, repository)

        try {
            val prefs = getSharedPreferences(DriveBackupWorker.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            if (!prefs.contains(DriveBackupWorker.PREF_AUTO_BACKUP_ENABLED)) {
                prefs.edit()
                    .putBoolean(DriveBackupWorker.PREF_AUTO_BACKUP_ENABLED, true)
                    .putString(DriveBackupWorker.PREF_BACKUP_FREQUENCY, "1h")
                    .putString(DriveBackupWorker.PREF_BACKUP_MODE, "completo")
                    .putString(DriveBackupWorker.PREF_SCRIPT_URL, DriveBackupWorker.DEFAULT_WEBHOOK_URL)
                    .apply()
            } else if (prefs.getString(DriveBackupWorker.PREF_SCRIPT_URL, "").isNullOrBlank()) {
                prefs.edit().putString(DriveBackupWorker.PREF_SCRIPT_URL, DriveBackupWorker.DEFAULT_WEBHOOK_URL).apply()
            }
            DriveBackupWorker.scheduleOrCancel(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun resetDatabaseAndRepository() {
        SiceDatabase.closeAndResetDatabase()
        AndroidDBInterface.inMemoryCache = null
        database = SiceDatabase.getDatabase(this, applicationScope)
        repository = SiceRepository(database.siceDao())
    }

    override fun getAttributionTag(): String? {
        return "sice_attribution"
    }
}

fun cleanLegacyTracesAndSanitizeStorage(context: android.content.Context) {
    try {
        // 1. Remove leftover temporary files (.tmp, .bak.tmp, temp_*) in filesDir while preserving main db & JSON
        val filesDir = context.filesDir
        if (filesDir != null && filesDir.exists()) {
            filesDir.listFiles()?.forEach { file ->
                if (file.isFile && (file.name.endsWith(".tmp") || file.name.startsWith("temp_") || file.name.endsWith(".bak.tmp"))) {
                    file.delete()
                }
            }
        }

        // 2. Clean external and internal cache leftovers older than 24h (avoid deleting main database)
        val cacheDirs = listOfNotNull(context.cacheDir, context.externalCacheDir)
        val now = System.currentTimeMillis()
        val dayInMs = 24 * 60 * 60 * 1000L
        for (dir in cacheDirs) {
            if (dir.exists()) {
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && (now - file.lastModified() > dayInMs) && !file.name.contains("entregas")) {
                        file.delete()
                    }
                }
            }
        }

        // 3. Ensure WebView code cache directories exist
        val baseDirs = listOfNotNull(context.cacheDir, context.codeCacheDir)
        for (base in baseDirs) {
            val codeCacheTargets = listOf(
                "WebView/Default/Code Cache/js",
                "WebView/Default/Code Cache/wasm"
            )
            for (relPath in codeCacheTargets) {
                val dir = java.io.File(base, relPath)
                if (!dir.exists()) {
                    dir.mkdirs()
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

