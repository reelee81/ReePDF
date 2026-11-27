package hu.reelee81.reepdf

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
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
import hu.reelee81.reepdf.viewer.ViewerEditPanel
import hu.reelee81.reepdf.viewer.ViewerEditPlan
import hu.reelee81.reepdf.viewer.ViewerEditQuestion
import hu.reelee81.reepdf.viewer.ViewerEditSaveResult
import hu.reelee81.reepdf.viewer.ViewerResume
import hu.reelee81.reepdf.viewer.ViewerState
import hu.reelee81.reepdf.viewer.ViewerWait
import hu.reelee81.reepdf.viewer.viewerView
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        private const val STATE_SPOT = "pdf_spot"
        private const val STATE_EDITING = "pdf_editing"
        private const val STATE_PAUSED = "pdf_edit_paused"
        private const val STATE_PANEL = "pdf_edit_panel"
        private const val STATE_ROW_OPEN = "pdf_edit_row_open"
        private const val STATE_QUESTION = "pdf_edit_question"
        private const val STATE_DELETING = "pdf_edit_deleting"
        private const val STATE_SAVING = "pdf_edit_saving"
        private const val NOTIFICATION_PROGRESS_MS = 5_000L
    }

    private lateinit var model: PdfViewerModel
    private var serviceCaption: String? = null
    private var serviceProgress = PdfProcessingForegroundService.PROGRESS_NONE
    private var serviceJob: Job? = null
    private val serviceScreen = PdfProcessingForegroundService.screen()
    private var accessibility: AccessibilityManager? = null
    private val touchExploration = AccessibilityManager.TouchExplorationStateChangeListener { enabled ->
        model.state?.readsPageTexts = enabled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serviceScreen.show()
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
            val resume = savedInstanceState?.let(::resumeFrom)
            model.start(
                title, file, page, savedInstanceState?.getString(STATE_OUTPUT_URI), AndroidEditJournal.of(this),
                AndroidTextFonts(applicationContext.resources), resume
            )
        }
        val state = model.state ?: return
        model.outputUri?.let(::setEditedDocumentResult)
        lifecycleScope.launch {
            val profiles = EditorProfiles.of(this@PdfViewer)
            val saved = if (created) profiles.liveSettings() else null
            saved?.let(state.editor::restore)
            state.editor.onSettingsChanged = { settings ->
                profiles.liveSettingsPrefs.edit { EditSettingsPrefs.put(this, settings) }
            }
        }
        state.editSaver = ::saveEdits
        state.editor.signatures.attach(AndroidSignatureStore(applicationContext))

        val frame = FrameLayout(this).apply {
            fitsSystemWindows = true
            addView(
                viewerView(
                    this@PdfViewer,
                    state,
                    this@PdfViewer,
                    R.font.roboto_thin,
                    R.font.roboto_extralight,
                    R.font.roboto_light,
                    R.font.roboto_regular,
                    R.font.roboto_medium,
                    R.font.roboto_semibold,
                    R.font.roboto_bold,
                    R.font.roboto_extrabold,
                    R.font.roboto_black,
                    R.dimen.dp_9999_main
                )
            )
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
        serviceScreen.show()
        model.state?.onShown()
    }

    override fun onResume() {
        super.onResume()
        serviceCaption?.let { PdfProcessingForegroundService.startIfNotRunning(this, it, serviceProgress) }
    }

    override fun onStop() {
        model.state?.onHidden()
        serviceScreen.hide()
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
            val resume = state.resumeState()
            outState.putSpot(STATE_SPOT, resume.spot)
            outState.putBoolean(STATE_EDITING, resume.editing)
            outState.putBoolean(STATE_PAUSED, resume.paused)
            resume.panel?.let { outState.putString(STATE_PANEL, it.name) }
            outState.putBoolean(STATE_ROW_OPEN, resume.rowOpen)
            resume.question?.let { outState.putString(STATE_QUESTION, it.name) }
            resume.deleting?.let { outState.putInt(STATE_DELETING, it) }
            outState.putBoolean(STATE_SAVING, resume.saving)
        }
        model.outputUri?.let { outState.putString(STATE_OUTPUT_URI, it) }
        super.onSaveInstanceState(outState)
    }

    override fun finish() {
        if (::model.isInitialized) model.close()
        super.finish()
    }

    override fun onDestroy() {
        serviceScreen.hide()
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

    private fun resumeFrom(saved: Bundle): ViewerResume? {
        if (!saved.containsKey(STATE_EDITING)) return null
        return ViewerResume(
            spot = saved.getSpot(STATE_SPOT),
            editing = saved.getBoolean(STATE_EDITING),
            paused = saved.getBoolean(STATE_PAUSED),
            panel = saved.getString(STATE_PANEL)?.let { name -> ViewerEditPanel.entries.firstOrNull { it.name == name } },
            rowOpen = saved.getBoolean(STATE_ROW_OPEN),
            question = saved.getString(STATE_QUESTION)?.let { name -> ViewerEditQuestion.entries.firstOrNull { it.name == name } },
            deleting = if (saved.containsKey(STATE_DELETING)) saved.getInt(STATE_DELETING) else null,
            saving = saved.getBoolean(STATE_SAVING)
        )
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
            serviceProgress = PdfProcessingForegroundService.PROGRESS_NONE
            PdfProcessingForegroundService.start(this, caption)
        }
        serviceJob = lifecycleScope.launch {
            delay((NOTIFICATION_PROGRESS_MS - ViewerWait.REVEAL_MS).milliseconds)
            serviceProgress = PdfProcessingForegroundService.PROGRESS_INDETERMINATE
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
