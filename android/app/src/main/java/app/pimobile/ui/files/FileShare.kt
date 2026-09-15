package app.pimobile.ui.files

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import app.pimobile.data.FilePaths
import app.pimobile.data.PiApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/** Downloads a server file into the cache and hands it to another app. */
class FileExporter(
    private val context: Context,
    private val api: PiApi,
    private val sessionId: String?,
    private val scope: CoroutineScope,
    private val snackbar: SnackbarHostState,
) {
    var busy by mutableStateOf(false)
        private set

    fun share(path: String) = run(path) { file ->
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType(FilePaths.mime(path))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri(file.name, uri)
        context.startActivity(Intent.createChooser(intent, file.name))
    }

    fun openWith(path: String) = run(path) { file ->
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, FilePaths.mime(path))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(Intent.createChooser(intent, file.name))
        } catch (_: ActivityNotFoundException) {
            snackbar.showSnackbar("No app can open ${file.name}")
        }
    }

    private fun run(path: String, action: suspend (File) -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                // One folder per path, so two files with the same name never collide.
                val dir = File(context.cacheDir, "shared/${Integer.toHexString(path.hashCode())}")
                val file = File(dir, FilePaths.name(path))
                api.download(FilePaths.api(path, "download", sessionId), file)
                action(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Download failed")
            } finally {
                busy = false
            }
        }
    }
}

@Composable
fun rememberFileExporter(api: PiApi, sessionId: String?, snackbar: SnackbarHostState): FileExporter {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(api, sessionId) { FileExporter(context, api, sessionId, scope, snackbar) }
}
