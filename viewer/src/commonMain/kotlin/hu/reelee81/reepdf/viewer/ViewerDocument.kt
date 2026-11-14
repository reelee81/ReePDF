package hu.reelee81.reepdf.viewer

import androidx.compose.ui.graphics.ImageBitmap

interface ViewerPageSource {
    val pageCount: Int

    suspend fun pageSizes(): ViewerPageSizes

    suspend fun render(page: Int, zoom: Float, left: Int, top: Int, width: Int, height: Int): ViewerRender

    suspend fun renderTiles(tiles: List<ViewerTile>): ViewerTilesRender

    fun release(image: ViewerImage)

    fun trimMemory()

    fun close()
}

interface ViewerDocument : ViewerPageSource {
    suspend fun search(page: Int, query: String): ViewerTextHits

    suspend fun count(page: Int, query: String): Int

    fun releaseCount()

    suspend fun word(page: Int, point: ViewerTextPoint, tolerance: Float): ViewerTextSpan?

    suspend fun caret(page: Int, point: ViewerTextPoint): ViewerTextPoint?

    suspend fun highlight(page: Int, from: ViewerTextPoint?, to: ViewerTextPoint?): FloatArray

    suspend fun copy(page: Int, from: ViewerTextPoint?, to: ViewerTextPoint?): String

    suspend fun text(page: Int): String
}

class ViewerPageSizes(val widths: FloatArray, val heights: FloatArray)

class ViewerImage(val bitmap: ImageBitmap, val bytes: Long)

sealed interface ViewerRender {
    class Done(val image: ViewerImage, val millis: Long) : ViewerRender

    data object Aborted : ViewerRender

    data object Failed : ViewerRender
}

sealed interface ViewerTilesRender {
    class Done(val images: List<ViewerImage>) : ViewerTilesRender

    data object Aborted : ViewerTilesRender

    data object Failed : ViewerTilesRender
}

interface ViewerActions {
    fun onBack()

    fun onEdit()

    fun onCancelWait()

    fun onCopy(text: String)

    fun onMessage(text: String)
}
