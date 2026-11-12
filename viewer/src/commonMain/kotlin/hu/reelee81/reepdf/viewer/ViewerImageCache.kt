package hu.reelee81.reepdf.viewer

class ViewerImageCache<K, V>(var budgetBytes: Long, private val bytesOf: (V) -> Long) {
    private val entries = LinkedHashMap<K, V>()

    var bytes: Long = 0L
        private set

    val size: Int
        get() = entries.size

    operator fun get(key: K): V? {
        val value = entries.remove(key) ?: return null
        entries[key] = value
        return value
    }

    fun peek(key: K): V? = entries[key]

    fun put(key: K, value: V): List<V> {
        val replaced = entries.remove(key)
        if (replaced != null) bytes -= bytesOf(replaced)
        entries[key] = value
        bytes += bytesOf(value)
        return listOfNotNull(replaced?.takeIf { it !== value })
    }

    fun trim(keep: (K) -> Boolean): List<V> {
        val evicted = ArrayList<V>()
        val iterator = entries.entries.iterator()
        while (bytes > budgetBytes && iterator.hasNext()) {
            val entry = iterator.next()
            if (keep(entry.key)) continue
            iterator.remove()
            bytes -= bytesOf(entry.value)
            evicted += entry.value
        }
        return evicted
    }

    fun retainOnly(keep: (K) -> Boolean): List<V> {
        val evicted = ArrayList<V>()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (keep(entry.key)) continue
            iterator.remove()
            bytes -= bytesOf(entry.value)
            evicted += entry.value
        }
        return evicted
    }

    fun clear(): List<V> = retainOnly { false }
}
