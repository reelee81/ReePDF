package hu.reelee81.reepdf

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import hu.reelee81.reepdf.viewer.ViewerActions
import hu.reelee81.reepdf.viewer.ViewerEditPlan
import hu.reelee81.reepdf.viewer.ViewerEditSaveResult
import hu.reelee81.reepdf.viewer.ViewerEditSettings
import hu.reelee81.reepdf.viewer.ViewerEditTool
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
        private const val EDIT_PREFS = "viewer_edit"
        private const val KEY_TOOL = "tool"
        private const val KEY_DRAWING_TOOL = "drawing_tool"
        private const val KEY_PEN_COLOR = "pen_color"
        private const val KEY_PEN_WIDTH = "pen_width"
        private const val KEY_HIGHLIGHTER_COLOR = "highlighter_color"
        private const val KEY_HIGHLIGHTER_WIDTH = "highlighter_width"
        private const val KEY_UNDO_PANEL_TOP = "undo_panel_top"
    }

    private lateinit var model: PdfViewerModel
    private var serviceCaption: String? = null
    private var serviceJob: Job? = null
    private var accessibility: AccessibilityManager? = null
    private val touchExploration = AccessibilityManager.TouchExplorationStateChangeListener { enabled ->
        model.state?.readsPageTexts = enabled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars()

        model = ViewModelProvider(this)[PdfViewerModel::class.java]
        val created = model.state == null
        if (created) {
            val path = savedInstanceState?.getString(STATE_PATH) ?: intent.getStringExtra(EXTRA_PATH)
            if (path == null) {
                Toast.makeText(this, getString(R.string.temporary_pdf_not_found), Toast.LENGTH_LONG).show()
                finish()
                return
            }
            val file = File(path)
            val title = savedInstanceState?.getString(STATE_TITLE) ?: intent.getStringExtra(EXTRA_TITLE) ?: file.name
            val page = savedInstanceState?.getInt(STATE_PAGE) ?: intent.getIntExtra(EXTRA_INITIAL_PAGE_INDEX, 0)
            model.start(title, file, page, savedInstanceState?.getString(STATE_OUTPUT_URI))
        }
        val state = model.state ?: return
        model.outputUri?.let(::setEditedDocumentResult)
        lifecycleScope.launch {
            val (prefs, saved) = withContext(Dispatchers.IO) {
                val prefs = applicationContext.getSharedPreferences(EDIT_PREFS, MODE_PRIVATE)
                prefs to if (created) readEditSettings(prefs) else null
            }
            saved?.let(state.editor::restore)
            state.editor.onSettingsChanged = { settings -> writeEditSettings(prefs, settings) }
        }
        state.editSaver = ::saveEdits

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
            snapshotFlow { state.waitRevealed to state.waitReason }.collect { (revealed, reason) ->
                updateService(revealed, reason)
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

    private suspend fun saveEdits(plan: ViewerEditPlan): ViewerEditSaveResult {
        val result = model.saveEdits({ cacheDir }, plan) { file ->
            FileProvider.getUriForFile(this, "$packageName.provider", file).toString()
        }
        if (result is ViewerEditSaveResult.Saved) model.outputUri?.let(::setEditedDocumentResult)
        return result
    }

    private fun readEditSettings(prefs: SharedPreferences): ViewerEditSettings {
        val defaults = ViewerEditSettings()
        fun tool(key: String, fallback: ViewerEditTool) =
            prefs.getString(key, null)?.let { name -> ViewerEditTool.entries.firstOrNull { it.name == name } } ?: fallback
        return ViewerEditSettings(
            tool = tool(KEY_TOOL, defaults.tool),
            drawingTool = tool(KEY_DRAWING_TOOL, defaults.drawingTool),
            penColor = prefs.getInt(KEY_PEN_COLOR, defaults.penColor),
            penWidth = prefs.getInt(KEY_PEN_WIDTH, defaults.penWidth),
            highlighterColor = prefs.getInt(KEY_HIGHLIGHTER_COLOR, defaults.highlighterColor),
            highlighterWidth = prefs.getInt(KEY_HIGHLIGHTER_WIDTH, defaults.highlighterWidth),
            undoPanelTop = prefs.getBoolean(KEY_UNDO_PANEL_TOP, defaults.undoPanelTop)
        )
    }

    private fun writeEditSettings(prefs: SharedPreferences, settings: ViewerEditSettings) {
        prefs.edit {
            putString(KEY_TOOL, settings.tool.name)
            putString(KEY_DRAWING_TOOL, settings.drawingTool.name)
            putInt(KEY_PEN_COLOR, settings.penColor)
            putInt(KEY_PEN_WIDTH, settings.penWidth)
            putInt(KEY_HIGHLIGHTER_COLOR, settings.highlighterColor)
            putInt(KEY_HIGHLIGHTER_WIDTH, settings.highlighterWidth)
            putBoolean(KEY_UNDO_PANEL_TOP, settings.undoPanelTop)
        }
    }

    private fun setEditedDocumentResult(uri: String) {
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_OUTPUT_URI, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    private fun updateService(revealed: Boolean, reason: ViewerWait.Reason?) {
        serviceJob?.cancel()
        if (!revealed) {
            if (serviceCaption != null) {
                serviceCaption = null
                PdfProcessingForegroundService.stop(this)
            }
            return
        }
        val caption = getString(
            when (reason) {
                ViewerWait.Reason.SAVING_EDITS -> R.string.pdf_edits_saving
                ViewerWait.Reason.OPENING_EDITED -> R.string.pdf_wait_reading_edited_pages
                else -> R.string.pdf_wait_reading_pages
            }
        )
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
        SystemBarsCompat.applyViewerBars(this)
    }
}
