package hu.reelee81.reepdf

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.DeadObjectException
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresExtension
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.Toolbar.LayoutParams
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.pdf.PdfWriteHandle
import com.itextpdf.io.source.RandomAccessSourceFactory
import com.itextpdf.kernel.pdf.PdfDocument as ITextPdfDocument
import com.itextpdf.kernel.pdf.ReaderProperties
import com.itextpdf.kernel.pdf.PdfReader
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import hu.reelee81.reepdf.core.PageBatch
import hu.reelee81.reepdf.pdf.PdfEditProjection
import hu.reelee81.reepdf.pdf.PdfEditorFlattening
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

class PdfEditorActivity : PdfActivity() {

    companion object {
        const val EXTRA_INPUT_URI = "pdf_input_uri"
        const val EXTRA_OUTPUT_URI = "pdf_output_uri"
        const val EXTRA_INITIAL_PAGE_INDEX = "pdf_initial_page_index"
        const val EXTRA_CURRENT_PAGE_INDEX = "pdf_current_page_index"
        private const val FRAG_TAG = "pdfEditorFrag"
        private const val STATE_DISCARD_CONFIRMATION_VISIBLE = "pdf_discard_confirmation_visible"
        private const val STATE_ERASE_ALL_CONFIRMATION_VISIBLE = "pdf_erase_all_confirmation_visible"
        private const val STATE_ERASE_UNSAVED_CONFIRMATION_VISIBLE = "pdf_erase_unsaved_confirmation_visible"
        private const val STATE_ERASE_UNSAVED_ALL = "pdf_erase_unsaved_all"
        private const val STATE_ERASED_FILE = "pdf_erased_file"
        private const val STATE_APPLIED_FILE = "pdf_applied_file"
        private const val DISABLED_ALPHA = 0.38f
        private val ERASE_OVERLAY_DELAY = 300.milliseconds
        private const val NEEDS_CONFIRMATION = -1
    }

    private lateinit var fragment: PdfEditorFragment
    private var saveButton: MaterialButton? = null
    private val eraseButtons = mutableListOf<View>()
    private var saving = false
    private var documentLoaded = false
    private var eraseJob: Job? = null
    private var eraseOverlayShown = false
    private var erasingAll = false
    private var searchingEdits = false
    private var eraseUnsavedAll = false
    private var erasedFile: File? = null
    private var erasedFileDelivered = false
    private var saveJob: Job? = null
    private var appliedFile: File? = null
    private var pendingReload: Pair<Uri, Int>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!supportsPdfEditing()) {
            Toast.makeText(this, getString(R.string.pdf_editor_requires_s_ext_18), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setContentView(R.layout.viewer_pdf)

        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !night
        SystemBarsCompat.applyNavBarColor(this, ContextCompat.getColor(this, R.color.pdf_viewer_sb_bg_color))

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.title = getString(R.string.edit_pdf)
        toolbar.navigationIcon = AppCompatResources.getDrawable(this, R.drawable.ic_arrow_back_24)
        toolbar.setNavigationOnClickListener { finishOrConfirmDiscard() }

        val uri = intent.getStringExtra(EXTRA_INPUT_URI)?.toUri()
        if (uri == null) {
            Toast.makeText(this, getString(R.string.temporary_pdf_not_found), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        fragment = (supportFragmentManager.findFragmentByTag(FRAG_TAG) as? PdfEditorFragment)
            ?: PdfEditorFragment().also {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.pdfFragContainer, it, FRAG_TAG)
                    .commitNow()
            }

        erasedFile = savedInstanceState?.getString(STATE_ERASED_FILE)?.let(::File)?.takeIf { it.isFile }
        appliedFile = savedInstanceState?.getString(STATE_APPLIED_FILE)?.let(::File)?.takeIf { it.isFile }
        if (savedInstanceState == null) {
            fragment.loadDocumentAtPage(uri, intent.getIntExtra(EXTRA_INITIAL_PAGE_INDEX, 0))
        } else {
            fragment.documentUri = erasedFile?.let(::uriFor) ?: uri
        }

        addSaveButton(toolbar)
        addEraseButtons(toolbar)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isPdfWaitActive() &&
                    !findViewById<View>(R.id.overlay_dialog_pdf_editor_discard).isVisible
                ) return
                finishOrConfirmDiscard()
            }
        })

        if (savedInstanceState?.getBoolean(STATE_DISCARD_CONFIRMATION_VISIBLE) == true) {
            showDiscardConfirmation()
        }
        if (savedInstanceState?.getBoolean(STATE_ERASE_ALL_CONFIRMATION_VISIBLE) == true) {
            showEraseAllConfirmation()
        }
        if (savedInstanceState?.getBoolean(STATE_ERASE_UNSAVED_CONFIRMATION_VISIBLE) == true) {
            showEraseUnsavedConfirmation(savedInstanceState.getBoolean(STATE_ERASE_UNSAVED_ALL))
        }
        onPdfOperationStateChanged()
    }

    override fun onResume() {
        super.onResume()
        pendingReload?.let { (uri, pageIndex) ->
            pendingReload = null
            reloadEditorDocument(uri, pageIndex)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(
            STATE_DISCARD_CONFIRMATION_VISIBLE,
            findViewById<View>(R.id.overlay_dialog_pdf_editor_discard)?.isVisible == true
        )
        outState.putBoolean(
            STATE_ERASE_ALL_CONFIRMATION_VISIBLE,
            findViewById<View>(R.id.overlay_dialog_pdf_editor_erase_all)?.isVisible == true
        )
        outState.putBoolean(
            STATE_ERASE_UNSAVED_CONFIRMATION_VISIBLE,
            findViewById<View>(R.id.overlay_dialog_pdf_editor_erase_unsaved)?.isVisible == true
        )
        outState.putBoolean(STATE_ERASE_UNSAVED_ALL, eraseUnsavedAll)
        erasedFile?.let { outState.putString(STATE_ERASED_FILE, it.absolutePath) }
        appliedFile?.let { outState.putString(STATE_APPLIED_FILE, it.absolutePath) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing) {
            if (!erasedFileDelivered) {
                erasedFile?.delete()
                erasedFile = null
            }
            appliedFile?.delete()
            appliedFile = null
        }
        super.onDestroy()
    }

    override fun isPdfNavigationInProgress(): Boolean =
        supportsPdfEditing() && ::fragment.isInitialized && fragment.isPageNavigationInProgress

    override fun isPdfSavingInProgress(): Boolean =
        saving || eraseJob != null ||
            (supportsPdfEditing() && ::fragment.isInitialized && fragment.isApplyEditsInProgress)

    override fun isPdfOperationInProgress(): Boolean =
        isPdfSavingInProgress() || isPdfNavigationInProgress()

    override fun isPdfWaitOverlayDeferred(): Boolean = eraseJob != null && !eraseOverlayShown

    override fun cancelablePdfOperationCaption(): Int? = when {
        eraseJob != null -> when {
            searchingEdits -> R.string.pdf_wait_searching_edits
            erasingAll -> R.string.pdf_wait_erasing_all
            else -> R.string.pdf_wait_erasing_visible
        }
        saveJob?.isCancelled == false -> R.string.pdf_edits_saving
        else -> null
    }

    override fun pdfNavigationCaption(): Int =
        if (isPdfDocumentLoading()) R.string.pdf_wait_editing_pages else R.string.pdf_wait_refreshing_pages

    override fun cancelPdfOperation() {
        eraseJob?.cancel()
        saveJob?.cancel()
        onPdfOperationStateChanged()
    }

    override fun isPdfDocumentLoading(): Boolean =
        supportsPdfEditing() && ::fragment.isInitialized && fragment.isDocumentLoading

    override fun onPdfLoadCancelled() {
        finishOrConfirmDiscard()
    }

    override fun isPdfDocumentOpening(): Boolean =
        supportsPdfEditing() && ::fragment.isInitialized && fragment.isAwaitingDocument

    override fun onPdfNavigationStateChanged(inProgress: Boolean) {
        if (!supportsPdfEditing() || !::fragment.isInitialized) return
        if (isPdfWaitOverlayDeferred()) return
        val ready = documentLoaded && !inProgress && !saving && eraseJob == null && !fragment.isApplyEditsInProgress
        saveButton?.isEnabled = ready
        for (button in eraseButtons) {
            button.isEnabled = ready
            button.alpha = if (ready) 1f else DISABLED_ALPHA
        }
        if (documentLoaded && !inProgress && !saving && eraseJob == null && !isFinishing && !isDestroyed &&
            !fragment.isEditModeEnabled
        ) {
            fragment.isEditModeEnabled = true
        }
    }

    private fun addSaveButton(toolbar: MaterialToolbar) {
        val s = resources.getDimensionPixelSize(R.dimen.dp_44)
        val horizontalPadding = resources.getDimensionPixelSize(R.dimen.dp_16)

        saveButton = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonStyle
        ).apply {
            text = getString(R.string.save_pdf_edits)
            minWidth = 0
            minimumWidth = 0
            insetLeft = 0
            insetRight = 0
            setPaddingRelative(horizontalPadding, paddingTop, horizontalPadding, paddingBottom)
            isEnabled = false
            layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, s).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                rightMargin = resources.getDimensionPixelSize(R.dimen.dp_20)
            }
            setOnClickListener { saveEdits() }
        }

        toolbar.addView(saveButton)
    }

    private fun addEraseButtons(toolbar: MaterialToolbar) {
        val size = resources.getDimensionPixelSize(R.dimen.dp_32)
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                rightMargin = resources.getDimensionPixelSize(R.dimen.dp_14)
            }
        }
        val visible = createEraseButton(R.drawable.ic_erase_one_page_24, R.string.erase_visible_pdf_edits, size)
        (visible.layoutParams as LinearLayout.LayoutParams).marginEnd = resources.getDimensionPixelSize(R.dimen.dp_10)
        visible.setOnClickListener { erasePdfEdits(all = false, confirmFirst = hasUnsavedEdits()) }
        val all = createEraseButton(R.drawable.ic_erase_all_pages_24, R.string.erase_all_pdf_edits, size)
        all.setOnClickListener { erasePdfEdits(all = true, confirmFirst = true) }
        buttons.addView(visible)
        buttons.addView(all)
        eraseButtons += visible
        eraseButtons += all
        toolbar.addView(buttons)
    }

    private fun createEraseButton(iconRes: Int, descriptionRes: Int, size: Int): AppCompatImageButton =
        AppCompatImageButton(this).apply {
            setImageDrawable(AppCompatResources.getDrawable(this@PdfEditorActivity, iconRes))
            contentDescription = getString(descriptionRes)
            imageTintList = AppCompatResources.getColorStateList(
                this@PdfEditorActivity,
                R.color.toolbar_navigation_icon_color
            )
            background = null
            scaleType = ImageView.ScaleType.FIT_CENTER
            val padding = resources.getDimensionPixelSize(R.dimen.dp_2)
            setPadding(padding, padding, padding, padding)
            isEnabled = false
            alpha = DISABLED_ALPHA
            layoutParams = LinearLayout.LayoutParams(size, size)
        }

    private fun canStartEditorOperation(): Boolean =
        supportsPdfEditing() && ::fragment.isInitialized && documentLoaded && !isPdfOperationInProgress()

    private fun hasUnsavedEdits(): Boolean =
        supportsPdfEditing() && ::fragment.isInitialized && fragment.hasUnsavedChanges

    private fun uriFor(file: File): Uri = FileProvider.getUriForFile(this, "$packageName.provider", file)

    private fun erasePdfEdits(all: Boolean, confirmFirst: Boolean = false) {
        if (!canStartEditorOperation()) return
        val input = fragment.documentUri ?: return
        val pages = if (all) null else fragment.visiblePageIndices.map { it + 1 }.toSet()
        if (pages != null && pages.isEmpty()) return
        val pageIndex = fragment.currentPageIndex
        val batchSize = PageBatch.PAGES
        val ownInput = erasedFile
        val open: () -> PdfReader = if (ownInput != null) {
            { PdfReader(ownInput) }
        } else {
            { readerFor(input) }
        }

        eraseOverlayShown = false
        erasingAll = all
        searchingEdits = confirmFirst
        val job = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            val output = File(cacheDir, "edited_${System.currentTimeMillis()}.pdf")
            var keepOutput = false
            val reveal = launch {
                delay(ERASE_OVERLAY_DELAY)
                showEraseOverlay()
            }
            try {
                val cleared = withContext(Dispatchers.IO) {
                    val total = ITextPdfDocument(open().setMemorySavingMode(true)).use { it.numberOfPages }
                    val scanned = pages?.count { it in 1..total } ?: total
                    val scan = progressStage(scanned.toLong() + if (confirmFirst) 0 else total)
                    if (!PdfEditProjection.hasLayers(open, pages, batchSize, onProgress = scan::advance) { ensureActive() }) {
                        0
                    } else if (confirmFirst) {
                        NEEDS_CONFIRMATION
                    } else {
                        withContext(Dispatchers.Main) {
                            reveal.cancel()
                            eraseOverlayShown = true
                            onPdfOperationStateChanged()
                        }
                        val clear = progressStage(total.toLong())
                        PdfEditProjection.clearLayers(open, output, pages, batchSize, onProgress = clear::advance) {
                            ensureActive()
                        }.also { clear.idle() }
                    }
                }
                if (cleared == NEEDS_CONFIRMATION) {
                    if (hasUnsavedEdits()) showEraseUnsavedConfirmation(all) else showEraseAllConfirmation()
                } else if (cleared > 0) {
                    keepOutput = true
                    erasedFile = output
                    appliedFile?.delete()
                    appliedFile = null
                    documentLoaded = false
                    reloadEditorDocument(uriFor(output), pageIndex)
                    ownInput?.delete()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(
                    this@PdfEditorActivity,
                    getString(R.string.pdf_edits_erase_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                reveal.cancel()
                if (!keepOutput) output.delete()
                eraseJob = null
                eraseOverlayShown = false
                onPdfOperationStateChanged()
            }
        }
        eraseJob = job
        onPdfOperationStateChanged()
        job.start()
    }

    private fun reloadEditorDocument(uri: Uri, pageIndex: Int) {
        if (!supportsPdfEditing()) return
        if (supportFragmentManager.isStateSaved) {
            pendingReload = uri to pageIndex
            return
        }
        val replacement = PdfEditorFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.pdfFragContainer, replacement, FRAG_TAG)
            .commitNow()
        fragment = replacement
        replacement.loadDocumentAtPage(uri, pageIndex, refresh = true)
    }

    private fun showEraseOverlay() {
        if (eraseJob == null || eraseOverlayShown) return
        eraseOverlayShown = true
        onPdfOperationStateChanged()
    }

    private fun readerFor(uri: Uri): PdfReader {
        val descriptor = contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException(getString(R.string.input_stream_is_null))
        val channel = ParcelFileDescriptor.AutoCloseInputStream(descriptor).channel
        return try {
            PdfReader(RandomAccessSourceFactory().createBestSource(channel), ReaderProperties())
        } catch (e: Exception) {
            channel.close()
            throw e
        }
    }

    private fun showEraseAllConfirmation() {
        val overlay = findViewById<View>(R.id.overlay_dialog_pdf_editor_erase_all)
        overlay.setOnClickListener { hideEraseAllConfirmation() }
        findViewById<View>(R.id.overlay_dialog_pdf_editor_erase_all_panel).setOnClickListener { }
        findViewById<MaterialButton>(R.id.pdf_editor_erase_all_cancel).setOnClickListener { hideEraseAllConfirmation() }
        findViewById<MaterialButton>(R.id.pdf_editor_erase_all_confirm).setOnClickListener {
            hideEraseAllConfirmation()
            erasePdfEdits(all = true)
        }
        overlay.isVisible = true
    }

    private fun hideEraseAllConfirmation() {
        findViewById<View>(R.id.overlay_dialog_pdf_editor_erase_all).apply {
            isVisible = false
            setOnClickListener(null)
        }
    }

    private fun showEraseUnsavedConfirmation(all: Boolean) {
        eraseUnsavedAll = all
        val overlay = findViewById<View>(R.id.overlay_dialog_pdf_editor_erase_unsaved)
        overlay.setOnClickListener { hideEraseUnsavedConfirmation() }
        findViewById<View>(R.id.overlay_dialog_pdf_editor_erase_unsaved_panel).setOnClickListener { }
        findViewById<TextView>(R.id.pdf_editor_erase_unsaved_message).setText(
            if (all) R.string.erase_all_pdf_edits_unsaved_confirm else R.string.erase_visible_pdf_edits_unsaved_confirm
        )
        findViewById<MaterialButton>(R.id.pdf_editor_erase_unsaved_cancel).setOnClickListener {
            hideEraseUnsavedConfirmation()
        }
        findViewById<MaterialButton>(R.id.pdf_editor_erase_unsaved_confirm).setOnClickListener {
            hideEraseUnsavedConfirmation()
            erasePdfEdits(all = all)
        }
        overlay.isVisible = true
    }

    private fun hideEraseUnsavedConfirmation() {
        findViewById<View>(R.id.overlay_dialog_pdf_editor_erase_unsaved).apply {
            isVisible = false
            setOnClickListener(null)
        }
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 18)
    fun onEditorDocumentLoaded() {
        documentLoaded = true
        onPdfOperationStateChanged()
    }

    fun onEditorDocumentLoadFailed() {
        documentLoaded = false
        saveButton?.isEnabled = false
    }

    private fun saveEdits() {
        if (!supportsPdfEditing()) return
        if (!documentLoaded) return
        if (fragment.isPageNavigationInProgress) return
        if (saving || eraseJob != null || fragment.isApplyEditsInProgress) return

        if (!fragment.hasUnsavedChanges) {
            val applied = appliedFile
            if (applied != null) {
                writeAndFlatten(handle = null, applied = applied)
                return
            }
            val erased = erasedFile
            if (erased != null) {
                erasedFileDelivered = true
                finishEditor(uriFor(erased))
                return
            }
            Toast.makeText(this, getString(R.string.no_pdf_edits_to_save), Toast.LENGTH_SHORT).show()
            return
        }

        saving = true
        onPdfOperationStateChanged()
        try {
            fragment.applyDraftEdits()
        } catch (e: Exception) {
            onApplyEditsFailed(e)
        }
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 18)
    fun onApplyEditsSuccess(handle: PdfWriteHandle) {
        writeAndFlatten(handle = handle, applied = null)
    }

    private fun writeAndFlatten(handle: PdfWriteHandle?, applied: File?) {
        saving = true
        val job = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            val stamp = System.currentTimeMillis()
            val raw = applied ?: File(cacheDir, "applied_$stamp.pdf")
            val outFile = File(cacheDir, "edited_$stamp.pdf")
            try {
                if (handle != null) {
                    withContext(NonCancellable) {
                        try {
                            withContext(Dispatchers.IO) {
                                handle.use { writeHandle ->
                                    ParcelFileDescriptor.open(
                                        raw,
                                        ParcelFileDescriptor.MODE_CREATE or
                                                ParcelFileDescriptor.MODE_TRUNCATE or
                                                ParcelFileDescriptor.MODE_READ_WRITE
                                    ).use { pfd ->
                                        writeHandle.writeTo(pfd)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            raw.delete()
                            throw e
                        }
                        appliedFile?.takeIf { it != raw }?.delete()
                        appliedFile = raw
                    }
                }
                withContext(Dispatchers.IO) { PdfEditorFlattening.flatten(raw, outFile, ::progressStage) { ensureActive() } }

                val outUri = uriFor(outFile)
                appliedFile?.delete()
                appliedFile = null
                erasedFile?.delete()
                erasedFile = null
                finishEditor(outUri)
            } catch (e: CancellationException) {
                outFile.delete()
                if (appliedFile != null && !isFinishing) {
                    Toast.makeText(this@PdfEditorActivity, getString(R.string.pdf_edits_save_cancelled), Toast.LENGTH_LONG).show()
                }
                throw e
            } catch (e: Exception) {
                outFile.delete()
                Toast.makeText(this@PdfEditorActivity, saveFailedMessage(e), Toast.LENGTH_LONG).show()
            } finally {
                saveJob = null
                saving = false
                onPdfOperationStateChanged()
            }
        }
        saveJob = job
        onPdfOperationStateChanged()
        job.start()
    }

    fun onApplyEditsFailed(error: Throwable) {
        saving = false
        saveButton?.isEnabled = true
        Toast.makeText(this, saveFailedMessage(error), Toast.LENGTH_LONG).show()
        onPdfOperationStateChanged()
    }

    private fun saveFailedMessage(error: Throwable): String =
        if (generateSequence(error) { it.cause }.any { it is DeadObjectException }) {
            getString(R.string.pdf_edits_save_engine_restarted)
        } else {
            getString(R.string.pdf_edits_save_failed, error.message)
        }

    private fun finishOrConfirmDiscard() {
        if (cancelablePdfOperationCaption() != null) {
            cancelPdfOperation()
            return
        }
        if (isPdfSavingInProgress()) return

        if (findViewById<View>(R.id.overlay_dialog_pdf_editor_erase_all).isVisible) {
            hideEraseAllConfirmation()
            return
        }

        if (findViewById<View>(R.id.overlay_dialog_pdf_editor_erase_unsaved).isVisible) {
            hideEraseUnsavedConfirmation()
            return
        }

        val overlay = findViewById<View>(R.id.overlay_dialog_pdf_editor_discard)
        if (overlay.isVisible) {
            hideDiscardConfirmation()
            return
        }

        if (!supportsPdfEditing()) {
            finish()
            return
        }

        if (::fragment.isInitialized && (fragment.hasUnsavedChanges || erasedFile != null || appliedFile != null)) {
            showDiscardConfirmation()
        } else {
            finishEditor()
        }
    }

    private fun showDiscardConfirmation() {
        val overlay = findViewById<View>(R.id.overlay_dialog_pdf_editor_discard)
        val panel = findViewById<View>(R.id.overlay_dialog_pdf_editor_discard_panel)
        val cancel = findViewById<MaterialButton>(R.id.pdf_editor_discard_cancel)
        val discard = findViewById<MaterialButton>(R.id.pdf_editor_discard_confirm)

        overlay.setOnClickListener { hideDiscardConfirmation() }
        panel.setOnClickListener { }
        cancel.setOnClickListener { hideDiscardConfirmation() }
        discard.setOnClickListener {
            hideDiscardConfirmation()
            finishEditor()
        }

        overlay.isVisible = true
    }

    private fun hideDiscardConfirmation() {
        findViewById<View>(R.id.overlay_dialog_pdf_editor_discard).apply {
            isVisible = false
            setOnClickListener(null)
        }
    }

    private fun finishEditor(outputUri: Uri? = null) {
        val currentPageIndex =
            if (supportsPdfEditing() && ::fragment.isInitialized) fragment.currentPageIndex else 0
        val resultData = Intent().putExtra(
            EXTRA_CURRENT_PAGE_INDEX,
            currentPageIndex
        )
        if (outputUri != null) {
            resultData.putExtra(EXTRA_OUTPUT_URI, outputUri.toString())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setResult(RESULT_OK, resultData)
        } else {
            setResult(RESULT_CANCELED, resultData)
        }

        finish()
    }

    @ChecksSdkIntAtLeast(extension = Build.VERSION_CODES.S, api = 18)
    private fun supportsPdfEditing(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 18
}
