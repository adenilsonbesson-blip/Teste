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

        const val WORK_NAME_MORNING = "condo_drive_backup_morning"
        const val WORK_NAME_EVENING = "condo_drive_backup_evening"

        fun scheduleOrCancel(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val isAutoEnabled = prefs.getBoolean(PREF_AUTO_BACKUP_ENABLED, true)
            val workManager = WorkManager.getInstance(context)

            if (!isAutoEnabled) {
                workManager.cancelUniqueWork(WORK_NAME_MORNING)
                workManager.cancelUniqueWork(WORK_NAME_EVENING)
                workManager.cancelUniqueWork(WORK_NAME_PERIODIC)
                workManager.cancelUniqueWork(WORK_NAME_ONE_TIME)
                Log.d(TAG, "Backup automático desativado e cancelado no WorkManager.")
                return
            }

            prefs.edit().putString(PREF_BACKUP_MODE, "completo").apply()

            val now = java.util.Calendar.getInstance()

            // 1. Manhã: 07:00
            val target7am = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 7)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
                if (before(now)) {
                    add(java.util.Calendar.DAY_OF_MONTH, 1)
                }
            }
            val delay7am = target7am.timeInMillis - now.timeInMillis

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val morningRequest = PeriodicWorkRequestBuilder<DriveBackupWorker>(
                24L, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setInitialDelay(delay7am, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .addTag(TAG)
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME_MORNING,
                ExistingPeriodicWorkPolicy.UPDATE,
                morningRequest
            )

            // 2. Tarde: 17:00 (5 PM)
            val target17pm = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 17)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
                if (before(now)) {
                    add(java.util.Calendar.DAY_OF_MONTH, 1)
                }
            }
            val delay17pm = target17pm.timeInMillis - now.timeInMillis

            val eveningRequest = PeriodicWorkRequestBuilder<DriveBackupWorker>(
                24L, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setInitialDelay(delay17pm, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .addTag(TAG)
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME_EVENING,
                ExistingPeriodicWorkPolicy.UPDATE,
                eveningRequest
            )

            Log.d(TAG, "Backups automáticos ativos para 07:00 (delay: ${delay7am / 1000}s) e 17:00 (delay: ${delay17pm / 1000}s).")
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
            return try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
                val network = cm.activeNetwork ?: return true
                val caps = cm.getNetworkCapabilities(network) ?: return true
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } catch (e: Exception) {
                true
            }
        }

        fun generateMonthlyPdfReport(context: Context, outputFile: File): Boolean {
            return try {
                val dbFile = context.getDatabasePath("entregas.db")
                if (!dbFile.exists()) return false

                val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                    dbFile.absolutePath,
                    null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                )

                val encomendas = mutableListOf<Map<String, String>>()
                val cursor = db.rawQuery("SELECT rastreio, plataforma, bloco, apto, morador, status, dataEntrada, dataEntrega FROM encomendas ORDER BY id DESC", null)
                while (cursor.moveToNext()) {
                    encomendas.add(mapOf(
                        "rastreio" to (cursor.getString(0) ?: ""),
                        "plataforma" to (cursor.getString(1) ?: ""),
                        "bloco" to (cursor.getString(2) ?: ""),
                        "apto" to (cursor.getString(3) ?: ""),
                        "morador" to (cursor.getString(4) ?: ""),
                        "status" to (cursor.getString(5) ?: ""),
                        "dataEntrada" to (cursor.getString(6) ?: ""),
                        "dataEntrega" to (cursor.getString(7) ?: "")
                    ))
                }
                cursor.close()
                db.close()

                val pdfDocument = android.graphics.pdf.PdfDocument()
                val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 1).create()

                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.BLACK
                    textSize = 10f
                }
                val titlePaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#1e3a8a")
                    textSize = 16f
                    isFakeBoldText = true
                }
                val headerPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#374151")
                    textSize = 9f
                    isFakeBoldText = true
                }
                val linePaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#d1d5db")
                    strokeWidth = 1f
                }

                var pageNumber = 1
                var page = pdfDocument.startPage(pageInfo)
                var canvas = page.canvas

                var y = 40f
                canvas.drawText("Relatório Mensal de Encomendas - Condomínio", 40f, y, titlePaint)
                y += 20f
                paint.textSize = 9f
                paint.color = android.graphics.Color.parseColor("#6b7280")
                val dateGen = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date())
                canvas.drawText("Gerado em: $dateGen | Total de registros: ${encomendas.size}", 40f, y, paint)
                y += 30f

                // Table Header
                canvas.drawRect(40f, y, 555f, y + 20f, android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#f3f4f6") })
                canvas.drawText("Rastreio", 45f, y + 14f, headerPaint)
                canvas.drawText("Plat.", 170f, y + 14f, headerPaint)
                canvas.drawText("Bloco/Apto", 230f, y + 14f, headerPaint)
                canvas.drawText("Morador", 300f, y + 14f, headerPaint)
                canvas.drawText("Status", 440f, y + 14f, headerPaint)
                canvas.drawText("Data Ent.", 500f, y + 14f, headerPaint)
                y += 25f

                paint.color = android.graphics.Color.BLACK
                paint.textSize = 9f

                for (item in encomendas) {
                    if (y > 780f) {
                        pdfDocument.finishPage(page)
                        pageNumber++
                        page = pdfDocument.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
                        canvas = page.canvas
                        y = 40f
                        canvas.drawText("Relatório de Encomendas (Continuação - Pág. $pageNumber)", 40f, y, titlePaint)
                        y += 30f
                        canvas.drawRect(40f, y, 555f, y + 20f, android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#f3f4f6") })
                        canvas.drawText("Rastreio", 45f, y + 14f, headerPaint)
                        canvas.drawText("Plat.", 170f, y + 14f, headerPaint)
                        canvas.drawText("Bloco/Apto", 230f, y + 14f, headerPaint)
                        canvas.drawText("Morador", 300f, y + 14f, headerPaint)
                        canvas.drawText("Status", 440f, y + 14f, headerPaint)
                        canvas.drawText("Data Ent.", 500f, y + 14f, headerPaint)
                        y += 25f
                    }

                    val rastreio = item["rastreio"] ?: ""
                    val plat = item["plataforma"] ?: ""
                    val blocoApto = "${item["bloco"]} - ${item["apto"]}"
                    val morador = item["morador"] ?: ""
                    val status = item["status"] ?: ""
                    val dataEnt = item["dataEntrada"] ?: ""

                    canvas.drawText(if (rastreio.length > 18) rastreio.substring(0, 15) + "..." else rastreio, 45f, y, paint)
                    canvas.drawText(if (plat.length > 10) plat.substring(0, 8) + ".." else plat, 170f, y, paint)
                    canvas.drawText(blocoApto, 230f, y, paint)
                    canvas.drawText(if (morador.length > 20) morador.substring(0, 18) + ".." else morador, 300f, y, paint)
                    canvas.drawText(status, 440f, y, paint)
                    canvas.drawText(dataEnt, 500f, y, paint)

                    y += 18f
                    canvas.drawLine(40f, y - 4f, 555f, y - 4f, linePaint)
                    y += 4f
                }

                pdfDocument.finishPage(page)
                java.io.FileOutputStream(outputFile).use { fos ->
                    pdfDocument.writeTo(fos)
                }
                pdfDocument.close()
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val app = applicationContext as? SiceApplication
        Log.d(TAG, "Iniciando execução do WorkManager para backup do banco de dados.")

        try {
            // 1. Checkpoint SQLite DB WAL to ensure complete .db file
            if (app != null) {
                try {
                    val siceDb = app.database
                    val writableDb = siceDb.openHelper.writableDatabase
                    writableDb.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
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
            // O backup automático sempre exporta o banco de dados original completo, sem filtros
            dbFile.copyTo(cachedBackup, overwrite = true)

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

            // 3. Gerar Relatório PDF Mensal de Encomendas e salvar na mesma pasta (backupcond) + Google Drive
            try {
                val monthYearStr = SimpleDateFormat("MMMM_yyyy", Locale("pt", "BR")).format(Date()).replaceFirstChar { it.uppercase() }
                val pdfFileName = "Relatorio_Encomendas_$monthYearStr.pdf"
                val cachedPdf = File(applicationContext.cacheDir, pdfFileName)
                
                if (generateMonthlyPdfReport(applicationContext, cachedPdf)) {
                    val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val backupCondDir = File(downloadDir, "backupcond")
                    if (!backupCondDir.exists()) backupCondDir.mkdirs()
                    val targetPdfFile = File(backupCondDir, pdfFileName)
                    cachedPdf.copyTo(targetPdfFile, overwrite = true)
                    savedLocations.add("Downloads/backupcond/$pdfFileName")

                    try {
                        val appSpecificBackupDir = applicationContext.getExternalFilesDir(null)?.let { File(it, "backupcond") }
                        if (appSpecificBackupDir != null) {
                            if (!appSpecificBackupDir.exists()) appSpecificBackupDir.mkdirs()
                            val targetAppPdf = File(appSpecificBackupDir, pdfFileName)
                            cachedPdf.copyTo(targetAppPdf, overwrite = true)
                        }
                    } catch (e: Exception) {}

                    var webhookUrlPdf = prefs.getString(PREF_SCRIPT_URL, "")?.trim() ?: ""
                    if (webhookUrlPdf.isEmpty()) webhookUrlPdf = DEFAULT_WEBHOOK_URL
                    if (webhookUrlPdf.isNotEmpty() && webhookUrlPdf.startsWith("http") && isOnline(applicationContext)) {
                        uploadToGoogleDrive(cachedPdf, pdfFileName, webhookUrlPdf, "application/pdf")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao gerar/enviar relatório PDF mensal: ${e.message}")
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
                    return Result.retry()
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

    private fun uploadToGoogleDrive(file: File, fileName: String, webhookUrl: String, mimeType: String = "application/x-sqlite3"): Boolean {
        return try {
            val bytes = file.readBytes()
            val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)

            val payload = JSONObject().apply {
                put("fileName", fileName)
                put("fileData", base64Data)
                put("mimeType", mimeType)
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
