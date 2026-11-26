package app.dot

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// Dot — single activity.
//
// Navigation is a small explicit screen state: Library, Player,
// Settings, or Licences. No navigation library, no extra dependency.
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
// Phase 1: import copies the ROM into filesDir/games/<sha256>.gb,
// stores the filename stem in the library index from day one (cover
// matching needs it), and tapping a game opens the player.
// Copyright (c) 2026 Dot contributors. All rights reserved.

sealed interface DotScreen {
    data object Library : DotScreen
    data class Player(val entry: LibraryEntry) : DotScreen
    data object Settings : DotScreen
    data object Licences : DotScreen
}

class MainActivity : ComponentActivity() {

    /** Latest import outcome, shown under the library header. */
    private var importMessage by mutableStateOf<String?>(null)

    private var screen by mutableStateOf<DotScreen>(DotScreen.Library)

    private var library by mutableStateOf<List<LibraryEntry>>(emptyList())

    private lateinit var pickGame: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = Store.loadLibrary(filesDir)
        pickGame = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            importMessage = if (uri == null) {
                "No file picked."
            } else {
                importGame(uri)
            }
        }
        setContent {
            DotTheme {
                when (val current = screen) {
                    DotScreen.Library -> LibraryScreen(
                        entries = library,
                        message = importMessage,
                        onAddGame = { pickGame.launch("*/*") },
                        onOpenGame = { entry ->
                            screen = DotScreen.Player(entry)
                        },
                        onSettings = { screen = DotScreen.Settings },
                        onLicences = { screen = DotScreen.Licences },
                    )
                    is DotScreen.Player -> GameScreen(
                        entry = current.entry,
                        onExit = {
                            library = Store.loadLibrary(filesDir)
                            screen = DotScreen.Library
                        },
                    )
                    DotScreen.Settings -> SettingsScreen(
                        onBack = { screen = DotScreen.Library },
                    )
                    DotScreen.Licences -> LicencesScreen(
                        onBack = { screen = DotScreen.Library },
                    )
                }
            }
        }
    }

    private fun importGame(uri: Uri): String {
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
            } ?: return "Could not open that file."
        } catch (e: Exception) {
            return "Could not read that file: ${e.message}"
        }
        return when (val verdict = DmgHeader.validate(bytes)) {
            is DmgHeader.Verdict.Accepted -> {
                val sha = Store.sha256Hex(bytes)
                val existing = library.any { it.sha256 == sha }
                if (existing) {
                    return "${verdict.title} is already in your library."
                }
                val romFile = Store.romFile(filesDir, sha)
                if (!Store.atomicWrite(romFile, bytes)) {
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
                    return "Could not save the library."
                }
                library = updated
                if (verdict.dualMode) {
                    "${entry.title} added — dual-mode game, Dot plays it in DMG mode."
                } else {
                    "${entry.title} added."
                }
            }
            is DmgHeader.Verdict.Refused -> verdict.reason
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
    message: String?,
    onAddGame: () -> Unit,
    onOpenGame: (LibraryEntry) -> Unit,
    onSettings: () -> Unit,
    onLicences: () -> Unit,
) {
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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenGame(entry) }
                            .padding(vertical = 8.dp),
                    ) {
                        Text(
                            entry.title,
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
