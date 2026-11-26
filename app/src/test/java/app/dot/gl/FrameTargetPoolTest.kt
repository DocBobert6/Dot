package app.dot.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The intermediate-target pool (GL-CHAIN-SPEC §1.4, §10.1): a
 * target is never handed out twice in one frame, reuse is
 * filter-aware, and the caller's exclude set (the held deflicker
 * history target) is never returned.
 */
class FrameTargetPoolTest {

    @Test
    fun selectReusableTargetExactCases() {
        // The §10.1 cases, verbatim.
        assertEquals(3, selectReusableTarget(listOf(1, 2, 3), setOf(1), setOf(2)))
        assertNull(selectReusableTarget(listOf(1, 2), setOf(1), setOf(2)))
        assertNull(selectReusableTarget(emptyList(), emptySet(), emptySet()))
        assertEquals(1, selectReusableTarget(listOf(1, 2), emptySet(), emptySet()))
    }

    @Test
    fun acquireHandsOutFreshIdsInOneFrame() {
        val pool = FrameTargetPool()
        val t1 = pool.acquire(480, 432, TargetFilter.NEAREST)
        val t2 = pool.acquire(480, 432, TargetFilter.NEAREST)
        assertTrue(t1.id != t2.id)
        assertEquals(480, t1.width)
        assertEquals(432, t1.height)
        assertEquals(TargetFilter.NEAREST, t1.filter)
    }

    @Test
    fun beginFrameReusesTargets() {
        val pool = FrameTargetPool()
        val t1 = pool.acquire(480, 432, TargetFilter.NEAREST)
        pool.acquire(480, 432, TargetFilter.NEAREST)
        pool.beginFrame()
        // Both targets are free again; the first candidate wins.
        val t3 = pool.acquire(480, 432, TargetFilter.NEAREST)
        assertEquals(t1.id, t3.id)
        assertEquals(2, pool.targetCount(480, 432, TargetFilter.NEAREST))
    }

    @Test
    fun excludeIsNeverReturned() {
        val pool = FrameTargetPool()
        val t1 = pool.acquire(480, 432, TargetFilter.NEAREST)
        pool.beginFrame()
        // t1 is held (the deflicker history target): the pool must
        // hand out a different target, not t1.
        val t2 = pool.acquire(480, 432, TargetFilter.NEAREST, exclude = setOf(t1.id))
        assertTrue(t2.id != t1.id)
    }

    @Test
    fun filterAwareReuse() {
        val pool = FrameTargetPool()
        val n = pool.acquire(480, 432, TargetFilter.NEAREST)
        val l = pool.acquire(480, 432, TargetFilter.LINEAR)
        assertTrue(n.id != l.id)
        assertEquals(1, pool.targetCount(480, 432, TargetFilter.NEAREST))
        assertEquals(1, pool.targetCount(480, 432, TargetFilter.LINEAR))
        assertEquals(2, pool.totalTargets())
        // A NEAREST request never returns the LINEAR target.
        pool.beginFrame()
        assertEquals(n.id, pool.acquire(480, 432, TargetFilter.NEAREST).id)
        pool.beginFrame()
        assertEquals(l.id, pool.acquire(480, 432, TargetFilter.LINEAR).id)
    }

    @Test
    fun sizeAwareReuse() {
        val pool = FrameTargetPool()
        val a = pool.acquire(480, 432, TargetFilter.NEAREST)
        val b = pool.acquire(1200, 1080, TargetFilter.NEAREST)
        assertTrue(a.id != b.id)
        pool.beginFrame()
        assertEquals(b.id, pool.acquire(1200, 1080, TargetFilter.NEAREST).id)
    }

    @Test
    fun dropAllResetsIds() {
        val pool = FrameTargetPool()
        pool.acquire(480, 432, TargetFilter.NEAREST)
        pool.acquire(480, 432, TargetFilter.NEAREST)
        assertEquals(2, pool.totalTargets())
        pool.dropAll()
        assertEquals(0, pool.totalTargets())
        assertEquals(1, pool.acquire(480, 432, TargetFilter.NEAREST).id)
    }
}
