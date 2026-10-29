package hu.reelee81.reepdf.core

enum class ScaleMode(val scale: Boolean, val fit: Boolean) {
    OFF(false, false),
    SCALE(true, false),
    FIT(true, true);

    companion object {
        fun of(scale: Boolean, fit: Boolean): ScaleMode = when {
            !scale -> OFF
            fit -> FIT
            else -> SCALE
        }

        fun at(position: Int): ScaleMode = entries[position.coerceIn(0, entries.size - 1)]
    }
}
