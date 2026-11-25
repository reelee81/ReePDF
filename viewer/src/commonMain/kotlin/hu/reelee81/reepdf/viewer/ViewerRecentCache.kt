package hu.reelee81.reepdf.viewer

internal class ViewerRecentCache<K : Any, V : Any>(private val limit: Int) {
    private val entries = LinkedHashMap<K, V>()

    init {
        require(limit > 0) { "A cache needs room for at least one entry" }
    }

    val size: Int
        get() = entries.size

    fun getOrPut(key: K, create: () -> V): V {
        val value = entries.remove(key) ?: create()
        entries[key] = value
        if (entries.size > limit) entries.remove(entries.keys.first())
        return value
    }
}
