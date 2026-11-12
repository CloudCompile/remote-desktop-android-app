package com.cjhauser.remotedesktop

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Rational
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val RdBg = Color(0xFF0C0C14)
private val RdSurface = Color(0xFF151522)
private val RdAccent = Color(0xFF4FC3F7)

/** Compose-visible PiP state; SessionScreen hides its controls while in PiP. */
object PipState {
    var isInPip by mutableStateOf(false)
}

class MainActivity : ComponentActivity() {

    // Mirrors the Compose session state so lifecycle callbacks can act on it.
    private var sessionUrl: String? = null
    private var webViewRef: android.webkit.WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialUrl = intent?.getStringExtra("url")?.takeIf { it.startsWith("http") }
        sessionUrl = initialUrl
        setContent {
            MaterialTheme(
                darkColorScheme(
                    primary = RdAccent,
                    background = RdBg,
                    surface = RdSurface,
                    onBackground = Color.White,
                    onSurface = Color.White,
                    surfaceVariant = Color(0xFF202030),
                    onSurfaceVariant = Color(0xFFB0B0C0)
                )
            ) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    App(
                        initialSession = initialUrl,
                        onSessionChanged = { sessionUrl = it },
                        onWebViewCreated = { webViewRef = it }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("url")?.takeIf { it.startsWith("http") }?.let {
            sessionUrl = it
            setIntent(intent)
            // Recreate compose state by simply updating the mirror; App() reads
            // the session from its own state, so force a compose restart.
            recreate()
        }
    }

    /** Going Home mid-session keeps the desktop alive in a floating window. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (sessionUrl != null && !isInPictureInPictureMode && canEnterPip()) {
            runCatching { enterPictureInPictureMode(pipParams()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipState.isInPip = isInPictureInPictureMode
    }

    private fun canEnterPip(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** Aspect ratio matches the desktop viewport so PiP shows the whole screen. */
    private fun pipParams(): PictureInPictureParams {
        val wv = webViewRef
        val w = wv?.width ?: 16
        val h = wv?.height ?: 9
        val ratio = if (w > 0 && h > 0) {
            runCatching { Rational(w, h) }.getOrNull() ?: Rational(16, 9)
        } else Rational(16, 9)
        return PictureInPictureParams.Builder()
            .setAspectRatio(ratio)
            .build()
    }
}

// ----------------------------------------------------------------------------------
// App root
// ----------------------------------------------------------------------------------

@Composable
fun App(
    initialSession: String?,
    onSessionChanged: (String?) -> Unit,
    onWebViewCreated: (android.webkit.WebView?) -> Unit
) {
    val context = LocalContext.current
    var connections by remember { mutableStateOf<List<Connection>>(ConnectionStore.load(context)) }
    var sessionUrl by remember { mutableStateOf(initialSession) }

    fun enterSession(url: String) {
        sessionUrl = url
        onSessionChanged(url)
    }

    fun leaveSession() {
        connections = ConnectionStore.load(context)
        sessionUrl = null
        onSessionChanged(null)
    }

    // Long-press the app icon: one shortcut per saved server.
    LaunchedEffect(connections) {
        if (connections.isEmpty()) return@LaunchedEffect
        val icon = IconCompat.createWithResource(context, R.drawable.ic_launcher_foreground)
        val shortcuts = connections.take(4).map { conn ->
            val intent = Intent(context, MainActivity::class.java).apply {
                action = "com.cjhauser.remotedesktop.CONNECT"
                putExtra("url", conn.url)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            ShortcutInfoCompat.Builder(context, conn.url)
                .setShortLabel(conn.name)
                .setIcon(icon)
                .setIntent(intent)
                .build()
        }
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }

    if (sessionUrl != null) {
        SessionScreen(
            url = sessionUrl!!,
            onWebViewCreated = onWebViewCreated,
            onLeave = { leaveSession() }
        )
    } else {
        ConnectionsScreen(
            connections = connections,
            onConnect = { url -> enterSession(url) },
            onDelete = { conn ->
                connections = ConnectionStore.remove(context, conn.url)
            }
        )
    }
}

// ----------------------------------------------------------------------------------
// Connections screen
// ----------------------------------------------------------------------------------

@Composable
fun ConnectionsScreen(
    connections: List<Connection>,
    onConnect: (String) -> Unit,
    onDelete: (Connection) -> Unit
) {
    var quickUrl by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🖥️", fontSize = 20.sp, modifier = Modifier.padding(end = 8.dp))
            Text("Remote Desktop", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Text(
            "Connects to a CloudCompile/remote-desktop server over LAN, Tailscale or Cloudflare.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = quickUrl,
                onValueChange = { quickUrl = it },
                placeholder = { Text("http://192.168.1.10:8090", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = {
                val target = quickUrl.trim().trimEnd('/')
                if (target.isNotBlank()) onConnect(target)
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "Connect")
            }
        }

        Text(
            "Saved connections",
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
        )

        if (connections.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No saved servers yet.\nConnect once and it will be saved — and appear\nas a long-press shortcut on the app icon.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(connections, key = { it.url }) { conn ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onConnect(conn.url) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.DesktopWindows, null, tint = RdAccent)
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(conn.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            conn.url,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { onDelete(conn) }) {
                        Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------------
// Session screen — immersive WebView running the server's noVNC UI
// ----------------------------------------------------------------------------------

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SessionScreen(url: String, onWebViewCreated: (android.webkit.WebView?) -> Unit, onLeave: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity

    var webView by remember { mutableStateOf<android.webkit.WebView?>(null) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var busyClipboard by remember { mutableStateOf(false) }
    var pendingUpload by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val inPip = PipState.isInPip

    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val parsed = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        pendingUpload?.onReceiveValue(parsed)
        pendingUpload = null
    }

    // ---- Immersive set-up: cutout-to-edge, keyboard never resizes the view ----
    DisposableEffect(url) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && window != null) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        val controller = window?.let {
            androidx.core.view.WindowCompat.getInsetsController(it, it.decorView)
        }
        controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && window != null) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
            controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler {
        if (!PipState.isInPip) showLeaveConfirm = true
    }

    fun toggleKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val wv = webView ?: return
        if (imm.isAcceptingText) {
            imm.hideSoftInputFromWindow(wv.windowToken, 0)
        } else {
            wv.requestFocus()
            imm.showSoftInput(wv, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun copyFromDesktop() {
        val wv = webView ?: return
        busyClipboard = true
        CoroutineScope(Dispatchers.IO).launch {
            val text = ClipboardSync.fetchRemote(wv, url)
            withContext(Dispatchers.Main) {
                busyClipboard = false
                if (text != null) {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("desktop", text))
                    Toast.makeText(context, "Copied from desktop", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Desktop clipboard is empty or unreachable", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun pasteToDesktop() {
        val wv = webView ?: return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(context, "Android clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }
        busyClipboard = true
        CoroutineScope(Dispatchers.IO).launch {
            val ok = ClipboardSync.pushRemote(wv, url, text)
            withContext(Dispatchers.Main) {
                busyClipboard = false
                Toast.makeText(
                    context,
                    if (ok) "Pasted to desktop" else "Could not reach the server",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun enterPip() {
        val act = activity ?: return
        runCatching {
            val wv = webView
            val w = wv?.width ?: 16
            val h = wv?.height ?: 9
            val ratio = if (w > 0 && h > 0) {
                runCatching { Rational(w, h) }.getOrNull() ?: Rational(16, 9)
            } else Rational(16, 9)
            act.enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(ratio).build()
            )
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        allowFileAccess = true
                        allowContentAccess = true
                        cacheMode = WebSettings.LOAD_DEFAULT
                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    }
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    setDownloadListener { dlUrl, agent, disposition, mime, _ ->
                        try {
                            val fileName = URLUtil.guessFileName(dlUrl, disposition, mime)
                            val request = DownloadManager.Request(Uri.parse(dlUrl)).apply {
                                setTitle(fileName)
                                addRequestHeader("cookie", CookieManager.getInstance().getCookie(dlUrl).orEmpty())
                                addRequestHeader("User-Agent", agent)
                                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                            }
                            (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                            Toast.makeText(ctx, "Downloading $fileName", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val scheme = request.url.scheme ?: return false
                            return when (scheme) {
                                "http", "https" -> false
                                else -> {
                                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                                    true
                                }
                            }
                        }

                        override fun onPageStarted(view: WebView, u: String, favicon: Bitmap?) {
                            val root = u.removeSuffix("/").ifBlank { u }
                            val existing = ConnectionStore.load(ctx).any { root.startsWith(it.url) }
                            if (!existing && root.startsWith("http")) {
                                val uri = Uri.parse(root)
                                ConnectionStore.upsert(ctx, Connection(name = uri.host ?: root, url = root))
                            }
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onShowFileChooser(
                            view: WebView,
                            callback: ValueCallback<Array<Uri>>,
                            params: FileChooserParams
                        ): Boolean {
                            pendingUpload?.onReceiveValue(null)
                            pendingUpload = callback
                            return runCatching {
                                uploadLauncher.launch(params.createIntent())
                                true
                            }.getOrElse {
                                pendingUpload = null
                                false
                            }
                        }
                    }

                    loadUrl(url)
                }.also {
                    webView = it
                    onWebViewCreated(it)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Floating session controls — hidden while in PiP (nothing to tap there).
        if (!inPip) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SmallFloatingActionButton(onClick = { toggleKeyboard() }) {
                    Icon(Icons.Default.Keyboard, "Toggle keyboard")
                }
                SmallFloatingActionButton(onClick = { if (!busyClipboard) pasteToDesktop() }) {
                    Icon(
                        Icons.Default.ContentPaste,
                        "Paste Android clipboard to desktop",
                        tint = if (busyClipboard) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary
                    )
                }
                SmallFloatingActionButton(onClick = { if (!busyClipboard) copyFromDesktop() }) {
                    Icon(
                        Icons.Default.ContentCopy,
                        "Copy desktop clipboard to phone",
                        tint = if (busyClipboard) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary
                    )
                }
                SmallFloatingActionButton(onClick = { enterPip() }) {
                    Icon(Icons.Default.PictureInPicture, "Continue in picture-in-picture")
                }
            }
        }
    }

    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text("Leave session?") },
            text = { Text("The desktop keeps running on the server; you can reconnect anytime.") },
            confirmButton = {
                TextButton(onClick = { showLeaveConfirm = false; onLeave() }) { Text("Leave") }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirm = false }) { Text("Stay") }
            }
        )
    }
}

/**
 * Native clipboard sync with the remote desktop's control API. The session
 * cookie lives in the WebView's cookie jar; reuse it verbatim as the Cookie
 * header so no login prompt is ever needed.
 */
private object ClipboardSync {

    private fun cookieHeader(webView: android.webkit.WebView, url: String): String =
        CookieManager.getInstance().getCookie(url).orEmpty()

    /** GET /api/clipboard -> "clipboard" field. */
    fun fetchRemote(webView: android.webkit.WebView, baseUrl: String): String? {
        return runCatching {
            val conn = URL("$baseUrl/api/clipboard").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Cookie", cookieHeader(webView, baseUrl))
            conn.setRequestProperty("Accept", "application/json")
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            JSONObject(body).optString("clipboard", "").ifBlank { null }
        }.getOrNull()
    }

    /** POST /api/clipboard/set {"text": ...} -> sets the X clipboard. */
    fun pushRemote(webView: android.webkit.WebView, baseUrl: String, text: String): Boolean {
        return runCatching {
            val conn = URL("$baseUrl/api/clipboard/set").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Cookie", cookieHeader(webView, baseUrl))
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(JSONObject().put("text", text).toString().toByteArray()) }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            ok
        }.getOrDefault(false)
    }
}
