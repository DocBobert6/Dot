package app.dot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Dot — layout store + D-pad math tests (pure logic, no device).
// Copyright (c) 2026 Dot contributors. All rights reserved.

class LayoutStoreTest {

    private fun dir(): File = createTempDir("dot-layout-test")

    @Test
    fun factoryLayoutHasEveryControl() {
        val factory = LayoutStore.factoryLayout()
        assertEquals(LayoutStore.CONTROL_IDS.size, factory.controls.size)
        for (id in LayoutStore.CONTROL_IDS) {
            val spec = factory.controls.getValue(id)
            assertTrue(spec.cx in 0.03f..0.97f)
            assertTrue(spec.cy in 0.03f..0.97f)
            assertTrue(spec.sizeDp in 36..220)
        }
        assertEquals(100, factory.opacity)
    }

    @Test
    fun loadFallsBackToFactoryWithNoFiles() {
        assertEquals(LayoutStore.factoryLayout(), LayoutStore.load(dir(), "abc"))
    }

    @Test
    fun customisedDefaultAppliesWhenNoGameOverride() {
        val files = dir()
        val draft = LayoutStore.factoryLayout().copy(
            controls = LayoutStore.factoryLayout().controls + (
                "a" to ControlSpec(0.5f, 0.5f, 80)
                ),
        )
        assertTrue(LayoutStore.saveDefault(files, draft))
        assertEquals(draft, LayoutStore.load(files, "abc"))
        assertTrue(LayoutStore.hasCustomDefault(files))
    }

    @Test
    fun gameOverrideWinsOverDefault() {
        val files = dir()
        val default = LayoutStore.factoryLayout().copy(opacity = 80)
        assertTrue(LayoutStore.saveDefault(files, default))
        val game = LayoutStore.factoryLayout().copy(opacity = 50)
        assertTrue(LayoutStore.save(files, "abc", game))
        assertEquals(game, LayoutStore.load(files, "abc"))
        assertEquals(default, LayoutStore.load(files, "xyz"))
    }

    @Test
    fun savingDefaultLayoutClearsTheOverride() {
        val files = dir()
        val game = LayoutStore.factoryLayout().copy(opacity = 50)
        assertTrue(LayoutStore.save(files, "abc", game))
        // The draft lands exactly on the effective default (factory):
        // the override is cleared, not saved.
        assertTrue(LayoutStore.save(files, "abc", LayoutStore.factoryLayout()))
        assertEquals(LayoutStore.factoryLayout(), LayoutStore.load(files, "abc"))
        assertTrue(!LayoutStore.layoutFile(files, "abc").exists())
    }

    @Test
    fun savingFactoryLayoutClearsTheCustomisedDefault() {
        val files = dir()
        assertTrue(LayoutStore.saveDefault(files, LayoutStore.factoryLayout().copy(opacity = 80)))
        assertTrue(LayoutStore.hasCustomDefault(files))
        assertTrue(LayoutStore.saveDefault(files, LayoutStore.factoryLayout()))
        assertTrue(!LayoutStore.hasCustomDefault(files))
        assertEquals(LayoutStore.factoryLayout(), LayoutStore.effectiveDefault(files))
    }

    @Test
    fun corruptFileReadsAsFactory() {
        val files = dir()
        LayoutStore.layoutFile(files, "abc").apply {
            parentFile?.mkdirs()
            writeText("this is not a layout\n" + "dpad.cx=banana\n" + "a.size=99999\n")
        }
        val loaded = LayoutStore.load(files, "abc")
        val factory = LayoutStore.factoryLayout()
        assertEquals(factory.controls.getValue("dpad"), loaded.controls.getValue("dpad"))
        // The oversized a size is clamped, not dropped.
        assertEquals(220, loaded.controls.getValue("a").sizeDp)
    }

    @Test
    fun clampKeepsCentresOnScreen() {
        val factory = LayoutStore.factoryLayout()
        val clamped = LayoutStore.clampSpec(
            ControlSpec(0.001f, 0.999f, 5),
            factory.controls.getValue("a"),
        )
        assertEquals(0.03f, clamped.cx, 0.0001f)
        assertEquals(0.97f, clamped.cy, 0.0001f)
        assertEquals(36, clamped.sizeDp)
    }

    @Test
    fun roundTripPreservesLayout() {
        val layout = LayoutStore.factoryLayout().copy(opacity = 75)
        assertEquals(layout, LayoutStore.parse(LayoutStore.encode(layout)))
    }
}

class DpadMathTest {

    private val half = 100f

    @Test
    fun deadZoneIsEmpty() {
        assertEquals(0, DpadMath.directionMask(0f, 0f, half))
        assertEquals(0, DpadMath.directionMask(20f, -10f, half))
        assertEquals(0, DpadMath.directionMask(22f, 0f, half))
    }

    @Test
    fun cardinalDirections() {
        assertEquals(Bridge.Input.UP, DpadMath.directionMask(0f, -80f, half))
        assertEquals(Bridge.Input.DOWN, DpadMath.directionMask(0f, 80f, half))
        assertEquals(Bridge.Input.LEFT, DpadMath.directionMask(-80f, 0f, half))
        assertEquals(Bridge.Input.RIGHT, DpadMath.directionMask(80f, 0f, half))
    }

    @Test
    fun diagonalNeedsTheRatio() {
        // 40 px minor vs 80 px major: 0.5 >= 0.35, diagonal.
        val diag = Bridge.Input.UP or Bridge.Input.RIGHT
        assertEquals(diag, DpadMath.directionMask(80f, -40f, half))
        // 20 px minor vs 80 px major: 0.25 < 0.35, single axis.
        assertEquals(Bridge.Input.RIGHT, DpadMath.directionMask(80f, -20f, half))
        // Exactly at the ratio: diagonal.
        val atRatio = Bridge.Input.DOWN or Bridge.Input.LEFT
        assertEquals(atRatio, DpadMath.directionMask(-80f, 28f, half))
    }

    @Test
    fun mergedMaskOrsPointers() {
        val merged = DpadMath.mergedMask(
            listOf(Triple(0f, -80f, half), Triple(-80f, 0f, half)),
        )
        assertEquals(Bridge.Input.UP or Bridge.Input.LEFT, merged)
    }
}
