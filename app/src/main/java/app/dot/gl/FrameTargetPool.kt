package app.dot.gl

// Dot — the intermediate-target pool (GL-CHAIN-SPEC §1.4).
// The pool is pure logic: it decides WHICH target a pass writes to,
// never touching GL. The renderer owns the GL objects (FBO + texture)
// behind each target id.
//
// Rules (§1.4, the speckle bug): a target is never handed out twice
// in one frame (the per-frame used-set); a target is reusable only
// when its size and filter match the request (filter-aware reuse);
// targets are RGBA16F first, RGBA8 on failure (the format lives in
// the renderer's allocation, not here). The pool never hands out a
// target that is in the caller's [exclude] set (the held deflicker
// history target, GL-CHAIN-SPEC §7).
// SPDX-License-Identifier: GPL-3.0-or-later

enum class TargetFilter { NEAREST, LINEAR }

/** A pooled intermediate target (id + size + filter). */
data class PooledTarget(val id: Int, val width: Int, val height: Int, val filter: TargetFilter)

/**
 * The pure selection function. Returns the first candidate that is
 * neither excluded nor already used this frame, or null when no
 * candidate qualifies. The exact cases (§10.1):
 *   ([1,2,3], exclude {1}, used {2}) -> 3
 *   ([1,2], exclude {1}, used {2}) -> null
 *   ([], {}, {}) -> null
 *   ([1,2], {}, {}) -> 1
 */
fun selectReusableTarget(
    candidates: List<Int>, exclude: Set<Int>, used: Set<Int>
): Int? {
    for (id in candidates) {
        if (id !in exclude && id !in used) return id
    }
    return null
}

class FrameTargetPool {
    private var nextId = 1
    private val buckets = mutableMapOf<Triple<Int, Int, TargetFilter>, MutableList<Int>>()
    private val used = mutableSetOf<Int>()

    /** Starts a frame: the used-set clears, so every target is free again. */
    fun beginFrame() {
        used.clear()
    }

    /**
     * Acquires a target of the requested size and filter. Returns an
     * existing reusable target (now marked used) or a fresh id the
     * renderer must allocate GL objects for (recorded in the bucket
     * and marked used). Targets in [exclude] are never returned.
     */
    fun acquire(
        width: Int, height: Int, filter: TargetFilter, exclude: Set<Int> = emptySet()
    ): PooledTarget {
        val key = Triple(width, height, filter)
        val candidates = buckets.getOrPut(key) { mutableListOf() }
        val existing = selectReusableTarget(candidates, exclude, used)
        if (existing != null) {
            used.add(existing)
            return PooledTarget(existing, width, height, filter)
        }
        val id = nextId++
        candidates.add(id)
        used.add(id)
        return PooledTarget(id, width, height, filter)
    }

    /** Drops every target (surface change, resolution change). */
    fun dropAll() {
        buckets.clear()
        used.clear()
        nextId = 1
    }

    /** How many targets exist in a bucket (for the pool-sizes test). */
    fun targetCount(width: Int, height: Int, filter: TargetFilter): Int =
        buckets[Triple(width, height, filter)]?.size ?: 0

    /** The total number of targets held by the pool. */
    fun totalTargets(): Int = buckets.values.sumOf { it.size }
}
