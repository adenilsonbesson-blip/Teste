package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import androidx.compose.animation.core.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.graphics.RectF
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.FileProvider
import com.example.ui.theme.MyApplicationTheme
import com.example.data.SiceRepository
import com.example.data.WebData
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.io.File
import java.io.FileWriter

fun findActivity(context: Context): Activity? {
    var ctx = context
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

fun prepareDbForMode(sourceDbFile: File, destDbFile: File, mode: String) {
    sourceDbFile.copyTo(destDbFile, overwrite = true)
    if (mode == "encomendas") {
        try {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(destDbFile.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
            db.execSQL("DELETE FROM moradores")
            db.execSQL("DELETE FROM porteiros")
            db.execSQL("DELETE FROM entregadores")
            db.execSQL("DELETE FROM block_qrcodes")
            db.execSQL("DELETE FROM web_data")
            db.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    } else if (mode == "sem_encomendas") {
        try {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(destDbFile.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
            db.execSQL("DELETE FROM encomendas")
            db.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

class MainActivity : ComponentActivity() {

    companion object {
        const val CAMERA_PERMISSION_CODE = 101
        var pendingPermissionRequest: PermissionRequest? = null
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            pendingPermissionRequest?.let { req ->
                val resources = req.resources ?: emptyArray()
                req.grant(resources.map { it.toString() }.toTypedArray())
            }
        } else {
            pendingPermissionRequest?.deny()
            Toast.makeText(this, "Permissão de câmera é necessária para escanear QR Codes.", Toast.LENGTH_LONG).show()
        }
        pendingPermissionRequest = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Search and clean vestigial temporary files from uninstalled versions while preserving active database
        cleanLegacyTracesAndSanitizeStorage(this)

        // Request Camera Permission on startup to avoid any user interruption during QR scanning
        requestCameraPermission()

        // Check for auto-restore backup file from previous installation
        checkAndPerformAutoRestore()

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                ) {
                    SiceWebViewContainer()
                }
            }
        }
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun checkAndPerformAutoRestore() {
        try {
            val autoRestoreFile = File(getExternalFilesDir(null), "backup_auto_restore.db")
            if (autoRestoreFile.exists() && autoRestoreFile.length() > 0) {
                val bytes = autoRestoreFile.readBytes()
                AndroidDBInterface(this).mergeIncomingSQLiteBytes(bytes)
                autoRestoreFile.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
@Composable
fun SiceWebViewContainer() {
    var showNativeStartup by remember { mutableStateOf(true) }
    
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(350)
        showNativeStartup = false
    }

    var fileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    
    // Scanner States
    var isScanning by remember { mutableStateOf(false) }
    var scanTarget by remember { mutableStateOf("entrada") }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    
    // Back navigation handling
    BackHandler(enabled = true) {
        if (isScanning) {
            isScanning = false
        } else {
            webViewInstance?.post {
                webViewInstance?.loadUrl("javascript:(function(){ if(typeof solicitarSairDoApp === 'function') solicitarSairDoApp(); else if(window.history.length > 1) window.history.back(); })()")
            }
        }
    }
    
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, webViewInstance) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    webViewInstance?.onPause()
                    webViewInstance?.pauseTimers()
                }
                Lifecycle.Event.ON_RESUME -> {
                    webViewInstance?.onResume()
                    webViewInstance?.resumeTimers()
                }
                Lifecycle.Event.ON_STOP -> {
                    if (isScanning) {
                        isScanning = false
                    }
                }
                Lifecycle.Event.ON_DESTROY -> {
                    try {
                        webViewInstance?.destroy()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            var results: Array<Uri>? = null
            if (data?.data != null) {
                results = arrayOf(data.data!!)
            } else if (data?.clipData != null) {
                val clipData = data.clipData!!
                results = Array(clipData.itemCount) { i ->
                    clipData.getItemAt(i).uri
                }
            }
            fileChooserCallback?.onReceiveValue(results)
        } else {
            fileChooserCallback?.onReceiveValue(null)
        }
        fileChooserCallback = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        androidx.compose.runtime.key(reloadKey) {
            AndroidView(
                factory = { context ->
                    val activityContext = findActivity(context) ?: context
                    
                    // Search and clean vestigial temporary files from uninstalled versions while preserving active database
                    cleanLegacyTracesAndSanitizeStorage(activityContext)
                    
                    WebView(activityContext).apply {
                        webViewInstance = this
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        isVerticalScrollBarEnabled = true
                        isHorizontalScrollBarEnabled = false
                        
                        // Configure persistent DOM databases & compatibility settings
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            allowFileAccess = true
                            allowContentAccess = true
                            allowFileAccessFromFileURLs = true
                            allowUniversalAccessFromFileURLs = true
                            mediaPlaybackRequiresUserGesture = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            cacheMode = WebSettings.LOAD_DEFAULT
                            
                            // Support viewport meta tags & auto scalability
                            useWideViewPort = true
                            loadWithOverviewMode = true
                        }
                        
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun closeApp() {
                                findActivity(activityContext)?.finish()
                            }
                            @JavascriptInterface
                            fun notifyAppReady() {
                                (findActivity(activityContext) ?: activityContext as? Activity)?.runOnUiThread {
                                    showNativeStartup = false
                                }
                            }
                        }, "AndroidApp")
                        
                        // Set custom WebChromeClient to handle HTML5 getUserMedia camera permissions on standard WebView
                        webChromeClient = object : WebChromeClient() {
                            override fun onShowFileChooser(
                                webView: WebView?,
                                filePathCallback: ValueCallback<Array<Uri>>?,
                                fileChooserParams: FileChooserParams?
                            ): Boolean {
                                fileChooserCallback?.onReceiveValue(null)
                                fileChooserCallback = filePathCallback
                                
                                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    type = "*/*"
                                }
                                try {
                                    filePickerLauncher.launch(Intent.createChooser(intent, "Selecionar backup"))
                                } catch (e: Exception) {
                                    fileChooserCallback = null
                                    return false
                                }
                                return true
                            }
                            
                            override fun onJsAlert(
                                view: WebView?,
                                url: String?,
                                message: String?,
                                result: android.webkit.JsResult?
                            ): Boolean {
                                val activity = findActivity(context)
                                if (activity != null) {
                                    android.app.AlertDialog.Builder(activity)
                                        .setTitle("Aviso")
                                        .setMessage(message)
                                        .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                                        .setCancelable(false)
                                        .create()
                                        .show()
                                    return true
                                }
                                return super.onJsAlert(view, url, message, result)
                            }

                            override fun onJsConfirm(
                                view: WebView?,
                                url: String?,
                                message: String?,
                                result: android.webkit.JsResult?
                            ): Boolean {
                                val activity = findActivity(context)
                                if (activity != null) {
                                    android.app.AlertDialog.Builder(activity)
                                        .setTitle("Confirmação")
                                        .setMessage(message)
                                        .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                                        .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                                        .setCancelable(false)
                                        .create()
                                        .show()
                                    return true
                                }
                                return super.onJsConfirm(view, url, message, result)
                            }

                            override fun onJsPrompt(
                                view: WebView?,
                                url: String?,
                                message: String?,
                                defaultValue: String?,
                                result: android.webkit.JsPromptResult?
                            ): Boolean {
                                val activity = findActivity(context)
                                if (activity != null) {
                                    val input = android.widget.EditText(activity)
                                    input.setText(defaultValue)
                                    android.app.AlertDialog.Builder(activity)
                                        .setTitle("Entrada")
                                        .setMessage(message)
                                        .setView(input)
                                        .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm(input.text.toString()) }
                                        .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                                        .setCancelable(false)
                                        .create()
                                        .show()
                                    return true
                                }
                                return super.onJsPrompt(view, url, message, defaultValue, result)
                            }

                            override fun onPermissionRequest(request: PermissionRequest) {
                                val resources = request.resources ?: emptyArray()
                                var containsCamera = false
                                for (resource in resources) {
                                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE == resource) {
                                        containsCamera = true
                                        break
                                    }
                                }
                                if (containsCamera) {
                                    val activity = findActivity(context)
                                    if (activity != null) {
                                        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                            activity.runOnUiThread {
                                                request.grant(resources)
                                            }
                                        } else {
                                            MainActivity.pendingPermissionRequest = request
                                            ActivityCompat.requestPermissions(
                                                activity,
                                                arrayOf(Manifest.permission.CAMERA),
                                                MainActivity.CAMERA_PERMISSION_CODE
                                            )
                                        }
                                    } else {
                                        request.deny()
                                    }
                                } else {
                                    request.grant(resources)
                                }
                            }
                        }
                        
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val url = request?.url?.toString() ?: return false
                                if (url.startsWith("whatsapp://") || url.startsWith("https://api.whatsapp.com/") || url.startsWith("https://wa.me/")) {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(intent)
                                        return true
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Não foi possível abrir o WhatsApp: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                        return true
                                    }
                                }
                                if (url.startsWith("http://") || url.startsWith("https://")) {
                                    return false // allow online CDN resources load standardly
                                }
                                return super.shouldOverrideUrlLoading(view, request)
                            }

                            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                                try {
                                    view?.let { wv ->
                                        val parent = wv.parent as? ViewGroup
                                        parent?.removeView(wv)
                                        wv.destroy()
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                                val act = findActivity(context)
                                act?.runOnUiThread {
                                    reloadKey++
                                }
                                return true
                            }
                        }
                        
                        // Add bridges to support modern local actions natively
                        val activity = findActivity(context) ?: (context as Activity)
                        addJavascriptInterface(AndroidShareInterface(context), "AndroidShare")
                        addJavascriptInterface(AndroidPrintInterface(context, this), "AndroidPrint")
                        addJavascriptInterface(CameraPermissionInterface(activity), "CameraPermissionLauncher")
                        addJavascriptInterface(AndroidDBInterface(context), "AndroidDB")
                        addJavascriptInterface(AndroidScannerInterface(activity) { target ->
                            scanTarget = target
                            isScanning = true
                        }, "AndroidScanner")
                        addJavascriptInterface(AndroidGeminiInterface(activity, this), "AndroidGemini")
                        addJavascriptInterface(AndroidDriveInterface(context, this), "AndroidDrive")
                        
                        // Load local compiled assets page
                        loadUrl("file:///android_asset/index.html")
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (isScanning) {
            NativeScannerView(
                scanTarget = scanTarget,
                onBarcodeScanned = { barcode ->
                    isScanning = false
                    webViewInstance?.post {
                        val cleanBarcode = barcode.replace("\'", "\\'")
                        if (scanTarget == "entrada") {
                            webViewInstance?.loadUrl("javascript:(function() { document.getElementById('barcode-input').value = '$cleanBarcode'; processBarcode('$cleanBarcode'); })()")
                        } else {
                            webViewInstance?.loadUrl("javascript:(function() { document.getElementById('barcode-baixa-input').value = '$cleanBarcode'; processarValidacaoQR('$cleanBarcode'); })()")
                        }
                    }
                },
                onClose = {
                    isScanning = false
                    webViewInstance?.post {
                        if (scanTarget == "entrada") {
                            webViewInstance?.loadUrl("javascript:desativarCamera()")
                        } else {
                            webViewInstance?.loadUrl("javascript:fecharModalValidacaoQR()")
                        }
                    }
                }
            )
        }

        if (showNativeStartup) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1E293B)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.img_startup_screen_1788182862266),
                    contentDescription = "Condo Entregas Startup Screen",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            }
        }
    }
}

class AndroidShareInterface(private val context: Context) {
    @JavascriptInterface
    fun shareText(text: String, filename: String) {
        val activity = findActivity(context)
        activity?.runOnUiThread {
            try {
                val cacheFile = File(context.cacheDir, filename)
                FileWriter(cacheFile).use { writer ->
                    writer.write(text)
                }
                
                val uri: Uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    cacheFile
                )
                
                val mimeType = if (filename.endsWith(".csv", ignoreCase = true)) "text/csv" else "text/plain"
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_SUBJECT, "Relatório de Entregas")
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                
                val chooser = Intent.createChooser(shareIntent, "Salvar / Compartilhar Relatório")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            } catch (e: Exception) {
                Toast.makeText(context, "Erro ao exportar: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    @JavascriptInterface
    fun exportSpreadsheet(content: String, filename: String) {
        val activity = findActivity(context)
        activity?.runOnUiThread {
            try {
                val actualFileName = if (filename.isNullOrBlank()) "CondoEntregas_Planilha_Completa.xlsx" else filename
                val cacheFile = File(context.cacheDir, actualFileName)
                val bytes = try {
                    val cleanContent = if (content.contains(";base64,")) content.substringAfter("base64,") else content
                    android.util.Base64.decode(cleanContent, android.util.Base64.DEFAULT)
                } catch (e: Exception) {
                    content.toByteArray(StandardCharsets.UTF_8)
                }
                FileOutputStream(cacheFile).use { stream ->
                    stream.write(bytes)
                }

                val uri: Uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    cacheFile
                )

                val mimeType = when {
                    actualFileName.endsWith(".csv", ignoreCase = true) -> "text/csv"
                    actualFileName.endsWith(".xlsx", ignoreCase = true) -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    else -> "application/vnd.ms-excel"
                }

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_SUBJECT, "Planilha de Dados ($actualFileName)")
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val chooser = Intent.createChooser(shareIntent, "Salvar Planilha (.xlsx)")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            } catch (e: Exception) {
                Toast.makeText(context, "Erro ao exportar planilha: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    @JavascriptInterface
    fun playBeep(isError: Boolean = false) {
        val activity = findActivity(context)
        activity?.runOnUiThread {
            try {
                val streamType = android.media.AudioManager.STREAM_MUSIC
                val toneType = if (isError) {
                    android.media.ToneGenerator.TONE_PROP_BEEP2
                } else {
                    android.media.ToneGenerator.TONE_PROP_BEEP
                }
                val toneGen = android.media.ToneGenerator(streamType, 100)
                toneGen.startTone(toneType, if (isError) 350 else 180)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @JavascriptInterface
    fun shareWhatsApp(text: String, base64Image: String? = null) {
        val activity = findActivity(context)
        activity?.runOnUiThread {
            try {
                var uri: Uri? = null
                if (!base64Image.isNullOrEmpty() && base64Image.contains("base64,")) {
                    val cleanedBase64 = base64Image.substringAfter("base64,")
                    val decodedBytes = android.util.Base64.decode(cleanedBase64, android.util.Base64.DEFAULT)
                    val cacheFile = File(context.cacheDir, "linha_do_tempo_encomenda.png")
                    java.io.FileOutputStream(cacheFile).use { fos ->
                        fos.write(decodedBytes)
                    }
                    uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        cacheFile
                    )
                }

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = if (uri != null) "image/png" else "text/plain"
                    if (uri != null) {
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    if (text.isNotEmpty()) {
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                val whatsappPackages = listOf("com.whatsapp", "com.whatsapp.w4b")
                var launched = false
                val pm = context.packageManager

                for (pkg in whatsappPackages) {
                    try {
                        pm.getPackageInfo(pkg, 0)
                        intent.setPackage(pkg)
                        context.startActivity(intent)
                        launched = true
                        break
                    } catch (e: Exception) {
                        // Package not installed, try next
                    }
                }

                if (!launched) {
                    intent.setPackage(null)
                    val chooser = Intent.createChooser(intent, "Enviar para o WhatsApp")
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(chooser)
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Erro ao abrir WhatsApp: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    @JavascriptInterface
    fun shareImageBase64(base64String: String, filename: String) {
        val activity = findActivity(context)
        activity?.runOnUiThread {
            try {
                // Determine format
                val mimeType = "image/png"
                val cleanedBase64 = base64String.substringAfter("base64,")
                val decodedBytes = android.util.Base64.decode(cleanedBase64, android.util.Base64.DEFAULT)
                
                val cacheFile = File(context.cacheDir, filename)
                java.io.FileOutputStream(cacheFile).use { fos ->
                    fos.write(decodedBytes)
                }
                
                val uri: Uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    cacheFile
                )
                
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                
                val chooser = Intent.createChooser(shareIntent, "Compartilhar Imagem via WhatsApp")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            } catch (e: Exception) {
                Toast.makeText(context, "Erro ao compartilhar imagem: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    @JavascriptInterface
    fun shareAppApk() {
        val activity = findActivity(context)
        activity?.runOnUiThread {
            try {
                val apkPath = context.applicationInfo.sourceDir
                val apkFile = File(apkPath)
                val destinationFile = File(context.cacheDir, "Controle_Entregas.apk")
                apkFile.copyTo(destinationFile, overwrite = true)
                
                val uri: Uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    destinationFile
                )
                
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.android.package-archive"
                    putExtra(Intent.EXTRA_SUBJECT, "Instalador Controle de Entregas")
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                
                val chooser = Intent.createChooser(shareIntent, "Compartilhar Aplicativo (APK)")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            } catch (e: Exception) {
                Toast.makeText(context, "Erro ao compartilhar aplicativo: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

class AndroidPrintInterface(private val context: Context, private val webView: WebView) {
    @JavascriptInterface
    fun print(jobName: String = "Relatorio entregas.pdf", landscape: Boolean = false) {
        val activity = findActivity(context)
        activity?.runOnUiThread {
            try {
                val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                val finalJobName = if (jobName.isBlank()) "Relatorio entregas.pdf" else jobName
                val printAdapter = webView.createPrintDocumentAdapter(finalJobName)
                val mediaSize = if (landscape) PrintAttributes.MediaSize.ISO_A4.asLandscape() else PrintAttributes.MediaSize.ISO_A4.asPortrait()
                val printAttributes = PrintAttributes.Builder()
                    .setMediaSize(mediaSize)
                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                    .build()
                printManager.print(finalJobName, printAdapter, printAttributes)
            } catch (e: Exception) {
                Toast.makeText(context, "Erro de impressão: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

class CameraPermissionInterface(private val activity: Activity) {
    @JavascriptInterface
    fun requestPermission() {
        activity.runOnUiThread {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.CAMERA), 101)
            }
        }
    }

    @JavascriptInterface
    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
}

class AndroidDBInterface(
    private val context: Context
) {
    companion object {
        @Volatile
        var inMemoryCache: String? = null

        fun prewarm(context: Context, repository: SiceRepository) {
            val file = File(context.filesDir, "entregas_backup_v16.json")
            if (file.exists()) {
                try {
                    val text = file.readText()
                    if (text.isNotBlank() && text.length > 5) {
                        inMemoryCache = text
                        return
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            try {
                val webData = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    repository.getWebData("sice_db_v16")
                }
                if (!webData.isNullOrBlank() && webData.length > 5) {
                    inMemoryCache = webData
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private val app = context.applicationContext as SiceApplication
    private val repository: SiceRepository
        get() = app.repository
    private val backupFile = File(context.filesDir, "entregas_backup_v16.json")
    private val saveScope = CoroutineScope(Dispatchers.IO)
    private val saveMutex = Mutex()
    private var relationalSyncJob: kotlinx.coroutines.Job? = null

    @JavascriptInterface
    fun save(json: String) {
        // Instant in-memory cache update
        inMemoryCache = json
        saveScope.launch {
            saveMutex.withLock {
                try {
                    // Fast atomic write to backup file
                    try {
                        val tmpFile = File(context.filesDir, "entregas_backup_v16.tmp")
                        tmpFile.writeText(json)
                        if (tmpFile.exists()) {
                            tmpFile.renameTo(backupFile)
                        }
                    } catch (e: Exception) {
                        try {
                            backupFile.writeText(json)
                        } catch (e2: Exception) {
                            e2.printStackTrace()
                        }
                    }

                    // Save directly to web_data key-value in SQLite for instant durable persistence
                    try {
                        repository.insertWebData(com.example.data.WebData(keyName = "sice_db_v16", jsonValue = json))
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Debounced relational tables sync: only parses and writes full relational rows when idle
            relationalSyncJob?.cancel()
            relationalSyncJob = saveScope.launch {
                kotlinx.coroutines.delay(2500)
                try {
                    val jObj = org.json.JSONObject(json)
                    
                    val moradoresList = mutableListOf<com.example.data.Morador>()
                    if (jObj.has("moradores")) {
                        val arr = jObj.getJSONArray("moradores")
                        for (i in 0 until arr.length()) {
                            val item = arr.getJSONObject(i)
                            moradoresList.add(com.example.data.Morador(
                                nome = item.optString("nome", ""),
                                bloco = item.optString("bloco", ""),
                                apto = item.optString("apto", "")
                            ))
                        }
                    }
                    
                    val porteirosList = mutableListOf<com.example.data.Porteiro>()
                    if (jObj.has("porteiros")) {
                        val arr = jObj.getJSONArray("porteiros")
                        for (i in 0 until arr.length()) {
                            val item = arr.getJSONObject(i)
                            porteirosList.add(com.example.data.Porteiro(
                                nome = item.optString("nome", ""),
                                turno = item.optString("turno", ""),
                                turnoInicio = item.optString("turnoInicio", ""),
                                turnoFim = item.optString("turnoFim", ""),
                                dia = item.optString("dia", ""),
                                dataInicio = item.optString("dataInicio", ""),
                                bloco = item.optString("bloco", "")
                            ))
                        }
                    }
                    
                    val entregadoresList = mutableListOf<com.example.data.Entregador>()
                    if (jObj.has("entregadores")) {
                        val arr = jObj.getJSONArray("entregadores")
                        for (i in 0 until arr.length()) {
                            val item = arr.getJSONObject(i)
                            entregadoresList.add(com.example.data.Entregador(
                                nome = item.optString("nome", ""),
                                empresa = item.optString("empresa", ""),
                                dia = item.optString("dia", ""),
                                dataInicio = item.optString("dataInicio", ""),
                                horaEntrada = item.optString("horaEntrada", ""),
                                horaSaida = item.optString("horaSaida", "")
                            ))
                        }
                    }
                    
                    val encomendasList = mutableListOf<com.example.data.Encomenda>()
                    if (jObj.has("encomendas")) {
                        val arr = jObj.getJSONArray("encomendas")
                        for (i in 0 until arr.length()) {
                            val item = arr.getJSONObject(i)
                            encomendasList.add(com.example.data.Encomenda(
                                id = item.optInt("id", 0),
                                rastreio = item.optString("rastreio", ""),
                                plataforma = item.optString("plataforma", ""),
                                bloco = item.optString("bloco", ""),
                                apto = item.optString("apto", ""),
                                morador = item.optString("morador", ""),
                                entregador = item.optString("entregador", ""),
                                status = item.optString("status", ""),
                                dataEntrada = item.optString("dataEntrada", ""),
                                horaEntrada = item.optString("horaEntrada", ""),
                                dataEntrega = item.optString("dataEntrega", ""),
                                horaEntrega = item.optString("horaEntrega", ""),
                                porteiroEntrega = item.optString("porteiroEntrega", ""),
                                assinaturaRaw = item.optString("assinaturaRaw", "")
                            ))
                        }
                    }

                    // Batch transaction sync for high performance when idle
                    repository.syncFullDatabase(moradoresList, porteirosList, entregadoresList, encomendasList)

                    // Sync QrCodes
                    if (jObj.has("qrcodes")) {
                        val qrObj = jObj.getJSONObject("qrcodes")
                        val keys = qrObj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val token = qrObj.optString(key, "")
                            if (key.isNotEmpty()) {
                                repository.insertBlockQrCode(com.example.data.BlockQrCode(bloco = key, token = token))
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    @JavascriptInterface
    fun load(): String {
        // 1. Instant in-memory cache (0ms)
        inMemoryCache?.let {
            if (it.isNotBlank() && it.length > 5) {
                return it
            }
        }
        // 2. Fast disk file read
        if (backupFile.exists()) {
            try {
                val text = backupFile.readText()
                if (text.isNotBlank() && text.length > 5) {
                    inMemoryCache = text
                    return text
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        // 3. Room SQLite WebData fallback
        try {
            val webData = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                repository.getWebData("sice_db_v16")
            }
            if (!webData.isNullOrBlank() && webData.length > 5) {
                inMemoryCache = webData
                return webData
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 4. Reconstruct from relational tables if necessary
        val reconstructed = reconstructJsonFromTables()
        if (reconstructed.isNotBlank() && reconstructed.length > 20) {
            inMemoryCache = reconstructed
            return reconstructed
        }
        return ""
    }


    @JavascriptInterface
    fun createAutoRestoreBackup(): Boolean {
        return try {
            val dbFile = context.getDatabasePath("entregas.db")
            val backupFile = File(context.getExternalFilesDir(null), "backup_auto_restore.db")
            if (dbFile.exists()) {
                dbFile.copyTo(backupFile, overwrite = true)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun reconstructJsonFromTables(): String {
        return try {
            val jObj = org.json.JSONObject()
            
            val listMoradores = runBlocking(Dispatchers.IO) {
                try {
                    repository.moradores.first()
                } catch(e: Exception) {
                    emptyList()
                }
            }
            val arrMoradores = org.json.JSONArray()
            for (m in listMoradores) {
                val mObj = org.json.JSONObject()
                mObj.put("id", m.id)
                mObj.put("nome", m.nome)
                mObj.put("bloco", m.bloco)
                mObj.put("apto", m.apto)
                arrMoradores.put(mObj)
            }
            jObj.put("moradores", arrMoradores)

            val listPorteiros = runBlocking(Dispatchers.IO) {
                try {
                    repository.porteiros.first()
                } catch(e: Exception) {
                    emptyList()
                }
            }
            val arrPorteiros = org.json.JSONArray()
            for (p in listPorteiros) {
                val pObj = org.json.JSONObject()
                pObj.put("id", p.id)
                pObj.put("nome", p.nome)
                pObj.put("turno", p.turno)
                pObj.put("turnoInicio", p.turnoInicio)
                pObj.put("turnoFim", p.turnoFim)
                pObj.put("dia", p.dia)
                pObj.put("dataInicio", p.dataInicio)
                pObj.put("bloco", p.bloco)
                arrPorteiros.put(pObj)
            }
            jObj.put("porteiros", arrPorteiros)

            val listEntregadores = runBlocking(Dispatchers.IO) {
                try {
                    repository.entregadores.first()
                } catch(e: Exception) {
                    emptyList()
                }
            }
            val arrEntregadores = org.json.JSONArray()
            for (ent in listEntregadores) {
                val entObj = org.json.JSONObject()
                entObj.put("id", ent.id)
                entObj.put("nome", ent.nome)
                entObj.put("empresa", ent.empresa)
                entObj.put("dia", ent.dia)
                entObj.put("dataInicio", ent.dataInicio)
                entObj.put("horaEntrada", ent.horaEntrada)
                entObj.put("horaSaida", ent.horaSaida)
                arrEntregadores.put(entObj)
            }
            jObj.put("entregadores", arrEntregadores)

            val listEncomendas = runBlocking(Dispatchers.IO) {
                try {
                    repository.encomendas.first()
                } catch(e: Exception) {
                    emptyList()
                }
            }
            val arrEncomendas = org.json.JSONArray()
            for (enc in listEncomendas) {
                val encObj = org.json.JSONObject()
                encObj.put("id", enc.id)
                encObj.put("rastreio", enc.rastreio)
                encObj.put("plataforma", enc.plataforma)
                encObj.put("bloco", enc.bloco)
                encObj.put("apto", enc.apto)
                encObj.put("morador", enc.morador)
                encObj.put("entregador", enc.entregador)
                encObj.put("status", enc.status)
                encObj.put("dataEntrada", enc.dataEntrada)
                encObj.put("horaEntrada", enc.horaEntrada)
                encObj.put("dataEntrega", enc.dataEntrega)
                encObj.put("horaEntrega", enc.horaEntrega)
                encObj.put("porteiroEntrega", enc.porteiroEntrega)
                encObj.put("assinaturaRaw", enc.assinaturaRaw)
                arrEncomendas.put(encObj)
            }
            jObj.put("encomendas", arrEncomendas)

            // Reconstruct block_qrcodes
            val listQrCodes = runBlocking(Dispatchers.IO) {
                try {
                    repository.blockQrCodes.first()
                } catch(e: Exception) {
                    emptyList()
                }
            }
            val qrcodesObj = org.json.JSONObject()
            for (qr in listQrCodes) {
                qrcodesObj.put(qr.bloco, qr.token)
            }
            jObj.put("qrcodes", qrcodesObj)

            // Preserve admin credentials from existing WebData if present
            runBlocking(Dispatchers.IO) {
                try {
                    val webData = repository.getWebData("sice_db_v16")
                    if (!webData.isNullOrBlank()) {
                        val oldObj = org.json.JSONObject(webData)
                        if (oldObj.has("adminUser")) jObj.put("adminUser", oldObj.getString("adminUser"))
                        if (oldObj.has("adminPassword")) jObj.put("adminPassword", oldObj.getString("adminPassword"))
                        if (oldObj.has("adminConfigured")) jObj.put("adminConfigured", oldObj.getBoolean("adminConfigured"))
                    }
                } catch(e: Exception) {
                    e.printStackTrace()
                }
            }

            // Other default / required fields
            jObj.put("ultimoEntregador", "")
            jObj.put("currentTheme", "teal")
            
            val arrPlats = org.json.JSONArray()
            val platforms = listOf("Mercado Livre", "Shopee", "Shein", "Magazine Luiza", "C&A", "Mile", "Natura", "Loggi", "TikTok", "Zara", "Amazon")
            for (p in platforms) {
                arrPlats.put(p)
            }
            jObj.put("plataformas", arrPlats)

            val jsonResult = jObj.toString()
            // Save it to WebData so we have it cached for future loads
            runBlocking(Dispatchers.IO) {
                repository.insertWebData(com.example.data.WebData(keyName = "sice_db_v16", jsonValue = jsonResult))
            }
            jsonResult
        } catch(e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    @JavascriptInterface
    fun exportOriginalDatabase(mode: String = "completo") {
        val activity = findActivity(context)
        activity?.runOnUiThread {
            try {
                runBlocking(Dispatchers.IO) {
                    try {
                        val siceDb = app.database
                        siceDb.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(FULL)")
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                val dbFile = context.getDatabasePath("entregas.db")
                if (!dbFile.exists()) {
                    Toast.makeText(context, "Erro: Banco de dados não existe no armazenamento local.", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }

                val dataStr = java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.getDefault()).format(java.util.Date())
                val horaStr = java.text.SimpleDateFormat("HH'h'mm", java.util.Locale.getDefault()).format(java.util.Date())
                val deviceName = DriveBackupWorker.getBackupDeviceName(context)
                val backupFileName = "Backup,$dataStr,$horaStr,$deviceName.db"
                val cacheBackupFile = File(context.cacheDir, backupFileName)
                prepareDbForMode(dbFile, cacheBackupFile, "completo")

                val uri: Uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    cacheBackupFile
                )

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_SUBJECT, "Backup (.db) - $backupFileName")
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val chooser = Intent.createChooser(shareIntent, "Salvar Backup (.db)")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            } catch (e: Exception) {
                Toast.makeText(context, "Erro ao exportar banco de dados original: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    @JavascriptInterface
    fun exportSpreadsheet(content: String, fileName: String = "CondoEntregas_Planilha_Completa.xlsx") {
        val activity = findActivity(context)
        activity?.runOnUiThread {
            try {
                val actualFileName = if (fileName.isNullOrBlank()) "CondoEntregas_Planilha_Completa.xlsx" else fileName
                val cacheFile = File(context.cacheDir, actualFileName)
                val bytes = try {
                    val cleanContent = if (content.contains(";base64,")) content.substringAfter("base64,") else content
                    android.util.Base64.decode(cleanContent, android.util.Base64.DEFAULT)
                } catch (e: Exception) {
                    content.toByteArray(StandardCharsets.UTF_8)
                }
                FileOutputStream(cacheFile).use { stream ->
                    stream.write(bytes)
                }

                val uri: Uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    cacheFile
                )

                val mimeType = when {
                    actualFileName.endsWith(".csv", ignoreCase = true) -> "text/csv"
                    actualFileName.endsWith(".xlsx", ignoreCase = true) -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    else -> "application/vnd.ms-excel"
                }

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_SUBJECT, "Planilha de Dados ($actualFileName)")
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val chooser = Intent.createChooser(shareIntent, "Salvar Planilha (.xlsx)")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            } catch (e: Exception) {
                Toast.makeText(context, "Erro ao exportar planilha: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    @JavascriptInterface
    fun getDeviceName(): String {
        return DriveBackupWorker.getBackupDeviceName(context)
    }

    @JavascriptInterface
    fun setCustomDeviceName(name: String): Boolean {
        val prefs = context.getSharedPreferences(DriveBackupWorker.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("custom_device_name", name.trim()).apply()
        return true
    }

    @JavascriptInterface
    fun clearEncomendasOnly(): Boolean {
        return try {
            runBlocking(Dispatchers.IO) {
                repository.deleteAllEncomendas()
                val siceDb = app.database
                siceDb.openHelper.writableDatabase.execSQL("DELETE FROM encomendas")
                siceDb.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(FULL)")
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    @JavascriptInterface
    fun getDatabaseSizeKB(): Double {
        return try {
            var bytes = 0L
            if (backupFile.exists()) {
                bytes += backupFile.length()
            }
            val dbFile = context.getDatabasePath("entregas.db")
            if (dbFile != null && dbFile.exists()) {
                bytes += dbFile.length()
            }
            val walFile = context.getDatabasePath("entregas.db-wal")
            if (walFile != null && walFile.exists()) {
                bytes += walFile.length()
            }
            val shmFile = context.getDatabasePath("entregas.db-shm")
            if (shmFile != null && shmFile.exists()) {
                bytes += shmFile.length()
            }
            if (bytes > 0) {
                bytes / 1024.0
            } else {
                0.0
            }
        } catch (e: Exception) {
            0.0
        }
    }

    fun mergeIncomingSQLiteBytes(dbBytes: ByteArray): String {
        return try {
            val tempFile = File(context.cacheDir, "temp_import_${System.currentTimeMillis()}.db")
            FileOutputStream(tempFile).use { fos ->
                fos.write(dbBytes)
            }
            val incomingDb = android.database.sqlite.SQLiteDatabase.openDatabase(
                tempFile.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            
            var incomingJson: String? = null
            try {
                val cursor = incomingDb.rawQuery("SELECT jsonValue FROM web_data WHERE keyName = 'sice_db_v16' LIMIT 1", null)
                if (cursor.moveToFirst()) {
                    incomingJson = cursor.getString(0)
                }
                cursor.close()
            } catch (e: Exception) {}

            val incomingObj = if (!incomingJson.isNullOrBlank()) {
                try { org.json.JSONObject(incomingJson) } catch (e: Exception) { extractJsonFromSQLite(incomingDb) }
            } else {
                extractJsonFromSQLite(incomingDb)
            }
            incomingDb.close()
            tempFile.delete()

            // Current data
            val currentRaw = load()
            val currentObj = if (currentRaw.isNotBlank()) {
                try { org.json.JSONObject(currentRaw) } catch (e: Exception) { org.json.JSONObject() }
            } else {
                org.json.JSONObject()
            }

            // Merge non-destructively and produce report
            val mergedRes = mergeJsonNonDestructive(currentObj, incomingObj)
            val mergedObj = mergedRes.getJSONObject("data")
            val reportObj = mergedRes.getJSONObject("report")

            save(mergedObj.toString())

            val res = org.json.JSONObject()
            res.put("success", true)
            res.put("report", reportObj)
            res.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            val res = org.json.JSONObject()
            res.put("success", false)
            res.put("error", e.localizedMessage ?: "Erro ao mesclar banco de dados")
            res.toString()
        }
    }

    private fun extractJsonFromSQLite(db: android.database.sqlite.SQLiteDatabase): org.json.JSONObject {
        val jObj = org.json.JSONObject()
        try {
            val arr = org.json.JSONArray()
            val c = db.rawQuery("SELECT * FROM moradores", null)
            val colNome = c.getColumnIndex("nome")
            val colBloco = c.getColumnIndex("bloco")
            val colApto = c.getColumnIndex("apto")
            while (c.moveToNext()) {
                val obj = org.json.JSONObject()
                obj.put("nome", if (colNome >= 0) c.getString(colNome) else "")
                obj.put("bloco", if (colBloco >= 0) c.getString(colBloco) else "")
                obj.put("apto", if (colApto >= 0) c.getString(colApto) else "")
                arr.put(obj)
            }
            c.close()
            jObj.put("moradores", arr)
        } catch (e: Exception) {}

        try {
            val arr = org.json.JSONArray()
            val c = db.rawQuery("SELECT * FROM porteiros", null)
            val colNome = c.getColumnIndex("nome")
            val colTurno = c.getColumnIndex("turno")
            val colTurnoIni = c.getColumnIndex("turnoInicio")
            val colTurnoFim = c.getColumnIndex("turnoFim")
            val colDia = c.getColumnIndex("dia")
            val colDataIni = c.getColumnIndex("dataInicio")
            val colBloco = c.getColumnIndex("bloco")
            while (c.moveToNext()) {
                val obj = org.json.JSONObject()
                obj.put("nome", if (colNome >= 0) c.getString(colNome) else "")
                obj.put("turno", if (colTurno >= 0) c.getString(colTurno) else "")
                obj.put("turnoInicio", if (colTurnoIni >= 0) c.getString(colTurnoIni) else "")
                obj.put("turnoFim", if (colTurnoFim >= 0) c.getString(colTurnoFim) else "")
                obj.put("dia", if (colDia >= 0) c.getString(colDia) else "")
                obj.put("dataInicio", if (colDataIni >= 0) c.getString(colDataIni) else "")
                obj.put("bloco", if (colBloco >= 0) c.getString(colBloco) else "")
                arr.put(obj)
            }
            c.close()
            jObj.put("porteiros", arr)
        } catch (e: Exception) {}

        try {
            val arr = org.json.JSONArray()
            val c = db.rawQuery("SELECT * FROM entregadores", null)
            val colNome = c.getColumnIndex("nome")
            val colEmpresa = c.getColumnIndex("empresa")
            val colDia = c.getColumnIndex("dia")
            val colDataIni = c.getColumnIndex("dataInicio")
            val colHoraEnt = c.getColumnIndex("horaEntrada")
            val colHoraSai = c.getColumnIndex("horaSaida")
            while (c.moveToNext()) {
                val obj = org.json.JSONObject()
                obj.put("nome", if (colNome >= 0) c.getString(colNome) else "")
                obj.put("empresa", if (colEmpresa >= 0) c.getString(colEmpresa) else "")
                obj.put("dia", if (colDia >= 0) c.getString(colDia) else "")
                obj.put("dataInicio", if (colDataIni >= 0) c.getString(colDataIni) else "")
                obj.put("horaEntrada", if (colHoraEnt >= 0) c.getString(colHoraEnt) else "")
                obj.put("horaSaida", if (colHoraSai >= 0) c.getString(colHoraSai) else "")
                arr.put(obj)
            }
            c.close()
            jObj.put("entregadores", arr)
        } catch (e: Exception) {}

        try {
            val arr = org.json.JSONArray()
            val c = db.rawQuery("SELECT * FROM encomendas", null)
            val colId = c.getColumnIndex("id")
            val colRastreio = c.getColumnIndex("rastreio")
            val colPlat = c.getColumnIndex("plataforma")
            val colBloco = c.getColumnIndex("bloco")
            val colApto = c.getColumnIndex("apto")
            val colMorador = c.getColumnIndex("morador")
            val colEntregador = c.getColumnIndex("entregador")
            val colStatus = c.getColumnIndex("status")
            val colDataEnt = c.getColumnIndex("dataEntrada")
            val colHoraEnt = c.getColumnIndex("horaEntrada")
            val colDataEntr = c.getColumnIndex("dataEntrega")
            val colHoraEntr = c.getColumnIndex("horaEntrega")
            val colPortEntr = c.getColumnIndex("porteiroEntrega")
            val colAss = c.getColumnIndex("assinaturaRaw")
            while (c.moveToNext()) {
                val obj = org.json.JSONObject()
                if (colId >= 0) obj.put("id", c.getInt(colId))
                obj.put("rastreio", if (colRastreio >= 0) c.getString(colRastreio) else "")
                obj.put("plataforma", if (colPlat >= 0) c.getString(colPlat) else "")
                obj.put("bloco", if (colBloco >= 0) c.getString(colBloco) else "")
                obj.put("apto", if (colApto >= 0) c.getString(colApto) else "")
                obj.put("morador", if (colMorador >= 0) c.getString(colMorador) else "")
                obj.put("entregador", if (colEntregador >= 0) c.getString(colEntregador) else "")
                obj.put("status", if (colStatus >= 0) c.getString(colStatus) else "Recebido")
                obj.put("dataEntrada", if (colDataEnt >= 0) c.getString(colDataEnt) else "")
                obj.put("horaEntrada", if (colHoraEnt >= 0) c.getString(colHoraEnt) else "")
                obj.put("dataEntrega", if (colDataEntr >= 0) c.getString(colDataEntr) else "")
                obj.put("horaEntrega", if (colHoraEntr >= 0) c.getString(colHoraEntr) else "")
                obj.put("porteiroEntrega", if (colPortEntr >= 0) c.getString(colPortEntr) else "")
                obj.put("assinaturaRaw", if (colAss >= 0) c.getString(colAss) else "")
                arr.put(obj)
            }
            c.close()
            jObj.put("encomendas", arr)
        } catch (e: Exception) {}

        return jObj
    }

    private fun mergeJsonNonDestructive(current: org.json.JSONObject, incoming: org.json.JSONObject): org.json.JSONObject {
        val result = org.json.JSONObject(current.toString())

        var addMor = 0; var skipMor = 0; var totalMor = 0
        var addPort = 0; var skipPort = 0; var totalPort = 0
        var addEnt = 0; var skipEnt = 0; var totalEnt = 0
        var addPlat = 0; var skipPlat = 0; var totalPlat = 0
        var addBloco = 0; var skipBloco = 0; var totalBloco = 0
        var addEnc = 0; var skipEnc = 0; var totalEnc = 0

        // 1. Moradores
        val currMoradores = if (result.has("moradores")) result.getJSONArray("moradores") else org.json.JSONArray()
        val moradorKeys = HashSet<String>()
        for (i in 0 until currMoradores.length()) {
            val m = currMoradores.getJSONObject(i)
            val key = "${m.optString("nome", "").trim().lowercase()}|${m.optString("bloco", "").trim().uppercase()}|${m.optString("apto", "").trim()}"
            moradorKeys.add(key)
        }
        if (incoming.has("moradores")) {
            val inMoradores = incoming.getJSONArray("moradores")
            for (i in 0 until inMoradores.length()) {
                totalMor++
                val m = inMoradores.getJSONObject(i)
                val key = "${m.optString("nome", "").trim().lowercase()}|${m.optString("bloco", "").trim().uppercase()}|${m.optString("apto", "").trim()}"
                if (key.length > 2 && !moradorKeys.contains(key)) {
                    moradorKeys.add(key)
                    currMoradores.put(m)
                    addMor++
                } else {
                    skipMor++
                }
            }
        }
        result.put("moradores", currMoradores)

        // 2. Porteiros
        val currPorteiros = if (result.has("porteiros")) result.getJSONArray("porteiros") else org.json.JSONArray()
        val porteiroKeys = HashSet<String>()
        for (i in 0 until currPorteiros.length()) {
            val p = currPorteiros.getJSONObject(i)
            porteiroKeys.add(p.optString("nome", "").trim().lowercase())
        }
        if (incoming.has("porteiros")) {
            val inPorteiros = incoming.getJSONArray("porteiros")
            for (i in 0 until inPorteiros.length()) {
                totalPort++
                val p = inPorteiros.getJSONObject(i)
                val key = p.optString("nome", "").trim().lowercase()
                if (key.isNotEmpty() && !porteiroKeys.contains(key)) {
                    porteiroKeys.add(key)
                    currPorteiros.put(p)
                    addPort++
                } else {
                    skipPort++
                }
            }
        }
        result.put("porteiros", currPorteiros)

        // 3. Entregadores
        val currEntregadores = if (result.has("entregadores")) result.getJSONArray("entregadores") else org.json.JSONArray()
        val entregadorKeys = HashSet<String>()
        for (i in 0 until currEntregadores.length()) {
            val ent = currEntregadores.getJSONObject(i)
            entregadorKeys.add(ent.optString("nome", "").trim().lowercase())
        }
        if (incoming.has("entregadores")) {
            val inEntregadores = incoming.getJSONArray("entregadores")
            for (i in 0 until inEntregadores.length()) {
                totalEnt++
                val ent = inEntregadores.getJSONObject(i)
                val key = ent.optString("nome", "").trim().lowercase()
                if (key.isNotEmpty() && !entregadorKeys.contains(key)) {
                    entregadorKeys.add(key)
                    currEntregadores.put(ent)
                    addEnt++
                } else {
                    skipEnt++
                }
            }
        }
        result.put("entregadores", currEntregadores)

        // 4. Plataformas
        val currPlataformas = if (result.has("plataformas")) result.getJSONArray("plataformas") else org.json.JSONArray()
        val platKeys = HashSet<String>()
        for (i in 0 until currPlataformas.length()) {
            platKeys.add(currPlataformas.optString(i, "").trim().lowercase())
        }
        if (incoming.has("plataformas")) {
            val inPlataformas = incoming.getJSONArray("plataformas")
            for (i in 0 until inPlataformas.length()) {
                totalPlat++
                val p = inPlataformas.optString(i, "").trim()
                if (p.isNotEmpty() && !platKeys.contains(p.lowercase())) {
                    platKeys.add(p.lowercase())
                    currPlataformas.put(p)
                    addPlat++
                } else {
                    skipPlat++
                }
            }
        }
        result.put("plataformas", currPlataformas)

        // 5. Blocos
        val currBlocos = if (result.has("blocos")) result.getJSONArray("blocos") else org.json.JSONArray()
        val blocoKeys = HashSet<String>()
        for (i in 0 until currBlocos.length()) {
            blocoKeys.add(currBlocos.optString(i, "").trim().uppercase())
        }
        if (incoming.has("blocos")) {
            val inBlocos = incoming.getJSONArray("blocos")
            for (i in 0 until inBlocos.length()) {
                totalBloco++
                val b = inBlocos.optString(i, "").trim().uppercase()
                if (b.isNotEmpty() && !blocoKeys.contains(b)) {
                    blocoKeys.add(b)
                    currBlocos.put(b)
                    addBloco++
                } else {
                    skipBloco++
                }
            }
        }
        result.put("blocos", currBlocos)

        // 6. Encomendas
        val currEncomendas = if (result.has("encomendas")) result.getJSONArray("encomendas") else org.json.JSONArray()
        var substituidasBaixaEnc = 0
        var maxId = 1000

        val rastreioToIdx = HashMap<String, Int>()
        val idToIdx = HashMap<Int, Int>()
        val compositeToIdx = HashMap<String, Int>()

        for (i in 0 until currEncomendas.length()) {
            val enc = currEncomendas.getJSONObject(i)
            val id = enc.optInt("id", 0)
            if (id > maxId) maxId = id
            if (id > 0) idToIdx[id] = i

            val rastreio = enc.optString("rastreio", "").trim().uppercase()
            if (rastreio.isNotEmpty()) {
                rastreioToIdx[rastreio] = i
            }

            val bloco = enc.optString("bloco", "").trim().uppercase()
            val apto = enc.optString("apto", "").trim()
            val dtEnt = enc.optString("dataEntrada", "").trim()
            val hrEnt = enc.optString("horaEntrada", "").trim()
            val compKey = "$dtEnt|$hrEnt|$bloco|$apto"
            if (compKey.length > 5) {
                compositeToIdx[compKey] = i
            }
        }

        fun hasBaixa(e: org.json.JSONObject): Boolean {
            val st = e.optString("status", "").trim()
            val isEntregue = st.equals("Entregue", ignoreCase = true)
            val hasEntregueBool = e.optBoolean("entregue", false)
            val hasData = e.optString("dataEntrega", "").trim().isNotEmpty()
            val hasHora = e.optString("horaEntrega", "").trim().isNotEmpty()
            val hasPorteiro = e.optString("porteiroEntrega", "").trim().isNotEmpty()
            val hasAssinatura = e.optString("assinaturaRaw", "").trim().isNotEmpty()
            val hasRecebedor = e.optString("recebedor", "").trim().isNotEmpty()
            val hasMoradorRet = e.optString("moradorRetirou", "").trim().isNotEmpty()
            return isEntregue || hasEntregueBool || hasData || hasHora || hasPorteiro || hasAssinatura || hasRecebedor || hasMoradorRet
        }

        fun isPendente(e: org.json.JSONObject): Boolean {
            return !hasBaixa(e)
        }

        if (incoming.has("encomendas")) {
            val inEncomendas = incoming.getJSONArray("encomendas")
            for (i in 0 until inEncomendas.length()) {
                totalEnc++
                val enc = inEncomendas.getJSONObject(i)
                val inRastreio = enc.optString("rastreio", "").trim().uppercase()
                val inId = enc.optInt("id", 0)
                val inBloco = enc.optString("bloco", "").trim().uppercase()
                val inApto = enc.optString("apto", "").trim()
                val inDtEnt = enc.optString("dataEntrada", "").trim()
                val inMorador = enc.optString("morador", "").trim().lowercase()
                val incomingComBaixa = hasBaixa(enc)

                var matchIdx: Int? = null
                
                // 1. Try match by ID first (strongest key)
                if (inId > 0) {
                    for (j in 0 until currEncomendas.length()) {
                        if (currEncomendas.getJSONObject(j).optInt("id", 0) == inId) {
                            matchIdx = j
                            break
                        }
                    }
                }

                // 2. If no ID match, try other criteria (Rastreio, Bloco/Apto/Data)
                if (matchIdx == null) {
                    for (j in 0 until currEncomendas.length()) {
                        val curr = currEncomendas.getJSONObject(j)
                        val currRastreio = curr.optString("rastreio", "").trim().uppercase()
                        val currBloco = curr.optString("bloco", "").trim().uppercase()
                        val currApto = curr.optString("apto", "").trim()
                        val currDtEnt = curr.optString("dataEntrada", "").trim()
                        val currMorador = curr.optString("morador", "").trim().lowercase()
                        
                        var isMatch = false
                        
                        // Match by Rastreio (if valid)
                        if (inRastreio.isNotEmpty() && inRastreio != "SN" && inRastreio != "S/N" && 
                            inRastreio != "0" && currRastreio == inRastreio) {
                            isMatch = true
                        }
                        // Match by Bloco/Apto/DataEntrada
                        else if (currBloco == inBloco && currApto == inApto && currDtEnt == inDtEnt) {
                            if (inMorador.isEmpty() || currMorador.isEmpty() || currMorador == inMorador) {
                                isMatch = true
                            }
                        }
                        
                        if (isMatch) {
                            // If pending and incoming has baixa, this is a strong match
                            if (isPendente(curr) && incomingComBaixa) {
                                matchIdx = j
                                break 
                            } else if (matchIdx == null) {
                                matchIdx = j 
                            }
                        }
                    }
                }

                if (matchIdx == null) {
                    maxId++
                    enc.put("id", maxId)
                    val newIdx = currEncomendas.length()
                    currEncomendas.put(enc)
                    addEnc++

                    val plat = enc.optString("plataforma", "").trim()
                    if (plat.isNotEmpty() && !platKeys.contains(plat.lowercase())) {
                        platKeys.add(plat.lowercase())
                        currPlataformas.put(plat)
                        addPlat++
                    }
                } else {
                    val existingEnc = currEncomendas.getJSONObject(matchIdx)
                    val existingPendente = isPendente(existingEnc)

                    // Always replace/update if pending and incoming has baixa, OR just update details
                    if (existingPendente && incomingComBaixa) {
                        val existingId = existingEnc.optInt("id", if (inId > 0) inId else maxId)
                        val keys = enc.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            if (k != "id") {
                                existingEnc.put(k, enc.get(k))
                            }
                        }
                        existingEnc.put("id", existingId)
                        existingEnc.put("status", "Entregue")
                        existingEnc.put("entregue", true)

                        if (existingEnc.optString("dataEntrega", "").isBlank()) {
                            val todayIso = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
                            existingEnc.put("dataEntrega", todayIso)
                        }
                        if (existingEnc.optString("horaEntrega", "").isBlank()) {
                            val nowTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                            existingEnc.put("horaEntrega", nowTime)
                        }

                        currEncomendas.put(matchIdx, existingEnc)
                        substituidasBaixaEnc++
                        addEnc++
                    } else if (!existingPendente && incomingComBaixa) {
                        // Already delivered, just update missing details
                        if (existingEnc.optString("assinaturaRaw", "").isBlank() && enc.optString("assinaturaRaw", "").isNotBlank()) {
                            existingEnc.put("assinaturaRaw", enc.optString("assinaturaRaw"))
                        }
                        if (existingEnc.optString("porteiroEntrega", "").isBlank() && enc.optString("porteiroEntrega", "").isNotBlank()) {
                            existingEnc.put("porteiroEntrega", enc.optString("porteiroEntrega"))
                        }
                        skipEnc++
                    } else {
                        skipEnc++
                    }
                }
            }
        }
        result.put("encomendas", currEncomendas)

        val repObj = org.json.JSONObject().apply {
            put("totalAnalisados", totalMor + totalPort + totalEnt + totalPlat + totalBloco + totalEnc)
            put("totalAdicionados", addMor + addPort + addEnt + addPlat + addBloco + addEnc)
            put("totalIgnorados", skipMor + skipPort + skipEnt + skipPlat + skipBloco + skipEnc)

            put("moradores", org.json.JSONObject().apply {
                put("analisados", totalMor)
                put("adicionados", addMor)
                put("ignorados", skipMor)
            })
            put("porteiros", org.json.JSONObject().apply {
                put("analisados", totalPort)
                put("adicionados", addPort)
                put("ignorados", skipPort)
            })
            put("entregadores", org.json.JSONObject().apply {
                put("analisados", totalEnt)
                put("adicionados", addEnt)
                put("ignorados", skipEnt)
            })
            put("plataformas", org.json.JSONObject().apply {
                put("analisados", totalPlat)
                put("adicionadas", addPlat)
                put("ignoradas", skipPlat)
            })
            put("blocos", org.json.JSONObject().apply {
                put("analisados", totalBloco)
                put("adicionados", addBloco)
                put("ignorados", skipBloco)
            })
            put("encomendas", org.json.JSONObject().apply {
                put("analisados", totalEnc)
                put("adicionadas", addEnc)
                put("substituidasBaixa", substituidasBaixaEnc)
                put("ignoradas", skipEnc)
            })
        }

        val wrapper = org.json.JSONObject()
        wrapper.put("data", result)
        wrapper.put("report", repObj)
        return wrapper
    }

    @JavascriptInterface
    fun importOriginalDatabase(base64DataUrl: String): String {
        return try {
            val pureBase64 = if (base64DataUrl.contains("base64,")) {
                base64DataUrl.substringAfter("base64,")
            } else {
                base64DataUrl
            }
            val dbBytes = android.util.Base64.decode(pureBase64, android.util.Base64.DEFAULT)
            
            val sqliteSignature = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
            if (dbBytes.size < 16) {
                return "{\"success\":false,\"error\":\"Arquivo não é um banco SQLite válido\"}"
            }
            val match = dbBytes.take(16).toByteArray().contentEquals(sqliteSignature)
            if (!match) {
                return "{\"success\":false,\"error\":\"Formato de banco inválido\"}"
            }

            mergeIncomingSQLiteBytes(dbBytes)
        } catch (e: Exception) {
            e.printStackTrace()
            "{\"success\":false,\"error\":\"${e.localizedMessage}\"}"
        }
    }

    @JavascriptInterface
    fun listBackupFiles(): String {
        val backupList = mutableListOf<org.json.JSONObject>()
        val scannedPaths = HashSet<String>()
        
        val dirsToScan = listOfNotNull(
            File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "backupcond"),
            File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "backupCond"),
            File(android.os.Environment.getExternalStorageDirectory(), "Download/backupcond"),
            File(android.os.Environment.getExternalStorageDirectory(), "backupCond"),
            context.getExternalFilesDir(null)?.let { File(it, "backupcond") },
            context.getExternalFilesDir(null)?.let { File(it, "backupCond") },
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            context.cacheDir,
            context.filesDir
        )

        val dateFormat = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault())

        for (dir in dirsToScan) {
            try {
                if (dir.exists() && dir.isDirectory) {
                    val files = dir.listFiles { _, name ->
                        val lower = name.lowercase(java.util.Locale.ROOT)
                        lower.endsWith(".db") || lower.endsWith(".sqlite") || lower.endsWith(".sqlite3") || lower.contains("condoentregas")
                    }
                    if (files != null) {
                        for (file in files) {
                            if (file.isFile && file.length() > 0 && !scannedPaths.contains(file.absolutePath)) {
                                scannedPaths.add(file.absolutePath)
                                val item = org.json.JSONObject()
                                item.put("name", file.name)
                                item.put("path", file.absolutePath)
                                item.put("size", file.length())
                                item.put("sizeFormatted", formatFileSize(file.length()))
                                item.put("lastModified", file.lastModified())
                                item.put("dateFormatted", dateFormat.format(java.util.Date(file.lastModified())))
                                val parentName = file.parentFile?.name ?: "backupCond"
                                item.put("directory", parentName)
                                backupList.add(item)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        backupList.sortByDescending { it.optLong("lastModified", 0L) }

        val result = org.json.JSONArray()
        for (obj in backupList) {
            result.put(obj)
        }
        return result.toString()
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        val index = if (digitGroups >= units.size) units.size - 1 else if (digitGroups < 0) 0 else digitGroups
        return String.format(java.util.Locale.US, "%.1f %s", size / Math.pow(1024.0, index.toDouble()), units[index])
    }

    @JavascriptInterface
    fun importOriginalDatabaseFromFilePath(filePath: String): String {
        return try {
            val file = File(filePath)
            if (!file.exists() || !file.canRead()) {
                return "{\"success\":false,\"error\":\"Arquivo de banco não encontrado ou inacessível\"}"
            }
            val dbBytes = file.readBytes()
            val sqliteSignature = "SQLite format 3\u0000".toByteArray(StandardCharsets.US_ASCII)
            if (dbBytes.size < 16) {
                return "{\"success\":false,\"error\":\"Arquivo inválido para banco SQLite\"}"
            }
            val match = dbBytes.take(16).toByteArray().contentEquals(sqliteSignature)
            if (!match) {
                return "{\"success\":false,\"error\":\"Formato de banco de dados incompatível\"}"
            }

            mergeIncomingSQLiteBytes(dbBytes)
        } catch (e: Exception) {
            e.printStackTrace()
            "{\"success\":false,\"error\":\"${e.localizedMessage}\"}"
        }
    }
}

class AndroidDriveInterface(
    private val context: Context,
    private val webView: WebView
) {
    private val app = context.applicationContext as SiceApplication
    private val repository: SiceRepository
        get() = app.repository
    private val scope = CoroutineScope(Dispatchers.IO)
    private val prefs = context.getSharedPreferences(DriveBackupWorker.PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREF_AUTO_BACKUP_ENABLED = DriveBackupWorker.PREF_AUTO_BACKUP_ENABLED
        private const val PREF_BACKUP_MODE = DriveBackupWorker.PREF_BACKUP_MODE
        private const val PREF_BACKUP_FREQUENCY = DriveBackupWorker.PREF_BACKUP_FREQUENCY
        private const val PREF_LAST_SYNC_TIME = DriveBackupWorker.PREF_LAST_SYNC_TIME
        private const val PREF_LAST_SYNC_STATUS = DriveBackupWorker.PREF_LAST_SYNC_STATUS
        private const val PREF_SCRIPT_URL = DriveBackupWorker.PREF_SCRIPT_URL
    }

    private fun isConnectedToInternet(): Boolean {
        return DriveBackupWorker.isOnline(context)
    }

    @JavascriptInterface
    fun getDriveSyncConfig(): String {
        val obj = JSONObject()
        obj.put("autoBackupEnabled", prefs.getBoolean(PREF_AUTO_BACKUP_ENABLED, true))
        obj.put("backupMode", prefs.getString(PREF_BACKUP_MODE, "completo") ?: "completo")
        obj.put("backupFrequency", prefs.getString(PREF_BACKUP_FREQUENCY, "diario") ?: "diario")
        obj.put("lastSyncTime", prefs.getString(PREF_LAST_SYNC_TIME, "Nunca sincronizado") ?: "Nunca sincronizado")
        obj.put("lastSyncStatus", prefs.getString(PREF_LAST_SYNC_STATUS, "Inativo") ?: "Inativo")
        obj.put("scriptWebhookUrl", prefs.getString(PREF_SCRIPT_URL, DriveBackupWorker.DEFAULT_WEBHOOK_URL) ?: DriveBackupWorker.DEFAULT_WEBHOOK_URL)
        obj.put("isConnected", isConnectedToInternet())
        return obj.toString()
    }

    @JavascriptInterface
    fun getAutomaticBackupDiagnostics(): String {
        val obj = JSONObject()
        val isAutoEnabled = prefs.getBoolean(PREF_AUTO_BACKUP_ENABLED, true)
        obj.put("autoBackupEnabled", isAutoEnabled)
        obj.put("scheduledTimes", "07:00 e 17:00")
        obj.put("lastSyncTime", prefs.getString(PREF_LAST_SYNC_TIME, "Aguardando primeiro disparo"))
        obj.put("lastSyncStatus", prefs.getString(PREF_LAST_SYNC_STATUS, "Ativo e Programado"))
        obj.put("lastDriveStatus", prefs.getString(DriveBackupWorker.PREF_LAST_DRIVE_STATUS, "Pronto"))
        obj.put("webhookConfigured", !prefs.getString(PREF_SCRIPT_URL, "").isNullOrBlank())

        val now = java.util.Calendar.getInstance()
        val target7am = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 7)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val target17pm = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 17)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val nextTarget = when {
            now.before(target7am) -> "Hoje às 07:00"
            now.before(target17pm) -> "Hoje às 17:00"
            else -> "Amanhã às 07:00"
        }
        obj.put("nextScheduledRun", nextTarget)
        obj.put("isOnline", DriveBackupWorker.isOnline(context))

        val dbFile = context.getDatabasePath("entregas.db")
        obj.put("dbExists", dbFile.exists())
        obj.put("dbSizeBytes", if (dbFile.exists()) dbFile.length() else 0L)

        return obj.toString()
    }

    @JavascriptInterface
    fun setAutoBackupEnabled(enabled: Boolean): Boolean {
        prefs.edit().putBoolean(PREF_AUTO_BACKUP_ENABLED, enabled).apply()
        DriveBackupWorker.scheduleOrCancel(context)
        if (enabled) {
            triggerSyncToDrive(false)
        }
        return true
    }

    @JavascriptInterface
    fun setBackupMode(mode: String): Boolean {
        prefs.edit().putString(PREF_BACKUP_MODE, mode).apply()
        return true
    }

    @JavascriptInterface
    fun setBackupFrequency(freq: String): Boolean {
        prefs.edit().putString(PREF_BACKUP_FREQUENCY, freq).apply()
        DriveBackupWorker.scheduleOrCancel(context)
        return true
    }

    @JavascriptInterface
    fun setScriptWebhookUrl(url: String): Boolean {
        prefs.edit().putString(PREF_SCRIPT_URL, url.trim()).apply()
        return true
    }

    private val isSyncRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    @JavascriptInterface
    fun triggerSyncToDrive(isManual: Boolean) {
        if (!isSyncRunning.compareAndSet(false, true)) {
            android.util.Log.w("MainActivity", "Sincronização já em andamento, ignorando nova chamada concorrente.")
            return
        }
        scope.launch {
            try {
                // Checkpoint e compactar (VACUUM) SQLite DB WAL
                try {
                    val siceDb = app.database
                    val writableDb = siceDb.openHelper.writableDatabase
                    writableDb.execSQL("VACUUM")
                    writableDb.execSQL("PRAGMA wal_checkpoint(FULL)")
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                val dbFile = context.getDatabasePath("entregas.db")
                if (!dbFile.exists()) {
                    val statusMsg = "Banco local vazio para backup"
                    prefs.edit().putString(PREF_LAST_SYNC_STATUS, statusMsg).apply()
                    notifyWebDriveStatus(false, statusMsg, isManual)
                    return@launch
                }

                val mode = prefs.getString(PREF_BACKUP_MODE, "completo") ?: "completo"
                val modeLabel = when(mode) {
                    "encomendas" -> "Encomendas"
                    "sem_encomendas" -> "SemEncomendas"
                    else -> "Completo"
                }

                val nowFmt = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                val dataStr = java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.getDefault()).format(java.util.Date())
                val horaStr = java.text.SimpleDateFormat("HH'h'mm", java.util.Locale.getDefault()).format(java.util.Date())
                val deviceName = DriveBackupWorker.getBackupDeviceName(context)
                val backupFileName = "Backup,$dataStr,$horaStr,$deviceName.db"

                val cachedBackup = File(context.cacheDir, backupFileName)
                // Copia diretamente o banco original completo sem filtros
                dbFile.copyTo(cachedBackup, overwrite = true)

                // Save strictly in Downloads/backupcond
                val savedLocations = mutableListOf<String>()
                try {
                    val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    val backupCondDir = File(downloadDir, "backupcond")
                    if (!backupCondDir.exists()) {
                        backupCondDir.mkdirs()
                    }
                    val targetFile = File(backupCondDir, backupFileName)
                    cachedBackup.copyTo(targetFile, overwrite = true)
                    savedLocations.add("Downloads/backupcond/$backupFileName")
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // 4. Envio direto para o Google Apps Script se configurado
                var driveStatusMsg = ""
                var webhookUrl = prefs.getString(PREF_SCRIPT_URL, "")?.trim() ?: ""
                if (webhookUrl.isEmpty()) {
                    webhookUrl = DriveBackupWorker.DEFAULT_WEBHOOK_URL
                }

                if (webhookUrl.isNotEmpty() && webhookUrl.startsWith("http")) {
                    if (isConnectedToInternet()) {
                        try {
                            val bytes = cachedBackup.readBytes()
                            val base64Data = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                            val payload = JSONObject().apply {
                                put("fileName", backupFileName)
                                put("fileData", base64Data)
                                put("mimeType", "application/x-sqlite3")
                                put("folderName", "backup")
                                put("account", "portcentral85@gmail.com")
                            }

                            val client = okhttp3.OkHttpClient.Builder()
                                .connectTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
                                .writeTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
                                .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
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
                            android.util.Log.d("MainActivity", "Resposta Google Script: Code=${response.code}, Body=$respBody")
                            if (response.isSuccessful || response.code in 200..302) {
                                driveStatusMsg = " | ☁️ Google Drive: Enviado com Sucesso"
                            } else if (response.code == 401 || response.code == 403) {
                                driveStatusMsg = " | ⚠️ Google Drive: Falha 401 (Altere 'Quem pode acessar' para 'Qualquer pessoa' no Apps Script)"
                            } else {
                                driveStatusMsg = " | ⚠️ Google Drive: Falha no Envio (${response.code})"
                            }
                        } catch (err: Exception) {
                            err.printStackTrace()
                            driveStatusMsg = " | ⚠️ Google Drive: Erro de Conexão"
                        }
                    } else {
                        driveStatusMsg = " | ☁️ Google Drive: Aguardando Internet"
                        // Agenda via WorkManager com restrição de rede para enviar assim que a internet voltar
                        DriveBackupWorker.triggerImmediateWork(context)
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

                val act = findActivity(context)
                act?.runOnUiThread {
                    if (isManual) {
                        Toast.makeText(context, "Backup salvo com sucesso em Downloads/backupcond!", Toast.LENGTH_LONG).show()
                    }
                }

                notifyWebDriveStatus(true, finalStatus, isManual)
            } catch (e: Exception) {
                e.printStackTrace()
                val errorMsg = "Erro no backup: ${e.localizedMessage}"
                prefs.edit().putString(PREF_LAST_SYNC_STATUS, errorMsg).apply()
                notifyWebDriveStatus(false, errorMsg, isManual)
            } finally {
                // Limpa arquivos de backup temporários e cache do aparelho
                try {
                    context.cacheDir.listFiles()?.forEach { f ->
                        try {
                            if (f.isFile) {
                                f.delete()
                            } else if (f.isDirectory && f.name != "WebView") {
                                f.deleteRecursively()
                            }
                        } catch (e: Exception) {}
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                isSyncRunning.set(false)
            }
        }
    }

    private fun notifyWebDriveStatus(success: Boolean, status: String, isManual: Boolean) {
        val act = findActivity(context)
        act?.runOnUiThread {
            try {
                val nowTime = prefs.getString(PREF_LAST_SYNC_TIME, "Nunca sincronizado") ?: "Nunca sincronizado"
                val safeStatus = status.replace("'", "\\'")
                val safeTime = nowTime.replace("'", "\\'")
                val js = "if (typeof onDriveSyncResult === 'function') { onDriveSyncResult($success, '$safeStatus', '$safeTime', $isManual); }"
                webView.evaluateJavascript(js, null)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

class AndroidScannerInterface(
    private val activity: Activity,
    private val onStartScan: (String) -> Unit
) {
    @JavascriptInterface
    fun startScanner(target: String) {
        activity.runOnUiThread {
            onStartScan(target)
        }
    }
}

class AndroidGeminiInterface(
    private val activity: Activity,
    private val webView: WebView
) {
    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO)

    @JavascriptInterface
    fun identifyPlatform(barcode: String, callbackName: String) {
        scope.launch {
            try {
                val apiKey = BuildConfig.GEMINI_API_KEY
                val json = JSONObject().apply {
                    put("contents", org.json.JSONArray().put(JSONObject().apply {
                        put("parts", org.json.JSONArray().put(JSONObject().apply {
                            put("text", "Dada a transportadora/logística baseada apenas neste código de rastreamento de uma encomenda e na sua experiência, identifique de qual plataforma de entrega esse código com maior probabilidade pertence (como Mercado Livre, Amazon, Shopee, Correios, Jadlog, Loggi, Shein, AliExpress, Magazine Luiza, etc). Retorne SOMENTE o nome da plataforma, sem ponto final, sem frases. Se não souber, retorne 'Outra'. Código de rastreio: $barcode")
                        }))
                    }))
                    put("generationConfig", JSONObject().apply {
                        put("temperature", 0.1)
                    })
                }

                val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey")
                    .post(body!!)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                
                var platformName = "Outra"
                if (response.isSuccessful && responseBody.isNotEmpty()) {
                    val root = JSONObject(responseBody)
                    val candidates = root.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val firstCandidate = candidates.getJSONObject(0)
                        val content = firstCandidate.optJSONObject("content")
                        val parts = content?.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            val text = parts.getJSONObject(0).optString("text")
                            if (!text.isNullOrBlank()) {
                                platformName = text.trim().replace("\n", "").replace("'", "")
                            }
                        }
                    }
                }
                
                activity.runOnUiThread {
                    webView.evaluateJavascript("javascript:${callbackName}('$platformName')", null)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                activity.runOnUiThread {
                    webView.evaluateJavascript("javascript:${callbackName}('Outra')", null)
                }
            }
        }
    }
}

@Composable
fun NativeScannerView(
    scanTarget: String,
    onBarcodeScanned: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    // Set up Barcode Scanner with ALL_FORMATS for maximum sensitivity
    val options = remember {
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
    }
    val scanner = remember { BarcodeScanning.getClient(options) }

    var isTorchOn by remember { mutableStateOf(false) }
    var cameraControl by remember { mutableStateOf<androidx.camera.core.CameraControl?>(null) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            try {
                if (cameraProviderFuture.isDone) {
                    val cameraProvider = cameraProviderFuture.get()
                    cameraProvider.unbindAll()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                cameraExecutor.shutdown()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    if (!hasCameraPermission) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.9f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    text = "Permissão de Câmera Necessária",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "A permissão de câmera é necessária para o funcionamento do leitor de códigos nativo.",
                    color = Color.LightGray,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                androidx.compose.material3.Button(
                    onClick = {
                        val activity = findActivity(context)
                        if (activity != null) {
                            ActivityCompat.requestPermissions(
                                activity,
                                arrayOf(Manifest.permission.CAMERA),
                                101
                            )
                        }
                    }
                ) {
                    Text("Conceder Permissão")
                }
                Spacer(modifier = Modifier.height(12.dp))
                androidx.compose.material3.TextButton(onClick = onClose) {
                    Text("Cancelar", color = Color.White)
                }
            }
        }
    } else {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val screenWidthPx = constraints.maxWidth.toFloat()
            val screenHeightPx = constraints.maxHeight.toFloat()

            // Detect orientation
            val isLandscape = screenWidthPx > screenHeightPx

            // Define scan box dimensions in dp based on orientation - optimized for long linear barcodes
            val boxWidthDp = if (isLandscape) 450.dp else 340.dp
            val boxHeightDp = if (isLandscape) 200.dp else 180.dp

            val density = LocalDensity.current
            val boxWidthPx = with(density) { boxWidthDp.toPx() }
            val boxHeightPx = with(density) { boxHeightDp.toPx() }

            val targetLeft = (screenWidthPx - boxWidthPx) / 2
            val targetTop = (screenHeightPx - boxHeightPx) / 2
            val targetRight = targetLeft + boxWidthPx
            val targetBottom = targetTop + boxHeightPx
            val targetRect = remember(targetLeft, targetTop, targetRight, targetBottom) {
                RectF(targetLeft, targetTop, targetRight, targetBottom)
            }

            val currentOnBarcodeScanned by rememberUpdatedState(onBarcodeScanned)

            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()

                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }

                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setResolutionSelector(
                                androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                                    .setResolutionStrategy(
                                        androidx.camera.core.resolutionselector.ResolutionStrategy(
                                            android.util.Size(1280, 720),
                                            androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                        )
                                    )
                                    .build()
                            )
                            .build()

                        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
                            val mediaImage = imageProxy.image
                            if (mediaImage != null) {
                                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                                val image = InputImage.fromMediaImage(mediaImage, rotationDegrees)
                                
                                val imageWidth = if (rotationDegrees == 90 || rotationDegrees == 270) imageProxy.height else imageProxy.width
                                val imageHeight = if (rotationDegrees == 90 || rotationDegrees == 270) imageProxy.width else imageProxy.height
                                
                                val scale = maxOf(screenWidthPx / imageWidth, screenHeightPx / imageHeight)
                                val scaledImageWidth = imageWidth * scale
                                val scaledImageHeight = imageHeight * scale
                                
                                val imageLeftOnScreen = (screenWidthPx - scaledImageWidth) / 2f
                                val imageTopOnScreen = (screenHeightPx - scaledImageHeight) / 2f

                                scanner.process(image)
                                    .addOnSuccessListener { barcodes ->
                                        if (barcodes.isNotEmpty()) {
                                            for (barcode in barcodes) {
                                                val rawValue = barcode.rawValue
                                                if (rawValue != null && rawValue.isNotBlank()) {
                                                    val boundingBox = barcode.boundingBox
                                                    var isValid = false
                                                    
                                                    if (boundingBox != null) {
                                                        val boxLeft = imageLeftOnScreen + (boundingBox.left * scale)
                                                        val boxTop = imageTopOnScreen + (boundingBox.top * scale)
                                                        val boxRight = imageLeftOnScreen + (boundingBox.right * scale)
                                                        val boxBottom = imageTopOnScreen + (boundingBox.bottom * scale)
                                                        
                                                        val barcodeRect = android.graphics.RectF(boxLeft, boxTop, boxRight, boxBottom)
                                                        
                                                        // Check if the center of the barcode is within the central target box
                                                        if (targetRect.contains(barcodeRect.centerX(), barcodeRect.centerY())) {
                                                            isValid = true
                                                        }
                                                    } else {
                                                        isValid = true
                                                    }
                                                    
                                                    if (isValid) {
                                                        currentOnBarcodeScanned(rawValue)
                                                        break
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    .addOnFailureListener {
                                        // Ignore and continue
                                    }
                                    .addOnCompleteListener {
                                        imageProxy.close()
                                    }
                            } else {
                                imageProxy.close()
                            }
                        }

                        val cameraSelector = when {
                            cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                            cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                            else -> CameraSelector.DEFAULT_BACK_CAMERA
                        }

                        try {
                            cameraProvider.unbindAll()
                            val camera = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                imageAnalysis
                            )
                            cameraControl = camera.cameraControl
                            
                            // Auto-focus setup: Focus on the center of the screen specifically
                            try {
                                val factory = previewView.meteringPointFactory
                                val point = factory.createPoint(screenWidthPx / 2, screenHeightPx / 2)
                                val action = androidx.camera.core.FocusMeteringAction.Builder(point)
                                    .disableAutoCancel() // Keep focusing
                                    .build()
                                cameraControl?.startFocusAndMetering(action)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            
                            try {
                                cameraControl?.enableTorch(isTorchOn)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            // Dynamic torch support watcher
            LaunchedEffect(isTorchOn, cameraControl) {
                try {
                    cameraControl?.enableTorch(isTorchOn)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Outer dark frame with centered scan box (Hollow window using EvenOdd path)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val path = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                    addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            left = targetLeft,
                            top = targetTop,
                            right = targetRight,
                            bottom = targetBottom,
                            cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx())
                        )
                    )
                }
                drawPath(path, color = Color.Black.copy(alpha = 0.5f))
            }

            // Beautiful illuminated borders on top of the hollow window
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(width = boxWidthDp, height = boxHeightDp)
                    .border(width = 2.5.dp, color = Color(0xFF38BDF8), shape = RoundedCornerShape(14.dp))
            ) {
                // Moving bright laser beam animation (up and down)
                val infiniteTransition = rememberInfiniteTransition(label = "laser_animation")
                val translationY by infiniteTransition.animateFloat(
                    initialValue = 4f,
                    targetValue = boxHeightPx - 8f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1400, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "laser_translation"
                )

                // Soft laser glow background
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .graphicsLayer(translationY = translationY - 5f)
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color(0xFFFF0055).copy(alpha = 0.25f),
                                    Color.Transparent
                                )
                            )
                        )
                )

                // Main sharp glowing laser line
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .graphicsLayer(translationY = translationY)
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color(0xFFFF1744).copy(alpha = 0.7f),
                                    Color(0xFFFF0055),
                                    Color(0xFFFFFFFF),
                                    Color(0xFFFF0055),
                                    Color(0xFFFF1744).copy(alpha = 0.7f),
                                    Color.Transparent
                                )
                            )
                        )
                )
            }

            // Instruction text below the scan target (Dynamic warning cards in 'baixa' mode)
            if (scanTarget.startsWith("baixa")) {
                val isEntregador = scanTarget.endsWith("entregador")
                val cardBgColor = if (isEntregador) Color(0xFFFEF3C7) else Color(0xFFD1FAE5) // light amber vs light green
                val cardBorderColor = if (isEntregador) Color(0xFFF59E0B) else Color(0xFF10B981) // amber vs green
                val cardTextColor = if (isEntregador) Color(0xFF78350F) else Color(0xFF065F46) // dark amber vs dark green
                val titleText = if (isEntregador) "⚠️ PASSO 2: QR CODE DO ENTREGADOR" else "🏢 PASSO 1: CRACHÁ DO PORTEIRO"
                val descText = if (isEntregador) 
                    "Porteiro validado com sucesso! Agora, por favor, escaneie o QR Code do Crachá do Entregador." 
                    else "Para iniciar a baixa das encomendas, escaneie o QR Code do seu Crachá de Porteiro."

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 72.dp)
                        .padding(horizontal = 24.dp)
                        .fillMaxWidth()
                        .background(cardBgColor, shape = RoundedCornerShape(12.dp))
                        .border(2.dp, cardBorderColor, shape = RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = titleText,
                        color = cardTextColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = descText,
                        color = cardTextColor.copy(alpha = 0.9f),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                Text(
                    text = "Centralize o código de barras ou QR Code no quadrado acima",
                    color = Color.White,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(top = if (isLandscape) 250.dp else 270.dp)
                        .background(Color.Black.copy(alpha = 0.7f), shape = RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }

            // Top action bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
                    .padding(16.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Fechar Leitor",
                        tint = Color.White
                    )
                }

                Text(
                    text = "CondoEntregas - Leitor Nativo",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), shape = RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )

                // Standard Flashlight button containing flashlight emoji (ultra-compatible)
                IconButton(
                    onClick = { isTorchOn = !isTorchOn },
                    modifier = Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
                ) {
                    Text(
                        text = if (isTorchOn) "💡" else "🔦",
                        fontSize = 20.sp
                    )
                }
            }
        }
    }
}


