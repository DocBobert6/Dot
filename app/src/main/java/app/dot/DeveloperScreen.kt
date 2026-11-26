package app.dot

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// Copyright (c) 2026 Dot contributors. All rights reserved.
//
// Dot — Developer screen (SHELL-SPEC §7.2). The benchmark harness
// UI: the live rolling readout, Start/Stop capture, the last
// capture's result lines, Export, the Import-trace entry, the
// "Chain under test" block and the "Build info" block. Polls the
// harness twice a second.
//
// A capture can be armed here before a game starts: the clocks run
// with no game, and the session retargets the armed capture at the
// game when it launches (§7.2: the game is re-read at finish).

@Composable
fun DeveloperScreen(
    entries: List<LibraryEntry>,
    onBack: () -> Unit,
    onOpenTrace: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("dot-settings", Context.MODE_PRIVATE)
    }
    var notice by remember { mutableStateOf<String?>(null) }
    // Polled state, refreshed twice a second.
    var live by remember { mutableStateOf<Benchmark.Stats?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var lastCapture by remember { mutableStateOf<Benchmark.Capture?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            live = Benchmark.liveStats()
            capturing = Benchmark.isCapturing()
            progress = Benchmark.captureProgress()
            lastCapture = Benchmark.lastCapture
            delay(500)
        }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(4000)
            notice = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Developer", style = MaterialTheme.typography.headlineLarge)
            TextButton(onClick = onBack) { Text("Back") }
        }

        // -- Live rolling readout ------------------------------------
        Text(
            "Live",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
        )
        val liveStats = live
        if (liveStats == null) {
            Text("No frames yet — start a game first.")
        } else {
            Text(
                "%.1f fps avg · p95 %.1f ms · worst %.1f ms · %d drops"
                    .format(
                        liveStats.avgFps, liveStats.p95Ms,
                        liveStats.worstMs, liveStats.drops,
                    ),
                fontFamily = FontFamily.Monospace,
            )
        }

        // -- Capture ---------------------------------------------------
        Text(
            "Benchmark capture",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
        )
        if (capturing) {
            Text("Capturing… $progress/${Benchmark.CAPTURE_FRAMES} frames")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Start is enabled only when no capture is running (§7.2).
            Button(
                onClick = {
                    // Armed with no game: the session retargets it
                    // at the game when one launches.
                    notice = if (Benchmark.startCapture("", "armed from Developer")) {
                        "Capture armed — start a game."
                    } else {
                        "A capture is already running."
                    }
                },
                enabled = !capturing,
            ) {
                Text(
                    if (capturing) {
                        "Capturing… $progress/${Benchmark.CAPTURE_FRAMES}"
                    } else {
                        "Start capture"
                    },
                )
            }
            Button(
                onClick = {
                    notice = when (val outcome = Benchmark.stopCapture()) {
                        is Benchmark.StopOutcome.Kept -> {
                            val frames = outcome.capture.delivered.frames
                            "Capture stopped — $frames frames (partial)."
                        }
                        Benchmark.StopOutcome.Discarded ->
                            "Capture discarded — under 60 frames"
                        Benchmark.StopOutcome.NotRunning ->
                            "No capture running."
                    }
                    lastCapture = Benchmark.lastCapture
                },
                enabled = capturing,
            ) { Text("Stop") }
        }
        HelpText(
            "600 delivered frames (~10 s at 59.73 fps). A manual " +
                "stop keeps the partial result only with at least 60 " +
                "frames. No auto-start, no auto-export.",
        )

        // -- Last capture ----------------------------------------------
        val capture = lastCapture
        Text(
            "Last capture",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
        )
        if (capture == null) {
            Text("No capture yet.")
        } else {
            val gameTitle = entries.firstOrNull { it.sha256 == capture.gameSha }
                ?.title ?: "unknown game"
            val d = capture.delivered
            val p = capture.presented
            Text("Game: $gameTitle")
            Text(
                "Frames: ${d.frames}" +
                    if (!capture.completed) " (stopped early — partial)" else "",
            )
            Text("Duration: %.0f ms".format(capture.durationMs))
            Text("Avg: %.2f fps".format(d.avgFps))
            Text("1% low: %.2f fps".format(d.low1PctFps))
            Text("p95: %.2f ms".format(d.p95Ms))
            Text("p99: %.2f ms".format(d.p99Ms))
            Text("Worst: %.2f ms".format(d.worstMs))
            Text("Dropped: ${d.drops} (%.2f%%)".format(d.dropPct))
            Text("Presented avg: %.2f fps".format(p.avgFps))
            Text("Presented p95: %.2f ms".format(p.p95Ms))
            Text("Presented dropped: ${p.drops} (%.2f%%)".format(p.dropPct))
            Text("Presented frames: ${p.frames}")
            Text("Draw path: ${capture.chainLabel}")
            Text(
                "Gate: " +
                    if (Benchmark.gatePass(capture)) "PASS" else "FAIL",
            )
            Spacer(Modifier.height(8.dp))
            // Export is enabled only once a completed capture exists
            // AND no capture is running — exporting while one runs
            // would hand over the previous capture while the new
            // one is still recording (§7.2).
            Button(
                onClick = {
                    notice = exportBenchmarkNotice(context, capture)
                },
                enabled = !capturing,
            ) { Text("Export result (JSON)") }
            if (capturing) {
                HelpText("Capture running — export when it finishes.")
            }
        }

        // -- Chain under test -------------------------------------------
        Text(
            "Chain under test",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
        )
        val renderResolution =
            prefs.getString("render_resolution", "auto") ?: "auto"
        Text("Draw path: GL chain")
        Text("Upscaler: sharp-bilinear")
        Text("Colour correction: none (grayscale)")
        Text("De-dither: dither-invert")
        Text("Render resolution: $renderResolution")
        Text("Output target: phone panel")
        val chain = capture?.chainLabel
            ?: "gl:deband>dither-invert>deflicker>scalefx3x+raa>sharp-bilinear>rcas"
        Text("Chain: $chain", fontFamily = FontFamily.Monospace)

        // -- Build info ---------------------------------------------------
        Text(
            "Build info",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
        )
        Text("Version: ${appVersionString(context)}")
        Text("Core: mGBA 0.11.0")
        Text("Core pin: f705b5d6")
        Text("Core source: libretro/mgba fork")

        // -- Import trace ---------------------------------------------------
        Text(
            "Import trace",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
        )
        Button(onClick = onOpenTrace) { Text("Open import trace") }

        if (notice != null) {
            Spacer(Modifier.height(24.dp))
            Text(notice ?: "")
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) { Text("Back") }
    }
}

/**
 * The export notice, naming the true location (§7.2). The name is
 * generated the same way Exports does, and the path branch follows
 * the same rule (MediaStore on API 29+, the app's external files
 * dir below it), so the notice always names where the file landed.
 */
private fun exportBenchmarkNotice(
    context: Context,
    capture: Benchmark.Capture,
): String {
    val name = "dot-${capture.gameSha.take(12)}-${capture.startedMs}.json"
    return if (Exports.exportBenchmark(context, capture)) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Saved to Downloads/benchmarks/$name"
        } else {
            "Saved to app storage/benchmarks/$name"
        }
    } else {
        "Export failed"
    }
}
