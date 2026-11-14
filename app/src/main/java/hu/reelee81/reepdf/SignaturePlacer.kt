package hu.reelee81.reepdf

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import hu.reelee81.reepdf.viewer.SignaturePlacerActions
import hu.reelee81.reepdf.viewer.SignaturePlacerSpec
import hu.reelee81.reepdf.viewer.signaturePlacerView
import kotlin.math.max
import kotlinx.coroutines.launch

class SignaturePlacer : AppCompatActivity(), SignaturePlacerActions {

    companion object {
        const val EXTRA_PATH = "placer_path"
        const val EXTRA_PAGE_INDEX = "placer_page_index"
        const val EXTRA_SIGNATURE = "placer_signature"
        const val EXTRA_PAGE_WIDTH_MM = "placer_page_width_mm"
        const val EXTRA_PAGE_HEIGHT_MM = "placer_page_height_mm"
        const val EXTRA_SIGNATURE_WIDTH_MM = "placer_signature_width_mm"
        const val EXTRA_SIGNATURE_HEIGHT_MM = "placer_signature_height_mm"
        const val EXTRA_MAX_X_MM = "placer_max_x_mm"
        const val EXTRA_MAX_Y_MM = "placer_max_y_mm"
        const val EXTRA_START_X_MM = "placer_start_x_mm"
        const val EXTRA_START_Y_MM = "placer_start_y_mm"
        const val EXTRA_DPI_INDEX = "placer_dpi_index"
        const val EXTRA_SCAN_EFFECT = "placer_scan_effect"
        const val EXTRA_GRAYSCALE = "placer_grayscale"
        const val EXTRA_X_MM = "placer_x_mm"
        const val EXTRA_Y_MM = "placer_y_mm"
    }

    private lateinit var model: SignaturePlacerModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemBarsCompat.applyViewerBars(this)

        model = ViewModelProvider(this)[SignaturePlacerModel::class.java]
        if (model.state == null) {
            val path = intent.getStringExtra(EXTRA_PATH)
            val signature = intent.getStringExtra(EXTRA_SIGNATURE)
            if (path == null || signature == null) {
                Toast.makeText(this, getString(R.string.temporary_pdf_not_found), Toast.LENGTH_LONG).show()
                finish()
                return
            }
            val metrics = resources.displayMetrics
            model.start(spec(), path, intent.getIntExtra(EXTRA_PAGE_INDEX, 0), signature, max(metrics.widthPixels, metrics.heightPixels))
        }
        val state = model.state ?: return
        setResult(RESULT_CANCELED, passThrough())

        val frame = FrameLayout(this).apply {
            fitsSystemWindows = true
            addView(signaturePlacerView(this@SignaturePlacer, state, this@SignaturePlacer, R.font.roboto_regular, R.font.roboto_medium))
        }
        setContentView(frame)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!state.handleBack()) finish()
            }
        })

        lifecycleScope.launch {
            snapshotFlow { state.openError }.collect { error ->
                if (error != null) {
                    Toast.makeText(this@SignaturePlacer, getString(R.string.failed_to_open_pdf_with_msg, error), Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
        lifecycleScope.launch {
            snapshotFlow { model.signatureFailed }.collect { failed ->
                if (failed) {
                    Toast.makeText(this@SignaturePlacer, getString(R.string.signature_image_invalid), Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        model.state?.onShown()
    }

    override fun onStop() {
        model.state?.onHidden()
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        model.state?.trimMemory()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        SystemBarsCompat.applyViewerBars(this)
    }

    override fun finish() {
        if (::model.isInitialized) model.close()
        super.finish()
    }

    override fun onBack() {
        onBackPressedDispatcher.onBackPressed()
    }

    override fun onSave() {
        val state = model.state ?: return
        if (!state.isReady) return
        setResult(RESULT_OK, passThrough().putExtra(EXTRA_X_MM, state.xMm).putExtra(EXTRA_Y_MM, state.yMm))
        finish()
    }

    override fun onDiscard() {
        finish()
    }

    private fun spec() = SignaturePlacerSpec(
        pageWidthMm = intent.getFloatExtra(EXTRA_PAGE_WIDTH_MM, 0f),
        pageHeightMm = intent.getFloatExtra(EXTRA_PAGE_HEIGHT_MM, 0f),
        signatureWidthMm = intent.getFloatExtra(EXTRA_SIGNATURE_WIDTH_MM, 0f),
        signatureHeightMm = intent.getFloatExtra(EXTRA_SIGNATURE_HEIGHT_MM, 0f),
        maxXMm = intent.getFloatExtra(EXTRA_MAX_X_MM, 0f),
        maxYMm = intent.getFloatExtra(EXTRA_MAX_Y_MM, 0f),
        startXMm = intent.getFloatExtra(EXTRA_START_X_MM, 0f),
        startYMm = intent.getFloatExtra(EXTRA_START_Y_MM, 0f)
    )

    private fun passThrough(): Intent = Intent()
        .putExtra(EXTRA_SIGNATURE, intent.getStringExtra(EXTRA_SIGNATURE))
        .putExtra(EXTRA_DPI_INDEX, intent.getIntExtra(EXTRA_DPI_INDEX, 0))
        .putExtra(EXTRA_SCAN_EFFECT, intent.getBooleanExtra(EXTRA_SCAN_EFFECT, false))
        .putExtra(EXTRA_GRAYSCALE, intent.getBooleanExtra(EXTRA_GRAYSCALE, false))
}
