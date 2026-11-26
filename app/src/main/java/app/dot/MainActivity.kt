package app.dot

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// Dot — single activity, Phase 0.
//
// Navigation is a small explicit screen state: Library or Licences.
// No navigation library, no extra dependency. The app launches
// straight into the Library with NO native code loaded — Bridge is
// only touched when a game starts, which is what the startup gate
// proves.
//
// The import launcher is registered HERE, in onCreate, with the
// Activity Result API — never in a composable. A composable-owned
// launcher is only re-registered if that composable recomposes after
// the activity is recreated while the picker is open; activity-level
// registration in onCreate is the recreation-safe pattern, and every
// outcome reports a message.
//
// Phase 0.1: import validates the header and reports the verdict.
// The library does not persist yet — that lands with the library
// store in the next step. No game playback yet — that is Phase 1.
// SPDX-License-Identifier: GPL-3.0-or-later

sealed interface DotScreen {
    data object Library : DotScreen
    data object Licences : DotScreen
}

class MainActivity : ComponentActivity() {

    /** Latest import outcome, shown under the library header. */
    private var importMessage by mutableStateOf<String?>(null)

    private var screen by mutableStateOf<DotScreen>(DotScreen.Library)

    private lateinit var pickGame: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickGame = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            importMessage = if (uri == null) {
                "No file picked."
            } else {
                importGame(uri)
            }
        }
        setContent {
            MaterialTheme {
                when (val current = screen) {
                    DotScreen.Library -> LibraryScreen(
                        message = importMessage,
                        onAddGame = { pickGame.launch("*/*") },
                        onLicences = { screen = DotScreen.Licences },
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
                val name = verdict.title.ifBlank { "Untitled" }
                if (verdict.dualMode) {
                    "$name — dual-mode game, Dot would play it in DMG mode."
                } else {
                    "$name — DMG game, Dot would accept this."
                }
            }
            is DmgHeader.Verdict.Refused -> verdict.reason
        }
    }
}

@Composable
private fun LibraryScreen(
    message: String?,
    onAddGame: () -> Unit,
    onLicences: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Text("Dot", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Original Game Boy, remastered",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAddGame) { Text("Add game") }
        Spacer(Modifier.height(16.dp))
        Text("No games yet — add a game to start.")
        if (message != null) {
            Spacer(Modifier.height(16.dp))
            Text(message)
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onLicences) { Text("Licences") }
    }
}

@Composable
private fun LicencesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val notice = rememberNotice(context)
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
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
