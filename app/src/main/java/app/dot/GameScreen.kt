package app.dot

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.dot.gl.GameGlView
import app.dot.gl.GlDrawStats
import app.dot.gl.GlFailureReport
import kotlinx.coroutines.delay

// Dot — the player screen.
//
// Portrait-first, like the real DMG: the 10:9 game area fills the
// width at the top, D-pad and A/B sit below it. Landscape is a
// supported rotation, not the default: the game area takes the full
// height and the controls sit to its right.
//
// Slice 2 renders the core's frame through the GL picture chain
// (GameGlView + GlRenderer): deband -> dither-invert -> deflicker
// -> ScaleFX 3x -> rAA -> sharp-bilinear -> [Mitchell] -> RCAS.
// There is no bitmap fallback: if GL is unavailable the player
// shows an honest error (GL-CHAIN-SPEC §9.1).
//
// No top bar in game. Back toggles pause. The pause menu is Resume
// first, then Save / Load, then Exit. Save states: 4 manual slots
// plus Auto (written on pause and on exit, never auto-loaded).
// Copyright (c) 2026 Dot contributors. All rights reserved.

@Composable
fun GameScreen(
    entry: LibraryEntry,
    onExit: () -> Unit,
    /** The library's "Move controls" opens the player with the
     *  layout editor open. Transient UI state: it is not part of
     *  the nav-stack key and does not survive recreation. */
    startWithEditorOpen: Boolean = false,
) {
    val context = LocalContext.current
    val activity = context as Activity
    val session = remember(entry.sha256) {
        PlayerSession(context.filesDir, entry)
    }
    val prefs = remember {
        context.getSharedPreferences("dot-settings", Context.MODE_PRIVATE)
    }
    val pictureSetting = remember {
        prefs.getString("picture_target", "auto") ?: "auto"
    }
    var glError by remember { mutableStateOf<String?>(null) }
    val glView = remember(entry.sha256) {
        GameGlView(
            context = context,
            pictureTarget = { pictureSetting },
            gameTitle = entry.title,
            appVersion = appVersionString(context),
            onGlFailed = { _, _, _ ->
                // The failure arrives on the GL thread; the error
                // state is a Compose state, so the write is posted
                // to the main thread, where recomposition is
                // guaranteed. A late failure (after composition)
                // must surface the error screen, not sit in a
                // @Volatile var the UI never re-reads.
                Handler(Looper.getMainLooper()).post {
                    glError = "The graphics chain failed. See Settings for the failure report."
                }
                Thread({ session.stop() }, "dot-gl-fail-stop").start()
            },
            // The test-pattern diagnostic flag, read live on every
            // frame (Developer toggle, default off).
            testPattern = { prefs.getBoolean("dev_test_pattern", false) },
        )
    }
    // A view-creation failure (no GLES 3.0) is set synchronously in
    // the view's init; surface it as the error screen.
    LaunchedEffect(glView) {
        glView.glError?.let { glError = it }
    }
    var pickerMode by remember { mutableStateOf<PickerMode?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var editorOpen by remember(entry.sha256) {
        mutableStateOf(startWithEditorOpen)
    }
    // "Move controls" pauses once the session reaches Running, so
    // the editor opens over the paused game. Exactly once per game
    // entry — a manual resume after that is the user's own.
    var editorAutoPaused by remember(entry.sha256) { mutableStateOf(false) }
    val state by session.state.collectAsState()
    LaunchedEffect(state) {
        if (startWithEditorOpen && !editorAutoPaused &&
            state == PlayerSession.State.Running
        ) {
            editorAutoPaused = true
            session.pause()
        }
    }
    // The resolved control layout (per-game, else the customised
    // default, else the factory). The layout editor refreshes it.
    var layout by remember(entry.sha256) {
        mutableStateOf(LayoutStore.load(context.filesDir, entry.sha256))
    }
    // The haptics engine: vibrator + live settings + the rumble
    // gate, so a UI fire never lands while the game itself rumbles.
    val haptics = remember(entry.sha256) {
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
            isRumbling = { session.rumbleGate?.isRumbling() == true },
        )
    }
    // Rewind hold: while true the emu loop is paused for the hold
    // and the pause menu stays hidden. The app backgrounding
    // cancels the hold (the session stays paused, menu shows).
    var rewindHolding by remember { mutableStateOf(false) }
    var isForeground by remember { mutableStateOf(true) }
    val ffVisible = remember {
        prefs.getBoolean("ff_visible", false)
    }
    val slowVisible = remember {
        prefs.getBoolean("slow_visible", false)
    }
    val rewindVisible = remember {
        prefs.getBoolean("rewind_visible", false)
    }
    // FPS counter (§7.1) and the benchmark live line (§7.2),
    // polled twice a second from the benchmark live summary. The
    // setting is re-read each tick, so it applies live, not only
    // on the next launch. While a benchmark capture is running the
    // benchmark live line takes precedence — the counter hides. The
    // counter shows nothing until a live summary has landed.
    var fpsOn by remember {
        mutableStateOf(prefs.getBoolean("fps_counter", false))
    }
    var liveFps by remember { mutableStateOf(0.0) }
    // Live draw stats (diagnostic, default off): the GlDrawStats
    // snapshot on screen while playing, polled twice a second
    // with the FPS counter. The setting is re-read each tick, so
    // it applies live, not only on the next launch.
    var drawStatsOn by remember {
        mutableStateOf(prefs.getBoolean("dev_draw_stats", false))
    }
    var drawStatsText by remember { mutableStateOf("") }

    // The dot-matrix boot gesture (§7.8 item 5): Dot's own mark
    // when a game launches — short, non-blocking. The session runs
    // underneath; the mark holds ~650 ms, then fades in 250 ms.
    // Per game entry, so a fresh launch boots again; a resume
    // from pause does not.
    var bootDone by remember(entry.sha256) { mutableStateOf(false) }
    val bootAlpha by animateFloatAsState(
        targetValue = if (bootDone) 0f else 1f,
        animationSpec = tween(durationMillis = 250),
        label = "dot-boot",
    )
    LaunchedEffect(entry.sha256) {
        delay(650)
        bootDone = true
    }
    var capturing by remember { mutableStateOf(false) }
    var captureProgress by remember { mutableIntStateOf(0) }
    var benchStats by remember { mutableStateOf<Benchmark.Stats?>(null) }

    // Gamepad (SHELL-SPEC §5): Player 1 only, silent, enabled on
    // entry and cleared on exit. The touch mask and the gamepad
    // mask merge here, before the session — the session's setInput
    // stores the merged mask, and the emu loop stays the single
    // pusher to the core.
    val gamepad = remember(entry.sha256) { Gamepad() }
    var touchMask by remember { mutableIntStateOf(0) }
    var gamepadMask by remember { mutableIntStateOf(0) }
    fun pushMergedMask() {
        session.setInput(touchMask or gamepadMask)
    }
    // Every pause path clears the held touch mask, the pad state,
    // the stick state and the stored session mask (set to 0) — so
    // no button can stick across a pause (§5).
    fun clearAllInput() {
        touchMask = 0
        gamepad.clear()
        session.setInput(0)
    }

    DisposableEffect(session) {
        session.glFrameSink = { frame -> glView.postFrame(frame) }
        session.onFrame = { glView.onFrameLanded() }
        // Game rumble (RumbleGate) and the speed / rewind arming the
        // session needs before the first frame.
        val vibrator =
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        session.rumbleGate = vibrator?.let { RumbleGate(it) }
        session.ffSpeed = prefs.getInt("ff_speed", 2).coerceIn(2, 4)
        session.volumePercent = prefs.getInt("volume", 80).coerceIn(0, 100)
        session.rewindArmed = prefs.getBoolean("rewind_visible", false)
        session.onBenchmarkDone = { capture ->
            val ok = Exports.exportBenchmark(context, capture)
            message = if (ok) {
                val s = capture.presented
                "Benchmark: %.1f fps avg, p95 %.1f ms, %d drops — gate %s"
                    .format(
                        s.avgFps, s.p95Ms, s.drops,
                        if (Benchmark.gatePass(capture)) "PASS" else "FAIL",
                    )
            } else {
                "Benchmark finished but the export failed."
            }
        }
        session.start()
        onDispose {
            session.glFrameSink = null
            Thread({ session.stop() }, "dot-stop").start()
        }
    }

    // The gamepad handler lives on the activity: installed on
    // player entry, cleared on exit. The callback merges the pad
    // mask with the touch mask and pushes the result to the session.
    DisposableEffect(gamepad) {
        val mainActivity = activity as MainActivity
        mainActivity.gamepadHandler = gamepad
        gamepad.onMaskChanged = { mask ->
            gamepadMask = mask
            pushMergedMask()
        }
        gamepad.enabled = true
        onDispose {
            gamepad.enabled = false
            gamepad.onMaskChanged = null
            gamepad.clear()
            mainActivity.gamepadHandler = null
        }
    }

    // Pause when the app backgrounds; the session writes the battery
    // save and the Auto state asynchronously (backgroundPause). The
    // pause toggle itself writes neither. Backgrounding also cancels
    // a rewind hold (the session stays paused, so the menu shows on
    // return) and drops the haptics lift-off gate.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isForeground = true
                Lifecycle.Event.ON_PAUSE -> {
                    isForeground = false
                    haptics.dropReleaseGate()
                    if (rewindHolding) rewindHolding = false
                    if (session.state.value == PlayerSession.State.Running) {
                        clearAllInput()
                        session.backgroundPause()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Immersive mode and full brightness while playing, restored
    // when leaving the game. The frame rate is requested on the GL
    // surface itself (GameGlView: Surface.setFrameRate(59.73)), not
    // via the window attributes.
    DisposableEffect(activity) {
        val window = activity.window
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        insets.hide(
            WindowInsetsCompat.Type.statusBars() or
                WindowInsetsCompat.Type.navigationBars(),
        )
        insets.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        val oldBrightness = window.attributes.screenBrightness
        window.attributes = window.attributes.apply {
            screenBrightness = 1.0f
        }
        onDispose {
            insets.show(
                WindowInsetsCompat.Type.statusBars() or
                    WindowInsetsCompat.Type.navigationBars(),
            )
            window.attributes = window.attributes.apply {
                screenBrightness = oldBrightness
            }
        }
    }
    DisposableEffect(activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity.window.clearFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            fpsOn = prefs.getBoolean("fps_counter", false)
            liveFps = Benchmark.liveFps
            drawStatsOn = prefs.getBoolean("dev_draw_stats", false)
            if (drawStatsOn) drawStatsText = GlDrawStats.snapshot()
            capturing = Benchmark.isCapturing()
            captureProgress = Benchmark.captureProgress()
            benchStats = Benchmark.liveStats()
            delay(500)
        }
    }

    BackHandler {
        when {
            pickerMode != null -> pickerMode = null
            rewindHolding -> {
                // Back cancels the rewind hold first.
                rewindHolding = false
                session.resume()
            }
            state == PlayerSession.State.Paused -> session.resume()
            state == PlayerSession.State.Running -> {
                haptics.dropReleaseGate()
                clearAllInput()
                session.pause()
            }
            else -> onExit()
        }
    }

    when (val s = state) {
        is PlayerSession.State.Error -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DotSkin.Charcoal)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "The game could not start.",
                    color = Color.White,
                    fontSize = 18.sp,
                )
                Spacer(Modifier.height(12.dp))
                Text(s.message, color = DotSkin.TextMuted, fontSize = 14.sp)
                Spacer(Modifier.height(24.dp))
                Button(onClick = onExit) { Text("Back to library") }
            }
            return
        }
        else -> Unit
    }

    // A GL failure (no GLES 3.0, or the chain latched): an honest
    // error, not a picture (GL-CHAIN-SPEC §9.1). The session is
    // already stopped by the failure callback.
    val glErr = glError
    if (glErr != null) {
        // The failure report is re-read when the error state
        // changes, so a failure recorded on the GL thread just
        // before the error posted is already on file (§10).
        val glFailure = remember(glErr) {
            GlFailureReport.read(context.filesDir)
        }
        var exportMessage by remember { mutableStateOf<String?>(null) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DotSkin.Charcoal)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "The graphics chain failed.",
                color = Color.White,
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(12.dp))
            Text(glErr, color = DotSkin.TextMuted, fontSize = 14.sp)
            Spacer(Modifier.height(24.dp))
            // Shown ONLY when a report is actually recorded: a
            // view-creation failure shows this screen too and may
            // have no GL failure on file, so the button is hidden
            // then, never a dead button (§10).
            if (glFailure != null) {
                Button(
                    onClick = {
                        exportMessage = GlFailureReport.export(
                            context, context.filesDir, glFailure,
                        )
                    },
                ) { Text("Export failure report") }
                Spacer(Modifier.height(12.dp))
            }
            if (exportMessage != null) {
                Text(exportMessage ?: "", color = DotSkin.TextMuted, fontSize = 14.sp)
                LaunchedEffect(exportMessage) {
                    delay(3000)
                    exportMessage = null
                }
            }
            Button(onClick = onExit) { Text("Back to library") }
        }
        return
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(DotSkin.Charcoal),
    ) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            GameArea(
                glView = glView,
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(10f / 9f),
            )
        } else {
            GameArea(
                glView = glView,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(10f / 9f),
            )
        }
        // The touch controls float over the whole screen at their
        // layout fractions (SHELL-SPEC §3/§4); the layer itself
        // takes no touches, only the controls do.
        OverlayControls(
            session = session,
            layout = layout,
            haptics = haptics,
            ffVisible = ffVisible,
            slowVisible = slowVisible,
            rewindVisible = rewindVisible,
            touchMask = touchMask,
            onTouchMaskChanged = { mask ->
                touchMask = mask
                pushMergedMask()
            },
            rewindHolding = rewindHolding,
            onRewindStart = {
                // The emu loop must be stopped before the first
                // unserialise, or a frame in flight races it. The
                // pause menu stays hidden while the hold is live.
                haptics.dropReleaseGate()
                clearAllInput()
                session.pause()
                rewindHolding = true
            },
            onRewindEnd = {
                rewindHolding = false
                session.resume()
            },
            isForeground = isForeground,
            isPaused = state == PlayerSession.State.Paused,
        )
        if (capturing && state == PlayerSession.State.Running) {
            // The benchmark live line (§7.2) takes precedence over
            // the FPS counter while a capture is running.
            val stats = benchStats
            Text(
                if (stats != null) {
                    "BENCH $captureProgress/${Benchmark.CAPTURE_FRAMES} · " +
                        "%.1f fps · p95 %.1f ms · %d drops".format(
                            stats.avgFps, stats.p95Ms, stats.drops,
                        )
                } else {
                    "BENCH $captureProgress/${Benchmark.CAPTURE_FRAMES} · " +
                        "waiting for frames"
                },
                color = Color.White,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
            )
        } else if (fpsOn && liveFps > 0 && state == PlayerSession.State.Running) {
            Text(
                "%.1f fps".format(liveFps),
                color = Color.White,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
            )
        }
        // The live draw stats (diagnostic, default off): the
        // GlDrawStats snapshot on the player screen, so the counts
        // can be read without opening Settings. It sits over the
        // game area's top-left corner and takes no touches.
        if (drawStatsOn && state == PlayerSession.State.Running) {
            Text(
                drawStatsText,
                color = Color.White,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 8.dp, top = 8.dp)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(4.dp),
            )
        }
        if (message != null) {
            Text(
                message ?: "",
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
            )
            LaunchedEffect(message) {
                delay(4000)
                message = null
            }
        }
        // The boot mark, on top of everything while it lasts.
        // No pointer handling: touches pass straight through to
        // the controls underneath.
        if (bootAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DotSkin.Charcoal)
                    .alpha(bootAlpha),
                contentAlignment = Alignment.Center,
            ) {
                DotMatrixMark(
                    modifier = Modifier.size(220.dp, 90.dp),
                )
            }
        }
    }

    // The pause menu stays hidden while a rewind hold is live —
    // the hold pauses the emu loop itself, and the menu would sit
    // over the game the user is rewinding through.
    if (state == PlayerSession.State.Paused && !rewindHolding) {
        PauseMenu(
            entry = entry,
            session = session,
            onResume = { session.resume() },
            onSave = { pickerMode = PickerMode.Save },
            onLoad = { pickerMode = PickerMode.Load },
            onMoveControls = { editorOpen = true },
            benchmarkRunning = capturing,
            benchmarkProgress = captureProgress,
            onBenchmark = {
                session.startBenchmark(glView.chainLabel)
                session.resume()
                message = "Benchmark running — 600 frames."
            },
            onReport = {
                val ok = Exports.exportPictureProblem(
                    context, entry, session,
                )
                message = if (ok) {
                    "Picture report saved to Downloads/dot-reports."
                } else {
                    "Picture report failed to save."
                }
            },
            onExit = onExit,
        )
    }

    // The layout editor overlays the paused game. Done commits the
    // draft (the commit rule clears the per-game override when the
    // draft lands on the effective default); Back discards it. The
    // pause menu is still showing underneath when it closes.
    if (editorOpen) {
        LayoutEditor(
            baseLayout = layout,
            resetTarget = LayoutStore.effectiveDefault(context.filesDir),
            haptics = haptics,
            ffVisible = ffVisible,
            slowVisible = slowVisible,
            rewindVisible = rewindVisible,
            prefs = prefs,
            onDone = { committed ->
                if (committed != null) {
                    val ok = LayoutStore.save(
                        context.filesDir, entry.sha256, committed,
                    )
                    if (ok) {
                        layout = LayoutStore.load(
                            context.filesDir, entry.sha256,
                        )
                        message = "Layout saved."
                    } else {
                        message = "Layout save failed."
                    }
                }
                editorOpen = false
            },
        )
    }

    pickerMode?.let { mode ->
        SlotPicker(
            mode = mode,
            session = session,
            onDismiss = { pickerMode = null },
            onPick = { slot ->
                pickerMode = null
                // Save/load run on the session's state-op executor;
                // the result comes back on the main thread.
                if (mode == PickerMode.Save) {
                    session.saveState(slot) { ok ->
                        message = if (ok) {
                            "Saved to slot ${slot + 1}."
                        } else {
                            "Save failed."
                        }
                    }
                } else {
                    val done: (Boolean) -> Unit = { ok ->
                        if (ok) {
                            session.resume()
                        } else {
                            message = "Load failed."
                        }
                    }
                    if (slot < 0) {
                        session.loadAutoState(done)
                    } else {
                        session.loadState(slot, done)
                    }
                }
            },
        )
    }
}

private enum class PickerMode { Save, Load }

@Composable
private fun GameArea(glView: GameGlView, modifier: Modifier) {
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { glView },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** "app.dot 0.1.0-phase0.1 (1)" — the app version for failure reports. */
internal fun appVersionString(context: Context): String {
    return try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionName = info.versionName ?: "unknown"
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "app.dot $versionName ($versionCode)"
    } catch (_: Exception) {
        "app.dot unknown"
    }
}

@Composable
private fun PauseMenu(
    entry: LibraryEntry,
    session: PlayerSession,
    onResume: () -> Unit,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    onMoveControls: () -> Unit,
    benchmarkRunning: Boolean,
    benchmarkProgress: Int,
    onBenchmark: () -> Unit,
    onReport: () -> Unit,
    onExit: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onResume,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.title)
                Spacer(Modifier.width(8.dp))
                // The power light: the only green in the app.
                PowerLight()
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onResume, modifier = Modifier.fillMaxWidth()) {
                    Text("Resume")
                }
                TextButton(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                    Text("Save to slot")
                }
                TextButton(onClick = onLoad, modifier = Modifier.fillMaxWidth()) {
                    Text("Load from slot")
                }
                TextButton(onClick = onMoveControls, modifier = Modifier.fillMaxWidth()) {
                    Text("Move controls")
                }
                // Start is enabled only when no capture is running
                // (§7.2): exporting while one runs would hand over
                // the previous capture while the new one records.
                TextButton(
                    onClick = onBenchmark,
                    enabled = !benchmarkRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (benchmarkRunning) {
                            "Capturing… $benchmarkProgress/${Benchmark.CAPTURE_FRAMES}"
                        } else {
                            "Start benchmark (600 frames)"
                        },
                    )
                }
                TextButton(onClick = onReport, modifier = Modifier.fillMaxWidth()) {
                    Text("Report picture problem")
                }
                TextButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
                    Text("Exit to library")
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun SlotPicker(
    mode: PickerMode,
    session: PlayerSession,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val title = if (mode == PickerMode.Save) "Save to slot" else "Load from slot"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (mode == PickerMode.Load && session.hasAutoState()) {
                    TextButton(
                        onClick = { onPick(-1) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Auto — last exit")
                    }
                }
                for (slot in 0 until 4) {
                    val hasData = session.slotHasData[slot]
                    TextButton(
                        onClick = { onPick(slot) },
                        enabled = mode == PickerMode.Save || hasData,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Slot ${slot + 1}" +
                                if (hasData) "" else " (empty)",
                        )
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Back to menu")
                }
            }
        },
        confirmButton = {},
    )
}
