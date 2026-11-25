package hu.reelee81.reepdf.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerRecentCacheTest {
    @Test
    fun theLeastRecentlyUsedEntryLeavesFirst() {
        val cache = ViewerRecentCache<Int, String>(3)
        var made = 0
        fun get(key: Int) = cache.getOrPut(key) { made++; "v$key" }

        get(1)
        get(2)
        get(3)
        get(1)
        get(4)

        assertEquals(3, cache.size)
        assertEquals(4, made)
        assertEquals("v1", get(1))
        assertEquals(4, made)
        assertEquals("v2", get(2))
        assertEquals(5, made)
        assertEquals("v4", get(4))
        assertEquals(5, made)
        assertEquals("v3", get(3))
        assertEquals(6, made)
        assertEquals(3, cache.size)
    }

    @Test
    fun aKeptEntryIsMadeOnce() {
        val cache = ViewerRecentCache<Long, IntArray>(512)
        var made = 0
        repeat(1000) { round -> cache.getOrPut((round % 300).toLong()) { made++; IntArray(1) } }

        assertEquals(300, made)
        assertEquals(300, cache.size)
    }
}
