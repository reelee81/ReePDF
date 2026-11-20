package hu.reelee81.reepdf

object TouchHit {
    fun isInside(x: Float, y: Float, left: Int, top: Int, width: Int, height: Int): Boolean =
        x >= left && x < left + width && y >= top && y < top + height
}
