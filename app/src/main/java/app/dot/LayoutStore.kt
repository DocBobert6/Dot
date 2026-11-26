package app.dot

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import kotlin.math.roundToInt

// Dot — control-layout model, storage, and resolution (SHELL-SPEC §3).
//
// Every control is one spec: its centre as a FRACTION of the full
// screen (so a layout survives rotation and screen sizes) plus its
// size in dp. Fractions are of the FULL screen, not the player area.
// Controls may sit anywhere, including over the game.
//
// Storage: one `.properties` file per game in filesDir/control-layouts/,
// named by the game's SHA stem (`<sha>.properties`). Keys: one triple
// per control — `<id>.cx` / `<id>.cy` (fractions) / `<id>.size` (dp) —
// plus `opacity` (0-100, default 100). The single customised default
// layout lives in the reserved file `default.properties` in the same
// directory. There is exactly one default file: Dot has no per-game
// picker UI (SHELL-SPEC §3.4), and the task confirms it.
//
// Resolution order: (1) the game's own file, if it exists; (2) the
// effective default (the customised default file when one exists,
// else the factory layout); (3) the factory layout.
//
// Commit rule (Done): a draft that lands exactly on the effective
// default layout CLEARS the per-game override instead of saving a
// stale copy, so a later edit of the default flows through. The
// Settings default editor compares against the factory layout
// instead: equal clears the customised default.
//
// Clamps: a centre is never closer than 0.03 to any edge (fractions
// 0.03-0.97); size is clamped to 36-220 dp. Every loaded control is
// clamped, so a hand-edited file can never strand a control off
// screen. An unparseable or non-finite value falls back to the
// default for that field only.
//
// The factory layout is re-derived for Dot's portrait canvas
// (reference 411x923 dp — Pixel 10a portrait): the 10:9 game area
// fills the width at the top (411x370 dp), the controls sit below
// it. Atomic's numbers are landscape and do NOT carry over.
// Copyright (c) 2026 Dot contributors. All rights reserved.

/** One control's stored spec: centre as a screen fraction, size in dp. */
data class ControlSpec(val cx: Float, val cy: Float, val sizeDp: Int)

/** A whole layout: every control's spec plus the whole-layout opacity. */
data class ControlLayout(
    val controls: Map<String, ControlSpec>,
    val opacity: Int = 100,
)

object LayoutStore {

    /** Control ids, in file order. DMG has no shoulders: no `l` / `r`. */
    val CONTROL_IDS = listOf("dpad", "a", "b", "start", "select", "ff", "slow", "rw")

    /** Clusters move/resize together by default. */
    val CLUSTERS: List<List<String>> = listOf(
        listOf("dpad"),
        listOf("a", "b"),
        listOf("start", "select"),
        listOf("ff", "slow", "rw"),
    )

    /** Reference canvas the factory layout is defined on (dp, portrait). */
    const val FACTORY_CANVAS_WIDTH_DP = 411f
    const val FACTORY_CANVAS_HEIGHT_DP = 923f

    const val MIN_SIZE_DP = 36
    const val MAX_SIZE_DP = 220
    const val EDGE_CLAMP = 0.03f
    const val SIZE_STEP_DP = 4
    const val OPACITY_STEP = 5

    const val KEY_EDITOR_SNAP = "editor_snap"
    const val KEY_EDITOR_TOGETHER = "editor_together"

    fun layoutsDir(filesDir: File): File = File(filesDir, "control-layouts")

    fun layoutFile(filesDir: File, sha256: String): File =
        File(layoutsDir(filesDir), "$sha256.properties")

    /** The single customised default layout file. */
    fun defaultFile(filesDir: File): File =
        File(layoutsDir(filesDir), "default.properties")

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("dot-settings", Context.MODE_PRIVATE)

    fun isSnapOn(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_EDITOR_SNAP, true)

    fun isTogetherOn(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_EDITOR_TOGETHER, true)

    fun setSnapOn(prefs: SharedPreferences, on: Boolean) {
        prefs.edit().putBoolean(KEY_EDITOR_SNAP, on).apply()
    }

    fun setTogetherOn(prefs: SharedPreferences, on: Boolean) {
        prefs.edit().putBoolean(KEY_EDITOR_TOGETHER, on).apply()
    }

    /** The factory layout, as fractions of the portrait reference canvas. */
    fun factoryLayout(): ControlLayout {
        fun spec(xDp: Float, yDp: Float, sizeDp: Int): ControlSpec =
            ControlSpec(
                (xDp / FACTORY_CANVAS_WIDTH_DP).coerceIn(EDGE_CLAMP, 1f - EDGE_CLAMP),
                (yDp / FACTORY_CANVAS_HEIGHT_DP).coerceIn(EDGE_CLAMP, 1f - EDGE_CLAMP),
                sizeDp.coerceIn(MIN_SIZE_DP, MAX_SIZE_DP),
            )
        // Game area occupies y 0..370. Controls below it.
        return ControlLayout(
            controls = mapOf(
                "dpad" to spec(88f, 575f, 144),
                "a" to spec(352f, 575f, 64),
                "b" to spec(282f, 575f, 64),
                "start" to spec(150f, 880f, 64),
                "select" to spec(216f, 880f, 64),
                "rw" to spec(268f, 880f, 48),
                "slow" to spec(324f, 880f, 48),
                "ff" to spec(380f, 880f, 48),
            ),
            opacity = 100,
        )
    }

    /** Clamps one loaded spec against the factory spec for that control. */
    fun clampSpec(spec: ControlSpec, factory: ControlSpec): ControlSpec {
        return ControlSpec(
            cx = spec.cx.takeIf { it.isFinite() }?.coerceIn(EDGE_CLAMP, 1f - EDGE_CLAMP)
                ?: factory.cx,
            cy = spec.cy.takeIf { it.isFinite() }?.coerceIn(EDGE_CLAMP, 1f - EDGE_CLAMP)
                ?: factory.cy,
            sizeDp = spec.sizeDp.coerceIn(MIN_SIZE_DP, MAX_SIZE_DP),
        )
    }

    /** Parses a layout file's text. A corrupt file reads as the factory default, never a crash. */
    fun parse(text: String): ControlLayout {
        val factory = factoryLayout()
        val values = HashMap<String, String>()
        for (rawLine in text.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            values[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
        }
        val controls = HashMap<String, ControlSpec>()
        for (id in CONTROL_IDS) {
            val f = factory.controls.getValue(id)
            val cx = values["$id.cx"]?.toFloatOrNull() ?: f.cx
            val cy = values["$id.cy"]?.toFloatOrNull() ?: f.cy
            val size = values["$id.size"]?.toIntOrNull() ?: f.sizeDp
            controls[id] = clampSpec(ControlSpec(cx, cy, size), f)
        }
        val opacity = values["opacity"]?.toIntOrNull()?.coerceIn(0, 100) ?: 100
        return ControlLayout(controls, opacity)
    }

    fun encode(layout: ControlLayout): String {
        val out = StringBuilder()
        for (id in CONTROL_IDS) {
            val spec = layout.controls[id] ?: continue
            out.append("$id.cx=${spec.cx}\n")
            out.append("$id.cy=${spec.cy}\n")
            out.append("$id.size=${spec.sizeDp}\n")
        }
        out.append("opacity=${layout.opacity}\n")
        return out.toString()
    }

    /** The effective default: the customised default file when one exists, else the factory layout. */
    fun effectiveDefault(filesDir: File): ControlLayout {
        val file = defaultFile(filesDir)
        if (!file.exists()) return factoryLayout()
        return try {
            parse(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            factoryLayout()
        }
    }

    fun hasCustomDefault(filesDir: File): Boolean = defaultFile(filesDir).exists()

    /**
     * The resolved layout for a game: its own file, else the
     * effective default, else the factory layout. A missing file
     * means the defaults; a corrupt file means the factory default.
     */
    fun load(filesDir: File, sha256: String): ControlLayout {
        val file = layoutFile(filesDir, sha256)
        if (!file.exists()) return effectiveDefault(filesDir)
        return try {
            parse(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            factoryLayout()
        }
    }

    /**
     * Saves a draft as the game's override — unless the draft lands
     * exactly on the effective default, in which case the override
     * is cleared instead, so a later edit of the default flows
     * through. Returns false only when the write itself fails; the
     * previous file is left in place on failure.
     */
    fun save(filesDir: File, sha256: String, draft: ControlLayout): Boolean {
        if (draft == effectiveDefault(filesDir)) {
            return clearOverride(filesDir, sha256)
        }
        return Store.atomicWrite(layoutFile(filesDir, sha256), encode(draft).toByteArray(Charsets.UTF_8))
    }

    /**
     * Saves a draft as the customised default — unless it lands
     * exactly on the factory layout, in which case the customised
     * default is cleared and the factory applies again.
     */
    fun saveDefault(filesDir: File, draft: ControlLayout): Boolean {
        if (draft == factoryLayout()) {
            return clearDefault(filesDir)
        }
        return Store.atomicWrite(defaultFile(filesDir), encode(draft).toByteArray(Charsets.UTF_8))
    }

    /** Clears a game's override (its layout file). */
    fun clearOverride(filesDir: File, sha256: String): Boolean {
        val file = layoutFile(filesDir, sha256)
        return try {
            if (!file.exists()) true else file.delete()
        } catch (_: Exception) {
            false
        }
    }

    /** Clears the customised default (its file and, for Dot, nothing else — there are no sidecars). */
    fun clearDefault(filesDir: File): Boolean {
        val file = defaultFile(filesDir)
        return try {
            if (!file.exists()) true else file.delete()
        } catch (_: Exception) {
            false
        }
    }

    /** Sizes in dp from fractions on a canvas of the given size. */
    fun controlPx(
        spec: ControlSpec,
        canvasWidthPx: Float,
        canvasHeightPx: Float,
        density: Float,
    ): ControlPx {
        val sizePx = spec.sizeDp * density
        return ControlPx(
            centerX = spec.cx * canvasWidthPx,
            centerY = spec.cy * canvasHeightPx,
            sizePx = sizePx,
        )
    }

    data class ControlPx(val centerX: Float, val centerY: Float, val sizePx: Float)

    /** Snaps a centre fraction to the 8 dp grid on this canvas. */
    fun snapFraction(
        fraction: Float,
        canvasPx: Float,
        density: Float,
        gridDp: Float = 8f,
    ): Float {
        if (canvasPx <= 0f || density <= 0f) return fraction
        val px = fraction * canvasPx
        val snapped = (px / (gridDp * density)).roundToInt() * gridDp * density
        return (snapped / canvasPx).coerceIn(EDGE_CLAMP, 1f - EDGE_CLAMP)
    }
}
