package hu.reelee81.reepdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.reelee81.reepdf.core.SignatureGeometry
import hu.reelee81.reepdf.viewer.SignaturePlacerSpec
import hu.reelee81.reepdf.viewer.SignaturePlacerState
import hu.reelee81.reepdf.viewer.ViewerViewport
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SignaturePlacerModel : ViewModel() {
    private var bitmap: Bitmap? = null

    var state: SignaturePlacerState? = null
        private set

    var signatureFailed by mutableStateOf(false)
        private set

    fun start(spec: SignaturePlacerSpec, path: String, pageIndex: Int, signaturePath: String, screenSide: Int) {
        val placer = SignaturePlacerState(spec, viewModelScope)
        state = placer
        placer.open { AndroidSinglePage.open(path, pageIndex) }
        viewModelScope.launch {
            val decoded = withContext(Dispatchers.IO) { decode(signaturePath, spec, screenSide) }
            if (decoded == null) {
                signatureFailed = true
            } else {
                bitmap = decoded
                placer.signature = decoded.asImageBitmap()
            }
        }
    }

    fun close() {
        state?.close()
    }

    override fun onCleared() {
        state?.close()
        state?.signature = null
        bitmap?.recycle()
        bitmap = null
    }

    private fun decode(path: String, spec: SignaturePlacerSpec, screenSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val width = max(1, ceil(screenSide * ViewerViewport.DOUBLE_TAP_FIT_FACTOR * spec.signatureWidthMm / spec.pageWidthMm).toInt())
        val height = max(1, ceil(width * bounds.outHeight.toFloat() / bounds.outWidth).toInt())
        val sample = SignatureGeometry.sampleSize(bounds.outWidth, bounds.outHeight, width, height)
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }
}
