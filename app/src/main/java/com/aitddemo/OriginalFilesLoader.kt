package com.aitddemo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aitddemo.original.GameFiles
import com.aitddemo.original.TreeFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** State of reading original game files for a screen. */
sealed interface OriginalState<out T> {
    data object Loading : OriginalState<Nothing>
    class Ready<T>(val data: T) : OriginalState<T>
    class Failed(val message: String) : OriginalState<Nothing>
}

class OriginalFilesLoader<T>(val state: OriginalState<T>, val chooseFolder: () -> Unit)

/** Results already read, by key: the intro reads the attic data in advance, so the attic opens without waiting. Cleared when another folder is chosen. */
private val readCache = java.util.concurrent.ConcurrentHashMap<String, Any>()

private const val PREFS = "aitd_original_files"
private const val KEY_TREE = "tree_uri"

/**
 * Reads data for a screen from the original game files with [read]; [missing] lists the files the screen needs that are not in the
 * folder. The folder (for example Download/alone) is chosen once through the system folder picker and remembered; nothing is read
 * from the app's own resources.
 */
@Composable
fun <T : Any> rememberOriginalFiles(missing: (GameFiles) -> List<String>, cacheKey: String? = null, read: (GameFiles) -> T): OriginalFilesLoader<T> {
    val ctx = LocalContext.current
    val currentMissing by rememberUpdatedState(missing)
    val currentRead by rememberUpdatedState(read)
    @Suppress("UNCHECKED_CAST")
    var state by remember { mutableStateOf<OriginalState<T>>((cacheKey?.let { readCache[it] } as T?)?.let { OriginalState.Ready(it) } ?: OriginalState.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // access for this session still works
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TREE, uri.toString()).apply()
            readCache.clear()
            attempt++
        }
    }
    LaunchedEffect(attempt) {
        if (state is OriginalState.Ready) return@LaunchedEffect // already there (read earlier)
        state = OriginalState.Loading
        val loaded = withContext(Dispatchers.IO) { loadOriginal(ctx, currentMissing, currentRead) }
        if (cacheKey != null && loaded is OriginalState.Ready) readCache[cacheKey] = loaded.data
        state = loaded
    }
    val hint = remember { DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/alone") }
    return OriginalFilesLoader(state) { launcher.launch(hint) }
}

private fun <T> loadOriginal(ctx: Context, missing: (GameFiles) -> List<String>, read: (GameFiles) -> T): OriginalState<T> {
    val saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE, null)
        ?: return OriginalState.Failed("Die Originaldateien von Alone in the Dark wurden noch nicht ausgewählt.\nBitte den Ordner mit den Spieldateien wählen (zum Beispiel Download/alone).")
    return try {
        val files = TreeFiles(ctx, Uri.parse(saved))
        val absent = missing(files)
        if (absent.isNotEmpty()) {
            return OriginalState.Failed("In diesem Ordner fehlen Originaldateien:\n" + absent.joinToString(", ") + "\nBitte den Ordner mit den Spieldateien wählen.")
        }
        OriginalState.Ready(read(files))
    } catch (e: OriginalFilesException) {
        OriginalState.Failed(e.message ?: "Die Originaldateien konnten nicht gelesen werden.")
    } catch (e: SecurityException) {
        OriginalState.Failed("Kein Zugriff auf den gewählten Ordner mehr. Bitte den Ordner erneut wählen.")
    } catch (e: Exception) {
        OriginalState.Failed("Die Originaldateien konnten nicht gelesen werden (${e.javaClass.simpleName}). Bitte den Ordner erneut wählen.")
    }
}

/** Message screen shown when the original files are missing or unreadable. */
@Composable
fun OriginalFilesErrorScreen(message: String, onChooseFolder: () -> Unit, onExit: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black).padding(24.dp)) {
        Column(Modifier.align(Alignment.Center), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText("Originaldateien nicht verfügbar", style = TextStyle(color = Color(0xFFF2E4B8), fontSize = 22.sp, textAlign = TextAlign.Center))
            BasicText(message, style = TextStyle(color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center))
            Box(Modifier.background(Color(0xFF5A3A1E)).clickable { onChooseFolder() }.padding(horizontal = 18.dp, vertical = 10.dp)) {
                BasicText("Ordner wählen", style = TextStyle(color = Color.White, fontSize = 18.sp))
            }
        }
        BackButton(onClick = onExit)
    }
}
