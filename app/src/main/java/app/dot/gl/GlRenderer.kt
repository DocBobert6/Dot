package app.dot.gl

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

// Dot — the GL picture chain renderer (GL-CHAIN-SPEC §1, §2, §9).
// Runs the locked chain once per emulated frame, on the GL thread:
// deband (CPU) -> dither-invert -> deflicker -> ScaleFX 3x -> rAA ->
// sharp-bilinear -> [Mitchell] -> RCAS (+ blue-noise output dither).
//
// Flip parity (§1.3) is computed from the passes actually run this
// frame, never a constant: inversion on = 10 FBO passes without
// Mitchell (12 with), inversion off = 9 (11 with); flip iff odd.
// Inversion is locked ON in Phase 1, so the parity is even (no
// flip) in practice — but the count is still computed, so a future
// toggle cannot silently mirror the picture (the alpha.65 lesson).
//
// A GL failure fails loudly (§9.3): the latch records it, the host
// is told, the report is written, and the surface clears to
// charcoal. There is no fallback picture.
// SPDX-License-Identifier: GPL-3.0-or-later

/** One frame posted to the renderer: the ARGB pixels, the shade plane, the content hash, and the deflicker mask. */
data class GlFrame(
    val argb: IntArray,
    val shades: ByteArray,
    val hash: Long,
    val deflickerMask: ByteArray,
)

/** The renderer's host (the view): failure + timing callbacks, and the pending-frame slot. */
interface GlChainHost {
    fun onGlFailed(where: String, program: String, e: Throwable)
    fun onDrawDurationNanos(nanos: Long)
    fun onChainLabel(label: String)
    fun glFailureGameTitle(): String?
    fun glFailureAppVersion(): String
    /** Reads and clears the pending frame; re-arms the render gate. Null when no frame is pending. */
    fun takePendingFrame(): GlFrame?
}

class GlRenderer(
    private val context: Context,
    private val host: GlChainHost,
    /** Returns the picture target: "1080p", "4k", or "auto". */
    private val pictureTarget: () -> String,
) : GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "DotGl"
        private val CHARCOAL = floatArrayOf(26f / 255f, 25f / 255f, 30f / 255f, 1f)
        const val SRC_W = 160
        const val SRC_H = 144
        const val SCALEFX_W = 480
        const val SCALEFX_H = 432
        private const val BLUE_NOISE_SIZE = 64
        private val IDENTITY_MATRIX = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
    }

    /** The failure latch (GlFailure.kt). */
    val latch = GlFailureLatch()

    /** The GPU renderer string, captured on the GL thread (null before the first surface creation). */
    @Volatile var gpuString: String? = null
        private set

    private val pool = FrameTargetPool()
    private val programs = mutableMapOf<Pair<String, String>, Int>()

    // Quad VBOs (unflipped + flipped), positions + texcoords interleaved.
    private var quadUnflippedVbo = 0
    private var quadFlippedVbo = 0

    // Persistent textures (not pooled): the debanded source, the
    // deflicker mask, and the blue-noise table.
    private var sourceTex = 0
    private var sourceTexAllocated = false
    private var maskTex = 0
    private var maskTexAllocated = false
    private var blueNoiseTex = 0

    // Pooled-target GL objects, keyed by the pool's target id.
    private data class TargetGl(val fbo: Int, val tex: Int)
    private val targetGl = mutableMapOf<Int, TargetGl>()

    // The held deflicker history target (a pooled target, excluded
    // from reuse while it is the history). -1 when there is none.
    private var historyTargetId = -1

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var resolvedTargetHeight = 1080
    private var frameCount = 0
    private var lastHash: Long = -1L

    // ---- GLSurfaceView.Renderer --------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        gpuString = GLES30.glGetString(GLES30.GL_RENDERER)
        // A fresh GL context means every old ID is dead: drop them
        // (never delete dead IDs) and rebuild.
        programs.clear()
        targetGl.clear()
        pool.dropAll()
        historyTargetId = -1
        quadUnflippedVbo = 0
        quadFlippedVbo = 0
        sourceTex = 0
        sourceTexAllocated = false
        maskTex = 0
        maskTexAllocated = false
        blueNoiseTex = 0
        lastHash = -1L
        buildQuads()
        buildPersistentTextures()
        // Clear the latch LAST: if anything above threw, it stays set.
        latch.clearOnSurfaceRebuilt()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        // The game target depends on surface size: drop every pooled
        // target (a stale pool across a change corrupts the chain).
        dropPooledTargets()
        resolvedTargetHeight = resolveTargetHeight()
    }

    override fun onDrawFrame(gl: GL10?) {
        if (latch.isFailed) {
            clearCharcoalFull()
            return
        }
        val startNanos = System.nanoTime()
        try {
            val frame = host.takePendingFrame() ?: return
            // Static-frame reuse (GL-CHAIN-SPEC §8.4): an unchanged
            // frame needs no re-render; the previous output stays.
            if (frame.hash == lastHash) return
            drawChain(frame)
            lastHash = frame.hash
        } catch (e: Throwable) {
            failGl("frame", "unknown", e)
        } finally {
            host.onDrawDurationNanos(System.nanoTime() - startNanos)
        }
    }

    // ---- the chain ----------------------------------------------------

    private fun drawChain(frame: GlFrame) {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) {
            clearCharcoalFull()
            return
        }
        val targetHeight = resolveTargetHeight()
        if (targetHeight != resolvedTargetHeight) {
            resolvedTargetHeight = targetHeight
            dropPooledTargets()
        }
        val internal = internalGameArea(targetHeight)
        val onScreen = onScreenGameArea(targetHeight)
        if (internal.first <= 0 || internal.second <= 0 ||
            onScreen.first <= 0 || onScreen.second <= 0
        ) {
            clearCharcoalFull()
            return
        }
        val useMitchell = internal.first > onScreen.first ||
            internal.second > onScreen.second
        val inversionOn = true // Locked ON in Phase 1.

        // The chain label names what actually drew (§1.1).
        host.onChainLabel(ChainParity.chainLabel(useMitchell))

        pool.beginFrame()
        val exclude = if (historyTargetId >= 0) setOf(historyTargetId) else emptySet()

        // 1. Deband the raw plane on CPU, upload as the source texture.
        val debanded = Deband.deband(frame.argb)
        uploadSource(debanded)
        uploadMask(frame.deflickerMask)

        // FBO pass count (for the flip parity, §1.3). Counted from
        // the passes actually run this frame, not a constant — the
        // counter below must stay in step with ChainParity.
        var fboPasses = 0

        // 2. Dither-invert the debanded plane.
        val invertTarget = acquireGl(SRC_W, SRC_H, TargetFilter.NEAREST, exclude)
        runPass(
            vert = "dot-common.vert", frag = "dither-invert.frag",
            inputTex = sourceTex, inputW = SRC_W, inputH = SRC_H,
            outFbo = invertTarget.fbo, outW = SRC_W, outH = SRC_H,
        )
        fboPasses++

        // 3. Deflicker-blend the inverted frame against the stored
        // inverted frame n-1 (the history target, or the inverted
        // frame itself on the first frame — blending a frame with
        // itself is a no-op).
        val prevTex = if (historyTargetId >= 0) {
            targetGl[historyTargetId]?.tex ?: invertTarget.tex
        } else {
            invertTarget.tex
        }
        val deflickerTarget = acquireGl(SRC_W, SRC_H, TargetFilter.NEAREST, exclude)
        runPass(
            vert = "dot-common.vert", frag = "deflicker.frag",
            inputTex = invertTarget.tex, inputW = SRC_W, inputH = SRC_H,
            extraTex = prevTex, extraName = "PrevTexture", extraW = SRC_W, extraH = SRC_H,
            maskTex = maskTex,
            outFbo = deflickerTarget.fbo, outW = SRC_W, outH = SRC_H,
        )
        fboPasses++
        // The current inverted frame becomes the history; the old
        // history target is released (no longer excluded).
        historyTargetId = invertTarget.id

        // The chain source for ScaleFX pass 4 is the frame as pass 0
        // saw it: the deflicker output.
        val chainSourceTex = deflickerTarget.tex

        // 4. ScaleFX passes 0-4.
        val metricTarget = acquireGl(SRC_W, SRC_H, TargetFilter.NEAREST, exclude)
        runPass(
            vert = "scalefx-pass0.vert", frag = "scalefx-pass0.frag",
            inputTex = deflickerTarget.tex, inputW = SRC_W, inputH = SRC_H,
            outFbo = metricTarget.fbo, outW = SRC_W, outH = SRC_H,
        )
        fboPasses++
        val strengthTarget = acquireGl(SRC_W, SRC_H, TargetFilter.NEAREST, exclude)
        runPass(
            vert = "scalefx-pass1.vert", frag = "scalefx-pass1.frag",
            inputTex = metricTarget.tex, inputW = SRC_W, inputH = SRC_H,
            outFbo = strengthTarget.fbo, outW = SRC_W, outH = SRC_H,
        )
        fboPasses++
        val junctionTarget = acquireGl(SRC_W, SRC_H, TargetFilter.NEAREST, exclude)
        runPass(
            vert = "scalefx-pass2.vert", frag = "scalefx-pass2.frag",
            inputTex = strengthTarget.tex, inputW = SRC_W, inputH = SRC_H,
            extraTex = metricTarget.tex, extraName = "PassPrev2Texture",
            extraW = SRC_W, extraH = SRC_H,
            outFbo = junctionTarget.fbo, outW = SRC_W, outH = SRC_H,
        )
        fboPasses++
        val tagTarget = acquireGl(SRC_W, SRC_H, TargetFilter.NEAREST, exclude)
        runPass(
            vert = "scalefx-pass3.vert", frag = "scalefx-pass3.frag",
            inputTex = junctionTarget.tex, inputW = SRC_W, inputH = SRC_H,
            outFbo = tagTarget.fbo, outW = SRC_W, outH = SRC_H,
        )
        fboPasses++
        val scalefxTarget = acquireGl(SCALEFX_W, SCALEFX_H, TargetFilter.NEAREST, exclude)
        runPass(
            vert = "scalefx-pass4.vert", frag = "scalefx-pass4.frag",
            inputTex = tagTarget.tex, inputW = SRC_W, inputH = SRC_H,
            extraTex = chainSourceTex, extraName = "PassPrev5Texture",
            extraW = SRC_W, extraH = SRC_H,
            outFbo = scalefxTarget.fbo, outW = SCALEFX_W, outH = SCALEFX_H,
        )
        fboPasses++

        // 5. rAA passes 0-1 (horizontal, then vertical).
        val raa0Target = acquireGl(SCALEFX_W, SCALEFX_H, TargetFilter.NEAREST, exclude)
        runPass(
            vert = "raa-pass0.vert", frag = "raa-pass0.frag",
            inputTex = scalefxTarget.tex, inputW = SCALEFX_W, inputH = SCALEFX_H,
            outFbo = raa0Target.fbo, outW = SCALEFX_W, outH = SCALEFX_H,
        )
        fboPasses++
        val raa1Target = acquireGl(SCALEFX_W, SCALEFX_H, TargetFilter.NEAREST, exclude)
        runPass(
            vert = "raa-pass1.vert", frag = "raa-pass1.frag",
            inputTex = raa0Target.tex, inputW = SCALEFX_W, inputH = SCALEFX_H,
            outFbo = raa1Target.fbo, outW = SCALEFX_W, outH = SCALEFX_H,
        )
        fboPasses++

        // 6. Sharp-bilinear final resample to the internal game area
        // (the game target is LINEAR — it is downsampled by Mitchell
        // or sampled by RCAS).
        val gameTarget = acquireGl(
            internal.first, internal.second, TargetFilter.LINEAR, exclude,
        )
        runPass(
            vert = "dot-common.vert", frag = "sharp-bilinear.frag",
            inputTex = raa1Target.tex, inputW = SCALEFX_W, inputH = SCALEFX_H,
            outFbo = gameTarget.fbo, outW = internal.first, outH = internal.second,
        )
        fboPasses++

        // 7. Mitchell downscale, only when internal > screen.
        var rcasInputTex = gameTarget.tex
        var rcasInputW = internal.first
        var rcasInputH = internal.second
        if (useMitchell) {
            val s = internal.first.toFloat() / onScreen.first.toFloat()
            val mitchellH = acquireGl(
                onScreen.first, internal.second, TargetFilter.NEAREST, exclude,
            )
            runPass(
                vert = "dot-common.vert", frag = "mitchell-h.frag",
                inputTex = gameTarget.tex, inputW = internal.first, inputH = internal.second,
                downscale = s,
                outFbo = mitchellH.fbo, outW = onScreen.first, outH = internal.second,
            )
            fboPasses++
            val mitchellV = acquireGl(
                onScreen.first, onScreen.second, TargetFilter.NEAREST, exclude,
            )
            runPass(
                vert = "dot-common.vert", frag = "mitchell-v.frag",
                inputTex = mitchellH.tex, inputW = onScreen.first, inputH = internal.second,
                downscale = s,
                outFbo = mitchellV.fbo, outW = onScreen.first, outH = onScreen.second,
            )
            fboPasses++
            rcasInputTex = mitchellV.tex
            rcasInputW = onScreen.first
            rcasInputH = onScreen.second
        }

        // 8. RCAS to the screen. The final flip is the parity of the
        // FBO passes actually run (§1.3) — and it must agree with
        // the pure table (ChainParity), which the tests pin.
        val flip = fboPasses % 2 == 1
        check(flip == ChainParity.needsFinalFlip(inversionOn, useMitchell)) {
            "FBO pass count $fboPasses disagrees with ChainParity " +
                "(expected ${ChainParity.fboPassCount(inversionOn, useMitchell)})"
        }
        clearCharcoalFull()
        val offsetX = (surfaceWidth - onScreen.first) / 2
        val offsetY = (surfaceHeight - onScreen.second) / 2
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        runPass(
            vert = "rcas.vert", frag = "rcas.frag",
            inputTex = rcasInputTex, inputW = rcasInputW, inputH = rcasInputH,
            outFbo = 0, outW = onScreen.first, outH = onScreen.second,
            viewportX = offsetX, viewportY = offsetY,
            flipped = flip, blueNoise = true,
        )
        frameCount++
        checkGl("chain")
    }

    // ---- framing (§1.2, §1.5) ----------------------------------------

    /** The resolved target height for the picture setting. */
    fun resolveTargetHeight(): Int {
        return when (pictureTarget()) {
            "1080p" -> 1080
            "4k" -> 2160
            else -> {
                // Auto (and any unknown value): follow the display.
                if (surfaceWidth <= 0 || surfaceHeight <= 0) return 1080
                val scale = minOf(
                    surfaceWidth / SRC_W, surfaceHeight / SRC_H,
                ).coerceAtLeast(1)
                SRC_H * scale
            }
        }
    }

    /** The internal game area (FBO) for a target height. */
    fun internalGameArea(targetHeight: Int): Pair<Int, Int> {
        val scale = (targetHeight / SRC_H).coerceAtLeast(1)
        return (SRC_W * scale) to (SRC_H * scale)
    }

    /** The on-screen game area for a target height (centred). */
    fun onScreenGameArea(targetHeight: Int): Pair<Int, Int> {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return 0 to 0
        val scale = minOf(
            surfaceWidth / SRC_W, surfaceHeight / SRC_H, targetHeight / SRC_H,
        ).coerceAtLeast(1)
        return (SRC_W * scale) to (SRC_H * scale)
    }

    // ---- target acquisition (§1.4) ------------------------------------

    private data class AcquiredTarget(val id: Int, val fbo: Int, val tex: Int)

    private fun acquireGl(
        width: Int, height: Int, filter: TargetFilter, exclude: Set<Int>,
    ): AcquiredTarget {
        val t = pool.acquire(width, height, filter, exclude)
        val existing = targetGl[t.id]
        if (existing != null) return AcquiredTarget(t.id, existing.fbo, existing.tex)
        val created = createTarget(width, height, filter)
        targetGl[t.id] = created
        return AcquiredTarget(t.id, created.fbo, created.tex)
    }

    /** Allocates an FBO + texture: RGBA16F first, RGBA8 on failure, else throw loudly. */
    private fun createTarget(width: Int, height: Int, filter: TargetFilter): TargetGl {
        val glFilter = if (filter == TargetFilter.LINEAR) GLES30.GL_LINEAR else GLES30.GL_NEAREST
        for (format in listOf(GLES30.GL_RGBA16F to GLES30.GL_HALF_FLOAT, GLES30.GL_RGBA8 to GLES30.GL_UNSIGNED_BYTE)) {
            val (internalFormat, type) = format
            val texIds = IntArray(1)
            GLES30.glGenTextures(1, texIds, 0)
            val tex = texIds[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, glFilter)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, glFilter)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, internalFormat, width, height, 0,
                GLES30.GL_RGBA, type, null,
            )
            val fboIds = IntArray(1)
            GLES30.glGenFramebuffers(1, fboIds, 0)
            val fbo = fboIds[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, tex, 0,
            )
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            if (status == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                return TargetGl(fbo, tex)
            }
            // Incomplete at this format: delete and try the fallback.
            GLES30.glDeleteFramebuffers(1, fboIds, 0)
            GLES30.glDeleteTextures(1, texIds, 0)
        }
        throw IllegalStateException("FBO incomplete at ${width}x$height")
    }

    private fun dropPooledTargets() {
        val fbos = targetGl.values.map { it.fbo }.toIntArray()
        val texs = targetGl.values.map { it.tex }.toIntArray()
        if (fbos.isNotEmpty()) GLES30.glDeleteFramebuffers(fbos.size, fbos, 0)
        if (texs.isNotEmpty()) GLES30.glDeleteTextures(texs.size, texs, 0)
        targetGl.clear()
        pool.dropAll()
        historyTargetId = -1
    }

    // ---- uploads -------------------------------------------------------

    private fun uploadSource(argb: IntArray) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTex)
        val buf = ByteBuffer.allocateDirect(argb.size * 4)
            .order(ByteOrder.nativeOrder()).asIntBuffer()
        buf.put(argb).position(0)
        if (!sourceTexAllocated) {
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, SRC_W, SRC_H, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf,
            )
            sourceTexAllocated = true
        } else {
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D, 0, 0, 0, SRC_W, SRC_H,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf,
            )
        }
    }

    private fun uploadMask(mask: ByteArray) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex)
        val buf = ByteBuffer.allocateDirect(mask.size)
            .order(ByteOrder.nativeOrder())
        buf.put(mask).position(0)
        if (!maskTexAllocated) {
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, SRC_W, SRC_H, 0,
                GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, buf,
            )
            maskTexAllocated = true
        } else {
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D, 0, 0, 0, SRC_W, SRC_H,
                GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, buf,
            )
        }
    }

    // ---- passes ---------------------------------------------------------

    /**
     * Runs one fullscreen pass. The main input is bound to unit 0
     * (samplers Texture / s_p / uTexture); the extra input, when
     * given, to unit 1 (PassPrev2Texture / PassPrev5Texture /
     * PrevTexture); the deflicker mask to unit 2 (BlendMask); the
     * blue-noise table to unit 1 for RCAS (BlueNoise). Uniforms
     * that a program does not declare are skipped (location < 0).
     */
    private fun runPass(
        vert: String, frag: String,
        inputTex: Int, inputW: Int, inputH: Int,
        extraTex: Int = 0, extraName: String = "", extraW: Int = 0, extraH: Int = 0,
        maskTex: Int = 0,
        outFbo: Int, outW: Int, outH: Int,
        viewportX: Int = 0, viewportY: Int = 0,
        flipped: Boolean = false,
        blueNoise: Boolean = false,
        downscale: Float = 0f,
    ) {
        val program = getProgram(vert, frag)
        GLES30.glUseProgram(program)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outFbo)
        // The screen pass (RCAS) draws inside the on-screen rect;
        // every FBO pass draws at (0,0) of its target.
        GLES30.glViewport(viewportX, viewportY, outW, outH)

        // Texture units.
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTex)
        setInt(program, "Texture", 0)
        setInt(program, "s_p", 0)
        setInt(program, "uTexture", 0)
        if (extraTex != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, extraTex)
            if (extraName.isNotEmpty()) setInt(program, extraName, 1)
        }
        if (maskTex != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex)
            setInt(program, "BlendMask", 2)
        }
        if (blueNoise) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blueNoiseTex)
            setInt(program, "BlueNoise", 1)
            val (ox, oy) = ResampleMath.blueNoiseOffset(frameCount)
            setVec2(program, "BlueNoiseOffset", ox.toFloat(), oy.toFloat())
        }

        // Sizes (§2): TextureSize = InputSize = main input size;
        // OutputSize = output size; the PassPrev* sizes are the
        // extra texture's size when one is bound, else the input's.
        setVec2(program, "TextureSize", inputW.toFloat(), inputH.toFloat())
        setVec2(program, "InputSize", inputW.toFloat(), inputH.toFloat())
        setVec2(program, "OutputSize", outW.toFloat(), outH.toFloat())
        val prevW = if (extraTex != 0) extraW else inputW
        val prevH = if (extraTex != 0) extraH else inputH
        setVec2(program, "PassPrev5TextureSize", prevW.toFloat(), prevH.toFloat())
        setVec2(program, "PassPrev5InputSize", inputW.toFloat(), inputH.toFloat())
        setVec2(program, "PassPrev2TextureSize", prevW.toFloat(), prevH.toFloat())
        setInt(program, "FrameCount", frameCount)
        setInt(program, "FrameDirection", 1)
        setVec2(program, "uTexel", 1f / inputW, 1f / inputH)
        if (downscale > 0f) setFloat(program, "Downscale", downscale)

        // Identity MVP.
        val mvpLoc = GLES30.glGetUniformLocation(program, "MVPMatrix")
        if (mvpLoc >= 0) {
            GLES30.glUniformMatrix4fv(mvpLoc, 1, false, IDENTITY_MATRIX, 0)
        }

        // The quad (flipped for the screen pass when parity says so).
        val vbo = if (flipped) quadFlippedVbo else quadUnflippedVbo
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        val posLoc = GLES30.glGetAttribLocation(program, "VertexCoord")
        if (posLoc >= 0) {
            GLES30.glEnableVertexAttribArray(posLoc)
            GLES30.glVertexAttribPointer(posLoc, 4, GLES30.GL_FLOAT, false, 24, 0)
        }
        val texLoc = GLES30.glGetAttribLocation(program, "TexCoord")
        if (texLoc >= 0) {
            GLES30.glEnableVertexAttribArray(texLoc)
            GLES30.glVertexAttribPointer(texLoc, 2, GLES30.GL_FLOAT, false, 24, 16)
        }
        // The MIT shaders' COLOR attribute is a constant white.
        val colorLoc = GLES30.glGetAttribLocation(program, "COLOR")
        if (colorLoc >= 0) {
            GLES30.glDisableVertexAttribArray(colorLoc)
            GLES30.glVertexAttrib4f(colorLoc, 1f, 1f, 1f, 1f)
        }
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        if (posLoc >= 0) GLES30.glDisableVertexAttribArray(posLoc)
        if (texLoc >= 0) GLES30.glDisableVertexAttribArray(texLoc)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    // ---- programs ---------------------------------------------------------

    private fun getProgram(vertAsset: String, fragAsset: String): Int {
        val key = vertAsset to fragAsset
        programs[key]?.let { return it }
        val vertSrc = context.assets.open("shaders/$vertAsset").readBytes().toString(Charsets.UTF_8)
        val fragSrc = context.assets.open("shaders/$fragAsset").readBytes().toString(Charsets.UTF_8)
        val vert = compileShader(GLES30.GL_VERTEX_SHADER, vertAsset, vertSrc)
        val frag = compileShader(GLES30.GL_FRAGMENT_SHADER, fragAsset, fragSrc)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vert)
        GLES30.glAttachShader(program, frag)
        GLES30.glLinkProgram(program)
        val linked = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw IllegalStateException("Program link failed ($vertAsset + $fragAsset): $log")
        }
        GLES30.glDeleteShader(vert)
        GLES30.glDeleteShader(frag)
        programs[key] = program
        return program
    }

    private fun compileShader(type: Int, name: String, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("Shader compile failed ($name): $log")
        }
        return shader
    }

    // ---- quads and persistent textures ------------------------------------

    private fun buildQuads() {
        quadUnflippedVbo = createQuadVbo(flipped = false)
        quadFlippedVbo = createQuadVbo(flipped = true)
    }

    /** One fullscreen triangle strip (BL, BR, TL, TR), positions + texcoords interleaved. */
    private fun createQuadVbo(flipped: Boolean): Int {
        // Unflipped texcoords: top samples v=0 (bitmaps upload
        // top-row-first). Flipped swaps them (§1.3).
        val tc = if (flipped) {
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        } else {
            floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        }
        val pos = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 0f, 1f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 0f, 1f,
        )
        val data = FloatArray(24)
        for (i in 0 until 4) {
            data[i * 6] = pos[i * 4]
            data[i * 6 + 1] = pos[i * 4 + 1]
            data[i * 6 + 2] = pos[i * 4 + 2]
            data[i * 6 + 3] = pos[i * 4 + 3]
            data[i * 6 + 4] = tc[i * 2]
            data[i * 6 + 5] = tc[i * 2 + 1]
        }
        val buf: FloatBuffer = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(data).position(0)
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, buf, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        return ids[0]
    }

    private fun buildPersistentTextures() {
        // The debanded source (RGBA8, NEAREST, 160x144).
        val srcIds = IntArray(1)
        GLES30.glGenTextures(1, srcIds, 0)
        sourceTex = srcIds[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        // The deflicker mask (R8, NEAREST, 160x144).
        val maskIds = IntArray(1)
        GLES30.glGenTextures(1, maskIds, 0)
        maskTex = maskIds[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        // The blue-noise table (R8, REPEAT, 64x64).
        val noiseIds = IntArray(1)
        GLES30.glGenTextures(1, noiseIds, 0)
        blueNoiseTex = noiseIds[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blueNoiseTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
        val noiseBuf = ByteBuffer.allocateDirect(BLUE_NOISE_64.size)
            .order(ByteOrder.nativeOrder())
        noiseBuf.put(BLUE_NOISE_64).position(0)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, BLUE_NOISE_SIZE, BLUE_NOISE_SIZE, 0,
            GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, noiseBuf,
        )
    }

    // ---- failure path (§9.3) ----------------------------------------------

    private fun failGl(where: String, program: String, e: Throwable) {
        latch.markFailed()
        clearCharcoalFull()
        val failure = GlFailure(
            time = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss z", java.util.Locale.US,
            ).format(java.util.Date()),
            appVersion = host.glFailureAppVersion(),
            device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})",
            gpu = gpuString,
            game = host.glFailureGameTitle(),
            where = where,
            program = program,
            fallbackEngaged = false,
            exceptionText = buildString {
                append(e.javaClass.name)
                append(": ")
                append(e.message)
                append("\n")
                for (line in e.stackTrace) append(line).append("\n")
            },
        )
        try {
            GlFailureReport.write(context.filesDir, failure)
        } catch (_: Exception) {
            // The report write never throws (it swallows internally);
            // this is a second guard so a failing chain never dies twice.
        }
        host.onGlFailed(where, program, e)
    }

    private fun clearCharcoalFull() {
        GLES30.glViewport(0, 0, surfaceWidth.coerceAtLeast(1), surfaceHeight.coerceAtLeast(1))
        GLES30.glClearColor(CHARCOAL[0], CHARCOAL[1], CHARCOAL[2], CHARCOAL[3])
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }

    /** A silent GL error (no throw): log it, do not latch (§9.3). */
    private fun checkGl(where: String) {
        val err = GLES30.glGetError()
        if (err != GLES30.GL_NO_ERROR) {
            Log.w(TAG, "GL error after $where: $err")
        }
    }

    // ---- uniform helpers (location < 0 is a no-op) --------------------------

    private fun setInt(program: Int, name: String, v: Int) {
        val loc = GLES30.glGetUniformLocation(program, name)
        if (loc >= 0) GLES30.glUniform1i(loc, v)
    }

    private fun setFloat(program: Int, name: String, v: Float) {
        val loc = GLES30.glGetUniformLocation(program, name)
        if (loc >= 0) GLES30.glUniform1f(loc, v)
    }

    private fun setVec2(program: Int, name: String, x: Float, y: Float) {
        val loc = GLES30.glGetUniformLocation(program, name)
        if (loc >= 0) GLES30.glUniform2f(loc, x, y)
    }
}
