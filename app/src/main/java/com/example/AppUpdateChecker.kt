package com.example

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.text.Html
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class DriveApkInfo(
    val fileId: String,
    val fileName: String,
    val lastModified: String,
    val downloadUrl: String,
    val updateKey: String
)

object AppUpdateChecker {
    const val UPDATE_FOLDER_ID = "1fDQpmuZY2OnCQ5iNWeB473lCL_iW6XHF"
    const val UPDATE_FOLDER_URL = "https://drive.google.com/drive/folders/$UPDATE_FOLDER_ID"
    private const val UPDATE_EMBED_URL = "https://drive.google.com/embeddedfolderview?id=$UPDATE_FOLDER_ID"

    private const val PREFS_NAME = "sice_app_update_prefs"
    private const val PREF_COMPLETED_KEYS = "completed_apk_keys"
    private const val PREF_PENDING_KEY = "pending_apk_key"
    private const val PREF_PENDING_VERSION_CODE = "pending_from_version_code"
    private const val PREF_PENDING_UPDATE_TIME = "pending_from_update_time"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun getPackageVersionInfo(context: Context): Pair<Long, Long> {
        return try {
            val pm = context.packageManager
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
            val versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo)
            val lastUpdateTime = pkgInfo.lastUpdateTime
            versionCode to lastUpdateTime
        } catch (_: Exception) {
            0L to 0L
        }
    }

    fun reconcilePendingUpdateIfInstalled(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pendingKey = prefs.getString(PREF_PENDING_KEY, null)?.trim()
        if (pendingKey.isNullOrEmpty()) return

        val pendingVersion = prefs.getLong(PREF_PENDING_VERSION_CODE, 0L)
        val pendingTime = prefs.getLong(PREF_PENDING_UPDATE_TIME, 0L)
        val (currentVersion, currentTime) = getPackageVersionInfo(context)

        if ((currentVersion > 0L && currentVersion > pendingVersion) ||
            (currentTime > 0L && pendingTime > 0L && currentTime > pendingTime)
        ) {
            markUpdateCompleted(context, pendingKey)
        }
    }

    fun onPackageReplaced(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pendingKey = prefs.getString(PREF_PENDING_KEY, null)?.trim()
        if (!pendingKey.isNullOrEmpty()) {
            markUpdateCompleted(context, pendingKey)
        }
    }

    fun markUpdateStarted(context: Context, apk: DriveApkInfo) {
        val (currentVersion, currentTime) = getPackageVersionInfo(context)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREF_PENDING_KEY, apk.updateKey)
            .putLong(PREF_PENDING_VERSION_CODE, currentVersion)
            .putLong(PREF_PENDING_UPDATE_TIME, currentTime)
            .apply()
    }

    fun markUpdateCompleted(context: Context, apkKey: String) {
        if (apkKey.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getStringSet(PREF_COMPLETED_KEYS, emptySet()) ?: emptySet()
        val updated = HashSet(existing)
        updated.add(apkKey)
        prefs.edit()
            .putStringSet(PREF_COMPLETED_KEYS, updated)
            .remove(PREF_PENDING_KEY)
            .remove(PREF_PENDING_VERSION_CODE)
            .remove(PREF_PENDING_UPDATE_TIME)
            .apply()
    }

    suspend fun checkForAvailableUpdate(context: Context): DriveApkInfo? = withContext(Dispatchers.IO) {
        try {
            reconcilePendingUpdateIfInstalled(context)

            if (!DriveBackupWorker.isOnline(context)) {
                return@withContext null
            }

            val apks = fetchApkFilesFromDriveFolder()
            if (apks.isEmpty()) {
                return@withContext null
            }

            val targetApk = apks.first()
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val completedKeys = prefs.getStringSet(PREF_COMPLETED_KEYS, emptySet()) ?: emptySet()
            if (completedKeys.contains(targetApk.updateKey)) {
                return@withContext null
            }

            targetApk
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchApkFilesFromDriveFolder(): List<DriveApkInfo> {
        val embedHtml = fetchUrl(UPDATE_EMBED_URL)
        if (!embedHtml.isNullOrBlank()) {
            val fromEmbed = parseEmbeddedFolderView(embedHtml)
            if (fromEmbed.isNotEmpty()) {
                return fromEmbed
            }
            // If Drive returned the valid embedded folder structure with zero .apk entries, folder has no APK
            if (embedHtml.contains("flip-entries", ignoreCase = true) ||
                embedHtml.contains("flip-folder", ignoreCase = true)
            ) {
                return emptyList()
            }
        }

        val folderHtml = fetchUrl(UPDATE_FOLDER_URL)
        if (!folderHtml.isNullOrBlank()) {
            return parseStandardFolderHtml(folderHtml)
        }
        return emptyList()
    }

    private fun fetchUrl(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                )
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseEmbeddedFolderView(html: String): List<DriveApkInfo> {
        val results = mutableListOf<DriveApkInfo>()
        val entryChunks = html.split("class=\"flip-entry\"")
        if (entryChunks.size > 1) {
            val idRegex = Regex("""id="entry-([a-zA-Z0-9_-]+)"""")
            val hrefIdRegex = Regex("""/file/d/([a-zA-Z0-9_-]+)""")
            val titleRegex = Regex("""class="flip-entry-title"[^>]*>([^<]+)<""")
            val modifiedRegex = Regex("""class="flip-entry-last-modified"[^>]*>([^<]*)<""")

            for (i in 1 until entryChunks.size) {
                val chunk = entryChunks[i]
                val rawTitle = titleRegex.find(chunk)?.groupValues?.getOrNull(1) ?: continue
                val fileName = decodeHtml(rawTitle).trim()
                if (!fileName.endsWith(".apk", ignoreCase = true)) continue

                val fileId = idRegex.find(chunk)?.groupValues?.getOrNull(1)
                    ?: hrefIdRegex.find(chunk)?.groupValues?.getOrNull(1)
                    ?: ""
                val rawModified = modifiedRegex.find(chunk)?.groupValues?.getOrNull(1) ?: ""
                val lastModified = decodeHtml(rawModified).trim()

                val downloadUrl = if (fileId.isNotBlank()) {
                    "https://drive.google.com/uc?export=download&id=$fileId"
                } else {
                    UPDATE_FOLDER_URL
                }
                val updateKey = "${fileId}|${fileName}|${lastModified}"
                results.add(
                    DriveApkInfo(
                        fileId = fileId,
                        fileName = fileName,
                        lastModified = lastModified,
                        downloadUrl = downloadUrl,
                        updateKey = updateKey
                    )
                )
            }
        }

        if (results.isEmpty()) {
            val linkRegex = Regex(
                """href="https://drive\.google\.com/file/d/([a-zA-Z0-9_-]{15,})/[^"]*"[^>]*>[\s\S]{0,400}?>([^<>\r\n]+\.apk)<""",
                RegexOption.IGNORE_CASE
            )
            linkRegex.findAll(html).forEach { match ->
                val fileId = match.groupValues[1].trim()
                val fileName = decodeHtml(match.groupValues[2]).trim()
                if (fileName.endsWith(".apk", ignoreCase = true) && results.none { it.fileId == fileId }) {
                    results.add(
                        DriveApkInfo(
                            fileId = fileId,
                            fileName = fileName,
                            lastModified = "",
                            downloadUrl = "https://drive.google.com/uc?export=download&id=$fileId",
                            updateKey = "${fileId}|${fileName}|"
                        )
                    )
                }
            }
        }

        return results.sortedByDescending { it.fileName.lowercase() }
    }

    private fun parseStandardFolderHtml(html: String): List<DriveApkInfo> {
        val results = mutableListOf<DriveApkInfo>()
        val unescaped = html.replace("\\\"", "\"").replace("\\u0026", "&")
        val entryRegex = Regex(
            """\["([a-zA-Z0-9_-]{20,})",\[[^\]]*\],"([^"\\/\r\n<>]+\.apk)""""",
            RegexOption.IGNORE_CASE
        )
        entryRegex.findAll(unescaped).forEach { match ->
            val fileId = match.groupValues[1].trim()
            val fileName = decodeHtml(match.groupValues[2]).trim()
            if (fileId != UPDATE_FOLDER_ID &&
                fileName.endsWith(".apk", ignoreCase = true) &&
                results.none { it.fileId == fileId }
            ) {
                results.add(
                    DriveApkInfo(
                        fileId = fileId,
                        fileName = fileName,
                        lastModified = "",
                        downloadUrl = "https://drive.google.com/uc?export=download&id=$fileId",
                        updateKey = "${fileId}|${fileName}|"
                    )
                )
            }
        }
        return results.sortedByDescending { it.fileName.lowercase() }
    }

    private fun decodeHtml(input: String): String {
        return Html.fromHtml(input, Html.FROM_HTML_MODE_LEGACY).toString()
    }
}
