package hu.reelee81.reepdf

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ext.SdkExtensions
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.Toolbar.LayoutParams
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import com.google.android.material.appbar.MaterialToolbar

class PdfViewer : PdfActivity() {

    companion object {
        const val EXTRA_URI = "pdf_uri"
        const val EXTRA_OUTPUT_URI = "pdf_output_uri"
        const val EXTRA_INITIAL_PAGE_INDEX = "pdf_initial_page_index"
        const val EXTRA_BATCH_SIZE = "pdf_batch_size"
        private const val FRAG_TAG = "pdfFragTag"
        private const val STATE_CURRENT_URI = "pdf_current_uri"
        private const val STATE_HAS_EDITED_DOCUMENT = "pdf_has_edited_document"
    }

    private var currentUri: Uri? = null
    private var hasEditedDocument = false
    private var reloadingEditedDocument = false
    private var returningUnedited = false
    private var pendingReload: Pair<Uri, Int>? = null

    private val editLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val fragment = currentFragment()
        val returnedPageIndex = result.data
            ?.getIntExtra(PdfEditorActivity.EXTRA_CURRENT_PAGE_INDEX, -1)
            ?.takeIf { it >= 0 }
        val editedUri = result.data
            ?.getStringExtra(PdfEditorActivity.EXTRA_OUTPUT_URI)
            ?.toUri()

        if (result.resultCode == RESULT_OK && editedUri != null) {
            currentUri = editedUri
            hasEditedDocument = true
            reloadingEditedDocument = true
            reloadViewerDocument(editedUri, returnedPageIndex ?: fragment?.currentPageIndex ?: 0)

            findViewById<MaterialToolbar>(R.id.toolbar).title = resolveDisplayName(editedUri)
            setEditedDocumentResult(editedUri)
        } else if (returnedPageIndex != null) {
            returningUnedited = true
            fragment?.scrollToPage(returnedPageIndex)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.viewer_pdf)

        run {
            val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            val insets = WindowCompat.getInsetsController(window, window.decorView)
            insets.isAppearanceLightStatusBars = !night
            run {
                val navColor = ContextCompat.getColor(this, R.color.pdf_viewer_sb_bg_color)
                SystemBarsCompat.applyNavBarColor(this, navColor)
            }
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.navigationIcon = AppCompatResources.getDrawable(this, R.drawable.ic_arrow_back_24)
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        val uriStr = savedInstanceState?.getString(STATE_CURRENT_URI) ?: intent.getStringExtra(EXTRA_URI)
        val uri: Uri? = uriStr?.toUri()
        if (uri == null) {
            Toast.makeText(this, getString(R.string.temporary_pdf_not_found), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        currentUri = uri
        hasEditedDocument = savedInstanceState?.getBoolean(STATE_HAS_EDITED_DOCUMENT) ?: false
        if (hasEditedDocument) setEditedDocumentResult(uri)

        toolbar.title = resolveDisplayName(uri)

        val supportsPdfViewer = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

        if (!supportsPdfViewer) {
            Toast.makeText(
                this,
                getString(R.string.pdf_viewer_requires),
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        val frag = (supportFragmentManager.findFragmentByTag(FRAG_TAG) as? ReadOnlyPdfViewerFragment)
            ?: ReadOnlyPdfViewerFragment().also {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.pdfFragContainer, it, FRAG_TAG)
                    .commitNow()
            }

        if (savedInstanceState == null) {
            val initialPageIndex = intent.getIntExtra(EXTRA_INITIAL_PAGE_INDEX, 0)
            frag.loadDocumentAtPage(uri, initialPageIndex)
        } else {
            frag.documentUri = uri
        }

        addToolbarButtons(toolbar)
        onPdfOperationStateChanged()
    }

    override fun onResume() {
        super.onResume()
        pendingReload?.let { (uri, pageIndex) ->
            pendingReload = null
            reloadViewerDocument(uri, pageIndex)
        }
    }

    private fun currentFragment(): ReadOnlyPdfViewerFragment? =
        supportFragmentManager.findFragmentByTag(FRAG_TAG) as? ReadOnlyPdfViewerFragment

    private fun reloadViewerDocument(uri: Uri, pageIndex: Int) {
        if (supportFragmentManager.isStateSaved) {
            pendingReload = uri to pageIndex
            return
        }
        val replacement = ReadOnlyPdfViewerFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.pdfFragContainer, replacement, FRAG_TAG)
            .commitNow()
        replacement.loadDocumentAtPage(uri, pageIndex, refresh = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_CURRENT_URI, currentUri?.toString())
        outState.putBoolean(STATE_HAS_EDITED_DOCUMENT, hasEditedDocument)
        super.onSaveInstanceState(outState)
    }

    override fun isPdfNavigationInProgress(): Boolean =
        (supportFragmentManager.findFragmentByTag(FRAG_TAG) as? ReadOnlyPdfViewerFragment)
            ?.isPageNavigationInProgress == true

    override fun onPdfNavigationStateChanged(inProgress: Boolean) {
        if (!inProgress) {
            reloadingEditedDocument = false
            returningUnedited = false
        }
    }

    override fun leavesOnPdfWaitCancel(): Boolean = reloadingEditedDocument && isPdfNavigationInProgress()

    override fun pdfNavigationCaption(): Int = when {
        reloadingEditedDocument -> R.string.pdf_wait_reading_edited_pages
        returningUnedited -> R.string.pdf_wait_reading_unedited_pages
        else -> R.string.pdf_wait_reading_pages
    }

    override fun isPdfDocumentLoading(): Boolean =
        (supportFragmentManager.findFragmentByTag(FRAG_TAG) as? ReadOnlyPdfViewerFragment)
            ?.isDocumentLoading == true

    override fun isPdfDocumentOpening(): Boolean =
        (supportFragmentManager.findFragmentByTag(FRAG_TAG) as? ReadOnlyPdfViewerFragment)
            ?.isAwaitingDocument == true

    private fun setEditedDocumentResult(uri: Uri) {
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_OUTPUT_URI, uri.toString())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    private fun supportsPdfEditing(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 18

    private fun addToolbarButtons(toolbar: MaterialToolbar) {
        val buttonSize = resources.getDimensionPixelSize(R.dimen.dp_32)
        val oldSearchRightMargin = toolbar.contentInsetStartWithNavigation +
                resources.getDimensionPixelSize(R.dimen.dp_10)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                marginEnd = oldSearchRightMargin + resources.getDimensionPixelSize(R.dimen.dp_6)
            }
        }

        buttons.addView(
            createToolbarIconButton(R.drawable.ic_search_24, getString(R.string.search_pdf), buttonSize).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd =
                    resources.getDimensionPixelSize(R.dimen.dp_4)
                setOnClickListener {
                    val current = currentFragment() ?: return@setOnClickListener
                    if (current.isPageNavigationInProgress) return@setOnClickListener
                    current.isTextSearchActive = true
                }
            }
        )

        if (supportsPdfEditing()) {
            buttons.addView(
                createToolbarIconButton(R.drawable.ic_edit_24, getString(R.string.edit_pdf), buttonSize).apply {
                    setOnClickListener {
                        val current = currentFragment() ?: return@setOnClickListener
                        if (current.isPageNavigationInProgress) return@setOnClickListener
                        val editUri = currentUri ?: return@setOnClickListener
                        editLauncher.launch(
                            Intent(this@PdfViewer, PdfEditorActivity::class.java).apply {
                                putExtra(PdfEditorActivity.EXTRA_INPUT_URI, editUri.toString())
                                putExtra(PdfEditorActivity.EXTRA_INITIAL_PAGE_INDEX, current.currentPageIndex)
                                putExtra(
                                    PdfEditorActivity.EXTRA_BATCH_SIZE,
                                    intent.getIntExtra(EXTRA_BATCH_SIZE, 1)
                                )
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        )
                    }
                }
            )
        }

        toolbar.addView(buttons)
    }

    private fun createToolbarIconButton(
        iconRes: Int,
        description: String,
        size: Int
    ): AppCompatImageButton =
        AppCompatImageButton(this).apply {
            setImageDrawable(AppCompatResources.getDrawable(this@PdfViewer, iconRes))
            contentDescription = description
            imageTintList = AppCompatResources.getColorStateList(this@PdfViewer, R.color.toolbar_navigation_icon_color)
            background = null
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(size, size)
        }

    private fun resolveDisplayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameIdx)
                } else {
                    uri.lastPathSegment
                }
            } ?: (uri.lastPathSegment ?: getString(R.string.app_name))
        } catch (_: Exception) {
            uri.lastPathSegment ?: getString(R.string.app_name)
        } ?: getString(R.string.app_name)
    }
}
