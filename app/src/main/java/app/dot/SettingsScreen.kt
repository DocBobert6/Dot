package app.dot

import android.content.Context
import android.os.Vibrator
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.sp
import app.dot.gl.GlFailureReport
import java.io.File
import kotlinx.coroutines.delay

// Copyright (c) 2026 Dot contributors. All rights reserved.
//
// Dot — Settings (SHELL-SPEC §1). One scrolling screen with a top
// bar ("Settings" + back). Values are snapshotted when the screen
// opens; a change writes through to the store immediately. Most
// display / control changes apply on the next game launch, not live
// (exceptions are noted). A single transient notice line at the
// bottom reports the result of Reset / Clear / layout actions.
// The screen uses status-bar padding (bars visible — §11).
//
// Sections: Display, Audio, Controls (with the Test haptics probe
// and the Default layout editor), Storage, Crash report, Graphics
// failure report, Developer, About. (Cover art joins it with the
// cover-art work in batch 6.)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDeveloper: () -> Unit,
    onOpenTrace: () -> Unit,
    onOpenLicences: () -> Unit,
) {
    val context = LocalContext.current
    val filesDir = context.filesDir
    val prefs = remember {
        context.getSharedPreferences("dot-settings", Context.MODE_PRIVATE)
    }
    var notice by remember { mutableStateOf<String?>(null) }
    var defaultEditorOpen by remember { mutableStateOf(false) }

    // Display (§1.1).
    var renderResolution by remember {
        mutableStateOf(prefs.getString("render_resolution", "auto") ?: "auto")
    }
    var fpsCounter by remember {
        mutableStateOf(prefs.getBoolean("fps_counter", false))
    }
    // Audio (§1.3).
    var volume by remember {
        mutableIntStateOf(prefs.getInt("volume", 80).coerceIn(0, 100))
    }
    var lowPass by remember {
        mutableIntStateOf(coerceLowPass(prefs.getInt("low_pass_range", 60)))
    }
    // Controls (§1.4).
    var hapticsEnabled by remember {
        mutableStateOf(prefs.getBoolean("haptics_enabled", true))
    }
    var hapticStrength by remember {
        mutableIntStateOf(prefs.getInt("haptic_strength", 100).coerceIn(0, 100))
    }
    var ffVisible by remember {
        mutableStateOf(prefs.getBoolean("ff_visible", false))
    }
    var ffSpeed by remember {
        mutableIntStateOf(prefs.getInt("ff_speed", 2).coerceIn(2, 4))
    }
    var slowVisible by remember {
        mutableStateOf(prefs.getBoolean("slow_visible", false))
    }
    var rewindVisible by remember {
        mutableStateOf(prefs.getBoolean("rewind_visible", false))
    }
    // The probe engine: the identical construction to the game's
    // (same vibrator, same live settings), so a probe fire is the
    // game's fire (§2.1). Nothing rumbles outside a game.
    val probeEngine = remember {
        val vibrator =
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        HapticsEngine(
            vibrator = vibrator,
            settings = {
                HapticsSettings(
                    prefs.getBoolean("haptics_enabled", true),
                    prefs.getInt("haptic_strength", 100),
                )
            },
            isRumbling = { false },
        )
    }
    // Storage (§1.5), crash report (§1.6), graphics failure (§1.7).
    var gamesSize by remember { mutableStateOf(gamesFolderSize(filesDir)) }
    var crashReport by remember { mutableStateOf(CrashReport.load(filesDir)) }
    var glFailure by remember { mutableStateOf(GlFailureReport.read(filesDir)) }
    var hasCustomDefault by remember {
        mutableStateOf(LayoutStore.hasCustomDefault(filesDir))
    }

    // The notice clears itself after a few seconds.
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
            Text("Settings", style = MaterialTheme.typography.headlineLarge)
            TextButton(onClick = onBack) { Text("Back") }
        }

        // -- Display -------------------------------------------------
        SectionHeader("Display")
        ChoiceRow(
            label = "Render resolution",
            options = listOf("auto" to "Auto", "1080p" to "1080p", "4k" to "4K"),
            selected = renderResolution,
            onSelect = { value ->
                renderResolution = value
                prefs.edit().putString("render_resolution", value).apply()
            },
        )
        HelpText(
            "Auto follows the display. 4K renders at 3240x2160 and " +
                "downsamples to the panel — sharper on a 1080p screen, " +
                "heavier on the GPU. Applies on the next launch.",
        )
        ToggleRow(
            label = "FPS counter",
            checked = fpsCounter,
            onChange = { checked ->
                fpsCounter = checked
                prefs.edit().putBoolean("fps_counter", checked).apply()
            },
        )
        HelpText(
            "Shows a live frame-rate counter on screen while you " +
                "play. Off by default. The benchmark line shows " +
                "instead during a capture.",
        )

        // -- Audio ---------------------------------------------------
        SectionHeader("Audio")
        SliderRow(
            label = "Volume",
            value = volume,
            range = 0..100,
            steps = 0,
            valueText = "$volume%",
            onChange = { value ->
                volume = value
                prefs.edit().putInt("volume", value).apply()
            },
        )
        HelpText("Applies on the next game launch.")
        SliderRow(
            label = "Low-pass strength",
            value = lowPass,
            range = 5..95,
            steps = 17,
            valueText = "$lowPass%",
            onChange = { value ->
                lowPass = coerceLowPass(value)
                prefs.edit().putInt("low_pass_range", lowPass).apply()
            },
        )
        HelpText(
            "The low-pass filter is always on — it softens the harsh " +
                "top end the sound core's own authors identified. This " +
                "slider sets how strongly: a higher value softens more. " +
                "60% is the core's own default. Applies on the next " +
                "launch.",
        )

        // -- Controls ------------------------------------------------
        SectionHeader("Controls")
        ToggleRow(
            label = "Haptics",
            checked = hapticsEnabled,
            onChange = { checked ->
                hapticsEnabled = checked
                prefs.edit().putBoolean("haptics_enabled", checked).apply()
            },
        )
        HelpText(
            "A short click for every button — A/B full, Start/Select " +
                "lighter, D-pad tick on direction change. 0 is silent, " +
                "like the toggle off. Applies on the next launch.",
        )
        SliderRow(
            label = "Haptic strength",
            value = hapticStrength,
            range = 0..100,
            steps = 19,
            valueText = "$hapticStrength%",
            onChange = { value ->
                // Snaps to 5% steps on release; the slider's steps
                // already land on them.
                hapticStrength = (value / 5 * 5).coerceIn(0, 100)
                prefs.edit().putInt("haptic_strength", hapticStrength).apply()
            },
        )
        HapticsProbe(probeEngine)
        ToggleRow(
            label = "Fast-forward button",
            checked = ffVisible,
            onChange = { checked ->
                ffVisible = checked
                prefs.edit().putBoolean("ff_visible", checked).apply()
            },
        )
        HelpText(
            "Hold the FF button to run at the chosen speed; audio is " +
                "muted while it runs. Hidden by default. Applies on " +
                "the next launch.",
        )
        ChoiceRow(
            label = "Fast-forward speed",
            options = listOf("2" to "2x", "3" to "3x", "4" to "4x"),
            selected = ffSpeed.toString(),
            onSelect = { value ->
                ffSpeed = value.toInt()
                prefs.edit().putInt("ff_speed", ffSpeed).apply()
            },
        )
        ToggleRow(
            label = "Slow-motion button",
            checked = slowVisible,
            onChange = { checked ->
                slowVisible = checked
                prefs.edit().putBoolean("slow_visible", checked).apply()
            },
        )
        HelpText(
            "A separate toggle from fast-forward: the 0.5x button " +
                "runs the game at half speed. Hidden by default. " +
                "Applies on the next launch.",
        )
        ToggleRow(
            label = "Rewind button",
            checked = rewindVisible,
            onChange = { checked ->
                rewindVisible = checked
                prefs.edit().putBoolean("rewind_visible", checked).apply()
            },
        )
        HelpText(
            "Hold the RW button to step back through the last 30 " +
                "seconds, one second at a time. Hidden by default. " +
                "Applies on the next launch.",
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = {
                    // Reset restores exactly the defaults map, then
                    // re-reads every field from the store into the
                    // screen, so no field can stay stale (§1.4).
                    prefs.edit()
                        .putBoolean("haptics_enabled", true)
                        .putInt("haptic_strength", 100)
                        .putBoolean("ff_visible", false)
                        .putInt("ff_speed", 2)
                        .putBoolean("slow_visible", false)
                        .putBoolean("rewind_visible", false)
                        .apply()
                    hapticsEnabled = prefs.getBoolean("haptics_enabled", true)
                    hapticStrength = prefs.getInt("haptic_strength", 100)
                    ffVisible = prefs.getBoolean("ff_visible", false)
                    ffSpeed = prefs.getInt("ff_speed", 2)
                    slowVisible = prefs.getBoolean("slow_visible", false)
                    rewindVisible = prefs.getBoolean("rewind_visible", false)
                    notice = "Controls reset to defaults"
                },
            ) { Text("Reset controls") }
        }

        // -- Default layout (§1.4, §3.4) -----------------------------
        SectionHeader("Default layout")
        Text(
            "The layout for games with no saved layout of their own. " +
                "Arrange it however you like; per-game adjustments " +
                "stay in the pause menu's Move controls.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (hasCustomDefault) {
                "In effect: your custom default layout."
            } else {
                "In effect: the factory layout."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = { defaultEditorOpen = true }) {
                Text("Edit default layout")
            }
            OutlinedButton(
                onClick = {
                    LayoutStore.clearDefault(filesDir)
                    hasCustomDefault = LayoutStore.hasCustomDefault(filesDir)
                    notice = "Factory layout restored — games with no " +
                        "saved layout will use it."
                },
            ) { Text("Factory default") }
        }

        // -- Storage (§1.5) ------------------------------------------
        SectionHeader("Storage")
        Text("Games folder: ${formatBytes(gamesSize)}")
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Saved states")
            Button(
                onClick = {
                    notice = if (clearSavedStates(filesDir)) {
                        "Saved states cleared"
                    } else {
                        "Could not clear saved states"
                    }
                },
            ) { Text("Clear saved states") }
        }

        // -- Crash report (§1.6) --------------------------------------
        SectionHeader("Crash report")
        val crash = crashReport
        if (crash == null) {
            Text("No crash recorded")
            HelpText("A force-stop or swipe-away kill leaves no report.")
        } else {
            val timeLine = crash.split('\n').getOrNull(1) ?: ""
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Last crash: ${timeLine.removePrefix("Time: ")}")
                TextButton(
                    onClick = {
                        CrashReport.clear(filesDir)
                        crashReport = CrashReport.load(filesDir)
                        notice = "Crash report cleared"
                    },
                ) { Text("Clear") }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                crash,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState()),
            )
            HelpText(
                "Only uncaught app crashes are recorded — a native " +
                    "crash in the game core leaves no report.",
            )
        }

        // -- Graphics failure report (§1.7) ---------------------------
        SectionHeader("Graphics failure report")
        val failure = glFailure
        if (failure == null) {
            Text("No graphics failure recorded")
            HelpText(
                "A failure is recorded the moment the graphics layer " +
                    "fails.",
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Last failure: ${failure.time}")
                TextButton(
                    onClick = {
                        GlFailureReport.clear(filesDir)
                        glFailure = GlFailureReport.read(filesDir)
                        notice = "Graphics failure report cleared"
                    },
                ) { Text("Clear") }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                GlFailureReport.format(failure),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState()),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    notice = GlFailureReport.export(context, filesDir, failure)
                },
            ) { Text("Export failure report") }
            HelpText(
                "Covers caught graphics-layer failures. NOT the same " +
                    "as the crash report above: a caught GL exception " +
                    "never reaches the crash handler, so the crash " +
                    "report correctly shows nothing for it.",
            )
        }

        // -- Developer (§1.8) -----------------------------------------
        SectionHeader("Developer")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Benchmark")
            Button(onClick = onOpenDeveloper) { Text("Open") }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Import trace")
            Button(onClick = onOpenTrace) { Text("Open") }
        }

        // -- About (§1.9) ----------------------------------------------
        SectionHeader("About")
        Text("Dot — a standalone Game Boy emulator.")
        Text("Built on the mGBA core (MPL-2.0).")
        Text("Not affiliated with or endorsed by Nintendo.")
        Text(
            "Version ${appVersionString(context)}",
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onOpenLicences) { Text("Licences") }

        // -- Notice ------------------------------------------------------
        if (notice != null) {
            Spacer(Modifier.height(24.dp))
            Text(notice ?: "")
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) { Text("Back") }
    }

    // The default-layout editor overlays Settings. Done compares the
    // draft against the factory layout: equal → clear the customised
    // default (so the factory applies again); otherwise save it as
    // the default (§3.4). Back discards. The status-bar padding
    // keeps the top action row out from under the status bar (the
    // editor's own insets protect the drag bounds).
    if (defaultEditorOpen) {
        LayoutEditor(
            baseLayout = LayoutStore.effectiveDefault(filesDir),
            resetTarget = LayoutStore.factoryLayout(),
            haptics = probeEngine,
            ffVisible = ffVisible,
            slowVisible = slowVisible,
            rewindVisible = rewindVisible,
            prefs = prefs,
            onDone = { committed ->
                if (committed != null) {
                    if (committed == LayoutStore.factoryLayout()) {
                        LayoutStore.clearDefault(filesDir)
                    } else {
                        LayoutStore.saveDefault(filesDir, committed)
                    }
                    hasCustomDefault = LayoutStore.hasCustomDefault(filesDir)
                    notice = "Default layout saved — games with no " +
                        "saved layout will use it."
                }
                defaultEditorOpen = false
            },
            modifier = Modifier.statusBarsPadding(),
        )
    }
}

// -- the Test haptics probe (§2.4) -------------------------------------

/**
 * Five buttons, each firing exactly one engine method on the real
 * vibrator and showing the outcome of the last fire next to the
 * button as one word: Played, Rejected, Threw. Below them, the
 * phone's live facts. Diagnostic, not a fix: the game itself stays
 * silent — only this screen reports outcomes.
 */
@Composable
private fun HapticsProbe(engine: HapticsEngine) {
    var pressOutcome by remember { mutableStateOf<HapticOutcome?>(null) }
    var lightOutcome by remember { mutableStateOf<HapticOutcome?>(null) }
    var rollOutcome by remember { mutableStateOf<HapticOutcome?>(null) }
    var releaseOutcome by remember { mutableStateOf<HapticOutcome?>(null) }
    var envelopeOutcome by remember { mutableStateOf<HapticOutcome?>(null) }

    Text(
        "Test haptics",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(
        "Tap a button to fire that effect on the real vibrator, " +
            "through the haptics settings above. The word next to " +
            "each button is what happened the last time it fired: " +
            "Played, Rejected (haptics off, strength 0, or no " +
            "vibrator), or Threw (the phone refused the effect). " +
            "The lines below are what the phone itself reports.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    ProbeButton(
        label = "Press click (A/B)",
        outcome = pressOutcome,
        onFire = { pressOutcome = engine.probePressClick() },
    )
    ProbeButton(
        label = "Light click (Start/Select)",
        outcome = lightOutcome,
        onFire = { lightOutcome = engine.probeLightClick() },
    )
    ProbeButton(
        label = "Roll tick (D-pad)",
        outcome = rollOutcome,
        onFire = { rollOutcome = engine.probeRollTick() },
    )
    ProbeButton(
        label = "Release tick",
        outcome = releaseOutcome,
        onFire = { releaseOutcome = engine.probeReleaseTick() },
    )
    ProbeButton(
        label = "Envelope click",
        outcome = envelopeOutcome,
        onFire = { envelopeOutcome = engine.probeEnvelopeClick() },
    )
    Spacer(Modifier.height(8.dp))
    val facts = engine.phoneFacts()
    Text("Android API ${facts.apiLevel}")
    Text("Vibrator: ${if (facts.hasVibrator) "present" else "absent"}")
    Text(
        "Envelope effects: " +
            if (facts.envelopeSupported) "Supported" else "Not supported",
    )
    Text(
        "CLICK primitive: " +
            if (facts.clickSupported) "Supported" else "Not supported",
    )
    Text(
        "TICK primitive: " +
            if (facts.tickSupported) "Supported" else "Not supported",
    )
    Text(
        "THUD primitive: " +
            if (facts.thudSupported) "Supported" else "Not supported",
    )
    Text("Haptics enabled: ${if (facts.hapticsEnabled) "on" else "off"}")
    Text("Haptic strength: ${facts.hapticStrength}%")
}

@Composable
private fun ProbeButton(
    label: String,
    outcome: HapticOutcome?,
    onFire: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = onFire) { Text(label) }
        Text(outcomeWord(outcome))
    }
}

private fun outcomeWord(outcome: HapticOutcome?): String = when (outcome) {
    HapticOutcome.PLAYED -> "Played"
    HapticOutcome.REJECTED -> "Rejected"
    HapticOutcome.THREW -> "Threw"
    null -> ""
}

// -- shared rows ---------------------------------------------------------

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}

@Composable
internal fun HelpText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Int,
    range: IntRange,
    steps: Int,
    valueText: String,
    onChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label)
            Text(valueText)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((value, text) in options) {
                if (value == selected) {
                    Button(onClick = { onSelect(value) }) { Text(text) }
                } else {
                    OutlinedButton(onClick = { onSelect(value) }) { Text(text) }
                }
            }
        }
    }
}

/** Low-pass strength: 5–95 in 5% steps, coerced on read (§1.3). */
private fun coerceLowPass(value: Int): Int {
    val snapped = ((value + 2) / 5 * 5)
    return snapped.coerceIn(5, 95)
}

/** Total size of the games directory, in bytes. */
private fun gamesFolderSize(filesDir: File): Long {
    return try {
        val dir = Store.gamesDir(filesDir)
        if (!dir.exists()) 0L else dir.listFiles()?.sumOf { it.length() } ?: 0L
    } catch (_: Exception) {
        0L
    }
}

/** Deletes every file in the states directory (manual slots AND
 *  auto states). True when the directory is empty afterwards. */
private fun clearSavedStates(filesDir: File): Boolean {
    return try {
        val dir = Store.statesDir(filesDir)
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
        }
        dir.listFiles()?.isEmpty() ?: true
    } catch (_: Exception) {
        false
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
