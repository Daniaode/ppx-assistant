package com.akari.ppx.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import io.noties.markwon.Markwon
import com.akari.ppx.BuildConfig
import com.akari.ppx.utils.AssistantRelease
import com.akari.ppx.utils.AssistantUpdates
import com.akari.ppx.utils.UpdateDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val UPDATE_PREFS = "update_prompt"
private const val KEY_PROMPTED = "prompted_version"

class AssistantUpdateState {
    var checking by mutableStateOf(false)
        private set
    var release by mutableStateOf<AssistantRelease?>(null)
    var downloading by mutableStateOf(false)
        private set
    var progress by mutableStateOf(0f)
        private set

    suspend fun check(manual: Boolean, context: Context, notify: (String) -> Unit) {
        if (checking) return
        checking = true
        try {
            val latest = withContext(Dispatchers.IO) { AssistantUpdates.latest() }
            if (latest != null && AssistantUpdates.isNewer(latest.version, BuildConfig.VERSION_NAME)) {
                // 自动检查只提示一次：同一个版本提示过就不再弹
                if (manual || !alreadyPrompted(context, latest.version)) {
                    release = latest
                    if (!manual) markPrompted(context, latest.version)
                }
            } else if (manual) {
                notify("已是最新版本")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (manual) notify("检查更新失败，请稍后重试")
        } finally {
            checking = false
        }
    }

    /** 内置下载：直链失败自动切加速代理。 */
    fun download(context: Context, onResult: (Boolean, String) -> Unit) {
        val target = release ?: return
        val url = target.downloadUrl
        if (url.isNullOrBlank()) {
            onResult(false, "该版本没有可下载的 APK")
            return
        }
        if (downloading) return
        downloading = true
        progress = 0f
        Thread {
            var lastReported = -1
            val file = UpdateDownloader.download(context, url) { done, total ->
                if (total > 0) {
                    val p = (done * 100 / total).toInt()
                    if (p != lastReported) {
                        lastReported = p
                        progress = done.toFloat() / total
                    }
                }
            }
            downloading = false
            if (file == null) {
                onResult(false, "下载失败，直链与加速代理均不可用")
            } else {
                onResult(installApk(context, file), "安装包已就绪，请在系统弹窗中确认安装")
            }
        }.start()
    }
}

private fun alreadyPrompted(context: Context, version: String): Boolean =
    context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
        .getString(KEY_PROMPTED, null) == version

private fun markPrompted(context: Context, version: String) {
    context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
        .edit().putString(KEY_PROMPTED, version).apply()
}

private fun installApk(context: Context, file: java.io.File): Boolean = runCatching {
    val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.updatefile", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    true
}.getOrDefault(false)

@Composable
fun rememberAssistantUpdateCheck(): Pair<Boolean, () -> Unit> {
    val context = LocalContext.current
    val state = remember { AssistantUpdateState() }
    val scope = rememberCoroutineScope()
    val notify: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    LaunchedEffect(Unit) { state.check(false, context, notify) }
    state.release?.let { release ->
        AlertDialog(
            onDismissRequest = { if (!state.downloading) state.release = null },
            title = { Text("发现新版本 ${release.version}") },
            text = {
                Column {
                    ReleaseMarkdown(release.notes)
                    if (state.downloading) {
                        Text("正在下载… ${(state.progress * 100).toInt()}%")
                        LinearProgressIndicator(
                            progress = state.progress,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !state.downloading,
                    onClick = {
                        state.download(context) { ok, message ->
                            scope.launch {
                                notify(message)
                                if (ok) state.release = null
                            }
                        }
                    }
                ) { Text(if (state.downloading) "下载中…" else "下载并安装") }
            },
            dismissButton = {
                TextButton(
                    enabled = !state.downloading,
                    onClick = { state.release = null }
                ) { Text("暂不更新") }
            }
        )
    }
    return state.checking to {
        scope.launch { state.check(true, context, notify) }
    }
}

@Composable
fun ReleaseMarkdown(markdown: String) {
    val context = LocalContext.current
    val color = MaterialTheme.colors.onSurface.toArgb()
    AndroidView(
        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
        factory = { ctx ->
            TextView(ctx).apply {
                movementMethod = LinkMovementMethod.getInstance()
            }
        },
        update = { view ->
            val markwon = Markwon.builder(context).build()
            markwon.setMarkdown(view, markdown)
            view.setTextColor(color)
        }
    )
}
