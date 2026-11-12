package hu.reelee81.reepdf.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewerImageCacheTest {
    private val cache = ViewerImageCache<String, String>(budgetBytes = 100L) { 40L }

    @Test
    fun trimDropsTheLeastRecentlyUsedFirst() {
        cache.put("a", "A")
        cache.put("b", "B")
        cache.put("c", "C")
        assertEquals(listOf("A"), cache.trim { false })
        assertEquals(80L, cache.bytes)
        cache["b"]
        cache.put("d", "D")
        assertEquals(listOf("C"), cache.trim { false })
        assertNull(cache["c"])
    }

    @Test
    fun trimKeepsWhatIsStillNeeded() {
        cache.put("a", "A")
        cache.put("b", "B")
        cache.put("c", "C")
        assertEquals(listOf("B"), cache.trim { it == "a" })
        assertEquals(listOf("A", "C"), listOf(cache["a"], cache["c"]))
    }

    @Test
    fun peekDoesNotChangeTheOrder() {
        cache.put("a", "A")
        cache.put("b", "B")
        cache.put("c", "C")
        assertEquals("A", cache.peek("a"))
        assertEquals(listOf("A"), cache.trim { false })
    }

    @Test
    fun replacingReturnsTheOldValue() {
        cache.put("a", "A")
        assertEquals(listOf("A"), cache.put("a", "A2"))
        assertEquals(40L, cache.bytes)
        assertEquals(1, cache.size)
    }

    @Test
    fun retainOnlyAndClearReleaseEverythingElse() {
        cache.put("a", "A")
        cache.put("b", "B")
        assertEquals(listOf("B"), cache.retainOnly { it == "a" })
        assertEquals(listOf("A"), cache.clear())
        assertEquals(0L, cache.bytes)
    }
}
