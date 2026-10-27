package hu.reelee81.reepdf

import android.graphics.Bitmap
import java.io.Serializable
import kotlin.jvm.Transient

class PageItem(
    @Transient var thumbnail: Bitmap?,
    val filePath: String?,
    val pageIndex: Int,
    val widthPts: Float,
    val heightPts: Float,
    val pageNumberText: String
) : Serializable {
    var isSelected: Boolean = false
}
