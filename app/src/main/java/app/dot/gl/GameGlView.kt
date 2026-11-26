package app.dot.gl

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Build
import android.util.AttributeSet
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
) : GLSurfaceView(context, attrs), GlChainHost {

    private val gate = FrameRenderGate()
    private val pendingFrame = AtomicReference<GlFrame?>(null)

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
            created = GlRenderer(context, this, pictureTarget)
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
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
    }

    /**
     * Posts a frame (emu thread). The gate's frameComplete side.
     *
     * The gate's return value is load-bearing: whichever side
     * completes a frame's pair requests the render. A previous
     * version discarded this value here, and a one-frame slip
     * between the emu, main, and GL threads wedged the gate
     * permanently — the pair completed on this side with no
     * render requested, so no draw ever re-armed it (the Phase 1
     * black screen on device, v0.1.0-phase1.1). Never discard it.
     */
    fun postFrame(frame: GlFrame) {
        pendingFrame.set(frame)
        if (gate.onFrameComplete()) {
            requestRenderIfReady()
        }
    }

    /** Reports that the posted frame has landed (main thread). Requests a render iff this completes the frame. */
    fun onFrameLanded() {
        if (gate.onFrameLanded()) {
            requestRenderIfReady()
        }
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
}
