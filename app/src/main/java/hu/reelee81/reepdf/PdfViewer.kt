package hu.reelee81.reepdf

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ext.SdkExtensions
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import hu.reelee81.reepdf.viewer.ViewerActions
import hu.reelee81.reepdf.viewer.ViewerState
import hu.reelee81.reepdf.viewer.ViewerWait
import hu.reelee81.reepdf.viewer.viewerView
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PdfViewer : AppCompatActivity(), ViewerActions {

    companion object {
        const val EXTRA_PATH = "pdf_path"
        const val EXTRA_TITLE = "pdf_title"
        const val EXTRA_OUTPUT_URI = "pdf_output_uri"
        const val EXTRA_INITIAL_PAGE_INDEX = "pdf_initial_page_index"
        private const val STATE_PATH = "pdf_current_path"
        private const val STATE_TITLE = "pdf_current_title"
        private const val STATE_OUTPUT_URI = "pdf_current_output_uri"
        private const val STATE_PAGE = "pdf_current_page"
        private const val NOTIFICATION_PROGRESS_MS = 5_000L
    }

    private lateinit var model: PdfViewerModel
    private var serviceCaption: String? = null
    private var serviceJob: Job? = null
    private var editJob: Job? = null
    private var accessibility: AccessibilityManager? = null
    private val touchExploration = AccessibilityManager.TouchExplorationStateChangeListener { enabled ->
        model.state?.readsPageTexts = enabled
    }

    private val editLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        onEditorResult(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars()

        model = ViewModelProvider(this)[PdfViewerModel::class.java]
        if (model.state == null) {
            val path = savedInstanceState?.getString(STATE_PATH) ?: intent.getStringExtra(EXTRA_PATH)
            if (path == null) {
                Toast.makeText(this, getString(R.string.temporary_pdf_not_found), Toast.LENGTH_LONG).show()
                finish()
                return
            }
            val file = File(path)
            val title = savedInstanceState?.getString(STATE_TITLE) ?: intent.getStringExtra(EXTRA_TITLE) ?: file.name
            val page = savedInstanceState?.getInt(STATE_PAGE) ?: intent.getIntExtra(EXTRA_INITIAL_PAGE_INDEX, 0)
            model.start(title, supportsPdfEditing(), file, page, savedInstanceState?.getString(STATE_OUTPUT_URI))
        }
        val state = model.state ?: return
        model.outputUri?.let(::setEditedDocumentResult)

        val frame = FrameLayout(this).apply {
            fitsSystemWindows = true
            addView(viewerView(this@PdfViewer, state, this@PdfViewer, R.font.roboto_regular, R.font.roboto_medium))
        }
        ViewCompat.setOnApplyWindowInsetsListener(frame) { view, insets ->
            val applied = ViewCompat.onApplyWindowInsets(view, insets)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                state.keyboardCover = (keyboard - view.paddingBottom).coerceAtLeast(0)
            }
            updateBottomGap(state, view)
            applied
        }
        frame.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ -> updateBottomGap(state, view) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { decor, insets ->
                val applied = ViewCompat.onApplyWindowInsets(decor, insets)
                state.keyboardCover = SystemBarsCompat.keyboardAboveNavigation(
                    insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
                    insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                )
                applied
            }
        }
        setContentView(frame)

        accessibility = getSystemService(AccessibilityManager::class.java)?.also { manager ->
            state.readsPageTexts = manager.isTouchExplorationEnabled
            manager.addTouchExplorationStateChangeListener(touchExploration)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!state.isWaiting && !state.handleBack()) finish()
            }
        })

        lifecycleScope.launch {
            snapshotFlow { state.waitRevealed to state.leavesOnCancel }.collect { (revealed, edited) ->
                updateService(revealed, edited)
            }
        }
        lifecycleScope.launch {
            snapshotFlow { state.openError }.collect { error ->
                if (error != null) {
                    Toast.makeText(this@PdfViewer, getString(R.string.failed_to_open_pdf_with_msg, error), Toast.LENGTH_LONG).show()
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
        applySystemBars()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        model.file?.let { outState.putString(STATE_PATH, it.absolutePath) }
        model.state?.let { state ->
            outState.putString(STATE_TITLE, state.title)
            outState.putInt(STATE_PAGE, state.currentPage)
        }
        model.outputUri?.let { outState.putString(STATE_OUTPUT_URI, it) }
        super.onSaveInstanceState(outState)
    }

    override fun finish() {
        if (::model.isInitialized) model.close()
        super.finish()
    }

    override fun onDestroy() {
        accessibility?.removeTouchExplorationStateChangeListener(touchExploration)
        accessibility = null
        serviceJob?.cancel()
        if (serviceCaption != null) {
            serviceCaption = null
            PdfProcessingForegroundService.stop(this)
        }
        super.onDestroy()
    }

    override fun onBack() {
        onBackPressedDispatcher.onBackPressed()
    }

    override fun onCancelWait() {
        finish()
    }

    override fun onCopy(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        ClipData.newPlainText(getString(R.string.pdf_clipboard_label), text).let(clipboard::setPrimaryClip)
    }

    override fun onMessage(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    override fun onEdit() {
        val state = model.state ?: return
        val file = model.file ?: return
        if (state.isWaiting || state.layout == null || editJob?.isActive == true) return
        val page = state.currentPage
        editJob = lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching { FileProvider.getUriForFile(this@PdfViewer, "$packageName.provider", file) }.getOrNull()
            } ?: return@launch
            editLauncher.launch(
                Intent(this@PdfViewer, PdfEditorActivity::class.java).apply {
                    putExtra(PdfEditorActivity.EXTRA_INPUT_URI, uri.toString())
                    putExtra(PdfEditorActivity.EXTRA_INITIAL_PAGE_INDEX, page)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }
    }

    private fun onEditorResult(resultCode: Int, data: Intent?) {
        val state = model.state ?: return
        val returnedPage = data?.getIntExtra(PdfEditorActivity.EXTRA_CURRENT_PAGE_INDEX, -1)?.takeIf { it >= 0 }
        val editedUri = data?.getStringExtra(PdfEditorActivity.EXTRA_OUTPUT_URI)
        if (resultCode == RESULT_OK && editedUri != null) {
            setEditedDocumentResult(editedUri)
            val edited = ownCacheFile(editedUri.toUri())
            if (edited == null) {
                finish()
                return
            }
            model.openEdited(edited, editedUri, returnedPage ?: state.currentPage)
        } else if (returnedPage != null) {
            state.scrollToPage(returnedPage)
        }
    }

    private fun ownCacheFile(uri: Uri): File? {
        if (uri.scheme != "content" || uri.authority != "$packageName.provider") return null
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != "cache") return null
        val name = segments[1]
        if (name.isEmpty() || name.contains('/') || name == "." || name == "..") return null
        return File(cacheDir, name)
    }

    private fun setEditedDocumentResult(uri: String) {
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_OUTPUT_URI, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    private fun updateService(revealed: Boolean, edited: Boolean) {
        serviceJob?.cancel()
        if (!revealed) {
            if (serviceCaption != null) {
                serviceCaption = null
                PdfProcessingForegroundService.stop(this)
            }
            return
        }
        val caption = getString(if (edited) R.string.pdf_wait_reading_edited_pages else R.string.pdf_wait_reading_pages)
        if (serviceCaption != caption) {
            serviceCaption = caption
            PdfProcessingForegroundService.start(this, caption)
        }
        serviceJob = lifecycleScope.launch {
            delay((NOTIFICATION_PROGRESS_MS - ViewerWait.REVEAL_MS).milliseconds)
            PdfProcessingForegroundService.start(this@PdfViewer, caption, PdfProcessingForegroundService.PROGRESS_INDETERMINATE)
        }
    }

    private fun updateBottomGap(state: ViewerState, view: View) {
        val insets = ViewCompat.getRootWindowInsets(view) ?: return
        val buttons = insets.getInsets(WindowInsetsCompat.Type.tappableElement())
        state.bottomGap = SystemBarsCompat.needsGestureGap(Build.VERSION.SDK_INT, buttons.left, buttons.bottom, buttons.right)
    }

    private fun applySystemBars() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val background = ContextCompat.getColor(this, R.color.pdf_viewer_sb_bg_color)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !night
        window.setBackgroundDrawable(background.toDrawable())
        SystemBarsCompat.applyStatusBarColor(this, background)
        SystemBarsCompat.applyNavBarColor(this, background)
    }

    private fun supportsPdfEditing(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 18
}
