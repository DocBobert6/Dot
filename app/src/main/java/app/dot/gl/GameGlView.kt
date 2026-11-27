package app.dot.gl

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import app.dot.Benchmark
import java.util.concurrent.atomic.AtomicReference

// Dot — the GL picture view (GL-CHAIN-SPEC §9.1, §9.2). A
// GLSurfaceView (GLES 3.0, 8-bit RGBA, no depth/stencil,
// RENDERMODE_WHEN_DIRTY) hosting the GlRenderer, owning the
// pending-frame slot and the FrameRenderGate.
//
// The frame flow: the session posts each emulated frame
// (postFrame, emu thread) and the GameScreen reports when it
// lands (onFrameLanded, main thread). The gate requests exactly
// one render per frame, by whichever side completes second.
// The renderer reads the pending frame on the GL thread
// (takePendingFrame) and re-arms the gate.
//
// Surface.setFrameRate(59.73) is requested on the holder surface
// (API 30+); the window-attributes refresh rate is NOT used.
// If view creation throws (no GLES 3.0), there is no fallback
// picture: the host reports the error and the player shows an
// honest error instead.
// Copyright (c) 2026 Dot contributors. All rights reserved.

class GameGlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    /** The picture target: "1080p", "4k", or "auto". */
    private val pictureTarget: () -> String = { "auto" },
    private val gameTitle: String? = null,
    private val appVersion: String = "app.dot",
    /** Called (main thread) when the GL chain fails. */
    private val onGlFailed: ((where: String, program: String, e: Throwable) -> Unit)? = null,
    /** The test-pattern diagnostic flag (Developer toggle, default off). Read live on every frame. */
    private val testPattern: () -> Boolean = { false },
    /** The direct-pattern diagnostic flag (Developer toggle, default off). Read live on every frame. */
    private val directPattern: () -> Boolean = { false },
    /** The chain-stage bisection selector (Developer setting, default off). Read live on every frame. */
    private val chainStage: () -> String = { "off" },
    /** The per-stage centre-pixel probes (Developer toggle, default off). Read live on every frame. */
    private val stageProbes: () -> Boolean = { false },
) : GLSurfaceView(context, attrs), GlChainHost {

    private val gate = FrameRenderGate()
    private val pendingFrame = AtomicReference<GlFrame?>(null)

    /** The delivery gate (FrameDeliveryGate): an unchanged frame is dropped here, before the render gate. */
    private val deliveryGate = FrameDeliveryGate()

    /** The direct-pattern render mode, as last applied. */
    private var directModeOn = false

    /** The test-pattern flag as last seen by postFrame (toggle-change detection). */
    private var lastPatternOn = testPattern()

    /** The chain-stage selector as last seen by postFrame (change detection). */
    private var lastChainStage = chainStage()

    /** Set once the first frame has been delivered (first-frame log). */
    @Volatile private var firstFrameLogged = false

    /** The latest chain label (what actually drew), for benchmarks and reports. */
    @Volatile var chainLabel: String = "gl:deband>dither-invert>deflicker>scalefx3x+raa>sharp-bilinear>rcas"
        private set

    /** The last GL draw duration in nanoseconds (for the benchmark harness). */
    @Volatile var lastDrawNanos: Long = 0L
        private set

    /** Set when view creation or the renderer fails; the player shows this as an honest error. */
    @Volatile var glError: String? = null
        private set

    /** The renderer, null when view creation failed (see glError). */
    val renderer: GlRenderer?

    init {
        var created: GlRenderer? = null
        try {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            preserveEGLContextOnPause = true
            created = GlRenderer(context, this, pictureTarget, testPattern, directPattern, chainStage, stageProbes)
            setRenderer(created)
            renderMode = RENDERMODE_WHEN_DIRTY
        } catch (e: Throwable) {
            glError = "OpenGL ES 3.0 is unavailable: ${e.message}"
        }
        renderer = created
        // Request the game's frame rate on the holder surface. The
        // surface may not exist yet; the callback fires on creation.
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d(TAG, "DotGl: surfaceCreated")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        holder.surface.setFrameRate(
                            59.73f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                        )
                    } catch (_: Exception) {
                        // A surface that refuses the rate still draws;
                        // the emu loop paces the frames.
                    }
                }
            }

            override fun surfaceChanged(
                holder: SurfaceHolder, format: Int, width: Int, height: Int,
            ) {
                // The format is what the next bug report settles the
                // EGL-config question with: the chooser asks for
                // 8,8,8,8,0,0, but the 1.3 report showed format 4
                // (RGB_565) here.
                Log.d(TAG, "DotGl: surfaceChanged format=$format ${width}x$height")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d(TAG, "DotGl: surfaceDestroyed")
            }
        })
    }

    /**
     * Posts a frame (emu thread). Returns true when the frame was
     * delivered to the render gate, false when it was dropped as
     * unchanged.
     *
     * The delivery gate is the Phase 1.4 fix for the on-device
     * flashing: GLSurfaceView swaps buffers after EVERY
     * onDrawFrame, including the ones that draw nothing, so the
     * 316 hash-skips in the 1.3 counts each swapped a stale buffer
     * and the screen cycled them. An unchanged frame is dropped
     * here, before the gate, so no onDrawFrame and no swap happens
     * for a static frame.
     *
     * The gate's return value is load-bearing (the 1.2 fix):
     * whichever side completes a frame's pair requests the render.
     * A previous version discarded this value here, and a
     * one-frame slip between the emu, main, and GL threads wedged
     * the gate permanently — the pair completed on this side with
     * no render requested, so no draw ever re-armed it (the Phase
     * 1 black screen on device, v0.1.0-phase1.1). Never discard it.
     */
    fun postFrame(frame: GlFrame): Boolean {
        // A test-pattern toggle change forces the next frame
        // through: the pattern draws instead of the game, so the
        // gate's hash (which keys on the game frame) must not
        // suppress it — and turning the pattern off must re-draw
        // the game frame.
        val patternOn = testPattern()
        if (patternOn != lastPatternOn) {
            lastPatternOn = patternOn
            deliveryGate.invalidate()
            Log.d(TAG, "DotGl: test pattern ${if (patternOn) "ON" else "OFF"} — delivery gate reset")
        }
        // A chain-stage selector change forces the next frame
        // through for the same reason: the bypass draws a different
        // picture from the same game frame, so the gate's hash must
        // not suppress it — and returning to Off must re-draw the
        // full chain.
        val stage = chainStage()
        if (stage != lastChainStage) {
            lastChainStage = stage
            deliveryGate.invalidate()
            Log.d(TAG, "DotGl: chain stage $stage — delivery gate reset")
        }
        if (!deliveryGate.deliverIfChanged(frame.hash)) {
            GlDrawStats.noteHashSkipped()
            return false
        }
        if (!firstFrameLogged) {
            firstFrameLogged = true
            Log.d(TAG, "DotGl: first frame delivered hash=${frame.hash}")
        }
        pendingFrame.set(frame)
        if (gate.onFrameComplete()) {
            requestRenderIfReady()
        }
        return true
    }

    /** Reports that the posted frame has landed (main thread). Requests a render iff this completes the frame. */
    fun onFrameLanded() {
        if (gate.onFrameLanded()) {
            requestRenderIfReady()
        }
    }

    /**
     * Applies the direct-pattern diagnostic mode (Phase 1.5):
     * continuous rendering while the direct pattern is on, so it
     * redraws every vsync whether or not the game delivers frames;
     * on-demand rendering again when it turns off. The delivery
     * gate is invalidated on any change: turning the pattern off
     * must re-draw the game frame, which would otherwise be
     * suppressed as unchanged (its hash matches the last delivered
     * game frame) while the last presented buffer still holds the
     * pattern. Called from the player screen's settings poll, on
     * the main thread; setRenderMode is thread-safe.
     */
    fun setDirectPatternMode(on: Boolean) {
        if (on == directModeOn) return
        directModeOn = on
        deliveryGate.invalidate()
        renderMode = if (on) RENDERMODE_CONTINUOUSLY else RENDERMODE_WHEN_DIRTY
        Log.d(TAG, "DotGl: direct pattern ${if (on) "ON — continuous rendering" else "OFF — on-demand rendering"}")
    }

    /**
     * Applies the media-overlay z-order diagnostic (Phase 1.5):
     * the surface is placed above the app window instead of below
     * it. If the picture appears with this on, the window was
     * covering the surface (the hole-punch is the fault). The
     * on-screen draw stats hide while it is on — they are window
     * content, under the surface. The framework recreates the
     * surface when the order changes (see the surfaceCreated /
     * surfaceDestroyed logs). Diagnostic only, never a silent fix.
     */
    fun setMediaOverlay(on: Boolean) {
        setZOrderMediaOverlay(on)
        Log.d(TAG, "DotGl: media overlay ${if (on) "ON" else "OFF"}")
    }

    /**
     * Requests a render only when the GL thread exists. If view
     * creation failed (no GLES 3.0), setRenderer never ran, so
     * requestRender would crash — and the error screen owns that
     * case, not the frame path.
     */
    private fun requestRenderIfReady() {
        if (renderer != null) {
            requestRender()
        }
    }

    // ---- GlChainHost ------------------------------------------------------

    override fun takePendingFrame(): GlFrame? {
        val frame = pendingFrame.getAndSet(null)
        if (frame != null) {
            // Re-arm the gate after reading the slots, so a frame
            // completing mid-render still gets its own request.
            gate.beginRender()
        }
        return frame
    }

    override fun onDeliveryInvalidated(reason: String) {
        deliveryGate.invalidate()
        Log.d(TAG, "DotGl: delivery gate invalidated ($reason)")
    }

    override fun onGlFailed(where: String, program: String, e: Throwable) {
        glError = "Graphics failure ($where): ${e.message}"
        onGlFailed?.invoke(where, program, e)
    }

    override fun onDrawDurationNanos(nanos: Long) {
        lastDrawNanos = nanos
    }

    /**
     * A frame actually drew: tick the benchmark's presented clock
     * and the live FPS window. Before Phase 1.3 nothing in
     * production ever called Benchmark.recordPresented, so the
     * presented stats never ticked, a 600-frame capture could never
     * complete, and the FPS counter read 0.0 forever (proved on
     * device: the benchmark sat waiting and never finished).
     */
    override fun onFramePresented() {
        Benchmark.recordPresented()
    }

    override fun onChainLabel(label: String) {
        chainLabel = label
    }

    override fun glFailureGameTitle(): String? = gameTitle

    override fun glFailureAppVersion(): String = appVersion

    companion object {
        private const val TAG = "DotGl"
    }
}
