package com.example

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import android.util.Base64
import android.util.Log
import androidx.work.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class DriveBackupWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val TAG = "DriveBackupWorker"
        const val WORK_NAME_PERIODIC = "condo_drive_backup_periodic"
        const val WORK_NAME_ONE_TIME = "condo_drive_backup_one_time"

        const val PREFS_NAME = "sice_drive_prefs"
        const val PREF_AUTO_BACKUP_ENABLED = "auto_backup_enabled"
        const val PREF_BACKUP_MODE = "backup_mode"
        const val PREF_BACKUP_FREQUENCY = "backup_frequency"
        const val PREF_LAST_SYNC_TIME = "last_sync_time"
        const val PREF_LAST_SYNC_STATUS = "last_sync_status"
        const val PREF_SCRIPT_URL = "script_webhook_url"
        const val PREF_LAST_DRIVE_STATUS = "last_drive_upload_status"

        const val DEFAULT_WEBHOOK_URL = "https://script.google.com/macros/s/AKfycbzQuEDDgb74tzIANJia5WA1jGrV6H1axqWH2pR62z5e4vNmHf17t3Et4eU3338CIpvkZQ/exec"
        const val DEFAULT_DRIVE_FOLDER_ID = "1ttezL7qJe9EIBolgob-E5AtXxqlo0YtD"

        fun getBackupDeviceName(context: Context?): String {
            val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val custom = prefs?.getString("custom_device_name", "")?.trim()
            if (!custom.isNullOrBlank()) {
                return custom.replace(Regex("[^a-zA-Z0-9 _-]"), "").trim().ifEmpty { "Aparelho" }
            }
            val model = android.os.Build.MODEL ?: "Android"
            val manufacturer = android.os.Build.MANUFACTURER ?: ""
            val combined = if (model.startsWith(manufacturer, ignoreCase = true)) {
                model
            } else {
                "$manufacturer $model"
            }
            return combined.replace(Regex("[^a-zA-Z0-9 _-]"), "").trim().ifEmpty { "Aparelho" }
        }

        fun scheduleOrCancel(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val isAutoEnabled = prefs.getBoolean(PREF_AUTO_BACKUP_ENABLED, true)
            val workManager = WorkManager.getInstance(context)

            if (!isAutoEnabled) {
                workManager.cancelUniqueWork(WORK_NAME_PERIODIC)
                Log.d(TAG, "Backup periódico desativado e cancelado no WorkManager.")
                return
            }

            // O backup automático sempre fará o backup completo
            prefs.edit().putString(PREF_BACKUP_MODE, "completo").apply()

            // O agendamento automático foi alterado para as 07:00 da manhã
            val now = java.util.Calendar.getInstance()
            val nextSevenAm = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 7)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
                if (before(now)) {
                    add(java.util.Calendar.DAY_OF_MONTH, 1)
                }
            }
            val initialDelayMs = nextSevenAm.timeInMillis - now.timeInMillis

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodicRequest = PeriodicWorkRequestBuilder<DriveBackupWorker>(
                24L, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .addTag(TAG)
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                periodicRequest
            )
            Log.d(TAG, "Backup periódico automático agendado para as 07:00 da manhã (delay inicial: ${initialDelayMs / 1000}s) no modo completo.")
        }

        fun triggerImmediateWork(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val oneTimeRequest = OneTimeWorkRequestBuilder<DriveBackupWorker>()
                .setConstraints(constraints)
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_ONE_TIME,
                ExistingWorkPolicy.REPLACE,
                oneTimeRequest
            )
            Log.d(TAG, "Backup imediato enfileirado no WorkManager com restrição de rede conectada.")
        }

        fun isOnline(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val app = applicationContext as? SiceApplication
        Log.d(TAG, "Iniciando execução do WorkManager para backup do banco de dados.")

        try {
            // 1. Checkpoint e compactar (VACUUM) SQLite DB WAL
            if (app != null) {
                try {
                    val siceDb = app.database
                    val writableDb = siceDb.openHelper.writableDatabase
                    writableDb.execSQL("VACUUM")
                    writableDb.execSQL("PRAGMA wal_checkpoint(FULL)")
                } catch (e: Exception) {
                    Log.w(TAG, "Aviso ao realizar checkpoint: ${e.message}")
                }
            }

            val dbFile = applicationContext.getDatabasePath("entregas.db")
            if (!dbFile.exists()) {
                val statusMsg = "Banco local vazio para backup"
                prefs.edit().putString(PREF_LAST_SYNC_STATUS, statusMsg).apply()
                return Result.success()
            }

            val mode = prefs.getString(PREF_BACKUP_MODE, "completo") ?: "completo"
            val modeLabel = when(mode) {
                "encomendas" -> "Encomendas"
                "sem_encomendas" -> "SemEncomendas"
                else -> "Completo"
            }

            val nowFmt = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
            val dataStr = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(Date())
            val horaStr = SimpleDateFormat("HH'h'mm", Locale.getDefault()).format(Date())
            val deviceName = getBackupDeviceName(applicationContext)
            val backupFileName = "Backup,$dataStr,$horaStr,$deviceName.db"

            val cachedBackup = File(applicationContext.cacheDir, backupFileName)
            prepareDbForMode(dbFile, cachedBackup, mode)

            // 2. Grava na pasta Downloads/backupcond
            val savedLocations = mutableListOf<String>()
            try {
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val backupCondDir = File(downloadDir, "backupcond")
                if (!backupCondDir.exists()) {
                    backupCondDir.mkdirs()
                }
                val targetFile = File(backupCondDir, backupFileName)
                cachedBackup.copyTo(targetFile, overwrite = true)
                savedLocations.add("Downloads/backupcond/$backupFileName")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao salvar em Downloads: ${e.message}")
            }

            try {
                val appSpecificBackupDir = applicationContext.getExternalFilesDir(null)?.let { File(it, "backupcond") }
                if (appSpecificBackupDir != null) {
                    if (!appSpecificBackupDir.exists()) appSpecificBackupDir.mkdirs()
                    val targetAppFile = File(appSpecificBackupDir, backupFileName)
                    cachedBackup.copyTo(targetAppFile, overwrite = true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Aviso ao salvar backup em storage interno do app: ${e.message}")
            }

            // 4. Limpeza de backups antigos: manter apenas os 2 mais recentes na pasta backupcond
            try {
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val backupCondDir = File(downloadDir, "backupcond")
                if (backupCondDir.exists() && backupCondDir.isDirectory) {
                    val files = backupCondDir.listFiles { _, name -> name.endsWith(".db") }
                    if (files != null && files.size > 2) {
                        files.sortByDescending { it.lastModified() }
                        for (i in 2 until files.size) {
                            files[i].delete()
                        }
                        Log.d(TAG, "Limpeza de backups antigos concluída: mantidos os 2 mais recentes.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao limpar backups antigos: ${e.message}")
            }

            // 3. Envio seguro para o Google Drive via Google Apps Script Webhook
            var driveStatusMsg = ""
            var webhookUrl = prefs.getString(PREF_SCRIPT_URL, "")?.trim() ?: ""
            if (webhookUrl.isEmpty()) {
                webhookUrl = DEFAULT_WEBHOOK_URL
            }

            if (webhookUrl.isNotEmpty() && webhookUrl.startsWith("http")) {
                if (isOnline(applicationContext)) {
                    val uploadSuccess = uploadToGoogleDrive(cachedBackup, backupFileName, webhookUrl)
                    if (uploadSuccess) {
                        driveStatusMsg = " | ☁️ Google Drive: Enviado com Sucesso"
                        prefs.edit().putString(PREF_LAST_DRIVE_STATUS, "Enviado com Sucesso ($nowFmt)").apply()
                    } else {
                        driveStatusMsg = " | ⚠️ Google Drive: Falha no Envio"
                        prefs.edit().putString(PREF_LAST_DRIVE_STATUS, "Falha no Envio").apply()
                        return Result.retry()
                    }
                } else {
                    driveStatusMsg = " | ☁️ Google Drive: Aguardando Internet"
                    prefs.edit().putString(PREF_LAST_DRIVE_STATUS, "Aguardando Conexão").apply()
                }
            } else {
                driveStatusMsg = " | ☁️ Google Drive: portcentral85@gmail.com (pasta backup)"
            }

            val localPathMessage = if (savedLocations.isNotEmpty()) {
                savedLocations.joinToString(" | ")
            } else {
                "Downloads/backupcond/$backupFileName"
            }

            val finalStatus = "Salvo em: $localPathMessage$driveStatusMsg"

            prefs.edit()
                .putString(PREF_LAST_SYNC_TIME, nowFmt)
                .putString(PREF_LAST_SYNC_STATUS, finalStatus)
                .apply()

            Log.d(TAG, "WorkManager finalizou backup com sucesso: $finalStatus")
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Exceção ao executar backup no WorkManager: ${e.message}", e)
            val errorMsg = "Erro no backup em segundo plano: ${e.localizedMessage}"
            prefs.edit().putString(PREF_LAST_SYNC_STATUS, errorMsg).apply()
            return Result.retry()
        } finally {
            // Limpa arquivos temporários de backup e arquivos do cache do aparelho
            try {
                applicationContext.cacheDir.listFiles()?.forEach { f ->
                    try {
                        if (f.isFile) {
                            f.delete()
                        } else if (f.isDirectory && f.name != "WebView") {
                            f.deleteRecursively()
                        }
                    } catch (e: Exception) {}
                }
                Log.d(TAG, "Cache de backup do aparelho limpo com sucesso.")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao limpar cache temporário: ${e.message}")
            }
        }
    }

    private fun uploadToGoogleDrive(file: File, fileName: String, webhookUrl: String): Boolean {
        return try {
            val bytes = file.readBytes()
            val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)

            val payload = JSONObject().apply {
                put("fileName", fileName)
                put("fileData", base64Data)
                put("mimeType", "application/x-sqlite3")
                put("folderName", "backup")
                put("account", "portcentral85@gmail.com")
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

            val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(webhookUrl)
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: ""
            Log.d(TAG, "Resposta do Google Apps Script: Code=${response.code}, Body=$respBody")

            if (response.isSuccessful || response.code in 200..302) {
                true
            } else {
                Log.w(TAG, "Erro HTTP do Google Script: ${response.code} - $respBody")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao fazer upload para o Google Apps Script: ${e.message}", e)
            false
        }
    }
}
