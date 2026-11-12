package com.cjhauser.remotedesktop

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
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

private val RdBg = Color(0xFF0C0C14)
private val RdSurface = Color(0xFF151522)
private val RdAccent = Color(0xFF4FC3F7)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                    App()
                }
            }
        }
    }
}

@Composable
fun App() {
    val context = LocalContext.current
    var connections by remember { mutableStateOf<List<Connection>>(ConnectionStore.load(context)) }
    var sessionUrl by remember { mutableStateOf<String?>(null) }

    if (sessionUrl != null) {
        SessionScreen(url = sessionUrl!!) {
            // returning from a session: refresh the saved list (a quick-connect
            // may have been persisted from SessionScreen's leave path)
            connections = ConnectionStore.load(context)
            sessionUrl = null
        }
    } else {
        ConnectionsScreen(
            connections = connections,
            onConnect = { url ->
                sessionUrl = url
            },
            onSave = { name, url ->
                connections = ConnectionStore.upsert(context, Connection(name = name, url = url))
            },
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
    onSave: (String, String) -> Unit,
    onDelete: (Connection) -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
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

        // Quick connect
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
                Icon(Icons.Default.ArrowForward, "Connect")
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
                    "No saved servers yet.\nConnect once and it will be saved.",
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
                        .clickable {
                            onConnect(conn.url)
                        }
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

    if (showAdd) {
        AddConnectionDialog(
            onDismiss = { showAdd = false },
            onSave = { name, url ->
                onSave(name, url)
                showAdd = false
            }
        )
    }
}

@Composable
fun AddConnectionDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("http://") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save connection") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (e.g. Home desktop)") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Server URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cleanUrl = url.trim().trimEnd('/')
                if (cleanUrl.startsWith("http")) onSave(name.ifBlank { cleanUrl }, cleanUrl)
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ----------------------------------------------------------------------------------
// Session screen — fullscreen WebView running the server's noVNC UI
// ----------------------------------------------------------------------------------

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SessionScreen(url: String, onLeave: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity

    var webView by remember { mutableStateOf<WebView?>(null) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var pendingUpload by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val parsed = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        pendingUpload?.onReceiveValue(parsed)
        pendingUpload = null
    }

    // Immersive + keep screen on for the whole session.
    DisposableEffect(url) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val controller = window?.let {
            androidx.core.view.WindowCompat.getInsetsController(it, it.decorView)
        }
        controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    // Back = confirm leave (an accidental back shouldn't drop the session).
    BackHandler { showLeaveConfirm = true }

    fun toggleKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val wv = webView ?: return
        if (wv.hasFocus()) {
            imm.hideSoftInputFromWindow(wv.windowToken, 0)
            wv.clearFocus()
        } else {
            wv.requestFocus()
            imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
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
                                "http", "https" -> false // stay in the session
                                else -> {
                                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                                    true
                                }
                            }
                        }

                        override fun onPageStarted(view: WebView, u: String, favicon: Bitmap?) {
                            // Persist every server the user actually logs into.
                            val root = u.removeSuffix("/").ifBlank { u }
                            val existing = ConnectionStore.load(ctx).any { root.startsWith(it.url) }
                            if (!existing && root.startsWith("http")) {
                                val uri = Uri.parse(root)
                                ConnectionStore.upsert(
                                    ctx,
                                    Connection(
                                        name = uri.host ?: root,
                                        url = root
                                    )
                                )
                            }
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        // Files: noVNC uploads files from the phone to ~/Downloads
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
                }.also { webView = it }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Floating keyboard toggle — the desktop UI has a keyboard button in its
        // toolbar too, but this one works even when the toolbar is hidden.
        FilledIconButton(
            onClick = { toggleKeyboard() },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .navigationBarsPadding()
        ) {
            Icon(Icons.Default.Keyboard, "Toggle keyboard")
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
