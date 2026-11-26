package app.dot

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
// NOTE: awaitPointerEvent() is a member of the AwaitPointerEventScope
// receiver inside awaitEachGesture — it takes no import. Importing it
// from either gestures or ui.input.pointer fails the build (two CI
// failures, 2026-11-26).
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay

// Dot — the player screen.
//
// Portrait-first, like the real DMG: the 10:9 game area fills the
// width at the top, D-pad and A/B sit below it. Landscape is a
// supported rotation, not the default: the game area takes the full
// height and the controls sit to its right.
//
// Slice 1 renders the core's frame as a Compose Image from the
// session's double-buffered bitmap. The GL picture chain (slice 2)
// replaces this view; the bitmap path stays as the documented
// fallback when GL is unavailable, exactly as in Atomic.
//
// No top bar in game. Back toggles pause. The pause menu is Resume
// first, then Save / Load, then Exit. Save states: 4 manual slots
// plus Auto (written on pause and on exit, never auto-loaded).
// SPDX-License-Identifier: GPL-3.0-or-later

@Composable
fun GameScreen(entry: LibraryEntry, onExit: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val session = remember(entry.sha256) {
        PlayerSession(context.filesDir, entry)
    }
    var displayed by remember { mutableStateOf(session.currentFrame()) }
    var pickerMode by remember { mutableStateOf<PickerMode?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val state by session.state.collectAsState()
    val fpsOn = remember {
        context.getSharedPreferences("dot-settings", Context.MODE_PRIVATE)
            .getBoolean("fps_counter", false)
    }
    var liveFps by remember { mutableStateOf(0.0) }

    DisposableEffect(session) {
        session.onFrame = { displayed = session.currentFrame() }
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
            Thread({ session.stop() }, "dot-stop").start()
        }
    }

    // Pause when the app backgrounds; the session writes the battery
    // save and the Auto state on pause.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE &&
                session.state.value == PlayerSession.State.Running
            ) {
                session.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Immersive mode, full brightness while playing, and a 59.73 Hz
    // frame-rate request, all restored when leaving the game.
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
        val oldRate = window.attributes.preferredRefreshRate
        window.attributes = window.attributes.apply {
            screenBrightness = 1.0f
            preferredRefreshRate = 59.73f
        }
        onDispose {
            insets.show(
                WindowInsetsCompat.Type.statusBars() or
                    WindowInsetsCompat.Type.navigationBars(),
            )
            window.attributes = window.attributes.apply {
                screenBrightness = oldBrightness
                preferredRefreshRate = oldRate
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

    // FPS counter: polled twice a second from the benchmark live
    // summary, exactly like Atomic's. Default OFF.
    if (fpsOn) {
        LaunchedEffect(Unit) {
            while (true) {
                liveFps = Benchmark.liveFps
                delay(500)
            }
        }
    }

    BackHandler {
        when {
            pickerMode != null -> pickerMode = null
            state == PlayerSession.State.Paused -> session.resume()
            state == PlayerSession.State.Running -> session.pause()
            else -> onExit()
        }
    }

    when (val s = state) {
        is PlayerSession.State.Error -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF141414))
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
                Text(s.message, color = Color(0xFFCCCCCC), fontSize = 14.sp)
                Spacer(Modifier.height(24.dp))
                Button(onClick = onExit) { Text("Back to library") }
            }
            return
        }
        else -> Unit
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF141414)),
    ) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            Row(modifier = Modifier.fillMaxSize()) {
                GameArea(
                    bitmap = displayed,
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(10f / 9f),
                )
                ControlsArea(
                    session = session,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                GameArea(
                    bitmap = displayed,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(10f / 9f),
                )
                ControlsArea(
                    session = session,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
        }
        if (fpsOn && state == PlayerSession.State.Running) {
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
    }

    if (state == PlayerSession.State.Paused) {
        PauseMenu(
            entry = entry,
            session = session,
            onResume = { session.resume() },
            onSave = { pickerMode = PickerMode.Save },
            onLoad = { pickerMode = PickerMode.Load },
            onBenchmark = {
                session.startBenchmark("bitmap-1x")
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

    pickerMode?.let { mode ->
        SlotPicker(
            mode = mode,
            session = session,
            onDismiss = { pickerMode = null },
            onPick = { slot ->
                pickerMode = null
                if (mode == PickerMode.Save) {
                    message = if (session.saveState(slot)) {
                        "Saved to slot ${slot + 1}."
                    } else {
                        "Save failed."
                    }
                } else {
                    val ok = if (slot < 0) {
                        session.loadAutoState()
                    } else {
                        session.loadState(slot)
                    }
                    if (ok) {
                        session.resume()
                    } else {
                        message = "Load failed."
                    }
                }
            },
        )
    }
}

private enum class PickerMode { Save, Load }

@Composable
private fun GameArea(bitmap: Bitmap, modifier: Modifier) {
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Game",
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ControlsArea(session: PlayerSession, modifier: Modifier) {
    var mask by remember { mutableIntStateOf(0) }
    fun update(bit: Int, down: Boolean) {
        mask = if (down) mask or bit else mask and bit.inv()
        session.setInput(mask)
    }
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DPad(onDirection = { bit, down -> update(bit, down) })
            ABButtons(onButton = { bit, down -> update(bit, down) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            SmallButton("SELECT") { down ->
                update(Bridge.Input.SELECT, down)
            }
            SmallButton("START") { down ->
                update(Bridge.Input.START, down)
            }
        }
    }
}

@Composable
private fun DPad(onDirection: (Int, Boolean) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PadButton("▲", 64.dp) { down ->
            onDirection(Bridge.Input.UP, down)
        }
        Row {
            PadButton("◀", 64.dp) { down ->
                onDirection(Bridge.Input.LEFT, down)
            }
            Spacer(Modifier.size(64.dp))
            PadButton("▶", 64.dp) { down ->
                onDirection(Bridge.Input.RIGHT, down)
            }
        }
        PadButton("▼", 64.dp) { down ->
            onDirection(Bridge.Input.DOWN, down)
        }
    }
}

@Composable
private fun ABButtons(onButton: (Int, Boolean) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton("B", 72.dp) { down -> onButton(Bridge.Input.B, down) }
        RoundButton("A", 72.dp) { down -> onButton(Bridge.Input.A, down) }
    }
}

@Composable
private fun PadButton(label: String, size: androidx.compose.ui.unit.Dp, onHold: (Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(if (pressed) Color(0xFF555555) else Color(0xFF2A2A2A))
            .holdButton(
                onDown = { pressed = true; onHold(true) },
                onUp = { pressed = false; onHold(false) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 20.sp)
    }
}

@Composable
private fun RoundButton(label: String, size: androidx.compose.ui.unit.Dp, onHold: (Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (pressed) Color(0xFF888888) else Color(0xFF3A3A3A))
            .holdButton(
                onDown = { pressed = true; onHold(true) },
                onUp = { pressed = false; onHold(false) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 22.sp)
    }
}

@Composable
private fun SmallButton(label: String, onHold: (Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (pressed) Color(0xFF555555) else Color(0xFF2A2A2A))
            .holdButton(
                onDown = { pressed = true; onHold(true) },
                onUp = { pressed = false; onHold(false) },
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}

/**
 * Press-and-hold input. The `finally` is load-bearing: without it a
 * gesture cancelled mid-press leaves the button stuck down (an
 * overlay-audit finding in Atomic/Poing).
 */
private fun Modifier.holdButton(onDown: () -> Unit, onUp: () -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown()
            onDown()
            try {
                do {
                    val event = awaitPointerEvent()
                } while (event.changes.any { it.pressed })
            } finally {
                onUp()
            }
        }
    }

@Composable
private fun PauseMenu(
    entry: LibraryEntry,
    session: PlayerSession,
    onResume: () -> Unit,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    onBenchmark: () -> Unit,
    onReport: () -> Unit,
    onExit: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onResume,
        title = { Text(entry.title) },
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
                TextButton(onClick = onBenchmark, modifier = Modifier.fillMaxWidth()) {
                    Text("Start benchmark (600 frames)")
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
