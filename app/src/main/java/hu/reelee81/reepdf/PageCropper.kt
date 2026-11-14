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
import hu.reelee81.reepdf.core.PdfCropRect
import hu.reelee81.reepdf.viewer.PageCropperActions
import hu.reelee81.reepdf.viewer.PageCropperSpec
import hu.reelee81.reepdf.viewer.PageCropperState
import hu.reelee81.reepdf.viewer.pageCropperView
import java.io.File
import kotlinx.coroutines.launch

class PageCropper : AppCompatActivity(), PageCropperActions {

    companion object {
        const val EXTRA_SOURCE_DIR = "cropper_source_dir"
        const val EXTRA_SOURCE_PAGE = "cropper_source_page"
        const val EXTRA_TURNS = "cropper_turns"
        const val EXTRA_GROUP = "cropper_group"
        const val EXTRA_FRAME = "cropper_frame"
        const val EXTRA_ALL_PAGES = "cropper_all_pages"
    }

    private lateinit var model: PageCropperModel
    private var resultSet = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemBarsCompat.applyViewerBars(this)

        model = ViewModelProvider(this)[PageCropperModel::class.java]
        if (model.state == null) {
            val dir = intent.getStringExtra(EXTRA_SOURCE_DIR)
            val frame = intent.getFloatArrayExtra(EXTRA_FRAME)
            if (dir == null || frame == null || frame.size != 4) {
                Toast.makeText(this, getString(R.string.temporary_pdf_not_found), Toast.LENGTH_LONG).show()
                finish()
                return
            }
            val spec = PageCropperSpec(
                left = frame[0],
                top = frame[1],
                right = frame[2],
                bottom = frame[3],
                allPages = intent.getBooleanExtra(EXTRA_ALL_PAGES, false),
                minSize = PdfCropRect.MIN_SIZE
            )
            model.start(spec, File(dir), intent.getIntExtra(EXTRA_SOURCE_PAGE, 0), intent.getIntExtra(EXTRA_TURNS, 0))
        }
        val state = model.state ?: return

        val frame = FrameLayout(this).apply {
            fitsSystemWindows = true
            addView(pageCropperView(this@PageCropper, state, this@PageCropper, R.font.roboto_regular, R.font.roboto_medium))
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
                    Toast.makeText(this@PageCropper, getString(R.string.failed_to_open_pdf_with_msg, error), Toast.LENGTH_LONG).show()
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
        if (::model.isInitialized) {
            val state = model.state
            if (state != null && !resultSet) setResult(RESULT_CANCELED, outcome(state))
            model.close()
        }
        super.finish()
    }

    override fun onBack() {
        onBackPressedDispatcher.onBackPressed()
    }

    override fun onSave() {
        val state = model.state ?: return
        if (!state.isReady) return
        val frame = state.frame
        setResult(RESULT_OK, outcome(state).putExtra(EXTRA_FRAME, floatArrayOf(frame.left, frame.top, frame.right, frame.bottom)))
        resultSet = true
        finish()
    }

    override fun onDiscard() {
        finish()
    }

    private fun outcome(state: PageCropperState): Intent = Intent()
        .putExtra(EXTRA_GROUP, intent.getIntExtra(EXTRA_GROUP, -1))
        .putExtra(EXTRA_TURNS, intent.getIntExtra(EXTRA_TURNS, 0))
        .putExtra(EXTRA_ALL_PAGES, state.allPages)
}
