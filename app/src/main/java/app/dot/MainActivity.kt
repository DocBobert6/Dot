package app.dot

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// Dot — single activity.
//
// Navigation is an explicit back stack (SHELL-SPEC §1.10): Library,
// Player, Settings, Licences, Developer, Trace. No navigation
// library, no extra dependency. A Player entry is keyed by the
// game's SHA, not title or file name. The stack survives activity
// recreation via string keys: `library`, `player:<sha>`,
// `settings`, `licences`, `developer`, `trace`. On restore, a
// Player key whose game is gone drops that entry and the stack
// falls back to Library; unknown keys are dropped; an empty result
// is Library.
//
// The app launches straight into the Library with NO native code
// loaded — Bridge is only touched when a game starts, which is what
// the startup gate proves.
//
// The import launcher is registered HERE, in onCreate, with the
// Activity Result API — never in a composable. A composable-owned
// launcher is only re-registered if that composable recomposes after
// the activity is recreated while the picker is open; activity-level
// registration in onCreate is the recreation-safe pattern, and every
// outcome reports a message.
//
// The crash handler is installed FIRST in onCreate (§9), next to
// the trace init. Every import step is a trace line (§12.1).
// Copyright (c) 2026 Dot contributors. All rights reserved.

sealed interface DotScreen {
    data object Library : DotScreen
    data class Player(val entry: LibraryEntry, val openEditor: Boolean = false) : DotScreen
    data object Settings : DotScreen
    data object Licences : DotScreen
    data object Trace : DotScreen

    /** The stack-persistence key (§1.10). The editor flag is
     *  transient UI state and is deliberately NOT part of the key.
     *  (Developer joins the stack with the Developer screen in
     *  batch 5b; a saved "developer" key is dropped as unknown
     *  until then.) */
    fun key(): String = when (this) {
        Library -> "library"
        is Player -> "player:${entry.sha256}"
        Settings -> "settings"
        Licences -> "licences"
        Trace -> "trace"
    }
}

class MainActivity : ComponentActivity() {

    /** The transient message line under the library header — the
     *  import outcome, or the double-back-to-exit prompt. */
    private var message by mutableStateOf<String?>(null)

    /** The back stack (§1.10). Top of the stack is the screen. */
    private var stack by mutableStateOf<List<DotScreen>>(listOf(DotScreen.Library))

    private var library by mutableStateOf<List<LibraryEntry>>(emptyList())

    /** Per-game presets, keyed by SHA (rename lives here, §12.3). */
    private var presets by mutableStateOf<Map<String, Presets.GamePreset>>(emptyMap())

    /** Monotonic uptime of the last Library back press (§1.10). */
    private var exitArmedAt = 0L

    /**
     * The gamepad currently driving the player (SHELL-SPEC §5), or
     * null outside the player. The player screen installs it on
     * entry and clears it on exit, so the pad never hijacks keys on
     * the library or settings screens.
     */
    var gamepadHandler: Gamepad? = null

    private lateinit var pickGame: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        // The crash handler goes first (§9), next to the trace init.
        CrashReport.install(filesDir)
        super.onCreate(savedInstanceState)
        reloadLibrary()
        stack = restoreStack(
            savedInstanceState?.getStringArrayList(NAV_STACK_KEY),
        )
        pickGame = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            message = if (uri == null) {
                ImportTrace.append(filesDir, "Pick dismissed (no file).")
                "No file picked."
            } else {
                importGame(uri)
            }
        }
        setContent {
            DotTheme {
                // The double-back prompt clears itself when the
                // 2000 ms window lapses (§1.10).
                LaunchedEffect(message) {
                    if (message == EXIT_PROMPT) {
                        delay(EXIT_WINDOW_MS)
                        if (message == EXIT_PROMPT) message = null
                    }
                }
                // Activity-level back: pops the stack, or arms the
                // double-back exit on the Library. The player's own
                // BackHandler (pause toggle) wins while the player
                // is showing — it is deeper in the composition.
                BackHandler {
                    if (stack.size > 1) {
                        pop()
                    } else {
                        val now = SystemClock.uptimeMillis()
                        if (now - exitArmedAt < EXIT_WINDOW_MS) {
                            finish()
                        } else {
                            exitArmedAt = now
                            message = EXIT_PROMPT
                        }
                    }
                }
                when (val current = stack.last()) {
                    DotScreen.Library -> LibraryScreen(
                        entries = library,
                        presets = presets,
                        message = message,
                        onAddGame = { pickGame.launch("*/*") },
                        onOpenGame = { entry ->
                            push(DotScreen.Player(entry))
                        },
                        onMoveControls = { entry ->
                            push(DotScreen.Player(entry, openEditor = true))
                        },
                        onRename = { entry, name ->
                            renameGame(entry, name)
                        },
                        onRemove = { entry ->
                            removeGame(entry)
                        },
                        onSettings = { push(DotScreen.Settings) },
                        onLicences = { push(DotScreen.Licences) },
                    )
                    is DotScreen.Player -> GameScreen(
                        entry = current.entry,
                        onExit = { pop() },
                        startWithEditorOpen = current.openEditor,
                    )
                    DotScreen.Settings -> SettingsScreen(
                        onBack = { pop() },
                    )
                    DotScreen.Licences -> LicencesScreen(
                        onBack = { pop() },
                    )
                    DotScreen.Trace -> TraceScreen(
                        traceText = ImportTrace.read(filesDir),
                        onClear = {
                            ImportTrace.clear(filesDir)
                            message = "Import trace cleared."
                        },
                        onBack = { pop() },
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(
            NAV_STACK_KEY,
            ArrayList(stack.map { it.key() }),
        )
    }

    private fun push(screen: DotScreen) {
        exitArmedAt = 0L
        stack = stack + screen
    }

    private fun pop() {
        if (stack.size <= 1) return
        val leaving = stack.last()
        exitArmedAt = 0L
        stack = stack.dropLast(1)
        // Leaving the player reloads the library (the session may
        // have written saves; the list shows what is on disk).
        if (leaving is DotScreen.Player) reloadLibrary()
    }

    private fun reloadLibrary() {
        library = Store.loadLibrary(filesDir)
        presets = library.associate { it.sha256 to Presets.load(filesDir, it.sha256) }
    }

    /**
     * Restores the stack from saved keys. A Player key whose game
     * is gone drops that entry; unknown keys are dropped; an empty
     * result is Library (§1.10).
     */
    private fun restoreStack(keys: ArrayList<String>?): List<DotScreen> {
        if (keys.isNullOrEmpty()) return listOf(DotScreen.Library)
        val restored = ArrayList<DotScreen>()
        for (key in keys) {
            when {
                key == "library" -> restored.add(DotScreen.Library)
                key == "settings" -> restored.add(DotScreen.Settings)
                key == "licences" -> restored.add(DotScreen.Licences)
                key == "trace" -> restored.add(DotScreen.Trace)
                key.startsWith("player:") -> {
                    val sha = key.removePrefix("player:")
                    val entry = library.firstOrNull { it.sha256 == sha }
                    if (entry != null) restored.add(DotScreen.Player(entry))
                }
                else -> Unit
            }
        }
        if (restored.isEmpty()) return listOf(DotScreen.Library)
        // The stack never holds two Libraries, and Library is the
        // bottom when present.
        val deduped = restored.distinct()
        return if (deduped.first() is DotScreen.Library) {
            deduped
        } else {
            listOf(DotScreen.Library) + deduped
        }
    }

    private fun renameGame(entry: LibraryEntry, name: String) {
        val trimmed = name.trim()
        val preset = Presets.load(filesDir, entry.sha256)
            .copy(rename = trimmed.ifBlank { null })
        if (Presets.save(filesDir, entry.sha256, preset)) {
            presets = presets + (entry.sha256 to preset)
            message = if (trimmed.isBlank()) {
                "Rename cleared — showing the header title."
            } else {
                "Renamed to \"$trimmed\"."
            }
        } else {
            message = "Rename failed."
        }
    }

    private fun removeGame(entry: LibraryEntry) {
        val name = Presets.displayName(entry, presets[entry.sha256] ?: Presets.GamePreset())
        if (!Store.removeGame(filesDir, entry.sha256)) {
            message = "Could not remove $name."
            return
        }
        val updated = library.filter { it.sha256 != entry.sha256 }
        if (!Store.saveLibrary(filesDir, updated)) {
            message = "Removed $name, but the library could not be saved."
            return
        }
        reloadLibrary()
        message = "$name removed."
    }

    private fun importGame(uri: Uri): String {
        ImportTrace.append(filesDir, "Picked: ${displayNameOf(uri) ?: uri}")
        val bytes = try {
            contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(DmgHeader.MAX_SIZE + 1)
                var read = 0
                while (read <= DmgHeader.MAX_SIZE) {
                    val n = input.read(buffer, read, buffer.size - read)
                    if (n < 0) break
                    read += n
                }
                buffer.copyOf(read)
            } ?: run {
                ImportTrace.append(filesDir, "STREAM_UNAVAILABLE: could not open the file.")
                return "Could not open that file."
            }
        } catch (e: Exception) {
            ImportTrace.append(filesDir, "STREAM_UNAVAILABLE: ${e.message}")
            return "Could not read that file: ${e.message}"
        }
        if (bytes.size > DmgHeader.MAX_SIZE) {
            ImportTrace.append(
                filesDir,
                "TOO_LARGE: ${bytes.size} bytes (cap ${DmgHeader.MAX_SIZE}).",
            )
            return "That file is too large for a Game Boy ROM (over 8 MB)."
        }
        return when (val verdict = DmgHeader.validate(bytes)) {
            is DmgHeader.Verdict.Accepted -> {
                val sha = Store.sha256Hex(bytes)
                val existing = library.any { it.sha256 == sha }
                if (existing) {
                    ImportTrace.append(filesDir, "Duplicate: ${verdict.title} is already in the library.")
                    return "${verdict.title} is already in your library."
                }
                val romFile = Store.romFile(filesDir, sha)
                if (!Store.atomicWrite(romFile, bytes)) {
                    ImportTrace.append(filesDir, "WRITE_ERROR: could not save ${verdict.title}.")
                    return "Could not save ${verdict.title}."
                }
                val displayName = displayNameOf(uri) ?: "game.gb"
                val stem = displayName.substringBeforeLast('.', displayName)
                val entry = LibraryEntry(
                    sha256 = sha,
                    title = verdict.title.ifBlank { stem },
                    sourceName = stem,
                    dualMode = verdict.dualMode,
                    addedMs = System.currentTimeMillis(),
                )
                val updated = library + entry
                if (!Store.saveLibrary(filesDir, updated)) {
                    romFile.delete()
                    ImportTrace.append(filesDir, "WRITE_ERROR: could not save the library index.")
                    return "Could not save the library."
                }
                reloadLibrary()
                ImportTrace.append(
                    filesDir,
                    "Added: ${entry.title} (${bytes.size} bytes)" +
                        if (verdict.dualMode) " — dual-mode, DMG mode." else ".",
                )
                if (verdict.dualMode) {
                    "${entry.title} added — dual-mode game, Dot plays it in DMG mode."
                } else {
                    "${entry.title} added."
                }
            }
            is DmgHeader.Verdict.Refused -> {
                ImportTrace.append(filesDir, "BAD_HEADER: ${verdict.reason}")
                verdict.reason
            }
        }
    }

    private fun displayNameOf(uri: Uri): String? {
        return try {
            contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val NAV_STACK_KEY = "dot_nav_stack"
        const val EXIT_PROMPT = "Press back again to exit"
        const val EXIT_WINDOW_MS = 2000L
    }
}

// Dot's in-app theme: silver and black, per the decided skin.
// Charcoal is the primary (near-black base), silver the container
// (faceplate chrome), white the text on charcoal.
@Composable
private fun DotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF1A1A1A),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFC0C0C0),
            onPrimaryContainer = Color(0xFF1A1A1A),
            background = Color(0xFFFFFFFF),
            onBackground = Color(0xFF1A1A1A),
        ),
        content = content,
    )
}

@Composable
private fun LibraryScreen(
    entries: List<LibraryEntry>,
    presets: Map<String, Presets.GamePreset>,
    message: String?,
    onAddGame: () -> Unit,
    onOpenGame: (LibraryEntry) -> Unit,
    onMoveControls: (LibraryEntry) -> Unit,
    onRename: (LibraryEntry, String) -> Unit,
    onRemove: (LibraryEntry) -> Unit,
    onSettings: () -> Unit,
    onLicences: () -> Unit,
) {
    // Long-press menu state: the entry the menu is open for, the
    // rename dialog target, and the remove-confirmation target.
    var menuEntry by remember { mutableStateOf<LibraryEntry?>(null) }
    var renameEntry by remember { mutableStateOf<LibraryEntry?>(null) }
    var removeEntry by remember { mutableStateOf<LibraryEntry?>(null) }
    var renameText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Text("Dot", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Original Game Boy, remastered",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAddGame) { Text("Add game") }
        if (message != null) {
            Spacer(Modifier.height(16.dp))
            Text(message)
        }
        Spacer(Modifier.height(16.dp))
        if (entries.isEmpty()) {
            Text("No games yet — add a game to start.")
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.sha256 }) { entry ->
                    val displayName = Presets.displayName(
                        entry,
                        presets[entry.sha256] ?: Presets.GamePreset(),
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onOpenGame(entry) },
                                onLongClick = { menuEntry = entry },
                            )
                            .padding(vertical = 8.dp),
                    ) {
                        Text(
                            displayName,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            entry.sourceName +
                                if (entry.dualMode) " · dual-mode, DMG mode" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onSettings) { Text("Settings") }
            Button(onClick = onLicences) { Text("Licences") }
        }
    }

    // Long-press menu (§12.2): Rename, Move controls, Remove.
    // (Set custom cover and Retry cover download join it with the
    // cover-art work in batch 6.)
    menuEntry?.let { entry ->
        val displayName = Presets.displayName(
            entry,
            presets[entry.sha256] ?: Presets.GamePreset(),
        )
        AlertDialog(
            onDismissRequest = { menuEntry = null },
            title = { Text(displayName) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            menuEntry = null
                            renameText = displayName
                            renameEntry = entry
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Rename") }
                    TextButton(
                        onClick = {
                            menuEntry = null
                            onMoveControls(entry)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Move controls") }
                    TextButton(
                        onClick = {
                            menuEntry = null
                            removeEntry = entry
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Remove") }
                }
            },
            confirmButton = {},
        )
    }

    renameEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { renameEntry = null },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRename(entry, renameText)
                        renameEntry = null
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameEntry = null }) { Text("Cancel") }
            },
        )
    }

    removeEntry?.let { entry ->
        val displayName = Presets.displayName(
            entry,
            presets[entry.sha256] ?: Presets.GamePreset(),
        )
        AlertDialog(
            onDismissRequest = { removeEntry = null },
            title = { Text("Remove $displayName?") },
            text = {
                Text(
                    "This deletes the game, its saves, states and " +
                        "covers. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemove(entry)
                        removeEntry = null
                    },
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { removeEntry = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("dot-settings", Context.MODE_PRIVATE)
    }
    var fpsCounter by remember {
        mutableStateOf(prefs.getBoolean("fps_counter", false))
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(24.dp))
        Text("Display", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("FPS counter")
            Switch(
                checked = fpsCounter,
                onCheckedChange = { checked ->
                    fpsCounter = checked
                    prefs.edit().putBoolean("fps_counter", checked).apply()
                },
            )
        }
        Text(
            "Shows the live delivered frame rate at the top of the " +
                "screen while playing. Default off.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.weight(1f))
        Button(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun LicencesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val notice = rememberNotice(context)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Text("Licences", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(16.dp))
        Text(
            text = notice,
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun TraceScreen(
    traceText: String,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Text("Import trace", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "A timestamped log of every Add game step — kept for " +
                "screenshotting after a failed import.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = traceText.ifBlank { "No imports yet." },
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onClear) { Text("Clear trace") }
            Button(onClick = onBack) { Text("Back") }
        }
    }
}

@Composable
private fun rememberNotice(context: android.content.Context): String {
    val state = androidx.compose.runtime.remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        state.value = try {
            context.resources.openRawResource(R.raw.notice).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "Licences unavailable: ${e.message}"
        }
    }
    return state.value
}
