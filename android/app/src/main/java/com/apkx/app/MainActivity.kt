package com.apkx.app

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            val scheme = when {
                android.os.Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
                android.os.Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) { AppScreen() }
        }
    }
}

data class Progress(val title: String, val fraction: Float)

class AppsViewModel(app: Application) : AndroidViewModel(app) {
    var apps by mutableStateOf<List<AppEntry>>(emptyList()); private set
    var loading by mutableStateOf(true); private set
    var progress by mutableStateOf<Progress?>(null); private set
    var message by mutableStateOf<String?>(null)

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        loading = true
        apps = withContext(Dispatchers.IO) { Apps.load(getApplication()) }
        loading = false
    }

    private fun run(block: suspend () -> String) = viewModelScope.launch {
        message = try { block() } catch (e: Exception) { "失敗：${e.message ?: e.javaClass.simpleName}" }
        progress = null
    }

    fun saveTo(app: AppEntry, uri: Uri) = run {
        withContext(Dispatchers.IO) {
            Apps.writeTo(getApplication<Application>().contentResolver, uri, app) { progress = Progress(app.label, it) }
        }
        "已儲存 ${app.fileName}"
    }

    fun saveAll(list: List<AppEntry>, tree: Uri) = run {
        withContext(Dispatchers.IO) {
            list.forEachIndexed { i, app ->
                Apps.writeToTree(getApplication<Application>().contentResolver, tree, app) {
                    progress = Progress("(${i + 1}/${list.size}) ${app.label}", it)
                }
            }
        }
        "已匯出 ${list.size} 個 App"
    }

    fun share(app: AppEntry, onReady: (File) -> Unit) = run {
        val f = withContext(Dispatchers.IO) {
            Apps.writeToCache(getApplication(), app) { progress = Progress(app.label, it) }
        }
        onReady(f)
        ""
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AppScreen(vm: AppsViewModel = viewModel()) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var detail by remember { mutableStateOf<AppEntry?>(null) }
    var pendingSave by remember { mutableStateOf<AppEntry?>(null) }
    val snackbar = remember { SnackbarHostState() }

    val visible = remember(vm.apps, query, showSystem) {
        vm.apps.filter { (showSystem || !it.isSystem) &&
            (query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true)) }
    }

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val app = pendingSave
        if (uri != null && app != null) vm.saveTo(app, uri)
        pendingSave = null
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            vm.saveAll(vm.apps.filter { it.packageName in selected }, uri)
            selected = emptySet()
        }
    }

    LaunchedEffect(vm.message) {
        vm.message?.takeIf { it.isNotEmpty() }?.let { snackbar.showSnackbar(it) }
        vm.message = null
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selected.isEmpty()) {
                TopAppBar(
                    title = { Text("APK 匯出") },
                    actions = {
                        IconButton(onClick = { vm.refresh() }) { Icon(Icons.Default.Refresh, "重新整理") }
                        FilterChip(
                            selected = showSystem, onClick = { showSystem = !showSystem },
                            label = { Text("系統 App") }, modifier = Modifier.padding(end = 8.dp),
                        )
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("已選 ${selected.size} 個") },
                    navigationIcon = { IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Default.Close, "取消") } },
                    actions = {
                        TextButton(onClick = { selected = selected + visible.map { it.packageName } }) { Text("全選") }
                    },
                )
            }
        },
        floatingActionButton = {
            if (selected.isNotEmpty()) ExtendedFloatingActionButton(
                onClick = { pickFolder.launch(null) },
                icon = { Icon(Icons.Default.Download, null) },
                text = { Text("匯出到資料夾") },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("搜尋名稱或套件名稱") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "清除") } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (vm.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("沒有符合的 App") }
            } else {
                Text(
                    "${visible.size} 個 App · 點一下匯出，長按可多選",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(visible, key = { it.packageName }) { app ->
                        val isSel = app.packageName in selected
                        fun toggle() { selected = if (isSel) selected - app.packageName else selected + app.packageName }
                        ListItem(
                            modifier = Modifier.combinedClickable(
                                onClick = { if (selected.isNotEmpty()) toggle() else detail = app },
                                onLongClick = { toggle() },
                            ),
                            colors = if (isSel) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                                     else ListItemDefaults.colors(),
                            leadingContent = { if (isSel) Icon(Icons.Default.CheckCircle, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary) else AppIcon(app.packageName) },
                            headlineContent = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text("${app.versionName} · ${Formatter.formatShortFileSize(ctx, app.totalBytes)}\n${app.packageName}",
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            },
                            trailingContent = { if (app.isSplit) AssistChip(onClick = {}, label = { Text("分割") }) },
                        )
                    }
                }
            }
        }
    }

    detail?.let { app ->
        ModalBottomSheet(onDismissRequest = { detail = null }) {
            DetailSheet(
                app = app,
                onSave = { detail = null; pendingSave = app; createDoc.launch(app.fileName) },
                onShare = {
                    detail = null
                    vm.share(app) { file ->
                        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = app.mimeType
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newRawUri(file.name, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        ctx.startActivity(Intent.createChooser(send, "分享 ${file.name}"))
                    }
                },
            )
        }
    }

    vm.progress?.let { p ->
        AlertDialog(
            onDismissRequest = {}, confirmButton = {},
            title = { Text("匯出中…") },
            text = {
                Column {
                    Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                    Text("${(p.fraction * 100).toInt()}%", Modifier.align(Alignment.End).padding(top = 4.dp))
                }
            },
        )
    }
}

@Composable
fun DetailSheet(app: AppEntry, onSave: () -> Unit, onShare: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app.packageName, 56)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(app.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(16.dp))
        InfoRow("版本", "${app.versionName} (${app.versionCode})")
        InfoRow("大小", Formatter.formatFileSize(ctx, app.totalBytes))
        InfoRow("檔案", if (app.isSplit) "${app.apkFiles.size} 個分割 APK" else "單一 APK")
        InfoRow("輸出", app.fileName)
        if (app.isSplit) {
            Text(
                "這是分割 APK，會打包成 .apks，請用 SAI 等安裝器安裝，或在電腦用 adb install-multiple。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text("儲存")
            }
            OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text("分享")
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) = Row(Modifier.padding(vertical = 4.dp)) {
    Text(label, Modifier.width(56.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value)
}

@Composable
fun AppIcon(packageName: String, sizeDp: Int = 40) {
    val ctx = LocalContext.current
    val px = (sizeDp * ctx.resources.displayMetrics.density).toInt()
    val bmp by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.packageManager.getApplicationIcon(packageName).toBitmap(px, px).asImageBitmap() }.getOrNull()
        }
    }
    Box(Modifier.size(sizeDp.dp)) { bmp?.let { Image(it, null, Modifier.fillMaxSize()) } }
}
