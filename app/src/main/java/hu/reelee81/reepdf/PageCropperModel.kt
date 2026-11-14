package hu.reelee81.reepdf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.reelee81.reepdf.pdf.PdfPageView
import hu.reelee81.reepdf.pdf.PdfSourceParts
import hu.reelee81.reepdf.viewer.PageCropperSpec
import hu.reelee81.reepdf.viewer.PageCropperState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PageCropperModel : ViewModel() {
    var state: PageCropperState? = null
        private set

    fun start(spec: PageCropperSpec, sourceDir: File, sourcePage: Int, quarterTurns: Int) {
        val cropper = PageCropperState(spec, viewModelScope)
        state = cropper
        cropper.open {
            val (location, view) = withContext(Dispatchers.IO) {
                val parts = PdfSourceParts.open(sourceDir)
                require(sourcePage in 0 until parts.total) { "Page ${sourcePage + 1} is outside 1..${parts.total}" }
                val location = parts.locate(sourcePage)
                location to PdfPageView.read(location.file, location.page, quarterTurns)
            }
            AndroidSinglePage.open(location.file.absolutePath, location.page, view)
        }
    }

    fun close() {
        state?.close()
    }

    override fun onCleared() {
        state?.close()
    }
}
