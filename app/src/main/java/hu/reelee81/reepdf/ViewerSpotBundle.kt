package hu.reelee81.reepdf

import android.os.Bundle
import hu.reelee81.reepdf.viewer.ViewerSpot

internal fun Bundle.putSpot(key: String, spot: ViewerSpot?) {
    if (spot == null) return
    putInt("${key}_page", spot.page)
    putDouble("${key}_top", spot.top)
    putDouble("${key}_left", spot.left)
    putFloat("${key}_zoom", spot.zoom)
}

internal fun Bundle.getSpot(key: String): ViewerSpot? {
    if (!containsKey("${key}_page")) return null
    return ViewerSpot(getInt("${key}_page"), getDouble("${key}_top"), getDouble("${key}_left"), getFloat("${key}_zoom"))
}
