package hu.reelee81.reepdf

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import android.os.SystemClock
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.AbsoluteSizeSpan
import android.util.AttributeSet
import android.util.Log
import android.util.Xml
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.MenuCompat
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withStarted
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowMetricsCalculator
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.progressindicator.BaseProgressIndicator
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.WriterProperties
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import hu.reelee81.reepdf.core.IncomingFileKind
import hu.reelee81.reepdf.core.IncomingFileKind.isSupportedImageFileName
import hu.reelee81.reepdf.core.IncomingFileKind.isSupportedImageMime
import hu.reelee81.reepdf.core.NupConfig
import hu.reelee81.reepdf.core.NupGeometry.DEFAULT_POSITION
import hu.reelee81.reepdf.core.NupGeometry.maxMarginMm
import hu.reelee81.reepdf.core.NupGeometry.validPosition
import hu.reelee81.reepdf.core.OutputFileName
import hu.reelee81.reepdf.core.PageBatch
import hu.reelee81.reepdf.core.PageSizes
import hu.reelee81.reepdf.core.PdfCropRect
import hu.reelee81.reepdf.core.PdfCrops
import hu.reelee81.reepdf.core.PdfOperationKind
import hu.reelee81.reepdf.core.PdfOperationResult
import hu.reelee81.reepdf.core.PdfOperationRunner
import hu.reelee81.reepdf.core.PdfOperationScreen
import hu.reelee81.reepdf.core.PdfOperationState
import hu.reelee81.reepdf.core.ProfileLoadRebuild
import hu.reelee81.reepdf.core.ProgressNotifyPacing
import hu.reelee81.reepdf.core.ScaleMode
import hu.reelee81.reepdf.core.SignatureGeometry
import hu.reelee81.reepdf.core.SignatureGeometry.refPxForSignatureDpi
import hu.reelee81.reepdf.core.WorkFileBackup
import hu.reelee81.reepdf.databinding.ActivityMainBinding
import hu.reelee81.reepdf.pdf.MuPdfSignedPageRenderer
import hu.reelee81.reepdf.pdf.PdfImportOperation
import hu.reelee81.reepdf.pdf.PdfIo
import hu.reelee81.reepdf.pdf.PdfLayoutChangeOperation
import hu.reelee81.reepdf.pdf.PdfOutputOperation
import hu.reelee81.reepdf.pdf.PdfPageSizes
import hu.reelee81.reepdf.pdf.PdfProgress
import hu.reelee81.reepdf.pdf.PdfSignOperation
import hu.reelee81.reepdf.pdf.PdfSourceParts
import hu.reelee81.reepdf.pdf.PdfViewerEditOperation
import hu.reelee81.reepdf.pdf.ProgressTracker
import hu.reelee81.reepdf.pdf.SignedPageRenderer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.text.Collator
import java.util.BitSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

class MainActivity : AppCompatActivity() {

    private companion object {
        const val PREFS_NAME_FRAGMENT = "ReePDFPrefs"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_ROWS = "rows"
        const val KEY_REEPROFILE1_ROWS = "reeprofile1_rows"
        const val KEY_REEPROFILE2_ROWS = "reeprofile2_rows"
        const val KEY_COLUMNS = "columns"
        const val KEY_REEPROFILE1_COLUMNS = "reeprofile1_columns"
        const val KEY_REEPROFILE2_COLUMNS = "reeprofile2_columns"
        const val KEY_MARGIN = "margin"
        const val KEY_REEPROFILE1_MARGIN = "reeprofile1_margin"
        const val KEY_REEPROFILE2_MARGIN = "reeprofile2_margin"
        const val KEY_ORIENTATION = "orientation"
        const val KEY_REEPROFILE1_ORIENTATION = "reeprofile1_orientation"
        const val KEY_REEPROFILE2_ORIENTATION = "reeprofile2_orientation"
        const val KEY_DRAW_FRAME = "draw_frame"
        const val KEY_REEPROFILE1_DRAW_FRAME = "reeprofile1_draw_frame"
        const val KEY_REEPROFILE2_DRAW_FRAME = "reeprofile2_draw_frame"
        const val KEY_SIG_POS_PORTRAIT_X = "sig_pos_portrait_x"
        const val KEY_REEPROFILE1_SIG_POS_PORTRAIT_X = "reeprofile1_sig_pos_portrait_x"
        const val KEY_REEPROFILE2_SIG_POS_PORTRAIT_X = "reeprofile2_sig_pos_portrait_x"
        const val KEY_SIG_POS_PORTRAIT_Y = "sig_pos_portrait_y"
        const val KEY_REEPROFILE1_SIG_POS_PORTRAIT_Y = "reeprofile1_sig_pos_portrait_y"
        const val KEY_REEPROFILE2_SIG_POS_PORTRAIT_Y = "reeprofile2_sig_pos_portrait_y"
        const val KEY_SIG_POS_LANDSCAPE_X = "sig_pos_landscape_x"
        const val KEY_REEPROFILE1_SIG_POS_LANDSCAPE_X = "reeprofile1_sig_pos_landscape_x"
        const val KEY_REEPROFILE2_SIG_POS_LANDSCAPE_X = "reeprofile2_sig_pos_landscape_x"
        const val KEY_SIG_POS_LANDSCAPE_Y = "sig_pos_landscape_y"
        const val KEY_REEPROFILE1_SIG_POS_LANDSCAPE_Y = "reeprofile1_sig_pos_landscape_y"
        const val KEY_REEPROFILE2_SIG_POS_LANDSCAPE_Y = "reeprofile2_sig_pos_landscape_y"
        const val KEY_SCALE = "scale"
        const val KEY_REEPROFILE1_SCALE = "reeprofile1_scale"
        const val KEY_REEPROFILE2_SCALE = "reeprofile2_scale"
        const val KEY_FIT = "fit"
        const val KEY_REEPROFILE1_FIT = "reeprofile1_fit"
        const val KEY_REEPROFILE2_FIT = "reeprofile2_fit"
        const val KEY_POSITION = "position"
        const val KEY_REEPROFILE1_POSITION = "reeprofile1_position"
        const val KEY_REEPROFILE2_POSITION = "reeprofile2_position"
        const val KEY_CROP = "crop"
        const val KEY_REEPROFILE1_CROP = "reeprofile1_crop"
        const val KEY_REEPROFILE2_CROP = "reeprofile2_crop"
        const val KEY_PASSWORD_OUT = "password_out"
        const val KEY_REEPROFILE1_PASSWORD_OUT = "reeprofile1_password_out"
        const val KEY_REEPROFILE2_PASSWORD_OUT = "reeprofile2_password_out"
        const val KEY_FILE_NAME_TEMPLATE = "file_name_template"
        const val KEY_REEPROFILE1_FILE_NAME_TEMPLATE = "reeprofile1_file_name_template"
        const val KEY_REEPROFILE2_FILE_NAME_TEMPLATE = "reeprofile2_file_name_template"
        const val KEY_LAST_SIG_DPI_INDEX = "key_last_signature_dpi_index"
        const val KEY_REEPROFILE1_SIG_DPI_INDEX = "reeprofile1_sig_dpi_index"
        const val KEY_REEPROFILE2_SIG_DPI_INDEX = "reeprofile2_sig_dpi_index"
        const val KEY_LAST_SCAN_EFFECT = "key_last_scan_effect"
        const val KEY_REEPROFILE1_SCAN_EFFECT = "reeprofile1_scan_effect"
        const val KEY_REEPROFILE2_SCAN_EFFECT = "reeprofile2_scan_effect"
        const val KEY_LAST_GRAYSCALE = "key_last_grayscale_page"
        const val KEY_REEPROFILE1_GRAYSCALE = "reeprofile1_grayscale"
        const val KEY_REEPROFILE2_GRAYSCALE = "reeprofile2_grayscale"
        const val KEY_LAST_INNER_PDF_READER = "key_last_inner_pdf_reader"
        const val KEY_LAST_LOADED_PROFILE = "key_last_loaded_profile"
        const val KEY_RESTORE_LOCK_UNTIL_RELOAD = "key_restore_lock_until_reload"
        const val KEY_FIRST_RUN_INITIALIZED = "key_first_run_initialized"
        const val KEY_THEME_RESTORE_FIRST = "key_theme_restore_first"
        const val KEY_THEME_RESTORE_OFFSET = "key_theme_restore_offset"
        const val KEY_THEME_RESTORE_SELECTED_CSV = "key_theme_restore_selected_csv"
        const val KEY_THEME_RESTORE_NSV_Y = "key_theme_restore_nsv_y"
        const val KEY_THEME_RESTORE_ROTATION_CSV = "key_theme_restore_rotation_csv"
        const val KEY_THEME_RESTORE_POSITION_CSV = "key_theme_restore_position_csv"
        const val KEY_THEME_RESTORE_CROP = "key_theme_restore_crop"
        const val DEFAULT_SIG_POS_PORTRAIT_X_MM = 155
        const val DEFAULT_SIG_POS_PORTRAIT_Y_MM = 245
        const val DEFAULT_SIG_POS_LANDSCAPE_X_MM = 235
        const val DEFAULT_SIG_POS_LANDSCAPE_Y_MM = 165

        private const val SOURCE_TEMP_NAME = "source_temp"
        private const val NUP_TEMP_NAME = "nup_temp.pdf"
        private const val SOURCE_TEMP_COPY_NAME = "source_temp_copy"
        private const val NUP_TEMP_COPY_NAME = "nup_temp_copy.pdf"
        private const val MAX_INPUT = 100
        private const val MAX_IMG_W = 8411
        private const val MAX_IMG_H = 8411
        private const val PROGRESS_TICK_MS = 500L
        private const val PROGRESS_IDLE_MS = 1_000L
        private const val PENDING_INTENT_RETRY_MS = 500L
        private const val OPEN_WITH_COLUMNS = 4
        private const val OPEN_WITH_ROWS = 2
        private const val OPEN_WITH_ANIMATION_MS = 200L
        private const val OPEN_WITH_TABLET_MIN_DP = 600
    }

    private class ThumbRestore(val targetIndex: Int?, val offsetPx: Int, val selected: Set<Int>?)

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: PageItemsViewModel
    private lateinit var adapter: ThumbnailAdapter
    private lateinit var timeStamp: String
    private lateinit var pdfPickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var pdfViewerLauncher: ActivityResultLauncher<Intent>
    private lateinit var signaturesDir: File
    private lateinit var signaturePickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var numberOfRows: TextInputEditText
    private lateinit var numberOfColumns: TextInputEditText
    private lateinit var margin: TextInputEditText
    private lateinit var signaturePosX: TextInputEditText
    private lateinit var signaturePosY: TextInputEditText
    private lateinit var emptyPage: MaterialButton
    private lateinit var printPdf: MaterialButton
    private lateinit var savePdf: MaterialButton
    private lateinit var sendPdf: MaterialButton
    private lateinit var addSignature: MaterialButton
    private lateinit var landscape: SwitchMaterial
    private lateinit var drawFrameSwitch: SwitchMaterial
    private lateinit var scaleSwitch: TriStateSwitch
    private lateinit var listStartButton: AppCompatImageButton
    private lateinit var listEndButton: AppCompatImageButton
    private lateinit var saveDocumentLauncher: ActivityResultLauncher<Intent>
    private lateinit var passwordOutCb: MaterialCheckBox

    private val isFitMeasurable = Build.VERSION.SDK_INT >= VERSION_CODES.O
    private var pendingSaveNamedPath: String? = null
    private var pendingSaveNamedDisplayName: String? = null
    private var isLoadingSettingsProfile = false
    private var regenRequestedDuringProfileLoad = false
    private var nbColumnsValid = true
    private var nbRowsValid = true
    private var marginValid = true
    private var sigXValid = true
    private var sigYValid = true
    private var suppressSigPosSwap = false
    private var currentSigFile: File? = null
    private var selectedSigIndex = -1
    private var fastScroller: AlwaysVisibleFastScroller? = null
    private var orderDirty = false
    private var lastGlobalAllSelected: Boolean? = null
    private var selectedDpiIndexForSignature = 0
    private var didRunNScrollColdStartFix = false
    private var isFirstCreate = true
    private var sourceRotation: IntArray = IntArray(0)
    private var sourcePosition: IntArray = IntArray(0)
    private var defaultPosition: Int = DEFAULT_POSITION
    private var sourceCrop: Map<Int, PdfCropRect> = emptyMap()
    private var cropAllPages: Boolean = false
    private var restoreImeAfterBackground = false
    private var restoreImeAfterBackgroundEditTextId = View.NO_ID
    private var restoreImeAfterBackgroundWasActive = false
    private var foldableInfoJob: Job? = null
    private var isTabletLikeDevice: Boolean = false
    private var suppressRootScrollLayoutCallback: Boolean = false
    private var lastRootW: Int = -1
    private var lastRootH: Int = -1
    private var bottomPanelView: View? = null
    private var hasWindowFocusFlag: Boolean = true
    private var pendingRvUpdate: Boolean = false
    private var suppressNup: Boolean = true
    private var lastBuiltNupConfig: NupConfig? = null
    private var isUiBlockedByProgress: Boolean = false
    private var lastNightMask: Int = -1
    private var pendingUiModeRecreate: Boolean = false
    private var signaturePositionEditorRerender: (() -> Unit)? = null
    private var savedSoftInputModeForOverlay: Int? = null
    private var passwordOverlayOwnsSecureFlag = false
    private var pendingCacheCleanupAfterExternalReturn = false
    private var didPauseForExternalAction = false
    private var orientationLockPrevRequested: Int? = null
    private var uiModeRecreateScheduled: Boolean = false
    private var lastConfigSnapshotForDiff: Configuration? = null
    private var openWithRequest: Pair<Uri, List<OpenWithApp>>? = null
    private var operationOverlay = false
    private var operationId: Long? = null
    private var operationRestore: ThumbRestore? = null
    private var discardOperation = false
    private var handoverId: Long? = null

    @Volatile private var isSigningCancelled: Boolean = false
    @Volatile private var isNupRebuilding = false
    @Volatile private var isFoldableDevice: Boolean = false
    @Volatile private var deferOrientationUnlockUntilThumbnailsReady: Boolean = false
    @Volatile private var deferredOrientationUnlockRequested: Boolean = false
    @Volatile private var reloadThumbnailsGeneration: Long = 0L
    @Volatile private var lastNamedExport: File? = null

    private val progressTracker = ProgressTracker(SystemClock::uptimeMillis)
    private var progressMessage = ""
    private var progressNotified = PdfProcessingForegroundService.PROGRESS_NONE
    private var progressNotifiedAt = 0L
    private val progressTicker = object : Runnable {
        override fun run() {
            if (renderProgress()) binding.root.postDelayed(this, PROGRESS_TICK_MS)
        }
    }

    private val prefsBackupLock = Any()
    private val rvHeightUpdateRunnable = Runnable {
        applyRvThumbnailsHeight()
    }

    private val activeJobs = CopyOnWriteArrayList<Job>()
    private val pendingIncomingIntents = ArrayDeque<Intent>()
    private val pendingIncomingIntentRetry = Runnable { processPendingIncomingIntents() }
    private val workspaceFilesCheck = Runnable { checkWorkspaceFiles() }

    private val savePrefsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data?.data != null) {
            val uri = result.data!!.data!!
            val job = lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val xml = exportAllSharedPreferencesXml()
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(xml.toByteArray(Charsets.UTF_8))
                        out.flush()
                    } ?: throw IOException(getString(R.string.output_stream_is_null))
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, getString(R.string.msg_prefs_saved_success), Toast.LENGTH_LONG).show()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, getString(R.string.msg_prefs_save_failed, e.message), Toast.LENGTH_LONG).show()
                    }
                }
            }
            activeJobs.add(job)
            job.invokeOnCompletion { activeJobs.remove(job) }
        }
    }

    private val loadPrefsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data?.data != null) {
            val uri = result.data!!.data!!
            val job = lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val xml = contentResolver.openInputStream(uri)?.use { inp ->
                        inp.readBytes().toString(Charsets.UTF_8)
                    } ?: throw IOException(getString(R.string.input_stream_is_null))

                    importAllSharedPreferencesFromXml(xml)

                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, getString(R.string.msg_prefs_loaded_success), Toast.LENGTH_LONG).show()

                        val sp = getSharedPreferences(PREFS_NAME_FRAGMENT, MODE_PRIVATE)
                        val profile = synchronized(prefsBackupLock) {
                            sp.getString(KEY_LAST_LOADED_PROFILE, "") ?: ""
                        }

                        when (profile) {
                            "reeprofile1" -> {
                                findViewById<Button>(R.id.load_reeprofile1_pages)?.performClick()
                            }
                            "reeprofile2" -> {
                                findViewById<Button>(R.id.load_reeprofile2_pages)?.performClick()
                            }
                            else -> {

                            }
                        }

                        invalidateOptionsMenu()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, getString(R.string.msg_prefs_load_failed, e.message), Toast.LENGTH_LONG).show()
                    }
                }
            }
            activeJobs.add(job)
            job.invokeOnCompletion { activeJobs.remove(job) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        if (savedInstanceState == null) {
            val operations = PdfOperations.runner(this)
            when (val start = PdfOperationScreen.onFreshStart(operations.state.value)) {
                is PdfOperationScreen.FreshStart.Discard -> {
                    discardOperation = true
                    operations.cancel(start.cancel)
                }
                is PdfOperationScreen.FreshStart.ClearCache -> {
                    start.acknowledge?.let { operations.acknowledge(it) }
                    clearCacheDir()
                }
            }
        }

        val prefs = getSharedPreferences(PREFS_NAME_FRAGMENT, MODE_PRIVATE)
        val theme = synchronized(prefsBackupLock) {
            prefs.getString(KEY_THEME_MODE, "system") ?: "system"
        }

        if (Build.VERSION.SDK_INT >= VERSION_CODES.S) {
            val uiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
            val appNightMode = when (theme) {
                "light" -> UiModeManager.MODE_NIGHT_NO
                "dark" -> UiModeManager.MODE_NIGHT_YES
                else -> UiModeManager.MODE_NIGHT_AUTO
            }
            uiModeManager.setApplicationNightMode(appNightMode)

            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        } else {
            val mode = when (theme) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(mode)
        }

        super.onCreate(savedInstanceState)

        isFirstCreate = (savedInstanceState == null)

        // START Alternative DEBUG mode check
        val isDebug = try {
            (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (_: Exception) {
            false
        }

        if (isDebug) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .penaltyLog()
                    .build()
            )
        }
        // STOP Alternative DEBUG mode check

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lastNightMask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

        val maxMetrics = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(this)
        val density = resources.displayMetrics.density
        val wDp = maxMetrics.bounds.width() / density
        val hDp = maxMetrics.bounds.height() / density
        val maxSmallestDp = min(wDp, hDp)

        isTabletLikeDevice = (maxSmallestDp >= 600f)

        binding.rootScroll.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            if (suppressRootScrollLayoutCallback) return@addOnLayoutChangeListener

            val w = right - left
            val h = bottom - top
            if (w == lastRootW && h == lastRootH) return@addOnLayoutChangeListener
            lastRootW = w
            lastRootH = h

            requestRvThumbnailsHeightUpdate()
        }

        requestRvThumbnailsHeightUpdate()


        run {
            val navColor = ContextCompat.getColor(this, R.color.app_background_color)
            SystemBarsCompat.applyNavBarColor(this, navColor)
        }

        setSupportActionBar(binding.toolbar)

        setThemedBackArrow()
        binding.toolbar.setNavigationOnClickListener { finishAndRemoveTask() }

        signaturesDir = File(filesDir, "Signatures")
        if (!signaturesDir.exists()) {
            signaturesDir.mkdirs()
        }

        val rvThumbnails: RecyclerView = findViewById(R.id.rv_thumbnails)
        numberOfRows = findViewById(R.id.number_of_rows)
        numberOfColumns = findViewById(R.id.number_of_columns)
        margin = findViewById(R.id.margin)
        passwordOutCb = findViewById(R.id.password_out)
        emptyPage = findViewById(R.id.empty_page)
        val selectPdf: Button = findViewById(R.id.select_pdf)
        printPdf = findViewById(R.id.print_pdf)
        savePdf = findViewById(R.id.save_pdf_file)
        sendPdf = findViewById(R.id.send_pdf)
        addSignature = findViewById(R.id.add_signature)
        landscape = findViewById(R.id.landscape)
        drawFrameSwitch = findViewById(R.id.drawframe)

        bottomPanelView = findViewById(R.id.bottom_panel)

        listStartButton = findViewById(R.id.list_start)
        listEndButton = findViewById(R.id.list_end)

        listStartButton.setOnClickListener {
            binding.rvThumbnails.scrollToPosition(0)
        }

        listEndButton.setOnClickListener {
            if (viewModel.pageItems.isNotEmpty()) {
                binding.rvThumbnails.scrollToPosition(viewModel.pageItems.size - 1)
            }
        }

        scaleSwitch = findViewById(R.id.scale)
        scaleSwitch.lastPosition = if (isFitMeasurable) ScaleMode.FIT.ordinal else ScaleMode.SCALE.ordinal

        signaturePosX = findViewById(R.id.signature_position_x)
        signaturePosY = findViewById(R.id.signature_position_y)

        val addTimeBtn: ImageButton = findViewById(R.id.add_time)

        numberOfRows.filters    = arrayOf(InputFilter.LengthFilter(3), numberRangeFilterNoOp(1..100))
        numberOfColumns.filters = arrayOf(InputFilter.LengthFilter(3), numberRangeFilterNoOp(1..100))
        margin.filters          = arrayOf(InputFilter.LengthFilter(3), numberRangeFilterNoOp(0..100))

        landscape.setOnCheckedChangeListener { _, checked ->
            if (!suppressSigPosSwap) swapSignaturePositionFields(checked)
            updateSignatureInputFilters()
            maybeRegenerateNupOnSettingChange(isLandscape = checked)
        }

        drawFrameSwitch.setOnCheckedChangeListener { _, _ ->
            maybeRegenerateNupOnSettingChange()
        }

        scaleSwitch.onPositionChanged = {
            maybeRegenerateNupOnSettingChange()
        }

        setupRegenerateOnBlur(numberOfRows)
        setupRegenerateOnBlur(numberOfColumns)
        setupRegenerateOnBlur(margin)

        viewModel = ViewModelProvider(this)[PageItemsViewModel::class.java]
        val pageItems = viewModel.pageItems

        localizeDefaultFileNameTemplatesInPrefs()
        loadSettings()

        timeStamp = System.currentTimeMillis().toString()

        pdfPickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                result.data?.data?.let { handlePdfUri(it) }
            }
        }

        pdfViewerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val editedUri = result.data
                ?.getStringExtra(PdfViewer.EXTRA_OUTPUT_URI)
                ?.toUri()

            if (result.resultCode == RESULT_OK && editedUri != null) {
                handleEditedPdfFromViewer(editedUri)
            } else {
                lifecycleScope.launch(Dispatchers.IO) {
                    cleanupCacheExceptTemps()
                }
            }
        }

        signaturePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val uri = result.data?.data
                uri?.let { copySignatureToAppDir(it) }
            }
        }

        saveDocumentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val uri = result.data?.data
                if (uri != null) {
                    startSaveWrite(uri)
                } else {
                    pendingSaveNamedPath = null
                    pendingSaveNamedDisplayName = null
                    lifecycleScope.launch(Dispatchers.IO) { cleanupCacheExceptTemps() }
                    maybeRunDeferredUiModeRecreate()
                }
            } else {
                pendingSaveNamedPath = null
                pendingSaveNamedDisplayName = null
                lifecycleScope.launch(Dispatchers.IO) { cleanupCacheExceptTemps() }
                maybeRunDeferredUiModeRecreate()
            }
        }

        rvThumbnails.layoutManager = GridLayoutManager(this, 2)
        adapter = ThumbnailAdapter(
            pageItems,
            deleteListener = { pos -> deleteNupPageGroupAndMirrorSource(pos) },
            rotateLeftListener = { pos -> rotateLeftNupPageGroupAndMirrorSource(pos) },
            positionListener = { pos -> showPositionDialog(listOf(pos), fromMainWindow = false) },
            positionIconFor = { pos -> positionIcon(positionsOfNupIndices(listOf(pos))) },
            cropListener = { pos -> showCropDialog(pos) },
            isCroppedAt = { pos -> isNupGroupCropped(pos) },
            clickListener = { position -> openPdfWithDriveOrOther(position) },
            selectionChanged = { _, _ -> updateButtonsState() }
        )
        rvThumbnails.adapter = adapter

        rvThumbnails.post {
            val lm = rvThumbnails.layoutManager as? GridLayoutManager ?: return@post

            val first = lm.findFirstVisibleItemPosition().takeIf { it != RecyclerView.NO_POSITION } ?: 0
            val last  = lm.findLastVisibleItemPosition().takeIf  { it != RecyclerView.NO_POSITION } ?: 0

            val start = (first - 4).coerceAtLeast(0)
            val end   = (last + 4).coerceAtMost(adapter.itemCount - 1)
            val r = start..end

            adapter.prefetchAroundVisible(this@MainActivity, r)
        }

        rvThumbnails.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? GridLayoutManager ?: return
                val first = lm.findFirstVisibleItemPosition()
                val last = lm.findLastVisibleItemPosition()
                if (first != RecyclerView.NO_POSITION && last != RecyclerView.NO_POSITION) {
                    val start = (first - 4).coerceAtLeast(0)
                    val end   = (last + 4).coerceAtMost(adapter.itemCount - 1)
                    adapter.prefetchAroundVisible(this@MainActivity, start..end)
                }
            }
        })

        binding.rvThumbnails.setHasFixedSize(true)
        binding.rvThumbnails.itemAnimator = null

        fastScroller = AlwaysVisibleFastScroller(binding.rvThumbnails, this)
        fastScroller?.setEnabledWhen(viewModel.pageItems.isNotEmpty())

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                    ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            private var userDragActive = false

            private var originalOrder: List<PageItem>? = null

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition

                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) {
                    return false
                }

                if (from != to) {
                    val moved = pageItems.removeAt(from)
                    pageItems.add(to, moved)
                    adapter.notifyItemMoved(from, to)
                    orderDirty = true
                }
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                userDragActive = (actionState == ItemTouchHelper.ACTION_STATE_DRAG)

                if (userDragActive) {
                    val job = lifecycleScope.launch {
                        backupSelectionState()
                    }
                    activeJobs.add(job)
                    job.invokeOnCompletion { activeJobs.remove(job) }

                    try { fastScroller?.setTemporarilyDisabled(true) } catch (_: Exception) {}

                    if (originalOrder == null) {
                        originalOrder = ArrayList(pageItems)
                    }
                }

                try {
                    (findViewById<RecyclerView>(R.id.rv_thumbnails).parent as? ViewGroup)
                        ?.requestDisallowInterceptTouchEvent(userDragActive)
                } catch (_: Exception) {}
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                try { recyclerView.parent?.requestDisallowInterceptTouchEvent(false) } catch (_: Exception) {}

                val hasOrderActuallyChanged = orderDirty && originalOrder?.equals(pageItems) == false

                if (hasOrderActuallyChanged) {
                    val doPersist = {
                        if (orderDirty) {
                            persistNupOrderToSourceTemp()
                            orderDirty = false
                        }
                        try { fastScroller?.setTemporarilyDisabled(false) } catch (_: Exception) {}
                        updateButtonsState()
                    }

                    val settleCheck = object : Runnable {
                        override fun run() {
                            if (userDragActive ||
                                recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE ||
                                recyclerView.isComputingLayout
                            ) {
                                recyclerView.postDelayed(this, 16L)
                            } else {
                                doPersist()
                            }
                        }
                    }
                    recyclerView.post(settleCheck)

                } else {
                    orderDirty = false
                    try { fastScroller?.setTemporarilyDisabled(false) } catch (_: Exception) {}
                    updateButtonsState()
                }

                originalOrder = null
            }
        }).attachToRecyclerView(rvThumbnails)

        numberOfColumns.addTextChangedListener(SimpleTextWatcher { validateInputs() })
        numberOfRows.addTextChangedListener(SimpleTextWatcher { validateInputs() })
        margin.addTextChangedListener(SimpleTextWatcher { validateInputs() })
        signaturePosX.addTextChangedListener(SimpleTextWatcher { validateInputs() })
        signaturePosY.addTextChangedListener(SimpleTextWatcher { validateInputs() })

        emptyPage.setOnClickListener { addEmptyPageToSource() }
        selectPdf.setOnClickListener { launchSelectPdf() }
        printPdf.setOnClickListener { printNupPdf() }
        savePdf.setOnClickListener { saveNupPdf() }
        sendPdf.setOnClickListener { shareNupPdf() }
        addSignature.setOnClickListener { showSignatureDialog() }

        addTimeBtn.setOnClickListener { showTimestampDialog() }

        val saveReeProfile1Pages: Button = findViewById(R.id.save_reeprofile1_pages)
        val saveReeProfile2Pages: Button = findViewById(R.id.save_reeprofile2_pages)
        saveReeProfile1Pages.setOnClickListener {
            saveSettings("reeprofile1")
            numberOfRows.clearFocus()
            numberOfColumns.clearFocus()
            margin.clearFocus()
            signaturePosX.clearFocus()
            signaturePosY.clearFocus()
            if (nupConfigChanged()) maybeRegenerateNupOnSettingChange()
        }
        saveReeProfile2Pages.setOnClickListener {
            saveSettings("reeprofile2")
            numberOfRows.clearFocus()
            numberOfColumns.clearFocus()
            margin.clearFocus()
            signaturePosX.clearFocus()
            signaturePosY.clearFocus()
            if (nupConfigChanged()) maybeRegenerateNupOnSettingChange()
        }

        val reeProfile1Pages: Button = findViewById(R.id.load_reeprofile1_pages)
        val reeProfile2Pages: Button = findViewById(R.id.load_reeprofile2_pages)
        reeProfile1Pages.setOnClickListener {
            loadSettings("reeprofile1")
            maybeRegenerateNupOnSettingChange()
        }
        reeProfile2Pages.setOnClickListener {
            loadSettings("reeprofile2")
            maybeRegenerateNupOnSettingChange()
        }

        val intent = intent
        val launchedFromHistory = (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0

        if (savedInstanceState == null && !launchedFromHistory) {
            handleIncomingShareIntent(intent)
        }
        setIntent(Intent())

        savedInstanceState?.getIntArray("state_last_built_nup")
            ?.takeIf { it.size == 7 }
            ?.let { values ->
                lastBuiltNupConfig = NupConfig(
                    rows = values[0],
                    cols = values[1],
                    marginMm = values[2],
                    isLandscape = values[3] != 0,
                    drawFrame = values[4] != 0,
                    scaleIndividually = values[5] != 0,
                    fitIndividually = values[6] != 0
                )
            }

        savedInstanceState
            ?.getString("state_last_named_export")
            ?.let { lastNamedExport = File(it) }
        pendingSaveNamedPath = savedInstanceState?.getString("state_pending_save_path")
        pendingSaveNamedDisplayName = savedInstanceState?.getString("state_pending_save_name")

        val bundleIndicesToSelect: Set<Int>? = savedInstanceState
            ?.getByteArray("state_selected_bits")
            ?.let { bytes ->
                val bits = BitSet.valueOf(bytes)
                buildSet {
                    var idx = bits.nextSetBit(0)
                    while (idx >= 0) {
                        add(idx)
                        idx = bits.nextSetBit(idx + 1)
                    }
                }
            }
            ?.takeIf { it.isNotEmpty() }

        savedInstanceState
            ?.getIntArray("state_source_rotation")
            ?.let { sourceRotation = it }

        savedInstanceState
            ?.getIntArray("state_source_position")
            ?.let { sourcePosition = it }

        val operationPending = PdfOperationScreen.isPending(PdfOperations.runner(this).state.value)

        runCatching {
            val sp = getSharedPreferences(PREFS_NAME_FRAGMENT, MODE_PRIVATE)

            if (savedInstanceState == null) {
                synchronized(prefsBackupLock) {
                    sp.edit {
                        remove(KEY_THEME_RESTORE_FIRST)
                        remove(KEY_THEME_RESTORE_OFFSET)
                        remove(KEY_THEME_RESTORE_SELECTED_CSV)
                        remove(KEY_THEME_RESTORE_ROTATION_CSV)
                        remove(KEY_THEME_RESTORE_POSITION_CSV)
                        remove(KEY_THEME_RESTORE_CROP)
                        remove(KEY_THEME_RESTORE_NSV_Y)
                        putBoolean(KEY_RESTORE_LOCK_UNTIL_RELOAD, false)
                    }
                }
            }

            val restFirst: Int
            val restOffset: Int
            val csv: String
            val nsvY: Int
            val needLockAtStart: Boolean

            synchronized(prefsBackupLock) {
                restFirst = sp.getInt(KEY_THEME_RESTORE_FIRST, RecyclerView.NO_POSITION)
                restOffset = sp.getInt(KEY_THEME_RESTORE_OFFSET, 0)
                csv = sp.getString(KEY_THEME_RESTORE_SELECTED_CSV, "") ?: ""
                nsvY = sp.getInt(KEY_THEME_RESTORE_NSV_Y, -1)
                needLockAtStart = sp.getBoolean(KEY_RESTORE_LOCK_UNTIL_RELOAD, false)
            }

            val hasRotationRestore = sp.contains(KEY_THEME_RESTORE_ROTATION_CSV)
            val rotationCsv = sp.getString(KEY_THEME_RESTORE_ROTATION_CSV, "") ?: ""
            val restoredRotation: IntArray? = if (hasRotationRestore) {
                if (rotationCsv.isNotEmpty()) {
                    rotationCsv.split(',').mapNotNull { it.toIntOrNull() }.toIntArray()
                } else {
                    IntArray(0)
                }
            } else null

            restoredRotation?.let { sourceRotation = it }

            if (sp.contains(KEY_THEME_RESTORE_POSITION_CSV)) {
                val positionCsv = sp.getString(KEY_THEME_RESTORE_POSITION_CSV, "") ?: ""
                sourcePosition = positionCsv.split(',').mapNotNull { it.toIntOrNull() }.toIntArray()
            }

            if (sp.contains(KEY_THEME_RESTORE_CROP)) {
                sourceCrop = PdfCrops.decode(sp.getString(KEY_THEME_RESTORE_CROP, "") ?: "")
            }

            val indicesToSelectPrefs: Set<Int>? = csv.split(',')
                .mapNotNull { it.toIntOrNull() }
                .toSet()
                .takeIf { it.isNotEmpty() }

            val indicesToSelect: Set<Int>? = indicesToSelectPrefs ?: bundleIndicesToSelect

            if (restFirst != RecyclerView.NO_POSITION || indicesToSelect != null || hasRotationRestore || nsvY >= 0) {
                synchronized(prefsBackupLock) {
                    sp.edit {
                        remove(KEY_THEME_RESTORE_FIRST)
                        remove(KEY_THEME_RESTORE_OFFSET)
                        remove(KEY_THEME_RESTORE_SELECTED_CSV)
                        remove(KEY_THEME_RESTORE_ROTATION_CSV)
                        remove(KEY_THEME_RESTORE_POSITION_CSV)
                        remove(KEY_THEME_RESTORE_CROP)
                        remove(KEY_THEME_RESTORE_NSV_Y)
                    }
                }

                if (operationPending) {
                    operationRestore = ThumbRestore(restFirst.takeIf { it != RecyclerView.NO_POSITION }, restOffset, indicesToSelect)
                    synchronized(prefsBackupLock) {
                        sp.edit { putBoolean(KEY_RESTORE_LOCK_UNTIL_RELOAD, false) }
                    }
                } else {
                    if (needLockAtStart) {
                        showInProgressUpdateUI(getString(R.string.in_progress_ui))
                        lockScreenOrientation()
                    }

                    reloadThumbnailsFromNup(
                        targetIndex = restFirst.takeIf { it != RecyclerView.NO_POSITION },
                        targetOffsetPx = restOffset,
                        restorePrevious = false,
                        indicesToSelect = indicesToSelect,
                        releaseUiAtStart = false,
                        onStarted = {
                            hideInProgressIfShownUpdateUI()
                            unlockScreenOrientation()
                            synchronized(prefsBackupLock) {
                                val spLock = getSharedPreferences(PREFS_NAME_FRAGMENT, MODE_PRIVATE)
                                spLock.edit { putBoolean(KEY_RESTORE_LOCK_UNTIL_RELOAD, false) }
                            }
                        }
                    )
                }

                if (nsvY >= 0) {
                    val nsv = findViewById<NestedScrollView>(R.id.root_scroll)
                    nsv?.post { nsv.scrollTo(0, nsvY) }
                }
            } else if (operationPending) {
                operationRestore = ThumbRestore(null, 0, null)
                synchronized(prefsBackupLock) {
                    sp.edit { putBoolean(KEY_RESTORE_LOCK_UNTIL_RELOAD, false) }
                }
            } else {
                if (needLockAtStart) {
                    showInProgressUpdateUI(getString(R.string.in_progress_ui))
                    lockScreenOrientation()
                }

                reloadThumbnailsFromNup(
                    onStarted = {
                        hideInProgressIfShownUpdateUI()
                        unlockScreenOrientation()
                        synchronized(prefsBackupLock) {
                            val spLock = getSharedPreferences(PREFS_NAME_FRAGMENT, MODE_PRIVATE)
                            spLock.edit { putBoolean(KEY_RESTORE_LOCK_UNTIL_RELOAD, false) }
                        }
                    }
                )
            }
        }.onFailure {
            val spLock = getSharedPreferences(PREFS_NAME_FRAGMENT, MODE_PRIVATE)
            if (operationPending) {
                operationRestore = ThumbRestore(null, 0, null)
                synchronized(prefsBackupLock) {
                    spLock.edit { putBoolean(KEY_RESTORE_LOCK_UNTIL_RELOAD, false) }
                }
            } else {
                val needLock = synchronized(prefsBackupLock) {
                    spLock.getBoolean(KEY_RESTORE_LOCK_UNTIL_RELOAD, false)
                }
                if (needLock) {
                    showInProgressUpdateUI(getString(R.string.in_progress_ui))
                    lockScreenOrientation()
                }

                reloadThumbnailsFromNup(
                    onStarted = {
                        hideInProgressIfShownUpdateUI()
                        unlockScreenOrientation()
                        synchronized(prefsBackupLock) {
                            spLock.edit { putBoolean(KEY_RESTORE_LOCK_UNTIL_RELOAD, false) }
                        }
                    }
                )
            }
        }

        observeOperations()

        updateButtonsState()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {

                if (::binding.isInitialized) {
                    if (binding.overlayProgressDialog.isVisible || binding.overlayProgressDialogUpdateUi.isVisible) {
                        return
                    }

                    if (binding.overlayDialogSignaturePosition.isVisible) {
                        binding.overlayDialogSignaturePosition.performClick()
                        return
                    }

                    if (binding.overlayDialogSignatureChooser.isVisible) {
                        binding.overlayDialogSignatureChooser.performClick()
                        return
                    }

                    if (binding.overlayDialogPassword.isVisible) {
                        binding.overlayDialogPassword.performClick()
                        return
                    }

                    if (binding.overlayDialogTimestamp.isVisible) {
                        binding.overlayDialogTimestamp.performClick()
                        return
                    }

                    if (binding.overlayDialogBatteryOptimization.isVisible) {
                        binding.overlayDialogBatteryOptimization.performClick()
                        return
                    }

                    if (binding.overlayDialogAbout.isVisible) {
                        binding.overlayDialogAbout.performClick()
                        return
                    }

                    if (binding.overlayDialogOpenWith.isVisible) {
                        binding.overlayDialogOpenWith.performClick()
                        return
                    }

                    if (binding.overlayDialogPositionOnPage.isVisible) {
                        binding.overlayDialogPositionOnPage.performClick()
                        return
                    }

                    if (binding.overlayDialogPdfCrop.isVisible) {
                        binding.overlayDialogPdfCrop.performClick()
                        return
                    }

                    if (binding.overlayDialogMaxCoordinates.isVisible) {
                        binding.overlayDialogMaxCoordinates.performClick()
                        return
                    }
                }

                finishAndRemoveTask()
            }
        })
    }

    private fun clearCacheDir() {
        try {
            cacheDir.listFiles()?.forEach { file -> file.deleteRecursively() }
        } catch (_: Exception) { }
    }

    private fun sourceDir(): File = File(cacheDir, SOURCE_TEMP_NAME)
    private fun nupTempFile(): File = File(cacheDir, NUP_TEMP_NAME)

    private fun workFileBackup(): WorkFileBackup =
        WorkFileBackup(cacheDir, SOURCE_TEMP_NAME, NUP_TEMP_NAME, SOURCE_TEMP_COPY_NAME, NUP_TEMP_COPY_NAME)

    private fun scheduleUiModeRecreate() {
        if (isFinishing || isDestroyed) return

        if (uiModeRecreateScheduled) return
        uiModeRecreateScheduled = true

        window.decorView.post {
            uiModeRecreateScheduled = false

            if (isFinishing || isDestroyed) {
                return@post
            }
            recreate()
        }
    }

    private fun maybeRunDeferredUiModeRecreate() {
        if (!pendingUiModeRecreate) return
        if (isUiBlockedByProgress) return
        if (orientationLockPrevRequested != null) return
        if (pendingSaveNamedPath != null) return

        pendingUiModeRecreate = false
        scheduleUiModeRecreate()
    }

    private fun recomputeTabletLikeFlag() {
        val maxMetrics = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(this)
        val density = resources.displayMetrics.density
        val wDp = maxMetrics.bounds.width() / density
        val hDp = maxMetrics.bounds.height() / density
        val maxSmallestDp = min(wDp, hDp)
        isTabletLikeDevice = (maxSmallestDp >= 600f)
    }

    private fun reapplyPanelsMaxWidthFromResources() {
        val maxPxMain = resources.getDimensionPixelSize(R.dimen.dp_9999_main)
        val maxPxOverlay = resources.getDimensionPixelSize(R.dimen.dp_9999_overlay)

        listOf(binding.topPanel, binding.bottomPanel)
            .forEach { updateMatchConstraintMaxWidth(it, maxPxMain) }

        listOf(
            binding.overlayDialogPasswordPanel,
            binding.overlayDialogSignaturePositionPanel,
            binding.overlayDialogSignatureChooserPanel,
            binding.overlayDialogTimestampPanel,
            binding.overlayDialogBatteryOptimizationPanel,
            binding.overlayDialogAboutPanel,
            binding.overlayDialogOpenWithPanel,
            binding.overlayDialogPositionOnPagePanel,
            binding.overlayDialogPdfCropPanel,
            binding.overlayDialogMaxCoordinatesPanel,
            binding.overlayProgressDialogPanel,
            binding.overlayProgressDialogUpdateUiPanel
        ).forEach { updateMatchConstraintMaxWidth(it, maxPxOverlay) }
    }

    private fun updateMatchConstraintMaxWidth(view: View, maxWidthPx: Int) {
        val lp = view.layoutParams as? LayoutParams
            ?: return

        if (lp.matchConstraintMaxWidth != maxWidthPx) {
            lp.matchConstraintMaxWidth = maxWidthPx
            view.layoutParams = lp
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        val prevConfig = lastConfigSnapshotForDiff?.let { Configuration(it) } ?: Configuration(newConfig)
        lastConfigSnapshotForDiff = Configuration(newConfig)

        super.onConfigurationChanged(newConfig)

        recomputeTabletLikeFlag()

        reapplyPanelsMaxWidthFromResources()

        openWithRequest?.let { (uri, apps) ->
            if (binding.overlayDialogOpenWith.isVisible) {
                binding.root.post {
                    if (openWithRequest != null) showOpenWithDialog(uri, apps, animate = false)
                }
            }
        }

        fastScroller?.refreshDisplayMetrics()

        requestRvThumbnailsHeightUpdate()

        val localeChanged = prevConfig.locales != newConfig.locales
        val fontScaleChanged = prevConfig.fontScale != newConfig.fontScale
        val layoutDirectionChanged = prevConfig.layoutDirection != newConfig.layoutDirection
        val keyboardChanged = prevConfig.keyboard != newConfig.keyboard
        val navigationChanged = prevConfig.navigation != newConfig.navigation
        val touchscreenChanged = prevConfig.touchscreen != newConfig.touchscreen
        val colorModeChanged =
            Build.VERSION.SDK_INT >= VERSION_CODES.O &&
                    prevConfig.colorMode != newConfig.colorMode
        val fontWeightAdjustmentChanged =
            Build.VERSION.SDK_INT >= VERSION_CODES.S &&
                    prevConfig.fontWeightAdjustment != newConfig.fontWeightAdjustment
        val mccChanged = prevConfig.mcc != newConfig.mcc
        val mncChanged = prevConfig.mnc != newConfig.mnc
        val grammaticalGenderChanged =
            Build.VERSION.SDK_INT >= VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    prevConfig.grammaticalGender != newConfig.grammaticalGender

        val nonUiModeConfigChangeNeedsRecreate =
            localeChanged ||
                    fontScaleChanged ||
                    layoutDirectionChanged ||
                    keyboardChanged ||
                    navigationChanged ||
                    touchscreenChanged ||
                    colorModeChanged ||
                    fontWeightAdjustmentChanged ||
                    mccChanged ||
                    mncChanged ||
                    grammaticalGenderChanged

        val prevNightMask = lastNightMask
        val nightMask = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK

        if (nightMask == Configuration.UI_MODE_NIGHT_UNDEFINED) {
            val rerender = signaturePositionEditorRerender

            if (::binding.isInitialized && binding.overlayDialogSignaturePosition.isVisible && rerender != null) {
                binding.root.post {
                    rerender.invoke()
                }
            }

            if (nonUiModeConfigChangeNeedsRecreate) {
                if (isUiBlockedByProgress || orientationLockPrevRequested != null) {
                    pendingUiModeRecreate = true
                } else {
                    scheduleUiModeRecreate()
                }
            }

            return
        }

        if (prevNightMask < 0) {
            lastNightMask = nightMask

            if (nonUiModeConfigChangeNeedsRecreate) {
                if (isUiBlockedByProgress || orientationLockPrevRequested != null) {
                    pendingUiModeRecreate = true
                } else {
                    scheduleUiModeRecreate()
                }
            }

            return
        }

        if (prevNightMask == Configuration.UI_MODE_NIGHT_UNDEFINED) {
            lastNightMask = nightMask

            if (nonUiModeConfigChangeNeedsRecreate) {
                if (isUiBlockedByProgress || orientationLockPrevRequested != null) {
                    pendingUiModeRecreate = true
                } else {
                    scheduleUiModeRecreate()
                }
            }

            return
        }

        val uiModeChanged = (nightMask != prevNightMask)
        if (uiModeChanged) {
            lastNightMask = nightMask

            window.decorView.post {
                if (isFinishing || isDestroyed) return@post

                val stableMask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                if (stableMask == Configuration.UI_MODE_NIGHT_UNDEFINED) {
                    return@post
                }

                if (stableMask != nightMask) {
                    lastNightMask = stableMask
                    return@post
                }

                if (isUiBlockedByProgress || orientationLockPrevRequested != null) {
                    pendingUiModeRecreate = true
                } else {
                    scheduleUiModeRecreate()
                }
            }
        } else {
            val rerender = signaturePositionEditorRerender

            if (::binding.isInitialized && binding.overlayDialogSignaturePosition.isVisible && rerender != null) {
                binding.root.post {
                    rerender.invoke()
                }
            }

            if (nonUiModeConfigChangeNeedsRecreate) {
                if (isUiBlockedByProgress || orientationLockPrevRequested != null) {
                    pendingUiModeRecreate = true
                } else {
                    scheduleUiModeRecreate()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val selectedIdx = viewModel.pageItems
            .mapIndexedNotNull { idx, it -> if (it.isSelected) idx else null }
        if (selectedIdx.isNotEmpty()) {
            val selectedBits = BitSet()
            for (idx in selectedIdx) selectedBits.set(idx)
            outState.putByteArray("state_selected_bits", selectedBits.toByteArray())
        }
        lastBuiltNupConfig?.let { cfg ->
            outState.putIntArray(
                "state_last_built_nup",
                intArrayOf(
                    cfg.rows,
                    cfg.cols,
                    cfg.marginMm,
                    if (cfg.isLandscape) 1 else 0,
                    if (cfg.drawFrame) 1 else 0,
                    if (cfg.scaleIndividually) 1 else 0,
                    if (cfg.fitIndividually) 1 else 0
                )
            )
        }
        lastNamedExport?.let { outState.putString("state_last_named_export", it.absolutePath) }
        pendingSaveNamedPath?.let { outState.putString("state_pending_save_path", it) }
        pendingSaveNamedDisplayName?.let { outState.putString("state_pending_save_name", it) }

        runCatching {
            val lm = binding.rvThumbnails.layoutManager as? LinearLayoutManager
            val first = lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
            val offset = if (first != RecyclerView.NO_POSITION) {
                lm?.findViewByPosition(first)?.top ?: 0
            } else 0

            val nsv = findViewById<NestedScrollView>(R.id.root_scroll)
            val nsvY = nsv?.scrollY ?: -1

            val csv = selectedIdx.joinToString(",")
            val rotationCsv = sourceRotation.joinToString(",")

            val sp = getSharedPreferences(PREFS_NAME_FRAGMENT, MODE_PRIVATE)
            synchronized(prefsBackupLock) {
                sp.edit {
                    putInt(KEY_THEME_RESTORE_FIRST, first)
                    putInt(KEY_THEME_RESTORE_OFFSET, offset)
                    putString(KEY_THEME_RESTORE_SELECTED_CSV, csv)
                    putString(KEY_THEME_RESTORE_ROTATION_CSV, rotationCsv)
                    putString(KEY_THEME_RESTORE_POSITION_CSV, sourcePosition.joinToString(","))
                    putString(KEY_THEME_RESTORE_CROP, PdfCrops.encode(sourceCrop))
                    putInt(KEY_THEME_RESTORE_NSV_Y, nsvY)
                    putBoolean(KEY_RESTORE_LOCK_UNTIL_RELOAD, true)
                }
            }
        }.onFailure {
        }
    }

    private fun lockScreenOrientation() {
        if (::binding.isInitialized && binding.overlayDialogPassword.isVisible) return
        if (orientationLockPrevRequested != null) return

        orientationLockPrevRequested = requestedOrientation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
    }

    private fun unlockScreenOrientation() {

        if (deferOrientationUnlockUntilThumbnailsReady) {
            deferredOrientationUnlockRequested = true
            return
        }

        val prev = orientationLockPrevRequested ?: return
        orientationLockPrevRequested = null
        requestedOrientation = prev
    }

    private fun readableSource(): PdfSourceParts {
        val dir = sourceDir()
        if (!PdfSourceParts.exists(dir)) return PdfSourceParts.empty(dir)
        return try { PdfSourceParts.open(dir) } catch (_: Exception) { PdfSourceParts.empty(dir) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        handleIncomingShareIntent(intent)

        setIntent(Intent())
    }

    private fun isBusyForIncomingIntent(): Boolean =
        binding.overlayProgressDialog.isVisible ||
            binding.overlayProgressDialogUpdateUi.isVisible ||
            binding.overlayDialogPassword.isVisible ||
            activeJobs.any { it.isActive } ||
            PdfOperationScreen.isPending(PdfOperations.runner(this).state.value)

    private fun checkWorkspaceFiles() {
        binding.root.removeCallbacks(workspaceFilesCheck)
        if (isFinishing || isDestroyed || viewModel.pageItems.isEmpty()) return
        if (isBusyForIncomingIntent() || isNupRebuilding || isLoadingSettingsProfile) {
            binding.root.postDelayed(workspaceFilesCheck, PENDING_INTENT_RETRY_MS)
            return
        }
        if (PdfSourceParts.exists(sourceDir()) && nupTempFile().exists()) return

        clearCacheDir()
        sourceRotation = IntArray(0)
        sourcePosition = IntArray(0)
        sourceCrop = emptyMap()
        lastBuiltNupConfig = null
        resetThumbDocKey("workspaceFilesCleared")
        clearThumbnails()
        updateButtonsState()
        Toast.makeText(this, getString(R.string.workspace_files_cleared), Toast.LENGTH_LONG).show()
    }

    private fun processPendingIncomingIntents() {
        binding.root.removeCallbacks(pendingIncomingIntentRetry)
        if (pendingIncomingIntents.isEmpty() || isFinishing || isDestroyed) return
        if (isBusyForIncomingIntent()) {
            binding.root.postDelayed(pendingIncomingIntentRetry, PENDING_INTENT_RETRY_MS)
            return
        }
        handleIncomingShareIntent(pendingIncomingIntents.removeFirst())
        if (pendingIncomingIntents.isNotEmpty()) {
            binding.root.postDelayed(pendingIncomingIntentRetry, PENDING_INTENT_RETRY_MS)
        }
    }

    private fun handleIncomingShareIntent(intent: Intent) {
        if (::binding.isInitialized && isBusyForIncomingIntent()) {
            pendingIncomingIntents.addLast(Intent(intent))
            binding.root.removeCallbacks(pendingIncomingIntentRetry)
            binding.root.postDelayed(pendingIncomingIntentRetry, PENDING_INTENT_RETRY_MS)
            return
        }

        val action = intent.action

        val collected = LinkedHashSet<Uri>()

        fun tryCollectIfLikelyPdf(uri: Uri?) {
            if (uri == null) return

            val mt = try { contentResolver.getType(uri) } catch (_: Exception) { null }
            val mtLc = mt?.lowercase(Locale.ROOT)
            val nameLc = try { extractFileName(uri).lowercase(Locale.ROOT) } catch (_: Exception) { "" }

            if (IncomingFileKind.looksLikePdf(mtLc, nameLc)) collected.add(uri)
        }

        fun processUris(uris: List<Uri>) {
            if (uris.isEmpty()) return

            val startProcessing = {
                if (uris.size == 1) {
                    handlePdfUri(uris.first())
                } else {
                    handlePdfUrisBatch(uris)
                }
            }

            val decor = window?.decorView
            val isWarmStart = try { decor?.isShown == true && hasWindowFocus() } catch (_: Exception) { false }
            if (isWarmStart) {
                startProcessing()
            } else {
                decor?.post(startProcessing) ?: startProcessing()
            }
        }

        when (action) {
            Intent.ACTION_VIEW -> {
                tryCollectIfLikelyPdf(intent.data)
                intent.clipData?.let { cd ->
                    for (i in 0 until cd.itemCount) tryCollectIfLikelyPdf(cd.getItemAt(i)?.uri)
                }
            }
            Intent.ACTION_SEND -> {
                tryCollectIfLikelyPdf(intent.getParcelableCompat(Intent.EXTRA_STREAM))
                intent.clipData?.let { cd ->
                    for (i in 0 until cd.itemCount) tryCollectIfLikelyPdf(cd.getItemAt(i)?.uri)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = intent.getParcelableArrayListCompat(Intent.EXTRA_STREAM)
                if (!list.isNullOrEmpty()) {
                    for (u in list) tryCollectIfLikelyPdf(u)
                } else {
                    val cd = intent.clipData
                    if (cd != null && cd.itemCount > 0) {
                        for (i in 0 until cd.itemCount) tryCollectIfLikelyPdf(cd.getItemAt(i)?.uri)
                    } else {
                        tryCollectIfLikelyPdf(intent.getParcelableCompat(Intent.EXTRA_STREAM))
                    }
                }
            }
        }

        if (collected.isNotEmpty()) {
            processUris(collected.toList())
            return
        }

        if (collected.isEmpty()) {
            when (action) {
                Intent.ACTION_VIEW -> {
                    val single = intent.data
                    if (single != null) {
                        val mtLc = try { contentResolver.getType(single)?.lowercase(Locale.ROOT) } catch (_: Exception) { null }
                        val nameLc = try { extractFileName(single).lowercase(Locale.ROOT) } catch (_: Exception) { "" }
                        if (isSupportedImageMime(mtLc) || isSupportedImageFileName(nameLc)) {
                            processUris(listOf(single))
                            return
                        } else {
                            val looksImageByMime = (mtLc?.startsWith("image/") == true)
                            if (looksImageByMime) {
                                Toast.makeText(this@MainActivity, getString(R.string.unsupported_image), Toast.LENGTH_LONG).show()
                                return
                            }
                        }
                    }
                }
                Intent.ACTION_SEND -> {
                    val single = intent.getParcelableCompat(Intent.EXTRA_STREAM)
                    if (single != null) {
                        val mtLc = try { contentResolver.getType(single)?.lowercase(Locale.ROOT) } catch (_: Exception) { null }
                        val nameLc = try { extractFileName(single).lowercase(Locale.ROOT) } catch (_: Exception) { "" }
                        if (isSupportedImageMime(mtLc) || isSupportedImageFileName(nameLc)) {
                            processUris(listOf(single))
                            return
                        } else {
                            val looksImageByMime = (mtLc?.startsWith("image/") == true)
                            if (looksImageByMime) {
                                Toast.makeText(this@MainActivity, getString(R.string.unsupported_image), Toast.LENGTH_LONG).show()
                                return
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handlePdfUrisBatch(uris: List<Uri>) {
        if (uris.isEmpty()) return
        importPdfUris(uris, detectImages = false)
    }

    private fun isTouchInsideView(ev: MotionEvent, view: View): Boolean {
        val location = IntArray(2)
        view.getLocationInWindow(location)

        val left = location[0].toFloat()
        val top = location[1].toFloat()
        val right = left + view.width
        val bottom = top + view.height

        if (ev.x in left..<right && ev.y in top..<bottom) {
            return true
        }

        view.getLocationOnScreen(location)

        val screenLeft = location[0].toFloat()
        val screenTop = location[1].toFloat()
        val screenRight = screenLeft + view.width
        val screenBottom = screenTop + view.height

        return ev.rawX in screenLeft..<screenRight && ev.rawY in screenTop..<screenBottom
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::binding.isInitialized && binding.overlayDialogPassword.isVisible) {
            return super.dispatchTouchEvent(ev)
        }

        if (ev.action == MotionEvent.ACTION_DOWN) {
            val view = currentFocus
            if (view is EditText) {
                val blockingIds = setOf(
                    R.id.number_of_rows,
                    R.id.number_of_columns,
                    R.id.margin,
                    R.id.signature_position_x,
                    R.id.signature_position_y,
                    R.id.file_name
                )
                if (view.id in blockingIds) {
                    if (!isTouchInsideView(ev, view)) {
                        view.clearFocus()

                        run {
                            val root = findViewById<View>(android.R.id.content)
                            if (root != null) {
                                val prevFocusable = root.isFocusableInTouchMode
                                root.isFocusableInTouchMode = true
                                root.requestFocus()
                                root.post {
                                    try {
                                        root.isFocusableInTouchMode = prevFocusable
                                    } catch (_: Exception) { }
                                }
                            }
                        }

                        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.hideSoftInputFromWindow(view.windowToken, 0)

                        return true
                    }
                }
            }
        }

        var shouldHideKeyboard = false
        var targetWindowToken: IBinder? = null

        var focusedBefore: View? = null

        if (ev.action == MotionEvent.ACTION_UP) {
            currentFocus?.let { focusedView ->
                if (focusedView is EditText) {
                    if (!isTouchInsideView(ev, focusedView)) {
                        shouldHideKeyboard = true
                        targetWindowToken = focusedView.windowToken
                        focusedBefore = focusedView
                    }
                }
            }
        }

        val handled = super.dispatchTouchEvent(ev)

        if (shouldHideKeyboard) {
            window?.decorView?.post {
                try {
                    focusedBefore?.let { fv ->
                        if (fv.isFocused) {
                            fv.clearFocus()
                        }
                    }

                    run {
                        val root = findViewById<View>(android.R.id.content)
                        if (root != null) {
                            val prevFocusable = root.isFocusableInTouchMode
                            root.isFocusableInTouchMode = true
                            root.requestFocus()
                            root.post {
                                try {
                                    root.isFocusableInTouchMode = prevFocusable
                                } catch (_: Exception) { }
                            }
                        }
                    }

                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    val token = targetWindowToken ?: window?.decorView?.windowToken
                    if (token != null) {
                        imm.hideSoftInputFromWindow(token, 0)
                    }
                } catch (_: Exception) { }
            }
        }

        return handled
    }

    private fun closeEditingAndHideKeyboardNoJump(et: EditText) {
        try {
            if (et.isFocused) {
                et.clearFocus()
            }

            run {
                val root = findViewById<View>(android.R.id.content)
                if (root != null) {
                    val prevFocusable = root.isFocusableInTouchMode
                    root.isFocusableInTouchMode = true
                    root.requestFocus()
                    root.post {
                        try {
                            root.isFocusableInTouchMode = prevFocusable
                        } catch (_: Exception) { }
                    }
                }
            }

            window?.decorView?.post {
                try {
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    val token = et.windowToken ?: window?.decorView?.windowToken
                    if (token != null) {
                        imm.hideSoftInputFromWindow(token, 0)
                    }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }

    private fun setupEnterClosesEditTextAndHidesKeyboard(et: EditText) {
        et.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN

            if (actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_NEXT ||
                isEnter
            ) {
                closeEditingAndHideKeyboardNoJump(et)
                true
            } else {
                false
            }
        }
    }

    private fun clearAnyEditTextFocusAfterRecreate() {
        try {
            var token: IBinder? = null

            val focused = currentFocus
            if (focused is EditText) {
                token = focused.windowToken
                if (focused.isFocused) {
                    focused.clearFocus()
                }
            }

            run {
                val root = findViewById<View>(android.R.id.content)
                if (root != null) {
                    val prevFocusable = root.isFocusableInTouchMode
                    root.isFocusableInTouchMode = true
                    root.requestFocus()
                    root.post {
                        try {
                            root.isFocusableInTouchMode = prevFocusable
                        } catch (_: Exception) { }
                    }
                }
            }

            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            val t = token ?: window?.decorView?.windowToken
            if (t != null) {
                imm.hideSoftInputFromWindow(t, 0)
            }
        } catch (_: Exception) { }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)

        val fileNameEt: EditText = findViewById(R.id.file_name)
        val restored = fileNameEt.text?.toString().orEmpty()
        val localized = localizeDefaultFileNameTemplate(restored, defaultFileNameTemplatesOfAllLanguages())
        if (localized != restored) fileNameEt.setText(localized)

        window?.decorView?.post {
            try {
                clearAnyEditTextFocusAfterRecreate()
            } catch (_: Exception) { }
        }
    }

    private fun numberRangeFilterNoOp(range: IntRange) = InputFilter { src, start, end, dest, dStart, dEnd ->
        val newText = dest.take(dStart).toString() + src.subSequence(start, end) + dest.drop(dEnd).toString()
        if (newText.isEmpty()) return@InputFilter null
        val v = newText.toIntOrNull()
        if (v != null && v in range) null else dest.subSequence(dStart, dEnd)
    }

    private fun updateSignatureInputFilters() {
        signaturePosX.filters = arrayOf(InputFilter.LengthFilter(3))
        signaturePosY.filters = arrayOf(InputFilter.LengthFilter(3))

        validateInputs()
    }

    private fun sigPosXKey(isLand: Boolean): String =
        if (isLand) KEY_SIG_POS_LANDSCAPE_X else KEY_SIG_POS_PORTRAIT_X

    private fun sigPosYKey(isLand: Boolean): String =
        if (isLand) KEY_SIG_POS_LANDSCAPE_Y else KEY_SIG_POS_PORTRAIT_Y

    private fun defaultSigPosX(isLand: Boolean): String =
        (if (isLand) DEFAULT_SIG_POS_LANDSCAPE_X_MM else DEFAULT_SIG_POS_PORTRAIT_X_MM).toString()

    private fun defaultSigPosY(isLand: Boolean): String =
        (if (isLand) DEFAULT_SIG_POS_LANDSCAPE_Y_MM else DEFAULT_SIG_POS_PORTRAIT_Y_MM).toString()

    private fun storedSignaturePosition(isLand: Boolean): Pair<String, String> {
        val prefs = getSharedPreferences(PREFS_NAME_FRAGMENT, MODE_PRIVATE)
        return synchronized(prefsBackupLock) {
            (prefs.getString(sigPosXKey(isLand), defaultSigPosX(isLand)) ?: defaultSigPosX(isLand)) to
                    (prefs.getString(sigPosYKey(isLand), defaultSigPosY(isLand)) ?: defaultSigPosY(isLand))
        }
    }

    private fun currentSignaturePosition(isLand: Boolean): Pair<String, String> =
        if (isLand == landscape.isChecked) {
            signaturePosX.text.toString() to signaturePosY.text.toString()
        } else {
            storedSignaturePosition(isLand)
        }

    private fun swapSignaturePositionFields(isLand: Boolean) {
        val prefs = getSharedPreferences(PREFS_NAME_FRAGMENT, MODE_PRIVATE)
        synchronized(prefsBackupLock) {
            prefs.edit {
                putString(sigPosXKey(!isLand), signaturePosX.text.toString())
                putString(sigPosYKey(!isLand), signaturePosY.text.toString())
            }
        }
        val (x, y) = storedSignaturePosition(isLand)
        signaturePosX.setText(x)
        signaturePosY.setText(y)
    }

    private fun showMaxCoordinatesAlert(maxXi: Int, maxYi: Int, imageTooLarge: Boolean) {
        val overlay = binding.overlayDialogMaxCoordinates
        val panel = binding.overlayDialogMaxCoordinatesPanel

        panel.removeAllViews()
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_max_coordinates, panel, false)
        panel.addView(dialogView)

        overlay.visibility = View.VISIBLE

        overlay.setOnClickListener {
            overlay.visibility = View.GONE
            overlay.setOnClickListener(null)
        }
        panel.setOnClickListener {
        }

        val maxCoordinates = getString(R.string.max_coordinates_x_y_mm, maxXi, maxYi)
        dialogView.findViewById<MaterialTextView>(R.id.dmc_text)?.text =
            if (imageTooLarge) "${getString(R.string.signature_image_too_large)}\n$maxCoordinates" else maxCoordinates

        val btnOk = dialogView.findViewById<Button>(R.id.dmc_btn_ok)

        btnOk?.setOnClickListener {
            overlay.visibility = View.GONE
            overlay.setOnClickListener(null)
        }
    }

    private fun currentSignatureRefPx(): Float = refPxForSignatureDpi(selectedDpiIndexForSignature)

    @SuppressLint("RtlHardcoded")
    private fun showSignatureDialog() {
        if (viewModel.pageItems.none { it.isSelected }) {
            Toast.makeText(this, getString(R.string.select_a_page), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.Main) {
            val signatureFiles = withContext(Dispatchers.IO) {
                (signaturesDir.listFiles { file ->
                    file.isFile &&
                            file.name.startsWith("sign_") &&
                            file.name.endsWith(".png", ignoreCase = true)
                }?.toList() ?: emptyList()).sortedBy { it.lastModified() }
            }

            val sigDisplayNames = signatureFiles.map { file ->
                file.name.removePrefix("sign_").removeSuffix(".png").replace('_', ' ')
            }.toTypedArray()

            selectedSigIndex = -1

            val prefs = getSharedPreferences(PREFS_NAME_FRAGMENT, MODE_PRIVATE)
            val lastDpiSaved = synchronized(prefsBackupLock) {
                prefs.getInt(KEY_LAST_SIG_DPI_INDEX, 0).coerceIn(0, 1)
            }
            selectedDpiIndexForSignature = lastDpiSaved

            val overlay = binding.overlayDialogSignatureChooser
            val panel = binding.overlayDialogSignatureChooserPanel
            panel.removeAllViews()
            val dialogView = LayoutInflater.from(this@MainActivity).inflate(R.layout.dialog_signature_chooser, panel, false)
            panel.addView(dialogView)
            overlay.visibility = View.VISIBLE
            overlay.bringToFront()

            overlay.setOnClickListener {
                overlay.visibility = View.GONE
                overlay.setOnClickListener(null)
            }
            panel.setOnClickListener {
            }

            val listView = dialogView.findViewById<ListView>(R.id.sic_listview)

            var applyScanEffect = synchronized(prefsBackupLock) {
                prefs.getBoolean(KEY_LAST_SCAN_EFFECT, false)
            }
            var applyGrayscalePage = synchronized(prefsBackupLock) {
                prefs.getBoolean(KEY_LAST_GRAYSCALE, false)
            }

            var overrideXmm: Float? = null
            var overrideYmm: Float? = null

            var dpiSelectedIndex = selectedDpiIndexForSignature

            run {
                data class RowViews(
                    val container: LinearLayout,
                    val text: MaterialTextView,
                    val rightGroup: LinearLayout?,
                    val radio: MaterialRadioButton?,
                    val edit: AppCompatImageButton?,
                    val scanCheck: MaterialCheckBox?,
                    val type: Int
                )

                val customAdapter = object : BaseAdapter() {
                    override fun getCount(): Int = sigDisplayNames.size + 4

                    override fun getItem(position: Int): Any =
                        when (position) {
                            0 -> getString(R.string.dpi_144)
                            1 -> getString(R.string.dpi_300)
                            2 -> getString(R.string.scan_effect)
                            3 -> getString(R.string.print_page_grayscale)
                            else -> sigDisplayNames[position - 4]
                        }

                    override fun getItemId(position: Int): Long = position.toLong()

                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val isDpiRow = (position == 0 || position == 1)
                        val isScanRow = (position == 2)
                        val isGrayRow = (position == 3)
                        val isSignatureRow = (position >= 4)

                        val desiredType = when {
                            isDpiRow -> 0
                            isScanRow || isGrayRow -> 1
                            else -> 2
                        }

                        val row = if (convertView == null || ((convertView.tag as? RowViews)?.type != desiredType)) {
                            val container = LinearLayout(parent.context).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                )

                                val padH = parent.resources.getDimensionPixelSize(R.dimen.dp_16)
                                val padV = parent.resources.getDimensionPixelSize(R.dimen.dp_12)
                                setPadding(
                                    padH,
                                    padV,
                                    padH - (padH - parent.resources.getDimensionPixelSize(R.dimen.dp_12)),
                                    padV
                                )
                                isClickable = true
                                isFocusable = true
                            }

                            val tv = MaterialTextView(parent.context).apply {
                                layoutParams = LinearLayout.LayoutParams(
                                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                                )
                                textAlignment = View.TEXT_ALIGNMENT_GRAVITY
                                gravity = Gravity.LEFT
                                TextViewCompat.setTextAppearance(
                                    this, R.style.TextAppearance_ReePDF_ListItem
                                )
                            }

                            if (isScanRow || isGrayRow) {
                                val rightGroup = LinearLayout(parent.context).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                                    layoutParams = LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT
                                    ).apply {
                                        gravity = Gravity.RIGHT
                                    }
                                    isBaselineAligned = false
                                }

                                val chk = MaterialCheckBox(parent.context).apply {
                                    layoutParams = LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT
                                    ).apply {
                                        gravity = Gravity.RIGHT
                                    }
                                    contentDescription = null
                                    background = null
                                    setPadding(0, 0, 0, 0)

                                    isUseMaterialThemeColors = false
                                    buttonDrawable = AppCompatResources.getDrawable(context, R.drawable.ic_checkbox)
                                    buttonTintList = AppCompatResources.getColorStateList(context, R.color.ic_checkbox_filename_color)

                                    isChecked = applyScanEffect

                                    isClickable = false
                                    isFocusable = false

                                    minWidth = 0
                                    minHeight = 0
                                }

                                run {
                                    val rbProbe = MaterialRadioButton(parent.context)
                                    val rbW = (rbProbe.buttonDrawable?.intrinsicWidth ?: 0)
                                    val chkW = (chk.buttonDrawable?.intrinsicWidth ?: 0)
                                    val extraEnd = (rbW - chkW)
                                    if (extraEnd > 0) {
                                        rightGroup.setPadding(0, 0, extraEnd, 0)
                                    }
                                }

                                rightGroup.addView(chk)
                                container.addView(tv)
                                container.addView(rightGroup)

                                container.tag = RowViews(
                                    container = container,
                                    text = tv,
                                    rightGroup = rightGroup,
                                    radio = null,
                                    edit = null,
                                    scanCheck = chk,
                                    type = 1
                                )
                                container
                            } else if (isDpiRow) {
                                val rightGroup = LinearLayout(parent.context).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                                    layoutParams = LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT
                                    ).apply {
                                        gravity = Gravity.RIGHT
                                    }
                                    isBaselineAligned = false
                                }

                                val rb = MaterialRadioButton(parent.context).apply {
                                    layoutParams = LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT
                                    ).apply {
                                        gravity = Gravity.RIGHT
                                    }
                                    isClickable = false
                                    isFocusable = false
                                    setPadding(0, 0, 0, 0)
                                    minWidth = 0
                                    minHeight = 0
                                }

                                rightGroup.addView(rb)
                                container.addView(tv)
                                container.addView(rightGroup)

                                container.tag = RowViews(
                                    container = container,
                                    text = tv,
                                    rightGroup = rightGroup,
                                    radio = rb,
                                    edit = null,
                                    scanCheck = null,
                                    type = 0
                                )
                                container
                            } else {
                                val rightGroup = LinearLayout(parent.context).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                                    layoutParams = LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT
                                    ).apply {
                                        gravity = Gravity.RIGHT
                                    }
                                    isBaselineAligned = false
                                }

                                val rb = MaterialRadioButton(parent.context).apply {
                                    layoutParams = LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT
                                    ).apply {
                                        gravity = Gravity.RIGHT
                                    }
                                    isClickable = false
                                    isFocusable = false
                                    setPadding(0, 0, 0, 0)
                                    minWidth = 0
                                    minHeight = 0
                                }

                                val editBtn = AppCompatImageButton(parent.context).apply {
                                    layoutParams = LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT
                                    ).apply {
                                        leftMargin = 0
                                        gravity = Gravity.RIGHT
                                        gravity = Gravity.CENTER_VERTICAL
                                    }
                                    setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_edit_24))
                                    contentDescription = null
                                    background = null
                                    isFocusable = false
                                    setPadding(0, 0, 0, 0)
                                }

                                rightGroup.addView(rb)
                                rightGroup.addView(editBtn)
                                container.addView(tv)
                                container.addView(rightGroup)

                                container.tag = RowViews(
                                    container = container,
                                    text = tv,
                                    rightGroup = rightGroup,
                                    radio = rb,
                                    edit = editBtn,
                                    scanCheck = null,
                                    type = 2
                                )
                                container
                            }
                        } else convertView

                        val holder = row.tag as RowViews

                        holder.container.background = if (position == 1 || position == 3) {
                            AppCompatResources.getDrawable(parent.context, R.drawable.signature_group_divider)
                        } else {
                            null
                        }

                        holder.text.text = when {
                            isDpiRow && position == 0 -> getString(R.string.dpi_144)
                            isDpiRow -> getString(R.string.dpi_300)
                            isScanRow -> getString(R.string.scan_effect)
                            isGrayRow -> getString(R.string.print_page_grayscale)
                            else -> sigDisplayNames[position - 4]
                        }

                        run {
                            val checkedPos = listView.checkedItemPosition
                            if (isSignatureRow) {
                                holder.radio?.isEnabled = true

                                holder.radio?.isChecked = (position == checkedPos)
                                val onePageSelected = (viewModel.pageItems.count { it.isSelected } == 1)
                                val isThisSigSelected = (position == checkedPos)
                                val canEdit = onePageSelected && isThisSigSelected
                                holder.edit?.isEnabled = canEdit

                                holder.edit?.setOnClickListener {
                                    if (!canEdit) return@setOnClickListener

                                    val singleIdx = viewModel.pageItems
                                        .mapIndexedNotNull { idx, it -> if (it.isSelected) idx else null }
                                        .firstOrNull() ?: return@setOnClickListener

                                    lifecycleScope.launch(Dispatchers.Main) {
                                        val sigFile = signatureFiles[position - 4]
                                        val (pxW, pxH) = withContext(Dispatchers.IO) {
                                            readImageBounds(sigFile.absolutePath)
                                        }

                                        if (pxW <= 0 || pxH <= 0) {
                                            Toast.makeText(this@MainActivity, getString(R.string.signature_image_invalid), Toast.LENGTH_LONG).show()
                                            return@launch
                                        }

                                        val isLand = landscape.isChecked
                                        val (maxXf, maxYf) = SignatureGeometry.maxPositionMm(
                                            pxW, pxH, refPxForSignatureDpi(dpiSelectedIndex), isLand
                                        )
                                        val maxXi = floor(maxXf).toInt()
                                        val maxYi = floor(maxYf).toInt()

                                        if (maxXf < 0f || maxYf < 0f) {
                                            showMaxCoordinatesAlert(maxXi, maxYi, imageTooLarge = true)
                                            return@launch
                                        }

                                        runCatching {
                                            openSignaturePositionEditor(
                                                signatureFile = sigFile,
                                                nupPageIndex = singleIdx
                                            ) { xmm, ymm ->
                                                if (xmm != null && ymm != null) {
                                                    overrideXmm = xmm
                                                    overrideYmm = ymm
                                                }
                                            }
                                        }.onFailure {
                                            Toast.makeText(this@MainActivity, getString(R.string.editor_open_error), Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            } else if (isScanRow) {
                                holder.scanCheck?.isChecked = applyScanEffect
                            } else if (isGrayRow) {
                                holder.scanCheck?.isChecked = applyGrayscalePage
                            } else if (isDpiRow) {
                                holder.radio?.isChecked = (dpiSelectedIndex == position)
                            }
                        }

                        holder.container.setOnClickListener {
                            if (isDpiRow) {
                                dpiSelectedIndex = position
                                selectedDpiIndexForSignature = position
                                synchronized(prefsBackupLock) {
                                    prefs.edit {
                                        putInt(KEY_LAST_SIG_DPI_INDEX, position)
                                    }
                                }
                                (listView.adapter as? BaseAdapter)?.notifyDataSetChanged()
                            } else if (isScanRow) {
                                applyScanEffect = !applyScanEffect
                                (row.tag as RowViews).scanCheck?.isChecked = applyScanEffect
                                synchronized(prefsBackupLock) {
                                    prefs.edit {
                                        putBoolean(KEY_LAST_SCAN_EFFECT, applyScanEffect)
                                    }
                                }
                                (listView.adapter as? BaseAdapter)?.notifyDataSetChanged()
                            } else if (isGrayRow) {
                                applyGrayscalePage = !applyGrayscalePage
                                (row.tag as RowViews).scanCheck?.isChecked = applyGrayscalePage
                                synchronized(prefsBackupLock) {
                                    prefs.edit {
                                        putBoolean(KEY_LAST_GRAYSCALE, applyGrayscalePage)
                                    }
                                }
                                (listView.adapter as? BaseAdapter)?.notifyDataSetChanged()
                            } else {
                                listView.setItemChecked(position, true)
                                (listView.adapter as? BaseAdapter)?.notifyDataSetChanged()
                                listView.performItemClick(row, position, listView.getItemIdAtPosition(position))
                            }
                        }

                        return row
                    }
                }

                listView.adapter = customAdapter
                listView.choiceMode = ListView.CHOICE_MODE_SINGLE
            }

            val btnNew = dialogView.findViewById<Button>(R.id.sic_new)
            val btnRemove = dialogView.findViewById<Button>(R.id.sic_remove)
            val btnOk = dialogView.findViewById<Button>(R.id.sic_ok)
            val btnCancel = dialogView.findViewById<Button>(R.id.sic_cancel)

            btnNew.setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/png"
                }
                signaturePickerLauncher.launch(intent)

                overlay.visibility = View.GONE
                overlay.setOnClickListener(null)
            }

            btnRemove.isEnabled = false
            btnRemove.setOnClickListener {
                val checkedPosition = listView.checkedItemPosition
                if (checkedPosition != ListView.INVALID_POSITION && checkedPosition >= 4) {
                    val job = lifecycleScope.launch(Dispatchers.IO) {
                        val fileToDelete = signatureFiles[checkedPosition - 4]
                        val deleted = fileToDelete.delete()
                        withContext(Dispatchers.Main) {
                            if (deleted) {
                                overlay.visibility = View.GONE
                                overlay.setOnClickListener(null)

                                showSignatureDialog()
                            } else {
                                Toast.makeText(this@MainActivity, getString(R.string.failed_to_delete_signature), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    activeJobs.add(job)
                    job.invokeOnCompletion { activeJobs.remove(job) }
                }
            }

            btnOk.isEnabled = false
            btnOk.setOnClickListener {
                val pos = listView.checkedItemPosition
                if (pos == ListView.INVALID_POSITION || pos < 4) return@setOnClickListener

                val sigFile = signatureFiles[pos - 4]

                val xmmFromEditor = overrideXmm
                val ymmFromEditor = overrideYmm

                val xStr = signaturePosX.text?.toString()?.trim().orEmpty()
                val yStr = signaturePosY.text?.toString()?.trim().orEmpty()
                val inputXmm = xmmFromEditor ?: (if (xStr.isEmpty()) 0f else xStr.toFloatOrNull())
                val inputYmm = ymmFromEditor ?: (if (yStr.isEmpty()) 0f else yStr.toFloatOrNull())

                if (inputXmm == null || inputYmm == null) {
                    Toast.makeText(this@MainActivity, getString(R.string.enter_value_0_100), Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                lifecycleScope.launch(Dispatchers.Main) {
                    val (pxW, pxH) = withContext(Dispatchers.IO) {
                        readImageBounds(sigFile.absolutePath)
                    }

                    if (pxW <= 0 || pxH <= 0) {
                        Toast.makeText(this@MainActivity, getString(R.string.signature_image_invalid), Toast.LENGTH_LONG).show()
                        return@launch
                    }

                    val isLand = landscape.isChecked
                    val (maxXf, maxYf) = SignatureGeometry.maxPositionMm(
                        pxW, pxH, refPxForSignatureDpi(dpiSelectedIndex), isLand
                    )
                    val maxXi = floor(maxXf).toInt()
                    val maxYi = floor(maxYf).toInt()

                    if (inputXmm !in 0f..maxXf || inputYmm !in 0f..maxYf) {
                        currentSigFile = null

                        signaturePosX.filters = arrayOf(InputFilter.LengthFilter(3))
                        signaturePosY.filters = arrayOf(InputFilter.LengthFilter(3))

                        overlay.visibility = View.GONE
                        overlay.setOnClickListener(null)

                        window?.decorView?.post {
                            showMaxCoordinatesAlert(maxXi, maxYi, imageTooLarge = maxXf < 0f || maxYf < 0f)
                        }
                        return@launch
                    }

                    signSelectedPagesOnSourceTemp(
                        signatureFile = sigFile,
                        signatureWidth = pxW,
                        signatureHeight = pxH,
                        inputXMM = inputXmm,
                        inputYMM = inputYmm,
                        applyScanEffect = applyScanEffect,
                        applyGrayscalePage = applyGrayscalePage
                    )

                    overlay.visibility = View.GONE
                    overlay.setOnClickListener(null)
                }
            }

            listView.setOnItemClickListener { _, _, position, _ ->
                if (position >= 4) currentSigFile = signatureFiles[position - 4]

                btnRemove.isEnabled = (position >= 4)
                btnOk.isEnabled     = false

                lifecycleScope.launch(Dispatchers.Main) {
                    if (position >= 4) {
                        val file = currentSigFile
                        val (pxW, pxH) = withContext(Dispatchers.IO) {
                            readImageBounds(file!!.absolutePath)
                        }

                        if (pxW > 0 && pxH > 0) {
                            signaturePosX.filters = arrayOf(InputFilter.LengthFilter(3))
                            signaturePosY.filters = arrayOf(InputFilter.LengthFilter(3))
                        }

                        btnOk.isEnabled = true

                        (listView.adapter as? BaseAdapter)?.notifyDataSetChanged()
                    } else {
                        (listView.adapter as? BaseAdapter)?.notifyDataSetChanged()

                        btnOk.isEnabled = false
                    }
                }
            }

            btnCancel.setOnClickListener {

                overlay.visibility = View.GONE
                overlay.setOnClickListener(null)
            }
        }
    }

    private class DraggableSignatureView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null
    ) : AppCompatImageView(context, attrs) {

        var minLeft: Int = 0
        var minTop: Int = 0
        var maxLeft: Int = Int.MAX_VALUE
        var maxTop: Int = Int.MAX_VALUE

        var onMoved: ((left: Int, top: Int) -> Unit)? = null

        private var dX = 0f
        private var dY = 0f

        private var downXRelToParent = 0f
        private var downYRelToParent = 0f

        private val clickSlop = ViewConfiguration.get(context).scaledTouchSlop

        private var disallowingParentIntercept = false

        private fun forceAllowParentIntercept() {
            if (!disallowingParentIntercept) return
            disallowingParentIntercept = false
            try {
                parent?.requestDisallowInterceptTouchEvent(false)
            } catch (_: Exception) {
            }
        }

        fun updatePosition(left: Int, top: Int) {
            layoutParams = (layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = left
                topMargin = top
            }
            requestLayout()
            onMoved?.invoke(left, top)
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            fun parentLocationOnScreen(): Pair<Int, Int> {
                val loc = IntArray(2)
                (parent as? View)?.getLocationOnScreen(loc)
                return loc[0] to loc[1]
            }

            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val lp = layoutParams as FrameLayout.LayoutParams
                    val (parentX, parentY) = parentLocationOnScreen()

                    dX = ev.rawX - parentX - lp.leftMargin
                    dY = ev.rawY - parentY - lp.topMargin
                    downXRelToParent = ev.rawX - parentX
                    downYRelToParent = ev.rawY - parentY

                    disallowingParentIntercept = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val (parentX, parentY) = parentLocationOnScreen()

                    val wantedLeft = (ev.rawX - parentX - dX).toInt()
                    val wantedTop  = (ev.rawY - parentY - dY).toInt()

                    val clampedLeft = wantedLeft.coerceIn(minLeft, maxLeft)
                    val clampedTop  = wantedTop.coerceIn(minTop,  maxTop)
                    updatePosition(clampedLeft, clampedTop)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    forceAllowParentIntercept()

                    val (parentX, parentY) = parentLocationOnScreen()
                    val dx = abs((ev.rawX - parentX) - downXRelToParent)
                    val dy = abs((ev.rawY - parentY) - downYRelToParent)
                    if (dx <= clickSlop && dy <= clickSlop) {
                        performClick()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    forceAllowParentIntercept()
                    return true
                }
            }
            return super.onTouchEvent(ev)
        }

        override fun onDetachedFromWindow() {
            forceAllowParentIntercept()
            super.onDetachedFromWindow()
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            if (!hasWindowFocus) {
                forceAllowParentIntercept()
            }
            super.onWindowFocusChanged(hasWindowFocus)
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    @SuppressLint("RtlHardcoded")
    private fun openSignaturePositionEditor(
        signatureFile: File,
        nupPageIndex: Int,
        onResult: (Float?, Float?) -> Unit
    ) {
        val ctx = this

        val overlay = binding.overlayDialogSignaturePosition
        val panel = binding.overlayDialogSignaturePositionPanel
        panel.removeAllViews()
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_signature_position, panel, false)
        panel.addView(dialogView)
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()

        panel.setOnClickListener {
        }

        signaturePositionEditorRerender = null

        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.sp_btn_cancel)
        val btnOk = dialogView.findViewById<MaterialButton>(R.id.sp_btn_ok)

        val spThumb = dialogView.findViewById<AppCompatImageView>(R.id.sp_thumbnail)
        val thumbParent = spThumb.parent as ViewGroup
        val indexInParent = thumbParent.indexOfChild(spThumb)
        thumbParent.removeView(spThumb)

        val scroller = NestedScrollView(ctx).apply {
            layoutParams = when (thumbParent) {
                is LinearLayout -> LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                else -> ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            isFillViewport = true
            clipToPadding = true
            clipChildren = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isVerticalScrollBarEnabled = true
        }
        thumbParent.addView(scroller, indexInParent)

        val stage = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 0, 0, 0)
            isClickable = true
            isFocusable = true
            clipToPadding = true
            clipChildren = true
        }
        scroller.addView(stage)

        var inner: FrameLayout? = null

        val pageView = spThumb.apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val sigView = DraggableSignatureView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            isFocusable = true
        }

        val isLand = landscape.isChecked
        val a4Wmm = if (isLand) 297f else 210f
        val a4Hmm = if (isLand) 210f else 297f

        val initXmm = signaturePosX.text?.toString()?.trim()?.toFloatOrNull() ?: 0f
        val initYmm = signaturePosY.text?.toString()?.trim()?.toFloatOrNull() ?: 0f

        var okReady = false
        var pageLeft = 0
        var pageTop = 0
        var pxPerMMX = 0f
        var pxPerMMY = 0f
        var currentLeft = 0
        var currentTop = 0

        var allowedMaxXmm = 0f
        var allowedMaxYmm = 0f

        var lastPageBitmap: Bitmap? = null
        var lastSigBitmap: Bitmap? = null
        var lastRenderedAvailW = -1

        var layoutRerenderRunnable: Runnable? = null
        var layoutListener: View.OnLayoutChangeListener? = null

        val finished = AtomicBoolean(false)

        fun dismissWithResult(xmm: Float?, ymm: Float?) {
            if (!finished.compareAndSet(false, true)) return

            signaturePositionEditorRerender = null

            overlay.visibility = View.GONE
            overlay.setOnClickListener(null)

            layoutListener?.let { ll ->
                stage.removeOnLayoutChangeListener(ll)
                scroller.removeOnLayoutChangeListener(ll)
                dialogView.removeOnLayoutChangeListener(ll)
            }

            layoutRerenderRunnable?.let { rr ->
                stage.removeCallbacks(rr)
            }

            runCatching {
                pageView.setImageDrawable(null)
                lastPageBitmap?.let { if (!it.isRecycled) it.recycle() }
                lastPageBitmap = null
            }

            runCatching {
                sigView.setImageDrawable(null)
                lastSigBitmap?.let { if (!it.isRecycled) it.recycle() }
                lastSigBitmap = null
            }

            onResult(xmm, ymm)
        }

        btnCancel.setOnClickListener {
            dismissWithResult(null, null)
        }
        btnOk.setOnClickListener {
            if (!okReady) {
                Toast.makeText(ctx, getString(R.string.in_progress_signature_editing), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val leftWithin = (currentLeft - pageLeft).toFloat()
            val topWithin  = (currentTop  - pageTop).toFloat()
            val outXmmRaw = leftWithin / pxPerMMX
            val outYmmRaw = topWithin  / pxPerMMY

            val outXmm = outXmmRaw.coerceIn(0f, allowedMaxXmm)
            val outYmm = outYmmRaw.coerceIn(0f, allowedMaxYmm)

            dismissWithResult(outXmm, outYmm)
        }

        fun requestRender(keepCurrentPosition: Boolean) {
            scroller.post {
                if (overlay.visibility != View.VISIBLE) return@post

                var availW = stage.width
                if (availW <= 0) availW = scroller.width
                if (availW <= 0) availW = thumbParent.width
                if (availW <= 0) availW = dialogView.width
                if (availW <= 0) availW = panel.width
                if (availW <= 0) availW = overlay.width
                if (availW <= 0) availW = 1

                if (keepCurrentPosition && availW == lastRenderedAvailW) {
                    return@post
                }
                lastRenderedAvailW = availW

                val carryXmm = if (keepCurrentPosition && okReady && pxPerMMX > 0f && pxPerMMY > 0f) {
                    val leftWithin = (currentLeft - pageLeft).toFloat()
                    (leftWithin / pxPerMMX).coerceAtLeast(0f)
                } else {
                    initXmm
                }

                val carryYmm = if (keepCurrentPosition && okReady && pxPerMMX > 0f && pxPerMMY > 0f) {
                    val topWithin = (currentTop - pageTop).toFloat()
                    (topWithin / pxPerMMY).coerceAtLeast(0f)
                } else {
                    initYmm
                }

                okReady = false

                val pageSize = if (isLand) PageSize.A4.rotate() else PageSize.A4
                val pageAspect = pageSize.width / pageSize.height

                val contentW = lastRenderedAvailW.coerceAtLeast(1)
                val contentH = max(1, floor(contentW / pageAspect).toInt())

                runCatching {
                    pageView.setImageDrawable(null)
                    lastPageBitmap?.let { if (!it.isRecycled) it.recycle() }
                    lastPageBitmap = null
                }
                runCatching {
                    sigView.setImageDrawable(null)
                    lastSigBitmap?.let { if (!it.isRecycled) it.recycle() }
                    lastSigBitmap = null
                }

                val nup = nupTempFile()
                if (!nup.exists()) {
                    Toast.makeText(ctx, getString(R.string.temporary_pdf_not_found), Toast.LENGTH_LONG).show()
                    dismissWithResult(null, null)
                    return@post
                }

                val pageBmp = kotlin.run {
                    var bmp: Bitmap? = null
                    try {
                        ParcelFileDescriptor.open(nup, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                            PdfRenderer(pfd).use { renderer ->
                                if (nupPageIndex !in 0 until renderer.pageCount) return@use
                                renderer.openPage(nupPageIndex).use { page ->
                                    val b = createBitmap(contentW, contentH, Bitmap.Config.ARGB_8888)
                                    val sx = contentW.toFloat() / page.width.toFloat()
                                    val sy = contentH.toFloat() / page.height.toFloat()
                                    val mat = Matrix().apply { postScale(sx, sy) }
                                    page.render(b, null, mat, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    bmp = b
                                }
                            }
                        }
                    } catch (_: Exception) { }
                    bmp
                }

                if (pageBmp == null) {
                    Toast.makeText(ctx, getString(R.string.failed_to_open_pdf_with_msg, "render"), Toast.LENGTH_LONG).show()
                    dismissWithResult(null, null)
                    return@post
                }

                if (inner == null) {
                    inner = FrameLayout(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            pageBmp.width,
                            pageBmp.height,
                            Gravity.CENTER
                        )
                        clipChildren = true
                        clipToPadding = true

                        val bg = ContextCompat.getColor(ctx, R.color.signature_position_pdf_bg)
                        setBackgroundColor(bg)
                    }
                    stage.addView(inner)
                } else {
                    inner.layoutParams = (inner.layoutParams as FrameLayout.LayoutParams).apply {
                        width = pageBmp.width
                        height = pageBmp.height
                        gravity = Gravity.CENTER
                    }
                }

                if (pageView.parent !== inner) {
                    (pageView.parent as? ViewGroup)?.removeView(pageView)
                    inner.addView(
                        pageView,
                        FrameLayout.LayoutParams(pageBmp.width, pageBmp.height, Gravity.CENTER)
                    )
                } else {
                    pageView.layoutParams = (pageView.layoutParams as FrameLayout.LayoutParams).apply {
                        width = pageBmp.width
                        height = pageBmp.height
                        gravity = Gravity.CENTER
                    }
                }

                pageView.setImageBitmap(pageBmp)
                lastPageBitmap = pageBmp
                pageView.requestLayout()

                val sigBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(signatureFile.absolutePath, sigBounds)

                val refAxisPx = if (isLand) pageBmp.height else pageBmp.width
                val desiredSigW = max(1, floor(sigBounds.outWidth  * (refAxisPx / currentSignatureRefPx())).toInt())
                val desiredSigH = max(1, floor(sigBounds.outHeight * (refAxisPx / currentSignatureRefPx())).toInt())

                fun calcInSampleSize(sw0: Int, sh0: Int, dw: Int, dh: Int): Int {
                    var sample = 1
                    val halfW = sw0 / 2
                    val halfH = sh0 / 2
                    while (halfW / sample >= dw && halfH / sample >= dh) sample *= 2
                    return sample.coerceAtLeast(1)
                }
                val sample = calcInSampleSize(sigBounds.outWidth, sigBounds.outHeight, desiredSigW, desiredSigH)
                val sigBmp = BitmapFactory.decodeFile(signatureFile.absolutePath, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                })

                if (sigBmp == null) {
                    Toast.makeText(ctx, getString(R.string.signature_image_invalid), Toast.LENGTH_LONG).show()
                    dismissWithResult(null, null)
                    return@post
                }

                if (sigView.parent !== inner) {
                    (sigView.parent as? ViewGroup)?.removeView(sigView)
                    inner.addView(
                        sigView,
                        FrameLayout.LayoutParams(desiredSigW, desiredSigH).apply { gravity = Gravity.TOP or Gravity.LEFT }
                    )
                } else {
                    sigView.layoutParams = (sigView.layoutParams as FrameLayout.LayoutParams).apply {
                        width = desiredSigW
                        height = desiredSigH
                    }
                }

                sigView.setImageBitmap(sigBmp)
                lastSigBitmap = sigBmp
                sigView.alpha = 1f
                sigView.visibility = View.VISIBLE
                sigView.bringToFront()
                sigView.requestLayout()

                pxPerMMX = pageBmp.width.toFloat() / a4Wmm
                pxPerMMY = pageBmp.height.toFloat() / a4Hmm

                val (maxXmm, maxYmm) = SignatureGeometry.maxPositionMm(
                    sigBounds.outWidth, sigBounds.outHeight, currentSignatureRefPx(), isLand
                )
                val rawMaxXmm = maxXmm.coerceAtLeast(0f)
                val rawMaxYmm = maxYmm.coerceAtLeast(0f)
                allowedMaxXmm = floor(rawMaxXmm)
                allowedMaxYmm = floor(rawMaxYmm)
                val allowedMaxLeftPx = floor(allowedMaxXmm * pxPerMMX).toInt()
                val allowedMaxTopPx  = floor(allowedMaxYmm * pxPerMMY).toInt()

                val initialLeftWithin = (carryXmm * pxPerMMX).coerceIn(0f, allowedMaxLeftPx.toFloat())
                val initialTopWithin  = (carryYmm * pxPerMMY).coerceIn(0f, allowedMaxTopPx.toFloat())

                pageLeft = 0
                pageTop  = 0

                currentLeft = initialLeftWithin.toInt()
                currentTop  = initialTopWithin.toInt()

                sigView.minLeft = 0
                sigView.minTop  = 0

                sigView.maxLeft = allowedMaxLeftPx
                sigView.maxTop  = allowedMaxTopPx

                sigView.onMoved = { l, t ->
                    currentLeft = l
                    currentTop  = t
                }

                sigView.updatePosition(currentLeft, currentTop)

                okReady = true
            }
        }

        signaturePositionEditorRerender = {
            requestRender(true)
        }

        dialogView.post {
            requestRender(false)
        }

        layoutRerenderRunnable = Runnable {

            if (overlay.visibility != View.VISIBLE) return@Runnable
            requestRender(true)
        }

        layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->

            if (overlay.visibility != View.VISIBLE) return@OnLayoutChangeListener

            val wNow = stage.width
            if (wNow <= 0) return@OnLayoutChangeListener
            if (wNow == lastRenderedAvailW) return@OnLayoutChangeListener

            val rr = layoutRerenderRunnable
            stage.removeCallbacks(rr)
            stage.postDelayed(rr, 60)
        }

        stage.addOnLayoutChangeListener(layoutListener)
        scroller.addOnLayoutChangeListener(layoutListener)
        dialogView.addOnLayoutChangeListener(layoutListener)

        overlay.setOnClickListener {
            dismissWithResult(null, null)
        }
    }

    private fun readImageBounds(path: String): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        return (opts.outWidth to opts.outHeight)
    }

    private fun copySignatureToAppDir(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                var fileName = extractFileName(uri)
                if (!fileName.startsWith("sign_")) fileName = "sign_$fileName"
                if (!fileName.endsWith(".png", ignoreCase = true)) {
                    fileName = fileName.substringBeforeLast('.') + ".png"
                }

                val targetFile = File(signaturesDir, fileName)
                var uniqueFile = targetFile
                var counter = 1
                while (uniqueFile.exists()) {
                    val baseName = fileName.substringBeforeLast('.')
                    val extension = fileName.substringAfterLast('.', "png")
                    uniqueFile = File(signaturesDir, "${baseName}_${counter}.$extension"); counter++
                }

                FileOutputStream(uniqueFile).use { outputStream -> inputStream.copyTo(outputStream) }
                showSignatureDialog()
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_copying_signature_with_msg, e.message), Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        super.onPause()

        try {
            if (!isChangingConfigurations) {
                val v = currentFocus
                if (v is EditText && v.isFocused) {
                    restoreImeAfterBackground = true
                    restoreImeAfterBackgroundEditTextId = v.id

                    restoreImeAfterBackgroundWasActive = try {
                        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.isActive(v)
                    } catch (_: Exception) {
                        true
                    }
                } else {
                    restoreImeAfterBackground = false
                    restoreImeAfterBackgroundEditTextId = View.NO_ID
                    restoreImeAfterBackgroundWasActive = false
                }
            } else {
                restoreImeAfterBackground = false
                restoreImeAfterBackgroundEditTextId = View.NO_ID
                restoreImeAfterBackgroundWasActive = false
            }
        } catch (_: Exception) { }

        val longOpRunning = runCatching {
            isUiBlockedByProgress ||
                    (::binding.isInitialized && (binding.overlayProgressDialog.isVisible || binding.overlayProgressDialogUpdateUi.isVisible))
        }.getOrDefault(false)

        if (!longOpRunning) {
            saveCurrentSettings()
        }

        if (pendingCacheCleanupAfterExternalReturn) {
            didPauseForExternalAction = true
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        hasWindowFocusFlag = hasFocus

        if (!hasFocus) {
            try {
                binding.rvThumbnails.parent?.requestDisallowInterceptTouchEvent(false)
            } catch (_: Exception) {
            }
        }

        if (hasFocus) {
            try {
                if (restoreImeAfterBackground && restoreImeAfterBackgroundWasActive) {
                    val v = findViewById<View>(restoreImeAfterBackgroundEditTextId)
                    if (v is EditText && v.isFocused) {
                        window?.decorView?.post {
                            try {
                                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                                imm.showSoftInput(v, 0)
                            } catch (_: Exception) { }
                        }
                    }
                }
            } catch (_: Exception) { }

            restoreImeAfterBackground = false
            restoreImeAfterBackgroundEditTextId = View.NO_ID
            restoreImeAfterBackgroundWasActive = false

            if (pendingRvUpdate) {
                pendingRvUpdate = false
                requestRvThumbnailsHeightUpdate()
            }

            window?.decorView?.post {
                if (isFinishing || isDestroyed) return@post
                maybeRunDeferredUiModeRecreate()
            }
        }
    }

    override fun onDestroy() {

        runCatching {
            if (::binding.isInitialized) {
                binding.root.removeCallbacks(progressTicker)
                binding.root.removeCallbacks(pendingIncomingIntentRetry)
                binding.root.removeCallbacks(workspaceFilesCheck)
                binding.overlayProgressDialog.visibility = View.GONE
                binding.overlayProgressDialogUpdateUi.visibility = View.GONE
            }
        }.onFailure {
        }

        signaturePositionEditorRerender = null
        saveCurrentSettings()
        (findViewById<RecyclerView>(R.id.rv_thumbnails).adapter as? ThumbnailAdapter)?.close()
        handoverId?.let { PdfOperations.runner(this).abandon(it) }
        if (isFinishing && !isChangingConfigurations) {
            val operations = PdfOperations.runner(this)
            PdfOperationScreen.onFinishing(operations.state.value)?.let { operations.cancel(it) }
            clearCacheDir()
        }
        super.onDestroy()
    }

    private fun snapshotInitialPrefsIfNeeded() {
        val sp = getSharedPreferences(PREFS_NAME_FRAGMENT, MODE_PRIVATE)

        synchronized(prefsBackupLock) {
            if (sp.getBoolean(KEY_FIRST_RUN_INITIALIZED, false)) return

            sp.edit {
                putString(KEY_ROWS, "1")
                putString(KEY_REEPROFILE1_ROWS, "1")
                putString(KEY_REEPROFILE2_ROWS, "2")
                putString(KEY_COLUMNS, "1")
                putString(KEY_REEPROFILE1_COLUMNS, "1")
                putString(KEY_REEPROFILE2_COLUMNS, "2")
                putBoolean(KEY_SCALE, false)
                putBoolean(KEY_REEPROFILE1_SCALE, false)
                putBoolean(KEY_REEPROFILE2_SCALE, true)
                putBoolean(KEY_FIT, false)
                putBoolean(KEY_REEPROFILE1_FIT, false)
                putBoolean(KEY_REEPROFILE2_FIT, false)
                putInt(KEY_POSITION, DEFAULT_POSITION)
                putInt(KEY_REEPROFILE1_POSITION, DEFAULT_POSITION)
                putInt(KEY_REEPROFILE2_POSITION, DEFAULT_POSITION)
                putBoolean(KEY_CROP, false)
                putBoolean(KEY_REEPROFILE1_CROP, false)
                putBoolean(KEY_REEPROFILE2_CROP, false)
                putString(KEY_MARGIN, "0")
                putString(KEY_REEPROFILE1_MARGIN, "0")
                putString(KEY_REEPROFILE2_MARGIN, "2")
                putBoolean(KEY_ORIENTATION, true)
                putBoolean(KEY_REEPROFILE1_ORIENTATION, true)
                putBoolean(KEY_REEPROFILE2_ORIENTATION, true)
                putBoolean(KEY_DRAW_FRAME, false)
                putBoolean(KEY_REEPROFILE1_DRAW_FRAME, false)
                putBoolean(KEY_REEPROFILE2_DRAW_FRAME, false)
                putString(KEY_SIG_POS_PORTRAIT_X, "155")
                putString(KEY_REEPROFILE1_SIG_POS_PORTRAIT_X, "155")
                putString(KEY_REEPROFILE2_SIG_POS_PORTRAIT_X, "155")
                putString(KEY_SIG_POS_PORTRAIT_Y, "245")
                putString(KEY_REEPROFILE1_SIG_POS_PORTRAIT_Y, "245")
                putString(KEY_REEPROFILE2_SIG_POS_PORTRAIT_Y, "245")
                putString(KEY_SIG_POS_LANDSCAPE_X, "235")
                putString(KEY_REEPROFILE1_SIG_POS_LANDSCAPE_X, "235")
                putString(KEY_REEPROFILE2_SIG_POS_LANDSCAPE_X, "235")
                putString(KEY_SIG_POS_LANDSCAPE_Y, "165")
                putString(KEY_REEPROFILE1_SIG_POS_LANDSCAPE_Y, "165")
                putString(KEY_REEPROFILE2_SIG_POS_LANDSCAPE_Y, "165")
                putBoolean(KEY_PASSWORD_OUT, false)
                putBoolean(KEY_REEPROFILE1_PASSWORD_OUT, false)
                putBoolean(KEY_REEPROFILE2_PASSWORD_OUT, false)
                putString(KEY_FILE_NAME_TEMPLATE, getString(R.string.default_tmpl_reeprofile1))
                putString(KEY_REEPROFILE1_FILE_NAME_TEMPLATE, getString(R.string.default_tmpl_reeprofile1))
                putString(KEY_REEPROFILE2_FILE_NAME_TEMPLATE, getString(R.string.default_tmpl_reeprofile2))
                putInt(KEY_LAST_SIG_DPI_INDEX, 0)
                putInt(KEY_REEPROFILE1_SIG_DPI_INDEX, 0)
                putInt(KEY_REEPROFILE2_SIG_DPI_INDEX, 0)
                putBoolean(KEY_LAST_SCAN_EFFECT, false)
                putBoolean(KEY_REEPROFILE1_SCAN_EFFECT, false)
                putBoolean(KEY_REEPROFILE2_SCAN_EFFECT, false)
                putBoolean(KEY_LAST_GRAYSCALE, false)
                putBoolean(KEY_REEPROFILE1_GRAYSCALE, false)
                putBoolean(KEY_REEPROFILE2_GRAYSCALE, false)
                putBoolean(KEY_LAST_INNER_PDF_READER, true)
                putString(KEY_LAST_LOADED_PROFILE, "reeprofile1")
                putString(KEY_THEME_MODE, "system")
                putBoolean(KEY_RESTORE_LOCK_UNTIL_RELOAD, false)
                putBoolean(KEY_FIRST_RUN_INITIALIZED, true)
            }
        }
    }

    private fun saveSettings(type: String) {
        val prefs = getSharedPreferences(PREFS_NAME_FRAGMENT, MODE_PRIVATE)
        val fileNameEt: EditText = findViewById(R.id.file_name)

        synchronized(prefsBackupLock) {
            prefs.edit {
                val rows = numberOfRows.text.toString()
                val columns = numberOfColumns.text.toString()
                val marginVal = margin.text.toString()
                val isPortrait = !landscape.isChecked
                val drawFrame = drawFrameSwitch.isChecked
                val (sigPortraitX, sigPortraitY) = currentSignaturePosition(false)
                val (sigLandscapeX, sigLandscapeY) = currentSignaturePosition(true)
                val scale = scaleMode().scale
                val fit = scaleMode().fit
                val fileNameTemplate = fileNameEt.text?.toString() ?: ""
                val passwordOutChecked = passwordOutCb.isChecked
                val sigDpiIndex = prefs.getInt(KEY_LAST_SIG_DPI_INDEX, 0)
                val scanEffect = prefs.getBoolean(KEY_LAST_SCAN_EFFECT, false)
                val grayscale = prefs.getBoolean(KEY_LAST_GRAYSCALE, false)

                when (type) {
                    "reeprofile1" -> {
                        putString(KEY_REEPROFILE1_ROWS, rows)
                        putString(KEY_REEPROFILE1_COLUMNS, columns)
                        putString(KEY_REEPROFILE1_MARGIN, marginVal)
                        putBoolean(KEY_REEPROFILE1_ORIENTATION, isPortrait)
                        putBoolean(KEY_REEPROFILE1_DRAW_FRAME, drawFrame)
                        putString(KEY_REEPROFILE1_SIG_POS_PORTRAIT_X, sigPortraitX)
                        putString(KEY_REEPROFILE1_SIG_POS_PORTRAIT_Y, sigPortraitY)
                        putString(KEY_REEPROFILE1_SIG_POS_LANDSCAPE_X, sigLandscapeX)
                        putString(KEY_REEPROFILE1_SIG_POS_LANDSCAPE_Y, sigLandscapeY)
                        putBoolean(KEY_REEPROFILE1_SCALE, scale)
                        putBoolean(KEY_REEPROFILE1_FIT, fit)
                        putInt(KEY_REEPROFILE1_POSITION, defaultPosition)
                        putBoolean(KEY_REEPROFILE1_CROP, cropAllPages)
                        putBoolean(KEY_REEPROFILE1_PASSWORD_OUT, passwordOutChecked)
                        putString(KEY_REEPROFILE1_FILE_NAME_TEMPLATE, fileNameTemplate)
                        putInt(KEY_REEPROFILE1_SIG_DPI_INDEX, sigDpiIndex)
                        putBoolean(KEY_REEPROFILE1_SCAN_EFFECT, scanEffect)
                        putBoolean(KEY_REEPROFILE1_GRAYSCALE, grayscale)
                    }

                    "reeprofile2" -> {
                        putString(KEY_REEPROFILE2_ROWS, rows)
                        putString(KEY_REEPROFILE2_COLUMNS, columns)
                        putString(KEY_REEPROFILE2_MARGIN, marginVal)
                        putBoolean(KEY_REEPROFILE2_ORIENTATION, isPortrait)
                        putBoolean(KEY_REEPROFILE2_DRAW_FRAME, drawFrame)
                        putString(KEY_REEPROFILE2_SIG_POS_PORTRAIT_X, sigPortraitX)
                        putString(KEY_REEPROFILE2_SIG_POS_PORTRAIT_Y, sigPortraitY)
                        putString(KEY_REEPROFILE2_SIG_POS_LANDSCAPE_X, sigLandscapeX)
                        putString(KEY_REEPROFILE2_SIG_POS_LANDSCAPE_Y, sigLandscapeY)
                        putBoolean(KEY_REEPROFILE2_SCALE, scale)
                        putBoolean(KEY_REEPROFILE2_FIT, fit)
                        putInt(KEY_REEPROFILE2_POSITION, defaultPosition)
                        putBoolean(KEY_REEPROFILE2_CROP, cropAllPages)
                        putBoolean(KEY_REEPROFILE2_PASSWORD_OUT, passwordOutChecked)
                        putString(KEY_REEPROFILE2_FILE_NAME_TEMPLATE, fileNameTemplate)
                        putInt(KEY_REEPROFILE2_SIG_DPI_INDEX, sigDpiIndex)
                        putBoolean(KEY_REEPROFILE2_SCAN_EFFECT, scanEffect)
                        putBoolean(KEY_REEPROFILE2_GRAYSCALE, grayscale)
                    }
                }
                putString(KEY_ROWS, rows)
                putString(KEY_COLUMNS, columns)
                putString(KEY_MARGIN, marginVal)
                putBoolean(KEY_ORIENTATION, isPortrait)
                putBoolean(KEY_DRAW_FRAME, drawFrame)
                putString(KEY_SIG_POS_PORTRAIT_X, sigPortraitX)
                putString(KEY_SIG_POS_PORTRAIT_Y, sigPortraitY)
                putString(KEY_SIG_POS_LANDSCAPE_X, sigLandscapeX)
                putString(KEY_SIG_POS_LANDSCAPE_Y, sigLandscapeY)
                putBoolean(KEY_SCALE, scale)
                putBoolean(KEY_FIT, fit)
                putInt(KEY_POSITION, defaultPosition)
                putBoolean(KEY_CROP, cropAllPages)
                putBoolean(KEY_PASSWORD_OUT, passwordOutChecked)
                putString(KEY_FILE_NAME_TEMPLATE, fileNameTemplate)
            }
            Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveCurrentSettings() {
        val prefs = getSharedPreferences(PREFS_NAME_FRAGMENT, MODE_PRIVATE)
        val fileNameEt: EditText = findViewById(R.id.file_name)

        synchronized(prefsBackupLock) {
            prefs.edit {
                putString(KEY_ROWS, numberOfRows.text.toString())
                putString(KEY_COLUMNS, numberOfColumns.text.toString())
                putString(KEY_MARGIN, margin.text.toString())
                putBoolean(KEY_ORIENTATION, !landscape.isChecked)
                putBoolean(KEY_DRAW_FRAME, drawFrameSwitch.isChecked)
                putString(sigPosXKey(landscape.isChecked), signaturePosX.text.toString())
                putString(sigPosYKey(landscape.isChecked), signaturePosY.text.toString())
                putBoolean(KEY_SCALE, scaleMode().scale)
                putBoolean(KEY_FIT, scaleMode().fit)
                putInt(KEY_POSITION, defaultPosition)
                putBoolean(KEY_CROP, cropAllPages)
                putBoolean(KEY_PASSWORD_OUT, passwordOutCb.isChecked)
                putString(KEY_FILE_NAME_TEMPLATE, fileNameEt.text?.toString() ?: "")
            }
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS_NAME_FRAGMENT, MODE_PRIVATE)

        val rows: String
        val columns: String
        val marginVal: String
        val isPortrait: Boolean
        val drawFrame: Boolean
        val sigX: String
        val sigY: String
        val scale: Boolean
        val fit: Boolean
        val passwordOutChecked: Boolean

        synchronized(prefsBackupLock) {
            rows = prefs.getString(KEY_ROWS, "1") ?: "1"
            columns = prefs.getString(KEY_COLUMNS, "1") ?: "1"
            marginVal = prefs.getString(KEY_MARGIN, "0") ?: "0"
            isPortrait = prefs.getBoolean(KEY_ORIENTATION, true)
            drawFrame = prefs.getBoolean(KEY_DRAW_FRAME, false)
            sigX = prefs.getString(sigPosXKey(!isPortrait), defaultSigPosX(!isPortrait)) ?: defaultSigPosX(!isPortrait)
            sigY = prefs.getString(sigPosYKey(!isPortrait), defaultSigPosY(!isPortrait)) ?: defaultSigPosY(!isPortrait)
            scale = prefs.getBoolean(KEY_SCALE, false)
            fit = prefs.getBoolean(KEY_FIT, false)
            defaultPosition = validPosition(prefs.getInt(KEY_POSITION, DEFAULT_POSITION))
            cropAllPages = prefs.getBoolean(KEY_CROP, false)
            passwordOutChecked = prefs.getBoolean(KEY_PASSWORD_OUT, false)
        }

        numberOfRows.setText(rows)
        numberOfColumns.setText(columns)
        margin.setText(marginVal)
        suppressSigPosSwap = true
        landscape.isChecked = !isPortrait
        suppressSigPosSwap = false
        drawFrameSwitch.isChecked = drawFrame
        signaturePosX.setText(sigX)
        signaturePosY.setText(sigY)
        passwordOutCb.isChecked = passwordOutChecked
        setScaleMode(scale, fit)

        setupEnterClosesEditTextAndHidesKeyboard(numberOfRows)
        setupEnterClosesEditTextAndHidesKeyboard(numberOfColumns)
        setupEnterClosesEditTextAndHidesKeyboard(margin)
        setupEnterClosesEditTextAndHidesKeyboard(signaturePosX)
        setupEnterClosesEditTextAndHidesKeyboard(signaturePosY)

        val fileNameEt: EditText = findViewById(R.id.file_name)
        val defaultTemplate = getString(R.string.default_tmpl_reeprofile1)
        val tmpl = synchronized(prefsBackupLock) {
            prefs.getString(KEY_FILE_NAME_TEMPLATE, defaultTemplate) ?: defaultTemplate
        }
        fileNameEt.setText(tmpl)

        if (fileNameEt.getTag(R.id.file_name) != true) {
            fileNameEt.addTextChangedListener(SimpleTextWatcher {
                val sNow = fileNameEt.text?.toString()?.trim().orEmpty()
                validateIntField(
                    fileNameEt,
                    getString(R.string.file_name_optional),
                    isValid = sNow.isNotBlank()
                )
            })
            fileNameEt.setTag(R.id.file_name, true)
        }
        val sInit = fileNameEt.text?.toString()?.trim().orEmpty()
        validateIntField(
            fileNameEt,
            getString(R.string.file_name_optional),
            isValid = sInit.isNotBlank()
        )

        updateFileNameHintBasedOnRowsCols()
        validateInputs()
    }

    private fun loadSettings(type: String) {
        isLoadingSettingsProfile = true
        regenRequestedDuringProfileLoad = false

        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                localizeDefaultFileNameTemplatesInPrefs()
                val prefs = getSharedPreferences(PREFS_NAME_FRAGMENT, MODE_PRIVATE)
                synchronized(prefsBackupLock) {
                    when (type) {
                        "reeprofile1" -> mapOf(
                            "nlRows" to (prefs.getString(KEY_REEPROFILE1_ROWS, "1") ?: "1"),
                            "nlColumns" to (prefs.getString(KEY_REEPROFILE1_COLUMNS, "1") ?: "1"),
                            "nlMargin" to (prefs.getString(KEY_REEPROFILE1_MARGIN, "0") ?: "0"),
                            "nlOrientation" to prefs.getBoolean(KEY_REEPROFILE1_ORIENTATION, true),
                            "nlDrawFrame" to prefs.getBoolean(KEY_REEPROFILE1_DRAW_FRAME, false),
                            "nlSigPosPortraitX" to (prefs.getString(
                                KEY_REEPROFILE1_SIG_POS_PORTRAIT_X,
                                DEFAULT_SIG_POS_PORTRAIT_X_MM.toString()
                            ) ?: DEFAULT_SIG_POS_PORTRAIT_X_MM.toString()),
                            "nlSigPosPortraitY" to (prefs.getString(
                                KEY_REEPROFILE1_SIG_POS_PORTRAIT_Y,
                                DEFAULT_SIG_POS_PORTRAIT_Y_MM.toString()
                            ) ?: DEFAULT_SIG_POS_PORTRAIT_Y_MM.toString()),
                            "nlSigPosLandscapeX" to (prefs.getString(
                                KEY_REEPROFILE1_SIG_POS_LANDSCAPE_X,
                                DEFAULT_SIG_POS_LANDSCAPE_X_MM.toString()
                            ) ?: DEFAULT_SIG_POS_LANDSCAPE_X_MM.toString()),
                            "nlSigPosLandscapeY" to (prefs.getString(
                                KEY_REEPROFILE1_SIG_POS_LANDSCAPE_Y,
                                DEFAULT_SIG_POS_LANDSCAPE_Y_MM.toString()
                            ) ?: DEFAULT_SIG_POS_LANDSCAPE_Y_MM.toString()),
                            "nlScale" to prefs.getBoolean(KEY_REEPROFILE1_SCALE, false),
                            "nlFit" to prefs.getBoolean(KEY_REEPROFILE1_FIT, false),
                            "nlPosition" to prefs.getInt(KEY_REEPROFILE1_POSITION, DEFAULT_POSITION),
                            "nlCrop" to prefs.getBoolean(KEY_REEPROFILE1_CROP, false),
                            "nlPasswordOut" to prefs.getBoolean(KEY_REEPROFILE1_PASSWORD_OUT, false),
                            "nlSigDpiIndex" to prefs.getInt(KEY_REEPROFILE1_SIG_DPI_INDEX, 0),
                            "nlScanEffect" to prefs.getBoolean(KEY_REEPROFILE1_SCAN_EFFECT, false),
                            "nlGrayscale" to prefs.getBoolean(KEY_REEPROFILE1_GRAYSCALE, false),
                            "nlFileNameTemplate" to (prefs.getString(
                                KEY_REEPROFILE1_FILE_NAME_TEMPLATE,
                                getString(R.string.default_tmpl_reeprofile1)
                            ) ?: getString(R.string.default_tmpl_reeprofile1))
                        )

                        "reeprofile2" -> mapOf(
                            "nlRows" to (prefs.getString(KEY_REEPROFILE2_ROWS, "2") ?: "2"),
                            "nlColumns" to (prefs.getString(KEY_REEPROFILE2_COLUMNS, "2") ?: "2"),
                            "nlMargin" to (prefs.getString(KEY_REEPROFILE2_MARGIN, "2") ?: "2"),
                            "nlOrientation" to prefs.getBoolean(KEY_REEPROFILE2_ORIENTATION, true),
                            "nlDrawFrame" to prefs.getBoolean(KEY_REEPROFILE2_DRAW_FRAME, false),
                            "nlSigPosPortraitX" to (prefs.getString(
                                KEY_REEPROFILE2_SIG_POS_PORTRAIT_X,
                                DEFAULT_SIG_POS_PORTRAIT_X_MM.toString()
                            ) ?: DEFAULT_SIG_POS_PORTRAIT_X_MM.toString()),
                            "nlSigPosPortraitY" to (prefs.getString(
                                KEY_REEPROFILE2_SIG_POS_PORTRAIT_Y,
                                DEFAULT_SIG_POS_PORTRAIT_Y_MM.toString()
                            ) ?: DEFAULT_SIG_POS_PORTRAIT_Y_MM.toString()),
                            "nlSigPosLandscapeX" to (prefs.getString(
                                KEY_REEPROFILE2_SIG_POS_LANDSCAPE_X,
                                DEFAULT_SIG_POS_LANDSCAPE_X_MM.toString()
                            ) ?: DEFAULT_SIG_POS_LANDSCAPE_X_MM.toString()),
                            "nlSigPosLandscapeY" to (prefs.getString(
                                KEY_REEPROFILE2_SIG_POS_LANDSCAPE_Y,
                                DEFAULT_SIG_POS_LANDSCAPE_Y_MM.toString()
                            ) ?: DEFAULT_SIG_POS_LANDSCAPE_Y_MM.toString()),
                            "nlScale" to prefs.getBoolean(KEY_REEPROFILE2_SCALE, true),
                            "nlFit" to prefs.getBoolean(KEY_REEPROFILE2_FIT, false),
                            "nlPosition" to prefs.getInt(KEY_REEPROFILE2_POSITION, DEFAULT_POSITION),
                            "nlCrop" to prefs.getBoolean(KEY_REEPROFILE2_CROP, false),
                            "nlPasswordOut" to prefs.getBoolean(KEY_REEPROFILE2_PASSWORD_OUT, false),
                            "nlSigDpiIndex" to prefs.getInt(KEY_REEPROFILE2_SIG_DPI_INDEX, 0),
                            "nlScanEffect" to prefs.getBoolean(KEY_REEPROFILE2_SCAN_EFFECT, false),
                            "nlGrayscale" to prefs.getBoolean(KEY_REEPROFILE2_GRAYSCALE, false),
                            "nlFileNameTemplate" to (prefs.getString(
                                KEY_REEPROFILE2_FILE_NAME_TEMPLATE,
                                getString(R.string.default_tmpl_reeprofile2)
                            ) ?: getString(R.string.default_tmpl_reeprofile2))
                        )

                        else -> emptyMap()
                    }
                }
            }

            numberOfRows.setText(data["nlRows"] as String)
            numberOfColumns.setText(data["nlColumns"] as String)
            margin.setText(data["nlMargin"] as String)
            val isPortrait = data["nlOrientation"] as Boolean
            suppressSigPosSwap = true
            landscape.isChecked = !isPortrait
            suppressSigPosSwap = false
            drawFrameSwitch.isChecked = data["nlDrawFrame"] as Boolean
            if (isPortrait) {
                signaturePosX.setText(data["nlSigPosPortraitX"] as String)
                signaturePosY.setText(data["nlSigPosPortraitY"] as String)
            } else {
                signaturePosX.setText(data["nlSigPosLandscapeX"] as String)
                signaturePosY.setText(data["nlSigPosLandscapeY"] as String)
            }
            setScaleMode(data["nlScale"] as Boolean, data["nlFit"] as Boolean)
            val profilePosition = validPosition(data["nlPosition"] as Int)
            cropAllPages = data["nlCrop"] as Boolean
            passwordOutCb.isChecked = data["nlPasswordOut"] as Boolean

            val fileNameEt: EditText = findViewById(R.id.file_name)
            fileNameEt.setText(data["nlFileNameTemplate"] as String)

            val prefs2 = getSharedPreferences(PREFS_NAME_FRAGMENT, MODE_PRIVATE)
            synchronized(prefsBackupLock) {
                prefs2.edit {
                    putString(KEY_FILE_NAME_TEMPLATE, fileNameEt.text?.toString() ?: "")
                    putString(KEY_SIG_POS_PORTRAIT_X, data["nlSigPosPortraitX"] as String)
                    putString(KEY_SIG_POS_PORTRAIT_Y, data["nlSigPosPortraitY"] as String)
                    putString(KEY_SIG_POS_LANDSCAPE_X, data["nlSigPosLandscapeX"] as String)
                    putString(KEY_SIG_POS_LANDSCAPE_Y, data["nlSigPosLandscapeY"] as String)
                    putInt(KEY_LAST_SIG_DPI_INDEX, data["nlSigDpiIndex"] as Int)
                    putBoolean(KEY_LAST_SCAN_EFFECT, data["nlScanEffect"] as Boolean)
                    putBoolean(KEY_LAST_GRAYSCALE, data["nlGrayscale"] as Boolean)
                    putString(KEY_LAST_LOADED_PROFILE, type)
                }
            }

            updateFileNameHintBasedOnRowsCols()
            validateInputs()

            isLoadingSettingsProfile = false

            var positionsGoWithRebuild = false
            if (regenRequestedDuringProfileLoad && nupTempFile().exists()) {
                regenRequestedDuringProfileLoad = false
                val rebuildNeeded = ProfileLoadRebuild.isNeeded(
                    built = lastBuiltNupConfig,
                    loaded = currentNupConfigFromInputs(),
                    positions = sourcePosition,
                    oldDefaultPosition = defaultPosition,
                    newDefaultPosition = profilePosition
                )
                if (rebuildNeeded) {
                    positionsGoWithRebuild = regenerateNupAsyncAndReloadThumbnails(profilePosition = profilePosition)
                }
            }
            if (!positionsGoWithRebuild) {
                defaultPosition = profilePosition
                sourcePosition.fill(defaultPosition)
                updateButtonsState()
            }
        }
    }

    private fun defaultFileNameTemplatesOfAllLanguages(): List<Pair<String, String>> =
        listOf(Locale.ENGLISH, Locale.forLanguageTag("hu")).map { locale ->
            val localized = createConfigurationContext(Configuration(resources.configuration).apply { setLocale(locale) })
            localized.getString(R.string.default_tmpl_reeprofile1) to localized.getString(R.string.default_tmpl_reeprofile2)
        }

    private fun localizeDefaultFileNameTemplate(value: String, defaults: List<Pair<String, String>>): String = when {
        defaults.any { it.first == value } -> getString(R.string.default_tmpl_reeprofile1)
        defaults.any { it.second == value } -> getString(R.string.default_tmpl_reeprofile2)
        else -> value
    }

    private fun localizeDefaultFileNameTemplatesInPrefs() {
        val defaults = defaultFileNameTemplatesOfAllLanguages()
        val prefs = getSharedPreferences(PREFS_NAME_FRAGMENT, MODE_PRIVATE)
        synchronized(prefsBackupLock) {
            val changes = listOf(KEY_FILE_NAME_TEMPLATE, KEY_REEPROFILE1_FILE_NAME_TEMPLATE, KEY_REEPROFILE2_FILE_NAME_TEMPLATE)
                .mapNotNull { key ->
                    val value = prefs.getString(key, null) ?: return@mapNotNull null
                    val localized = localizeDefaultFileNameTemplate(value, defaults)
                    if (localized != value) key to localized else null
                }
            if (changes.isNotEmpty()) {
                prefs.edit { for ((key, value) in changes) putString(key, value) }
            }
        }
    }

    private fun updateFileNameHintBasedOnRowsCols() {
        val rowsStr = numberOfRows.text?.toString()?.trim().orEmpty()
        val colsStr = numberOfColumns.text?.toString()?.trim().orEmpty()

        val rows = rowsStr.toIntOrNull()
        val cols = colsStr.toIntOrNull()

        val isRowsEmpty = rowsStr.isEmpty()
        val isColsEmpty = colsStr.isEmpty()

        val isReeProfile1 =
            (isRowsEmpty && isColsEmpty) ||
                    (isRowsEmpty && cols == 1) ||
                    (rows == 1 && isColsEmpty) ||
                    (rows == 1 && cols == 1)

        val fileNameEt: EditText = findViewById(R.id.file_name)
        val hintRes = if (isReeProfile1) R.string.default_tmpl_reeprofile1 else R.string.default_tmpl_reeprofile2
        fileNameEt.hint = getString(hintRes)
    }

    private fun validateInputs() {
        nbColumnsValid = validateIntField(numberOfColumns, 1, getString(R.string.enter_value_1_100))
        nbRowsValid    = validateIntField(numberOfRows,    1, getString(R.string.enter_value_1_100))
        marginValid    = validateIntField(margin,          0, getString(R.string.enter_value_0_100))
        val isPortrait = !landscape.isChecked
        val maxX = if (isPortrait) 210 else 297
        val maxY = if (isPortrait) 297 else 210
        sigXValid = checkUpToMax(signaturePosX.text.toString(), maxX)
        sigYValid = checkUpToMax(signaturePosY.text.toString(), maxY)

        validateIntFieldX(signaturePosX, isPortrait)
        validateIntFieldY(signaturePosY, isPortrait)

        updateFileNameHintBasedOnRowsCols()
        updateButtonsState()
    }

    private fun checkUpToMax(s: String, max: Int): Boolean {
        return try { val v = s.toInt(); v in 0..max } catch (_: Exception) { false }
    }

    private fun buildErrorTextFromDimen(et: EditText, msg: String, dimenRes: Int): CharSequence {
        val sizePx = et.resources.getDimensionPixelSize(dimenRes)
        return SpannableString(msg).apply {
            setSpan(AbsoluteSizeSpan(sizePx), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun validateIntField(
        et: EditText,
        min: Int,
        errorMsg: String,
        max: Int = 100
    ): Boolean {
        val s = et.text.toString()
        val ok = s.isNotEmpty() && (s.toIntOrNull()?.let { it in min..max } == true)

        et.error = if (ok) {
            null
        } else {
            val sizePx = et.resources.getDimensionPixelSize(R.dimen.dp_14)
            SpannableString(errorMsg).apply {
                setSpan(
                    AbsoluteSizeSpan(sizePx),
                    0, length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return ok
    }

    private fun validateIntField(
        et: EditText,
        errorMsg: String,
        isValid: Boolean
    ): Boolean {
        et.error = if (isValid) {
            null
        } else {
            val sizePx = et.resources.getDimensionPixelSize(R.dimen.dp_14)
            SpannableString(errorMsg).apply {
                setSpan(
                    AbsoluteSizeSpan(sizePx),
                    0, length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return isValid
    }

    private fun validateIntFieldX(
        et: EditText,
        isPortrait: Boolean
    ): Boolean {
        val emptyMsg = if (isPortrait) {
            et.context.getString(R.string.enter_value_0_209)
        } else {
            et.context.getString(R.string.enter_value_0_296)
        }

        val s = et.text?.toString()?.trim().orEmpty()
        val ok = s.isNotEmpty()

        et.error = if (ok) {
            null
        } else {
            buildErrorTextFromDimen(et, emptyMsg, R.dimen.dp_14)
        }

        return ok
    }

    private fun validateIntFieldY(
        et: EditText,
        isPortrait: Boolean
    ): Boolean {
        val emptyMsg = if (isPortrait) {
            et.context.getString(R.string.enter_value_0_296)
        } else {
            et.context.getString(R.string.enter_value_0_209)
        }

        val s = et.text?.toString()?.trim().orEmpty()
        val ok = s.isNotEmpty()

        et.error = if (ok) {
            null
        } else {
            buildErrorTextFromDimen(et, emptyMsg, R.dimen.dp_14)
        }

        return ok
    }

    private fun updateButtonsState() {
        val hasPages = viewModel.pageItems.isNotEmpty()
        val hasSelection = viewModel.pageItems.any { it.isSelected }

        listStartButton.isEnabled = hasPages
        listEndButton.isEnabled = hasPages

        emptyPage.isEnabled = hasPages

        addSignature.isEnabled = hasSelection && hasPages

        printPdf.isEnabled = hasPages
        savePdf.isEnabled = hasPages
        sendPdf.isEnabled  = hasPages

        val allSelect = findViewById<AppCompatCheckBox>(R.id.all_select)
        if (allSelect.getTag(R.id.all_select) != true) {
            allSelect.setOnClickListener {
                val shouldSelectAll = allSelect.isChecked
                if (shouldSelectAll) {
                    adapter.selectAll()
                } else {
                    adapter.clearAllSelections()
                }
                updateButtonsState()
            }
            allSelect.setTag(R.id.all_select, true)
        }
        allSelect.isEnabled = hasPages
        val allChecked = hasPages && viewModel.pageItems.all { it.isSelected }
        if (allSelect.isChecked != allChecked) {
            allSelect.isChecked = allChecked
        }

        val deleteBtn = findViewById<AppCompatImageButton>(R.id.delete_select)
        if (deleteBtn.getTag(R.id.delete_select) != true) {
            deleteBtn.setOnClickListener {
                deleteSelectedNupGroups()
            }
            deleteBtn.setTag(R.id.delete_select, true)
        }
        deleteBtn.isEnabled   = hasPages && hasSelection
        deleteBtn.isActivated = hasSelection

        val rotateLeftBtn = findViewById<AppCompatImageButton>(R.id.rotate_left_select)
        if (rotateLeftBtn.getTag(R.id.rotate_left_select) != true) {
            rotateLeftBtn.setOnClickListener {
                rotateLeftSelectedNupGroups()
            }
            rotateLeftBtn.setTag(R.id.rotate_left_select, true)
        }
        rotateLeftBtn.isEnabled   = hasPages && hasSelection
        rotateLeftBtn.isActivated = hasSelection

        val positionBtn = findViewById<AppCompatImageButton>(R.id.position_select)
        if (positionBtn.getTag(R.id.position_select) != true) {
            positionBtn.setOnClickListener {
                val selectedIdx = viewModel.pageItems
                    .mapIndexedNotNull { idx, it -> if (it.isSelected) idx else null }
                if (selectedIdx.isNotEmpty()) showPositionDialog(selectedIdx, fromMainWindow = true)
            }
            positionBtn.setTag(R.id.position_select, true)
        }
        positionBtn.isEnabled   = hasPages && hasSelection
        positionBtn.isActivated = hasSelection
        val positionTargets = viewModel.pageItems
            .mapIndexedNotNull { idx, it -> if (it.isSelected) idx else null }
            .ifEmpty { viewModel.pageItems.indices.toList() }
        positionBtn.setImageResource(positionIcon(positionsOfNupIndices(positionTargets)))

        fastScroller?.setEnabledWhen(hasPages)

        val shouldNotify = (lastGlobalAllSelected == null || lastGlobalAllSelected != allChecked)
        lastGlobalAllSelected = allChecked
        if (shouldNotify) {

            val rv = findViewById<RecyclerView>(R.id.rv_thumbnails)
            val lm = rv.layoutManager as? LinearLayoutManager
            val first = lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
            val last  = lm?.findLastVisibleItemPosition()  ?: RecyclerView.NO_POSITION
            if (first != RecyclerView.NO_POSITION && last >= first) {
                val count = last - first + 1
                if (rv.isComputingLayout) {
                    rv.post { adapter.notifyItemRangeChanged(first, count, "force") }
                } else {
                    adapter.notifyItemRangeChanged(first, count, "force")
                }
            }
        }
    }

    private fun resetThumbDocKey(reason: String) {
        (findViewById<RecyclerView>(R.id.rv_thumbnails).adapter as? ThumbnailAdapter)
            ?.resetForNewDocument("$reason|${System.nanoTime()}")
    }

    private fun showInProgressUpdateUI(
        message: String = getString(R.string.in_progress_ui),
    ) {
        val resolvedMessage = message.ifBlank { getString(R.string.in_progress_ui) }

        PdfProcessingForegroundService.start(this, resolvedMessage)
        resetProgress(resolvedMessage)

        runCatching {
            val panel = binding.overlayProgressDialogUpdateUiPanel
            panel.removeAllViews()
            val view = LayoutInflater.from(this).inflate(R.layout.progress_dialog_update_ui, panel, false)
            view.findViewById<MaterialTextView>(R.id.progress_bar_update_ui_simple_text)?.text = resolvedMessage
            panel.addView(view)
            binding.overlayProgressDialogUpdateUi.visibility = View.VISIBLE
        }.onFailure {
        }

        isUiBlockedByProgress = true
    }


    private fun hideInProgressIfShownUpdateUI() {
        PdfProcessingForegroundService.stop(this)
        binding.root.removeCallbacks(progressTicker)

        runCatching {
            binding.overlayProgressDialogUpdateUi.visibility = View.GONE
        }.onFailure {
        }

        isUiBlockedByProgress = false
    }

    private suspend fun backupSelectionState() {
        val selectedCsv: String
        val firstPos: Int
        val topOffset: Int

        withContext(Dispatchers.Main.immediate) {
            selectedCsv = viewModel.pageItems
                .mapIndexedNotNull { idx, item -> if (item.isSelected) idx else null }
                .joinToString(",")

            val lm = binding.rvThumbnails.layoutManager as? LinearLayoutManager
            firstPos = lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
            topOffset = if (firstPos != RecyclerView.NO_POSITION) {
                lm?.findViewByPosition(firstPos)?.top ?: 0
            } else 0
        }

        withContext(Dispatchers.IO) {
            longOperationStateBackup().saveSelection(selectedCsv, firstPos, topOffset)
        }
    }

    private fun longOperationStateBackup(): LongOperationStateBackup =
        LongOperationStateBackup(getSharedPreferences(PREFS_NAME_FRAGMENT, MODE_PRIVATE), prefsBackupLock)

    private fun backupUiStateBeforeLongOperation() {
        runCatching {
            val cfg = lastBuiltNupConfig ?: currentNupConfigFromInputs()

            val backup = longOperationStateBackup()
            val rotationCsv = sourceRotation.joinToString(",")

            backup.saveLayout(
                cfg,
                rotationCsv,
                sourcePosition.joinToString(","),
                defaultPosition,
                PdfCrops.encode(sourceCrop)
            )
        }.onFailure {
        }
    }

    private fun clearBackupStateKeysFromPrefs() {
        runCatching {
            longOperationStateBackup().clear()
        }.onFailure {
        }
    }

    private suspend fun restoreAfterCancel(restoreNupBackup: () -> Boolean) {
        val saved = withContext(Dispatchers.IO) {
            longOperationStateBackup().read(defaultPosition)
        }

        clearBackupStateKeysFromPrefs()

        val restoredNupBackup = restoreNupBackup()

        resetThumbDocKey("cancelLongRunningWorkAndCleanup")

        if (restoredNupBackup) {
            val prevSuppressNup = suppressNup
            runCatching {
                suppressNup = true

                numberOfRows.setText(saved.rows)
                numberOfColumns.setText(saved.columns)
                margin.setText(saved.margin)

                landscape.isChecked = !saved.isPortrait

                drawFrameSwitch.isChecked = saved.drawFrame
                setScaleMode(saved.scale, saved.fit)

                lastBuiltNupConfig = NupConfig(
                    saved.rows.toIntOrNull() ?: 1,
                    saved.columns.toIntOrNull() ?: 1,
                    saved.margin.toIntOrNull() ?: 0,
                    !saved.isPortrait,
                    saved.drawFrame,
                    saved.scale,
                    saved.fit
                )

                sourceRotation = if (saved.rotationCsv.isNotEmpty()) {
                    saved.rotationCsv.split(',').mapNotNull { it.toIntOrNull() }.toIntArray()
                } else {
                    IntArray(0)
                }

                sourcePosition = saved.positionCsv.split(',').mapNotNull { it.toIntOrNull() }.toIntArray()
                defaultPosition = validPosition(saved.defaultPosition)
                sourceCrop = PdfCrops.decode(saved.crop)

                suppressNup = prevSuppressNup
            }.onFailure {
                suppressNup = prevSuppressNup
            }
        }

        progressTracker.accepting = true
        reloadThumbnailsFromNup(
            targetIndex = saved.thumbFirst.takeIf { it != RecyclerView.NO_POSITION },
            targetOffsetPx = saved.thumbOffset,
            restorePrevious = true,
            indicesToSelect = saved.selected,
            releaseUiAtStart = false,
            onStarted = {
                unlockScreenOrientation()
                hideInProgressIfShownUpdateUI()
                isSigningCancelled = false
                finishHandover()
            }
        )
    }

    private fun showInProgress(message: String) {
        val resolvedMessage = message.ifBlank { getString(R.string.in_progress_my) }

        runCatching {
            val wasShowingForBackup = (binding.overlayProgressDialog.isVisible)
            if (!wasShowingForBackup) {
                backupUiStateBeforeLongOperation()
            }
        }.onFailure {
        }

        operationOverlay = true

        runCatching {
            val wasShowing = (binding.overlayProgressDialog.isVisible)

            if (!wasShowing) {
                progressTracker.accepting = true
                resetProgress(resolvedMessage)
            }
            progressMessage = resolvedMessage
            progressNotified = PdfProcessingForegroundService.PROGRESS_NONE

            val panel = binding.overlayProgressDialogPanel
            panel.removeAllViews()
            val view = LayoutInflater.from(this).inflate(R.layout.progress_dialog, panel, false)
            view.findViewById<MaterialTextView>(R.id.progress_bar_simple_text)?.text = resolvedMessage
            view.findViewById<MaterialTextView>(R.id.progress_bar_percent)?.let { percent ->
                percent.minWidth = ceil(percent.paint.measureText(getString(R.string.progress_percent, 100))).toInt()
            }

            val btnCancel = view.findViewById<MaterialButton>(R.id.progress_bar_cancel)
            btnCancel?.setOnClickListener {
                operationId?.let { PdfOperations.runner(this).cancel(it) }

                hideInProgressIfShown()

                showInProgressUpdateUI()
            }

            panel.addView(view)
            binding.overlayProgressDialog.visibility = View.VISIBLE
        }.onFailure {
        }

        isUiBlockedByProgress = true

        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun resetProgress(message: String) {
        progressTracker.reset()
        progressMessage = message
        progressNotified = PdfProcessingForegroundService.PROGRESS_NONE
        progressNotifiedAt = 0L
        binding.root.removeCallbacks(progressTicker)
        binding.root.postDelayed(progressTicker, PROGRESS_TICK_MS)
    }

    private fun progressStage(remaining: Long): PdfProgress = progressTracker.stage(remaining)

    private fun renderProgress(): Boolean {
        if (!::binding.isInitialized || isFinishing || isDestroyed) return false
        val linear = binding.overlayProgressDialog.isVisible
        if (!linear && !binding.overlayProgressDialogUpdateUi.isVisible) return false

        val now = SystemClock.uptimeMillis()
        if (now - progressTracker.startedAt < ProgressNotifyPacing.REVEAL_MS) return true

        val percent = if (operationOverlay) {
            (PdfOperations.runner(this).state.value as? PdfOperationState.Running)?.percent
                ?: PdfProcessingForegroundService.PROGRESS_INDETERMINATE
        } else {
            progressTracker.percent(now, PROGRESS_IDLE_MS)
                ?: PdfProcessingForegroundService.PROGRESS_INDETERMINATE
        }

        val indicator: BaseProgressIndicator<*>?
        val label: TextView?
        if (linear) {
            indicator = binding.overlayProgressDialogPanel.findViewById(R.id.progress_bar_indicator)
            label = binding.overlayProgressDialogPanel.findViewById(R.id.progress_bar_percent)
        } else {
            indicator = binding.overlayProgressDialogUpdateUiPanel.findViewById(R.id.progress_bar_indicator_update_ui)
            label = binding.overlayProgressDialogUpdateUiPanel.findViewById(R.id.progress_bar_update_ui_percent)
        }
        if (percent >= 0) {
            if (indicator?.isIndeterminate == true) indicator.isIndeterminate = false
            indicator?.setProgressCompat(percent, true)
            label?.text = getString(R.string.progress_percent, percent)
            label?.visibility = View.VISIBLE
        } else {
            if (indicator?.isIndeterminate == false) indicator.isIndeterminate = true
            label?.visibility = if (linear) View.GONE else View.INVISIBLE
        }

        if (!operationOverlay &&
            (handoverId == null || PdfOperations.runner(this).isHolding) &&
            ProgressNotifyPacing.waitMs(progressTracker.startedAt, progressNotified, progressNotifiedAt, percent, now) == 0L
        ) {
            progressNotified = percent
            progressNotifiedAt = now
            PdfProcessingForegroundService.start(this, progressMessage, percent)
        }
        return true
    }

    private fun hideInProgressIfShown() {
        PdfProcessingForegroundService.stop(this)
        binding.root.removeCallbacks(progressTicker)

        runCatching {
            binding.overlayProgressDialog.visibility = View.GONE
            binding.overlayProgressDialogUpdateUi.visibility = View.GONE
        }.onFailure {
        }

        isUiBlockedByProgress = false

        window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun readIntOr(et: EditText, min: Int, def: Int): Int {
        val v = et.text?.toString()?.trim()?.toIntOrNull()
        return (v ?: def).coerceIn(min, MAX_INPUT)
    }

    private fun currentNupConfigFromInputs(isLand: Boolean = landscape.isChecked): NupConfig {
        val rows     = readIntOr(numberOfRows,    min = 1, def = 1)
        val cols     = readIntOr(numberOfColumns, min = 1, def = 1)
        val marginMm = min(readIntOr(margin, min = 0, def = 0), maxMarginMm(rows, cols, isLand))
        val drawFrame = drawFrameSwitch.isChecked
        val scaleInd = scaleMode().scale
        val fitInd = scaleMode().fit && isFitMeasurable
        return NupConfig(rows, cols, marginMm, isLand, drawFrame, scaleInd, fitInd)
    }

    private fun scaleMode(): ScaleMode = ScaleMode.at(scaleSwitch.position)

    private fun setScaleMode(scale: Boolean, fit: Boolean) {
        scaleSwitch.position = ScaleMode.of(scale, fit && isFitMeasurable).ordinal
    }

    private fun nupConfigChanged(): Boolean {
        val cur = currentNupConfigFromInputs()
        val last = lastBuiltNupConfig
        return last == null || last != cur
    }

    private fun maybeRegenerateNupOnSettingChange(isLandscape: Boolean = landscape.isChecked) {
        if (suppressNup) {
            return
        }

        if (nupTempFile().exists()) {
            if (isLoadingSettingsProfile) {
                regenRequestedDuringProfileLoad = true
                return
            }
            regenerateNupAsyncAndReloadThumbnails(isLandscape)
        }
    }

    private fun setupRegenerateOnBlur(et: EditText) {

        et.setOnFocusChangeListener { _, hasFocus ->if (hasFocus) {
            et.setTag(et.id, et.text?.toString())
        } else {
            if (suppressNup || !nupTempFile().exists()) {
                return@setOnFocusChangeListener
            }

            val valueOnBlur = et.text?.toString()

            val valueOnFocus = et.getTag(et.id) as? String

            val normalizedValueOnFocus = when (et.id) {
                R.id.number_of_rows, R.id.number_of_columns -> if (valueOnFocus.isNullOrEmpty()) "1" else valueOnFocus
                R.id.margin -> if (valueOnFocus.isNullOrEmpty()) "0" else valueOnFocus
                else -> valueOnFocus
            }

            val normalizedValueOnBlur = when (et.id) {
                R.id.number_of_rows, R.id.number_of_columns -> if (valueOnBlur.isNullOrEmpty()) "1" else valueOnBlur
                R.id.margin -> if (valueOnBlur.isNullOrEmpty()) "0" else valueOnBlur
                else -> valueOnBlur
            }

            if (normalizedValueOnFocus != normalizedValueOnBlur) {
                if (nupConfigChanged()) {
                    regenerateNupAsyncAndReloadThumbnails()
                }
            }
        }
        }
    }

    private fun launchSelectPdf() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/pdf",
                    "image/jpeg",
                    "image/jpg",
                    "image/png"
                )
            )
        }
        pdfPickerLauncher.launch(i)
    }

    private fun handlePdfUri(uri: Uri) {
        importPdfUris(listOf(uri), detectImages = true)
    }

    private fun importPdfUris(uris: List<Uri>, detectImages: Boolean) {
        val job = lifecycleScope.launch(Dispatchers.IO) {
            val sources = ArrayList<PdfImportOperation.Source>()
            try {
                for ((index, uri) in uris.withIndex()) {
                    prepareImport(uri, index, detectImages)?.let { sources += it }
                }
            } catch (e: CancellationException) {
                for (source in sources) runCatching { source.file.delete() }
                throw e
            }
            if (sources.isEmpty()) return@launch

            try {
                withContext(Dispatchers.Main) { startImportWhenFree(sources) }
            } catch (e: CancellationException) {
                for (source in sources) runCatching { source.file.delete() }
                throw e
            }
        }
        activeJobs.add(job)
        job.invokeOnCompletion { activeJobs.remove(job) }
    }

    private suspend fun startImportWhenFree(sources: List<PdfImportOperation.Source>) {
        val runner = PdfOperations.runner(this)
        while (true) {
            runner.state.first { it is PdfOperationState.Idle }
            val started = withStarted {
                !isUiBlockedByProgress && runner.state.value is PdfOperationState.Idle && startImport(sources)
            }
            if (started) return
            delay(PENDING_INTENT_RETRY_MS.milliseconds)
        }
    }

    private suspend fun prepareImport(uri: Uri, index: Int, detectImages: Boolean): PdfImportOperation.Source? {
        val fileName = extractFileName(uri)
        val tempSrc = File(cacheDir, "${timeStamp}_${index}_$fileName")
        try {
            val isImage = detectImages && run {
                val mtLc = try { contentResolver.getType(uri)?.lowercase(Locale.ROOT) } catch (_: Exception) { null }
                isSupportedImageMime(mtLc) || isSupportedImageFileName(fileName.lowercase(Locale.ROOT))
            }

            contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(tempSrc).use { outputStream ->
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } > 0) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                }
            } ?: throw IOException(getString(R.string.input_stream_is_null))

            if (isImage) return PdfImportOperation.Source(tempSrc, null, null, isImage = true)

            var passwordBytes: ByteArray? = null
            var attempt = 0
            var openFailure: Exception? = null

            while (true) {
                val probe = PdfImportOperation.probe(tempSrc, passwordBytes)
                if (probe.noPages) openFailure = IOException(getString(R.string.no_source_pages))
                probe.failure?.let { openFailure = it }

                if (probe.canOpen) break

                val entered: String? = withContext(Dispatchers.Main) {
                    promptForPdfPassword(fileName, showError = (attempt > 0))
                }

                if (entered == null) {
                    runCatching { tempSrc.delete() }
                    return null
                }

                passwordBytes = entered.toByteArray(Charsets.UTF_8)
                attempt++
            }

            return PdfImportOperation.Source(tempSrc, passwordBytes, openFailure)
        } catch (e: CancellationException) {
            runCatching { tempSrc.delete() }
            throw e
        } catch (e: Exception) {
            runCatching { tempSrc.delete() }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, getString(R.string.error_loading_pdf_with_msg, e.message), Toast.LENGTH_LONG).show()
            }
            return null
        }
    }

    private fun startImport(sources: List<PdfImportOperation.Source>): Boolean {
        val selected = selectedNupIndices()
        val oldPer = run {
            val last = lastBuiltNupConfig ?: currentNupConfigFromInputs()
            (last.rows.coerceAtLeast(1)) * (last.cols.coerceAtLeast(1))
        }

        val operation = PdfImportOperation(
            files = operationFiles(),
            saveSelection = selectionSaver(selected),
            input = PdfImportOperation.Input(
                sources = sources,
                oldPerGroup = oldPer,
                config = currentNupConfigFromInputs(),
                crops = sourceCrop,
                rotation = sourceRotation.copyOf(),
                position = sourcePosition.copyOf(),
                defaultPosition = defaultPosition,
                batchSize = PageBatch.PAGES,
                selected = selected
            ),
            progress = operationProgress(),
            imageToPdf = ::createTempPdfFromImageFile,
            warn = { warning, error -> Log.w("PdfMuPdfFitBounds", warning, error) }
        )

        return startOperation(
            PdfOperationKind.IMPORT,
            getString(R.string.in_progress_opening),
            onCancelled = operation::restoreIfBackedUp,
            work = operation::execute
        )
    }

    @WorkerThread
    @Throws(IOException::class)
    private fun createTempPdfFromImageFile(imageFile: File): File {
        val out = File(cacheDir, "${timeStamp}_from_image_${System.currentTimeMillis()}.pdf")

        val bounds0 = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imageFile.absolutePath, bounds0)
        val w0 = bounds0.outWidth
        val h0 = bounds0.outHeight

        if (w0 <= 0 || h0 <= 0) throw IOException(getString(R.string.image_decode_failed))

        if (w0 > MAX_IMG_W || h0 > MAX_IMG_H) {
            throw IOException(getString(R.string.large_image))
        }

        return try {
            if (imageFile.isLikelyPng()) {
                createPdfFromPngTiled(imageFile, out, w0, h0)
            } else {
                val imageData = ImageDataFactory.create(imageFile.absolutePath)
                val imgW = imageData.width.coerceAtLeast(1f)
                val imgH = imageData.height.coerceAtLeast(1f)
                val pageRect = Rectangle(imgW, imgH)

                PdfDocument(PdfWriter(out.absolutePath, writerProps())).use { destDoc ->
                    destDoc.setFlushUnusedObjects(true)
                    val pg = destDoc.addNewPage(PageSize(pageRect))
                    PdfCanvas(pg).addImageFittedIntoRectangle(imageData, pageRect, false)
                    pg.flush()
                }
                out
            }
        } catch (e: OutOfMemoryError) {
            throw e
        } catch (e: Throwable) {
            throw e
        }
    }

    private fun File.isLikelyPng(): Boolean {
        return try {
            FileInputStream(this).use { fis ->
                val header = ByteArray(8)
                fis.read(header) == 8 &&
                        header[0] == 0x89.toByte() &&
                        header[1] == 0x50.toByte() &&
                        header[2] == 0x4E.toByte() &&
                        header[3] == 0x47.toByte() &&
                        header[4] == 0x0D.toByte() &&
                        header[5] == 0x0A.toByte() &&
                        header[6] == 0x1A.toByte() &&
                        header[7] == 0x0A.toByte()
            }
        } catch (_: Throwable) {
            name.lowercase().endsWith(".png")
        }
    }

    private object BitmapRegionDecoderCompat {

        @RequiresApi(VERSION_CODES.S)
        private object Api31Impl {
            @JvmStatic
            fun newInstance(path: String): BitmapRegionDecoder {
                return BitmapRegionDecoder.newInstance(path)
            }
        }

        private object LegacyImpl {
            @Suppress("DEPRECATION")
            @JvmStatic
            fun newInstance(path: String): BitmapRegionDecoder {
                return BitmapRegionDecoder.newInstance(path, false)
            }
        }

        fun newInstance(file: File): BitmapRegionDecoder {
            val path = file.absolutePath
            return if (Build.VERSION.SDK_INT >= VERSION_CODES.S) {
                Api31Impl.newInstance(path)
            } else {
                LegacyImpl.newInstance(path)
            }
        }
    }

    private fun newRegionDecoderCompat(pngFile: File): BitmapRegionDecoder {
        return BitmapRegionDecoderCompat.newInstance(pngFile)
    }

    private fun pickTileHeight(width: Int, bytesPerPixel: Int = 4): Int {
        val budgetBytes = 8 * 1024 * 1024
        val perRow = (width.toLong() * bytesPerPixel).coerceAtLeast(1L)
        return (budgetBytes / perRow).toInt().coerceIn(256, 2048)
    }

    @WorkerThread
    @Throws(IOException::class)
    private fun createPdfFromPngTiled(
        pngFile: File,
        outFile: File,
        width: Int,
        height: Int
    ): File {
        val pageRect = Rectangle(width.toFloat(), height.toFloat())
        val tileHeight = pickTileHeight(width)

        val flattenBgColor = runCatching {
            ContextCompat.getColor(this@MainActivity, R.color.white)
        }.getOrElse { Color.WHITE }

        PdfDocument(PdfWriter(outFile.absolutePath, writerProps())).use { pdf ->
            pdf.setFlushUnusedObjects(true)
            val page = pdf.addNewPage(PageSize(pageRect))
            val canvas = PdfCanvas(page)

            val decoder = try {
                newRegionDecoderCompat(pngFile)
            } catch (e: Throwable) {
                throw IOException(getString(R.string.bitmap_region_decoder_init_failed), e)
            }

            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val bos = ByteArrayOutputStream(1 shl 20)
            try {
                var y = 0
                while (y < height) {
                    val h = min(tileHeight, height - y)
                    val srcRect = Rect(0, y, width, y + h)

                    val bmp: Bitmap = decoder.decodeRegion(srcRect, opts)
                        ?: throw IOException(getString(R.string.decode_region_returned_null_at, y))

                    var toRecycleFlattened: Bitmap? = null
                    try {
                        val toCompress: Bitmap = if (bmp.hasAlpha()) {
                            createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888).apply {
                                setHasAlpha(false)
                                Canvas(this).apply {
                                    drawColor(flattenBgColor)
                                    drawBitmap(bmp, 0f, 0f, null)
                                }
                            }.also { toRecycleFlattened = it }
                        } else {
                            bmp
                        }

                        bos.reset()
                        if (!toCompress.compress(Bitmap.CompressFormat.JPEG, 100, bos)) {
                            throw IOException(getString(R.string.jpeg_compress_failed_at, y))
                        }

                        val imgData = ImageDataFactory.create(bos.toByteArray())
                        val destY = (height - (y + h)).toFloat()
                        val destRect = Rectangle(0f, destY, width.toFloat(), h.toFloat())
                        canvas.addImageFittedIntoRectangle(imgData, destRect, false)
                    } finally {
                        toRecycleFlattened?.recycle()
                        bmp.recycle()
                    }

                    y += h
                }

                canvas.release()
                page.flush()
            } finally {
                try { bos.close() } catch (_: Throwable) {}
                decoder.recycle()
            }
        }

        return outFile
    }

    private fun applySoftInputModeForPasswordOverlay() {
        val secureFlag = WindowManager.LayoutParams.FLAG_SECURE
        if (!passwordOverlayOwnsSecureFlag && window.attributes.flags and secureFlag == 0) {
            window.addFlags(secureFlag)
            passwordOverlayOwnsSecureFlag = true
        }

        if (savedSoftInputModeForOverlay == null) {
            savedSoftInputModeForOverlay = window.attributes.softInputMode
        }
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        )
    }

    private fun restoreSoftInputModeAfterPasswordOverlay() {
        savedSoftInputModeForOverlay?.let { oldMode ->
            savedSoftInputModeForOverlay = null
            window.setSoftInputMode(oldMode)
        }

        if (passwordOverlayOwnsSecureFlag) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            passwordOverlayOwnsSecureFlag = false
        }
    }


    private fun setupPasswordInputForDialog(
        dialogView: View,
        showError: Boolean,
        errorHintResId: Int? = null
    ): PasswordToggleEditText {

        val et = dialogView.findViewById<PasswordToggleEditText>(R.id.pwd_edittext)

        runCatching { et.setInitialStateMasked() }

        et.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_DONE || isEnter) {

                dialogView.findViewById<Button>(R.id.pwd_ok)?.performClick()
                true
            } else {
                false
            }
        }

        val originalHintColor = ContextCompat.getColor(this, R.color.password_hint_text_color)
        runCatching { et.setHintTextColor(originalHintColor) }

        if (errorHintResId != null || showError) {
            et.hint = resources.getString(errorHintResId ?: R.string.wrong_password)
            runCatching {
                et.setHintTextColor(ContextCompat.getColor(this, R.color.password_error_text_color))
            }
            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (!s.isNullOrEmpty()) {
                        et.hint = resources.getString(R.string.password_hint)
                        runCatching { et.setHintTextColor(originalHintColor) }
                        et.removeTextChangedListener(this)
                    }
                }
            }
            et.addTextChangedListener(watcher)
        } else {
            et.hint = resources.getString(R.string.password_hint)
            runCatching { et.setHintTextColor(originalHintColor) }
        }

        return et
    }

    private suspend fun promptForPdfPassword(fileName: String, showError: Boolean = false): String? =
        suspendCancellableCoroutine { cont ->

            applySoftInputModeForPasswordOverlay()

            val overlay = binding.overlayDialogPassword
            val panel = binding.overlayDialogPasswordPanel

            panel.removeAllViews()
            val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_pdf_password, panel, false)
            panel.addView(dialogView)

            overlay.visibility = View.VISIBLE

            overlay.setOnClickListener {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                val token = currentFocus?.windowToken ?: runCatching { overlay.windowToken }.getOrNull()
                runCatching { imm?.hideSoftInputFromWindow(token, 0) }

                overlay.visibility = View.GONE
                overlay.setOnClickListener(null)
                restoreSoftInputModeAfterPasswordOverlay()
                if (!cont.isCompleted) cont.resume(null)
            }
            panel.setOnClickListener {
            }

            (dialogView as? ViewGroup)?.let { vg ->
                (vg.getChildAt(0) as? TextView)?.setText(R.string.enter_pdf_password)
            }
            dialogView.findViewById<TextView>(R.id.pwd_file_name)?.apply {
                text = fileName
                visibility = View.VISIBLE
            }

            val et = setupPasswordInputForDialog(
                dialogView = dialogView,
                showError = showError,
                errorHintResId = null
            )

            val btnOk     = dialogView.findViewById<Button>(R.id.pwd_ok)
            val btnCancel = dialogView.findViewById<Button>(R.id.pwd_cancel)

            btnOk.setOnClickListener {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                val token = currentFocus?.windowToken ?: runCatching { overlay.windowToken }.getOrNull()
                runCatching { imm?.hideSoftInputFromWindow(token, 0) }

                val pwd = et.text?.toString()?.trim().orEmpty()

                overlay.visibility = View.GONE
                overlay.setOnClickListener(null)

                restoreSoftInputModeAfterPasswordOverlay()

                if (!cont.isCompleted) cont.resume(pwd)
            }

            btnCancel.setOnClickListener {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                val token = currentFocus?.windowToken ?: runCatching { overlay.windowToken }.getOrNull()
                runCatching { imm?.hideSoftInputFromWindow(token, 0) }

                overlay.visibility = View.GONE
                overlay.setOnClickListener(null)

                restoreSoftInputModeAfterPasswordOverlay()

                if (!cont.isCompleted) cont.resume(null)
            }

            cont.invokeOnCancellation {
                runCatching {
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                    val token = currentFocus?.windowToken ?: runCatching { overlay.windowToken }.getOrNull()
                    runCatching { imm?.hideSoftInputFromWindow(token, 0) }

                    overlay.visibility = View.GONE
                    overlay.setOnClickListener(null)

                    restoreSoftInputModeAfterPasswordOverlay()
                }.onFailure {
                }
            }

            runCatching {
                et.requestFocus()
            }.onFailure {
            }

            et.post {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                val token = currentFocus?.windowToken ?: runCatching { overlay.windowToken }.getOrNull()
                runCatching { imm?.hideSoftInputFromWindow(token, 0) }
            }
        }


    private fun extractFileName(uri: Uri): String {
        var result: String? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) result = cursor.getString(idx)
                }
            }
        } catch (_: Exception) { }
        return result ?: uri.lastPathSegment ?: getString(R.string.unknown_pdf)
    }

    private fun writerProps(): WriterProperties = PdfIo.writerProperties()

    private fun rdr(path: String, password: ByteArray? = null): PdfReader = PdfIo.reader(path, password)

    private fun addEmptyPageToSource() {
        if (!PdfSourceParts.exists(sourceDir())) {
            Toast.makeText(this, getString(R.string.select_a_page), Toast.LENGTH_SHORT).show()
            return
        }

        startLayoutChange(
            PdfOperationKind.EMPTY_PAGE,
            getString(R.string.in_progress_adding_empty_page),
            PdfLayoutChangeOperation.Change.AddEmptyPage
        )
    }

    private fun regenerateNupAsyncAndReloadThumbnails(
        isLandscape: Boolean = landscape.isChecked,
        profilePosition: Int? = null
    ): Boolean {
        if (suppressNup) return false

        if (isNupRebuilding) return false
        isNupRebuilding = true

        val oldPer = run {
            val last = lastBuiltNupConfig ?: currentNupConfigFromInputs()
            (last.rows.coerceAtLeast(1)) * (last.cols.coerceAtLeast(1))
        }

        return startLayoutChange(
            PdfOperationKind.LAYOUT,
            getString(R.string.in_progress_page_layout),
            PdfLayoutChangeOperation.Change.Rebuild(oldPer, resetPositions = profilePosition != null),
            newDefaultPosition = profilePosition ?: defaultPosition,
            config = currentNupConfigFromInputs(isLandscape)
        )
    }

    private fun clearThumbnails() {
        val oldCount = viewModel.pageItems.size
        viewModel.pageItems.forEach { it.thumbnail?.recycle() }
        if (oldCount > 0) {
            viewModel.pageItems.clear()
            adapter.notifyItemRangeRemoved(0, oldCount)
        } else {
            viewModel.pageItems.clear()
        }
    }

    private fun reloadThumbnailsFromNup(
        targetIndex: Int? = null,
        targetOffsetPx: Int = 0,
        restorePrevious: Boolean = true,
        indicesToSelect: Set<Int>? = null,
        releaseUiAtStart: Boolean = false,
        onStarted: (() -> Unit)? = null,
        pageSizes: PageSizes? = null
    ) {
        val myGen = ++reloadThumbnailsGeneration

        if (releaseUiAtStart) {
            hideInProgressIfShown()
        }

        val shouldDeferUnlock = (orientationLockPrevRequested != null)
        if (shouldDeferUnlock) {
            deferOrientationUnlockUntilThumbnailsReady = true
            deferredOrientationUnlockRequested = false
        }

        val lm = binding.rvThumbnails.layoutManager as LinearLayoutManager
        val firstVisiblePosition = lm.findFirstVisibleItemPosition()
        val offset = lm.findViewByPosition(firstVisiblePosition)?.top ?: 0

        clearThumbnails()

        var releasedUiOnce = false
        var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null
        var timeoutRunnable: Runnable? = null
        var watchdogRunnable: Runnable? = null
        var waitRange: IntRange? = null
        val pendingKeys = LinkedHashSet<String>()

        fun cleanupWaitersIfOwner() {
            try {
                preDrawListener?.let { l ->
                    if (binding.rvThumbnails.viewTreeObserver.isAlive) {
                        binding.rvThumbnails.viewTreeObserver.removeOnPreDrawListener(l)
                    }
                }
            } catch (_: Exception) {
            }
            preDrawListener = null

            timeoutRunnable?.let { r ->
                binding.rvThumbnails.removeCallbacks(r)
            }
            timeoutRunnable = null

            watchdogRunnable?.let { r ->
                binding.rvThumbnails.removeCallbacks(r)
            }
            watchdogRunnable = null

            if (myGen == reloadThumbnailsGeneration) {
                runCatching { adapter.setOnThumbnailFinishedListener(null) }
            }

            pendingKeys.clear()
        }

        fun releaseDeferredUnlockAndInvokeOnStarted() {
            if (releasedUiOnce) return

            if (myGen != reloadThumbnailsGeneration) {
                releasedUiOnce = true
                cleanupWaitersIfOwner()
                return
            }

            releasedUiOnce = true
            cleanupWaitersIfOwner()

            if (shouldDeferUnlock) {
                deferOrientationUnlockUntilThumbnailsReady = false
            }

            onStarted?.invoke()

            runCatching {
                binding.overlayProgressDialog.findViewById<MaterialButton>(R.id.progress_bar_cancel)?.isEnabled = false

                clearBackupStateKeysFromPrefs()
            }

            binding.overlayProgressDialog.post {
                if (binding.overlayProgressDialog.isVisible) return@post
                if (isSigningCancelled) return@post

                runCatching {
                    val srcCopy = File(cacheDir, SOURCE_TEMP_COPY_NAME)
                    if (srcCopy.exists()) srcCopy.deleteRecursively()
                }
                runCatching {
                    val nupCopy = File(cacheDir, NUP_TEMP_COPY_NAME)
                    if (nupCopy.exists()) nupCopy.delete()
                }
            }

            if (shouldDeferUnlock && deferredOrientationUnlockRequested) {
                deferredOrientationUnlockRequested = false
                unlockScreenOrientation()
            }

            binding.rvThumbnails.post {
                if (isFinishing || isDestroyed) return@post
                maybeRunDeferredUiModeRecreate()
            }
        }

        val job = lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                try {
                    val nup = nupTempFile()
                    if (!nup.exists() || !PdfSourceParts.exists(sourceDir())) return@withContext emptyList()

                    val per = perGroup()
                    val source = readableSource()
                    val totalSrcPages = source.total
                    if (totalSrcPages <= 0) return@withContext emptyList()

                    val nupPages = (totalSrcPages + per - 1) / per
                    val list = ArrayList<PageItem>(nupPages)

                    val cfg = mappingConfig()
                    val (fallbackW, fallbackH) = PdfPageSizes.fallback(cfg)

                    val skipReadingAnyPdfSize = !PdfPageSizes.isNeeded(cfg)

                    if (skipReadingAnyPdfSize) {
                        for (g in 0 until nupPages) {
                            val pageNumberText = if (per == 1) {
                                "${g + 1}/$totalSrcPages"
                            } else {
                                val totalGroups = (totalSrcPages + per - 1) / per
                                "${g + 1}/$totalGroups"
                            }

                            list.add(
                                PageItem(
                                    thumbnail = null,
                                    filePath = nup.absolutePath,
                                    pageIndex = g,
                                    widthPts = fallbackW,
                                    heightPts = fallbackH,
                                    pageNumberText = pageNumberText
                                )
                            )
                        }
                    } else {
                        val sizes = pageSizes?.takeIf { it.widths.size == totalSrcPages }
                            ?: PdfPageSizes.read(
                                source,
                                totalSrcPages,
                                PageBatch.PAGES,
                                fallbackW,
                                fallbackH,
                                progressStage(totalSrcPages.toLong())
                            )
                        val widths = sizes.widths
                        val heights = sizes.heights

                        for (g in 0 until nupPages) {
                            val pageNumberText = if (per == 1) {
                                "${g + 1}/$totalSrcPages"
                            } else {
                                val totalGroups = (totalSrcPages + per - 1) / per
                                "${g + 1}/$totalGroups"
                            }

                            val firstSrcPageZeroBased = g * per

                            val wPtsForLabel =
                                if (firstSrcPageZeroBased in 0 until totalSrcPages) widths[firstSrcPageZeroBased]
                                else fallbackW

                            val hPtsForLabel =
                                if (firstSrcPageZeroBased in 0 until totalSrcPages) heights[firstSrcPageZeroBased]
                                else fallbackH

                            list.add(
                                PageItem(
                                    thumbnail = null,
                                    filePath = nup.absolutePath,
                                    pageIndex = g,
                                    widthPts = wPtsForLabel,
                                    heightPts = hPtsForLabel,
                                    pageNumberText = pageNumberText
                                )
                            )
                        }
                    }

                    list
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
            }

            if (items.isEmpty()) {
                updateButtonsState()

                releaseDeferredUnlockAndInvokeOnStarted()
                return@launch
            }

            viewModel.pageItems.addAll(items)
            adapter.notifyItemRangeInserted(0, items.size)

            binding.rvThumbnails.post {
                val lm2 = binding.rvThumbnails.layoutManager as? LinearLayoutManager

                when {
                    targetIndex != null -> lm2?.scrollToPositionWithOffset(targetIndex, targetOffsetPx)
                    restorePrevious && firstVisiblePosition != RecyclerView.NO_POSITION ->
                        lm2?.scrollToPositionWithOffset(firstVisiblePosition, offset)
                }

                indicesToSelect?.let { set ->
                    val max = viewModel.pageItems.size
                    set.forEach { i ->
                        if (i in 0 until max) {
                            viewModel.pageItems[i].isSelected = true
                            adapter.notifyItemChanged(i, "force")
                        }
                    }
                    updateButtonsState()
                }

                timeoutRunnable = Runnable {
                    if (isFinishing || isDestroyed) return@Runnable

                    if (!releasedUiOnce && myGen == reloadThumbnailsGeneration) {
                        releaseDeferredUnlockAndInvokeOnStarted()
                    }
                }
                binding.rvThumbnails.postDelayed(timeoutRunnable!!, 10 * 60 * 1000L)

                val stallReleaseMs = 15_000L
                val watchdogIntervalMs = 500L
                var lastPendingCount = Int.MAX_VALUE
                var lastProgressAt = System.currentTimeMillis()
                watchdogRunnable = object : Runnable {
                    override fun run() {
                        if (isFinishing || isDestroyed || releasedUiOnce) return
                        if (myGen != reloadThumbnailsGeneration) {
                            releaseDeferredUnlockAndInvokeOnStarted()
                            return
                        }
                        val now = System.currentTimeMillis()
                        val range = waitRange
                        if (range == null) {
                            binding.rvThumbnails.invalidate()
                        } else {
                            val it = pendingKeys.iterator()
                            while (it.hasNext()) {
                                if (adapter.isThumbnailCachedByKey(it.next())) it.remove()
                            }
                            if (pendingKeys.isEmpty()) {
                                releaseDeferredUnlockAndInvokeOnStarted()
                                return
                            }
                            if (pendingKeys.size < lastPendingCount) {
                                lastPendingCount = pendingKeys.size
                                lastProgressAt = now
                            }
                            adapter.prefetchAroundVisible(this@MainActivity, range)
                        }
                        if (now - lastProgressAt >= stallReleaseMs) {
                            releaseDeferredUnlockAndInvokeOnStarted()
                            return
                        }
                        binding.rvThumbnails.postDelayed(this, watchdogIntervalMs)
                    }
                }
                binding.rvThumbnails.postDelayed(watchdogRunnable!!, watchdogIntervalMs)

                preDrawListener = object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        if (releasedUiOnce) return true
                        if (myGen != reloadThumbnailsGeneration) {
                            releaseDeferredUnlockAndInvokeOnStarted()
                            return true
                        }

                        val f = lm2?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
                        val l = lm2?.findLastVisibleItemPosition() ?: RecyclerView.NO_POSITION
                        if (f == RecyclerView.NO_POSITION || l == RecyclerView.NO_POSITION || l < f) {
                            return true
                        }

                        try {
                            if (binding.rvThumbnails.viewTreeObserver.isAlive) {
                                binding.rvThumbnails.viewTreeObserver.removeOnPreDrawListener(this)
                            }
                        } catch (_: Exception) {
                        }
                        preDrawListener = null

                        adapter.notifyItemRangeChanged(f, l - f + 1, "force")

                        val start = (f - 4).coerceAtLeast(0)
                        val end = (l + 4).coerceAtMost(adapter.itemCount - 1)
                        if (start > end) {
                            releaseDeferredUnlockAndInvokeOnStarted()
                            return true
                        }

                        val rangeToWait = start..end
                        waitRange = rangeToWait
                        adapter.prefetchAroundVisible(this@MainActivity, rangeToWait)

                        pendingKeys.clear()
                        for (i in rangeToWait) {
                            adapter.getCacheKeyForPosition(i)?.let { pendingKeys.add(it) }
                        }

                        run {
                            val it = pendingKeys.iterator()
                            while (it.hasNext()) {
                                val k = it.next()
                                if (adapter.isThumbnailCachedByKey(k)) it.remove()
                            }
                        }

                        if (pendingKeys.isEmpty()) {
                            releaseDeferredUnlockAndInvokeOnStarted()
                            return true
                        }

                        adapter.setOnThumbnailFinishedListener { key ->
                            if (releasedUiOnce) return@setOnThumbnailFinishedListener
                            if (myGen != reloadThumbnailsGeneration) return@setOnThumbnailFinishedListener

                            if (pendingKeys.remove(key)) {
                                if (pendingKeys.isEmpty()) {
                                    binding.rvThumbnails.post {
                                        releaseDeferredUnlockAndInvokeOnStarted()
                                    }
                                }
                            }
                        }

                        run {
                            val it = pendingKeys.iterator()
                            while (it.hasNext()) {
                                val k = it.next()
                                if (adapter.isThumbnailCachedByKey(k)) it.remove()
                            }
                        }
                        if (pendingKeys.isEmpty()) {
                            releaseDeferredUnlockAndInvokeOnStarted()
                        }

                        return true
                    }
                }

                try {
                    binding.rvThumbnails.viewTreeObserver.addOnPreDrawListener(preDrawListener)
                } catch (_: Exception) {
                    releaseDeferredUnlockAndInvokeOnStarted()
                }
            }

            updateButtonsState()
        }

        activeJobs.add(job)
        job.invokeOnCompletion { cause ->
            activeJobs.remove(job)

            if (cause != null) {
                binding.rvThumbnails.post {
                    releaseDeferredUnlockAndInvokeOnStarted()
                }
            }
        }
    }

    private fun mappingConfig(): NupConfig {
        return lastBuiltNupConfig ?: currentNupConfigFromInputs()
    }

    private fun perGroup(): Int {
        val cfg = mappingConfig()
        val r = max(1, min(100, cfg.rows))
        val c = max(1, min(100, cfg.cols))
        return r * c
    }

    private fun persistNupOrderToSourceTemp() {
        if (viewModel.pageItems.isEmpty()) return

        startLayoutChange(
            PdfOperationKind.REORDER,
            getString(R.string.in_progress_reordering),
            PdfLayoutChangeOperation.Change.Reorder(viewModel.pageItems.map { it.pageIndex }),
            savesSelection = false
        )
    }

    private fun rotateLeftNupPageGroupAndMirrorSource(nupIndex: Int) {
        updateRotationForNupIndices(listOf(nupIndex))
    }

    private fun rotateLeftSelectedNupGroups() {        val selectedIdx = viewModel.pageItems
        .mapIndexedNotNull { idx, it -> if (it.isSelected) idx else null }
        .sorted()

        if (selectedIdx.isEmpty()) return

        updateRotationForNupIndices(selectedIdx)
    }

    private fun updateRotationForNupIndices(nupIndices: List<Int>) {
        startLayoutChange(
            PdfOperationKind.ROTATION,
            getString(R.string.in_progress_rotation),
            PdfLayoutChangeOperation.Change.Rotate(nupIndices)
        )
    }

    private val positionIcons = intArrayOf(
        R.drawable.ic_position_00_24,
        R.drawable.ic_position_01_24,
        R.drawable.ic_position_02_24,
        R.drawable.ic_position_03_24,
        R.drawable.ic_position_04_24,
        R.drawable.ic_position_05_24,
        R.drawable.ic_position_06_24,
        R.drawable.ic_position_07_24,
        R.drawable.ic_position_08_24,
        R.drawable.ic_position_09_24
    )

    private fun positionsOfNupIndices(nupIndices: List<Int>): Set<Int> {
        if (nupIndices.isEmpty()) return emptySet()
        val per = perGroup()
        val positions = HashSet<Int>()
        for (nupIndex in nupIndices) {
            val start = nupIndex * per
            if (start >= sourcePosition.size) {
                positions.add(defaultPosition)
                continue
            }
            val end = min(sourcePosition.size, start + per)
            for (i in start until end) positions.add(validPosition(sourcePosition[i]))
        }
        return positions
    }

    private fun positionIcon(positions: Set<Int>): Int = when {
        positions.isEmpty() -> positionIcons[defaultPosition]
        positions.size > 1 -> R.drawable.ic_position_10_24
        else -> positionIcons[positions.first()]
    }

    private fun showPositionDialog(nupIndices: List<Int>, fromMainWindow: Boolean) {
        if (nupIndices.isEmpty()) return

        val overlay = binding.overlayDialogPositionOnPage
        val panel = binding.overlayDialogPositionOnPagePanel

        panel.removeAllViews()
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_position_on_page, panel, false)
        panel.addView(dialogView)

        overlay.visibility = View.VISIBLE

        fun close() {
            overlay.visibility = View.GONE
            overlay.setOnClickListener(null)
        }

        overlay.setOnClickListener { close() }
        panel.setOnClickListener {
        }

        val current = positionsOfNupIndices(nupIndices).singleOrNull()
        var selected = current
        val grid = dialogView.findViewById<AppCompatImageView>(R.id.ponp_grid)
        val btnOk = dialogView.findViewById<Button>(R.id.ponp_btn_ok)
        val btnCancel = dialogView.findViewById<Button>(R.id.ponp_btn_cancel)

        fun render() {
            grid.setImageResource(selected?.let { positionIcons[it] } ?: R.drawable.ic_position_00_24)
            btnOk.isEnabled = selected != null && selected != current
        }

        intArrayOf(
            R.id.ponp_cell_1, R.id.ponp_cell_2, R.id.ponp_cell_3,
            R.id.ponp_cell_4, R.id.ponp_cell_5, R.id.ponp_cell_6,
            R.id.ponp_cell_7, R.id.ponp_cell_8, R.id.ponp_cell_9
        ).forEachIndexed { index, id ->
            dialogView.findViewById<View>(id).setOnClickListener {
                selected = index + 1
                render()
            }
        }
        render()

        btnCancel.setOnClickListener { close() }

        btnOk.setOnClickListener {
            val target = selected ?: return@setOnClickListener
            close()
            updatePositionForNupIndices(nupIndices, target, if (fromMainWindow) target else defaultPosition)
        }
    }

    private fun updatePositionForNupIndices(nupIndices: List<Int>, position: Int, newDefaultPosition: Int) {
        startLayoutChange(
            PdfOperationKind.POSITION,
            getString(R.string.in_progress_position),
            PdfLayoutChangeOperation.Change.Position(nupIndices, position),
            newDefaultPosition
        )
    }

    private fun isNupGroupCropped(nupIndex: Int): Boolean {
        if (sourceCrop.isEmpty()) return false
        val per = perGroup()
        val start = nupIndex * per
        return (start until start + per).any { it in sourceCrop }
    }

    private fun showCropDialog(nupIndex: Int) {
        val firstSource = nupIndex * perGroup()

        val overlay = binding.overlayDialogPdfCrop
        val panel = binding.overlayDialogPdfCropPanel

        panel.removeAllViews()
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_pdf_crop, panel, false)
        panel.addView(dialogView)

        overlay.visibility = View.VISIBLE

        var closed = false
        fun close() {
            closed = true
            overlay.visibility = View.GONE
            overlay.setOnClickListener(null)
            panel.removeAllViews()
        }

        overlay.setOnClickListener { close() }
        panel.setOnClickListener {
        }

        val cropScroll = dialogView.findViewById<NestedScrollView>(R.id.crop_scroll)
        val cropView = dialogView.findViewById<PdfCropView>(R.id.crop_view)
        val allPages = dialogView.findViewById<SwitchMaterial>(R.id.crop_all_pages)
        val btnCancel = dialogView.findViewById<Button>(R.id.crop_btn_cancel)
        val btnFull = dialogView.findViewById<Button>(R.id.crop_btn_full)
        val btnOk = dialogView.findViewById<Button>(R.id.crop_btn_ok)

        allPages.isChecked = cropAllPages
        allPages.setOnCheckedChangeListener { _, checked -> cropAllPages = checked }

        val rotation = sourceRotation.getOrElse(firstSource) { 0 }
        val current = PdfCrops.toDisplay(sourceCrop[firstSource], rotation)
        cropView.setFrame(current[0], current[1], current[2], current[3])

        btnFull.isEnabled = false
        btnOk.isEnabled = false
        val previewSize = resources.displayMetrics.widthPixels
        lifecycleScope.launch {
            val preview = withContext(Dispatchers.IO) {
                runCatching { renderCropPreview(firstSource, rotation, previewSize) }.getOrNull()
            }
            if (closed) return@launch
            if (preview == null) {
                close()
                return@launch
            }
            cropView.setPage(preview)
            cropScroll.post {
                if (closed) return@post
                var availW = cropScroll.width
                if (availW <= 0) availW = dialogView.width
                if (availW <= 0) availW = panel.width
                if (availW <= 0) return@post
                cropView.layoutParams = cropView.layoutParams.apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = max(1, (availW.toFloat() * preview.height / preview.width).toInt())
                }
            }
            btnFull.isEnabled = true
            btnOk.isEnabled = true
        }

        btnCancel.setOnClickListener { close() }
        btnFull.setOnClickListener { cropView.resetFrame() }
        btnOk.setOnClickListener {
            val frame = cropView.frame()
            val crop = PdfCrops.fromDisplay(frame.left, frame.top, frame.right, frame.bottom, rotation)
            val applyToAll = allPages.isChecked
            close()
            updateCropForNupGroup(nupIndex, crop, applyToAll)
        }
    }

    @WorkerThread
    private fun renderCropPreview(sourceIndex: Int, rotation: Int, maxSide: Int): Bitmap? {
        val parts = PdfSourceParts.open(sourceDir())
        if (sourceIndex !in 0 until parts.total) return null
        val location = parts.locate(sourceIndex)

        val ownRotation = PdfDocument(rdr(location.file.absolutePath)).use { pdf ->
            pdf.getPage(location.page + 1).rotation
        }

        val rendered = ParcelFileDescriptor.open(location.file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (location.page >= renderer.pageCount) return null
                renderer.openPage(location.page).use { page ->
                    val scale = maxSide.coerceAtLeast(1).toFloat() / max(page.width, page.height).coerceAtLeast(1)
                    val bitmap = createBitmap(
                        (page.width * scale).toInt().coerceAtLeast(1),
                        (page.height * scale).toInt().coerceAtLeast(1)
                    )
                    Canvas(bitmap).drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }

        val turns = Math.floorMod(ownRotation / 90 + rotation, 4)
        if (turns == 0) return rendered
        val matrix = Matrix().apply { postRotate(-90f * turns) }
        val rotated = Bitmap.createBitmap(rendered, 0, 0, rendered.width, rendered.height, matrix, true)
        if (rotated !== rendered) rendered.recycle()
        return rotated
    }

    private fun updateCropForNupGroup(nupIndex: Int, crop: PdfCropRect?, applyToAll: Boolean) {
        startLayoutChange(
            PdfOperationKind.CROP,
            getString(R.string.in_progress_crop),
            PdfLayoutChangeOperation.Change.Crop(nupIndex, crop, applyToAll)
        )
    }

    private fun startLayoutChange(
        kind: PdfOperationKind,
        message: String,
        change: PdfLayoutChangeOperation.Change,
        newDefaultPosition: Int = defaultPosition,
        config: NupConfig = currentNupConfigFromInputs(),
        savesSelection: Boolean = true
    ): Boolean {
        if (change !is PdfLayoutChangeOperation.Change.Rebuild && !PdfSourceParts.exists(sourceDir())) return false

        val selectedIdxBefore = selectedNupIndices()
        val saveSelection = selectionSaver(selectedIdxBefore)

        val operation = PdfLayoutChangeOperation(
            files = operationFiles(),
            saveSelection = { if (savesSelection) saveSelection() },
            input = PdfLayoutChangeOperation.Input(
                change = change,
                perGroup = perGroup(),
                config = config,
                crops = sourceCrop,
                rotation = sourceRotation.copyOf(),
                position = sourcePosition.copyOf(),
                defaultPosition = newDefaultPosition,
                batchSize = PageBatch.PAGES,
                selected = selectedIdxBefore
            ),
            progress = operationProgress(),
            warn = { warning, error -> Log.w("PdfMuPdfFitBounds", warning, error) }
        )

        return startOperation(
            kind,
            message,
            onCancelled = operation::restoreIfBackedUp,
            work = operation::execute
        )
    }

    private fun selectedNupIndices(): List<Int> =
        viewModel.pageItems.mapIndexedNotNull { idx, it -> if (it.isSelected) idx else null }

    private fun selectionSaver(selected: List<Int>): () -> Unit {
        val lm = binding.rvThumbnails.layoutManager as? LinearLayoutManager
        val firstPos = lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        val topOffset = if (firstPos != RecyclerView.NO_POSITION) {
            lm?.findViewByPosition(firstPos)?.top ?: 0
        } else 0

        val stateBackup = longOperationStateBackup()
        val selectedCsv = selected.joinToString(",")
        return { stateBackup.saveSelection(selectedCsv, firstPos, topOffset) }
    }

    private fun operationFiles(): PdfLayoutChangeOperation.Files =
        PdfLayoutChangeOperation.Files(cacheDir, sourceDir(), nupTempFile(), workFileBackup())

    private fun operationProgress(): PdfLayoutChangeOperation.Progress =
        PdfLayoutChangeOperation.Progress(SystemClock::uptimeMillis, PROGRESS_TICK_MS, PROGRESS_IDLE_MS)

    private fun startOperation(
        kind: PdfOperationKind,
        message: String,
        onCancelled: suspend () -> Unit,
        work: suspend (report: (percent: Int?) -> Unit) -> PdfOperationResult?
    ): Boolean {
        showInProgress(message)
        lockScreenOrientation()
        if (::adapter.isInitialized) runCatching { adapter.pausePrefetchAndCancel() }

        val id =PdfOperations.runner(this).start(kind, message, onCancelled, work)
        if (id == null) {
            isNupRebuilding = false
            operationOverlay = false
            if (::adapter.isInitialized) runCatching { adapter.resumePrefetch() }
            hideInProgressIfShown()
            unlockScreenOrientation()
            return false
        }
        operationId = id
        return true
    }

    private fun observeOperations() {
        val runner = PdfOperations.runner(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                runner.state.collect { state -> onOperationState(runner, state) }
            }
        }
    }

    private fun onOperationState(runner: PdfOperationRunner, state: PdfOperationState) {
        when (val step = PdfOperationScreen.step(state, operationId, operationOverlay, discardOperation, handoverId)) {
            PdfOperationScreen.Step.Ignore -> Unit
            is PdfOperationScreen.Step.Adopt -> operationId = step.id
            is PdfOperationScreen.Step.Attach -> {
                operationId = step.id
                attachOperation(step.message, step.updateUi)
            }
            is PdfOperationScreen.Step.Ended -> endOperation(runner, step, state.request?.kind)
        }
    }

    private fun endOperation(runner: PdfOperationRunner, step: PdfOperationScreen.Step.Ended, kind: PdfOperationKind?) {
        val restore = operationRestore
        operationRestore = null
        operationId = null
        operationOverlay = false
        isNupRebuilding = false
        if (::adapter.isInitialized) runCatching { adapter.resumePrefetch() }
        if (kind == PdfOperationKind.SAVE_WRITE) endSaveWrite()

        when (step) {
            is PdfOperationScreen.Step.Finish -> {
                if (kind.isOutput) {
                    runner.acknowledge(step.id)
                    finishOutput(step.result, restore, kind)
                    return
                }
                val result = step.result as? PdfLayoutChangeOperation.Result
                if (result == null || result.cleared) runner.acknowledge(step.id) else handOver(runner, step.id)
                finishLayoutChange(result, restore, kind)
            }
            is PdfOperationScreen.Step.Fail -> {
                runner.acknowledge(step.id)
                failLayoutChange(step.error, restore, kind)
            }
            is PdfOperationScreen.Step.Restore -> {
                handOver(runner, step.id)
                restoreCancelledOperation(kind)
            }
            is PdfOperationScreen.Step.Discarded -> {
                runner.acknowledge(step.id)
                discardOperation = false
                clearCacheDir()
                hideInProgressIfShownUpdateUI()
                unlockScreenOrientation()
                updateButtonsState()
            }
        }
        if (kind == PdfOperationKind.SAVE_WRITE) maybeRunDeferredUiModeRecreate()
    }

    private fun handOver(runner: PdfOperationRunner, id: Long) {
        handoverId = id
        runner.handOver(id)
    }

    private fun finishHandover() {
        val id = handoverId ?: return
        handoverId = null
        PdfOperations.runner(this).acknowledge(id)
    }

    private fun attachOperation(message: String, updateUi: Boolean) {
        operationOverlay = true
        if (updateUi) {
            showInProgressUpdateUI()
        } else {
            showInProgress(message)
        }
        lockScreenOrientation()
        if (::adapter.isInitialized) runCatching { adapter.pausePrefetchAndCancel() }
    }

    private fun finishLayoutChange(result: PdfLayoutChangeOperation.Result?, restore: ThumbRestore?, kind: PdfOperationKind?) {
        if (result == null) {
            if (kind == PdfOperationKind.EMPTY_PAGE) {
                Toast.makeText(this, getString(R.string.select_a_page), Toast.LENGTH_SHORT).show()
            }
            hideInProgressIfShown()
            unlockScreenOrientation()
            restore?.let { reloadAfterOperation(it) }
            return
        }

        if (result.cleared) {
            clearCacheDir()
            sourceRotation = IntArray(0)
            sourcePosition = IntArray(0)
            sourceCrop = emptyMap()

            clearThumbnails()
            updateButtonsState()
            hideInProgressIfShown()
            unlockScreenOrientation()
            return
        }

        sourceCrop = result.crops
        sourceRotation = result.rotation
        sourcePosition = result.position
        defaultPosition = result.defaultPosition
        lastBuiltNupConfig = result.config
        progressTracker.continueFrom(result.progress)

        resetThumbDocKey("layoutChange:$kind")

        if (kind == PdfOperationKind.SIGN) {
            currentSigFile = null
            updateSignatureInputFilters()
        }

        if (kind == PdfOperationKind.DELETE_SELECTED) {
            for (i in viewModel.pageItems.indices.reversed()) {
                if (viewModel.pageItems[i].isSelected) {
                    val removed = viewModel.pageItems.removeAt(i)
                    removed.thumbnail = null
                    adapter.notifyItemRemoved(i)
                }
            }
        }

        val onReloadStarted: () -> Unit = {
            hideInProgressIfShown()
            unlockScreenOrientation()
            finishHandover()
        }

        reloadThumbnailsFromNup(
            targetIndex = restore?.targetIndex,
            targetOffsetPx = restore?.offsetPx ?: 0,
            restorePrevious = true,
            indicesToSelect = if (kind == PdfOperationKind.DELETE_SELECTED || kind == PdfOperationKind.SIGN) null else result.selected.toSet(),
            releaseUiAtStart = false,
            onStarted = onReloadStarted,
            pageSizes = result.pageSizes
        )

        updateButtonsState()
        unlockScreenOrientation()
    }

    private fun failLayoutChange(error: Throwable, restore: ThumbRestore?, kind: PdfOperationKind?) {
        val messageRes = when (kind) {
            PdfOperationKind.ROTATION -> R.string.failed_to_rotate_selected_groups_with_msg
            PdfOperationKind.POSITION -> R.string.failed_to_position_selected_groups_with_msg
            PdfOperationKind.LAYOUT -> R.string.failed_to_build_pages_with_msg
            PdfOperationKind.DELETE_GROUP -> R.string.failed_to_delete_page_group_with_msg
            PdfOperationKind.DELETE_SELECTED -> R.string.failed_to_delete_selected_groups_with_msg
            PdfOperationKind.EMPTY_PAGE -> R.string.failed_to_add_empty_page_with_msg
            PdfOperationKind.REORDER -> R.string.reorder_failed_with_msg
            PdfOperationKind.IMPORT -> R.string.error_loading_pdf_with_msg
            PdfOperationKind.VIEWER_EDIT -> R.string.pdf_edits_save_failed
            PdfOperationKind.SIGN -> R.string.signing_failed_with_msg
            PdfOperationKind.PRINT -> R.string.failed_to_generate_pdf_with_msg
            PdfOperationKind.SEND -> R.string.failed_to_generate_share_pdf_with_msg
            PdfOperationKind.OPEN -> R.string.failed_to_open_pdf_with_msg
            PdfOperationKind.SAVE_EXPORT, PdfOperationKind.SAVE_WRITE -> R.string.failed_to_generate_pdf_with_msg
            PdfOperationKind.CROP, null -> R.string.failed_to_crop_selected_groups_with_msg
        }
        Toast.makeText(this, getString(messageRes, error.message), Toast.LENGTH_LONG).show()
        if (kind.rewritesSource) {
            restoreCancelledOperation(kind)
            return
        }
        hideInProgressIfShown()
        unlockScreenOrientation()
        restore?.let { reloadAfterOperation(it) }
    }

    private fun restoreCancelledOperation(kind: PdfOperationKind?) {
        if (!binding.overlayProgressDialogUpdateUi.isVisible) {
            hideInProgressIfShown()
            showInProgressUpdateUI()
        }

        isSigningCancelled = true
        progressTracker.cancel()
        reloadThumbnailsGeneration++
        isNupRebuilding = false
        isLoadingSettingsProfile = false

        lifecycleScope.launch {
            restoreAfterCancel { kind == PdfOperationKind.LAYOUT }
        }
    }

    private fun reloadAfterOperation(restore: ThumbRestore) {
        reloadThumbnailsFromNup(
            targetIndex = restore.targetIndex,
            targetOffsetPx = restore.offsetPx,
            restorePrevious = false,
            indicesToSelect = restore.selected,
            releaseUiAtStart = false
        )
    }

    private val PdfOperationKind?.isOutput: Boolean
        get() = this == PdfOperationKind.PRINT || this == PdfOperationKind.SEND || this == PdfOperationKind.OPEN ||
            this == PdfOperationKind.SAVE_EXPORT || this == PdfOperationKind.SAVE_WRITE

    private val PdfOperationKind?.rewritesSource: Boolean
        get() = this == PdfOperationKind.DELETE_GROUP || this == PdfOperationKind.DELETE_SELECTED ||
            this == PdfOperationKind.EMPTY_PAGE || this == PdfOperationKind.REORDER || this == PdfOperationKind.SIGN

    private fun deleteNupPageGroupAndMirrorSource(nupIndex: Int) {
        startLayoutChange(
            PdfOperationKind.DELETE_GROUP,
            getString(R.string.in_progress_delete),
            PdfLayoutChangeOperation.Change.DeleteGroups(listOf(nupIndex))
        )
    }

    private fun deleteSelectedNupGroups() {
        val selectedNup = viewModel.pageItems
            .mapIndexedNotNull { idx, it -> if (it.isSelected) idx else null }
            .sorted()

        if (selectedNup.isEmpty()) return

        startLayoutChange(
            PdfOperationKind.DELETE_SELECTED,
            getString(R.string.in_progress_delete),
            PdfLayoutChangeOperation.Change.DeleteGroups(selectedNup)
        )
    }

    private fun ownCacheFileFor(uri: Uri): File? {
        if (uri.scheme != "content" || uri.authority != "$packageName.provider") return null
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != "cache") return null
        val name = segments[1]
        if (name.isEmpty() || name.contains('/') || name == "." || name == "..") return null
        val file = File(cacheDir, name)
        return file.takeIf { it.isFile && it.parentFile?.canonicalPath == cacheDir.canonicalPath }
    }

    private fun requestRvThumbnailsHeightUpdate() {
        if (!::binding.isInitialized) return

        val allowUpdateWithoutWindowFocus: Boolean = try {
            val progressShowing = (
                    binding.overlayProgressDialog.isVisible ||
                            binding.overlayProgressDialogUpdateUi.isVisible
                    )
            val sigPosShowing = (binding.overlayDialogSignaturePosition.isVisible)

            progressShowing || sigPosShowing
        } catch (_: Exception) {
            false
        }

        if (!hasWindowFocusFlag && !allowUpdateWithoutWindowFocus) {
            pendingRvUpdate = true
            return
        }

        if (isFinishing || isDestroyed) return

        val v = binding.rootScroll
        if (!v.isAttachedToWindow) return

        v.removeCallbacks(rvHeightUpdateRunnable)
        v.postDelayed(rvHeightUpdateRunnable, 80)
    }

    private fun setRvHeightSafely(newHeight: Int) {
        if (!::binding.isInitialized) return
        val rv = binding.rvThumbnails
        val lp = rv.layoutParams ?: return
        if (lp.height == newHeight) return

        suppressRootScrollLayoutCallback = true
        lp.height = newHeight
        rv.layoutParams = lp

        binding.rootScroll.postOnAnimation {
            suppressRootScrollLayoutCallback = false
        }
    }

    override fun onStart() {
        super.onStart()

        PdfOperations.runner(this).screenStarted()

        lastConfigSnapshotForDiff = Configuration(resources.configuration)

        startFoldableLayoutTracking()
    }

    private fun startFoldableLayoutTracking() {
        if (foldableInfoJob != null) return

        foldableInfoJob = lifecycleScope.launch {
            try {
                WindowInfoTracker.getOrCreate(this@MainActivity)
                    .windowLayoutInfo(this@MainActivity)
                    .collect { info ->
                        val foldableNow = info.displayFeatures.any { it is FoldingFeature }
                        isFoldableDevice = isFoldableDevice || foldableNow
                        requestRvThumbnailsHeightUpdate()
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private fun applyRvThumbnailsHeight() {
        if (!::binding.isInitialized) return
        if (isFinishing || isDestroyed) return

        val c = resources.configuration
        val portrait = (c.screenHeightDp >= c.screenWidthDp)

        val shouldFillLikeTabletPortrait =
            (!isFoldableDevice) && portrait && isTabletLikeDevice

        val minPx = resources.getDimensionPixelSize(R.dimen.dp_420)

        if (!shouldFillLikeTabletPortrait) {
            setRvHeightSafely(minPx)
            return
        }

        binding.rootScroll.post {
            if (!::binding.isInitialized) return@post
            if (isFinishing || isDestroyed) return@post

            val rv = binding.rvThumbnails
            val scroll = binding.rootScroll

            val bottom = bottomPanelView

            val locRv = IntArray(2)
            val locScroll = IntArray(2)
            rv.getLocationInWindow(locRv)
            scroll.getLocationInWindow(locScroll)

            val bottomShown = (bottom != null && bottom.isShown && bottom.height > 0)
            val reservedForBottomPanel = if (bottomShown) bottom.height else 0

            val extraPaddingBottom = max(0, scroll.paddingBottom - reservedForBottomPanel)

            val scrollBottomInWindow = locScroll[1] + scroll.height - extraPaddingBottom

            var effectiveBottomInWindow = scrollBottomInWindow
            if (bottomShown) {
                val locBottom = IntArray(2)
                bottom.getLocationInWindow(locBottom)
                effectiveBottomInWindow = min(effectiveBottomInWindow, locBottom[1])
            }

            val scrollTopInWindow = locScroll[1] + scroll.paddingTop
            val rvTopInWindow = max(locRv[1], scrollTopInWindow)

            var availablePx = effectiveBottomInWindow - rvTopInWindow
            if (availablePx < 0) availablePx = 0

            val section = rv.parent as? View
            val sectionLp = section?.layoutParams as? ViewGroup.MarginLayoutParams
            if (sectionLp != null && !bottomShown) {
                availablePx -= sectionLp.bottomMargin
                if (availablePx < 0) availablePx = 0
            }

            val targetPx = max(minPx, availablePx)
            setRvHeightSafely(targetPx)
        }
    }

    override fun onStop() {
        try {
            if (::binding.isInitialized) {
                binding.rootScroll.removeCallbacks(rvHeightUpdateRunnable)
            }
        } catch (_: Exception) { }

        suppressRootScrollLayoutCallback = false

        pendingRvUpdate = false
        hasWindowFocusFlag = false

        foldableInfoJob?.cancel()
        foldableInfoJob = null

        PdfOperations.runner(this).screenStopped()

        super.onStop()
    }

    private fun isAndroidxPdfAvailable(): Boolean =
        try {
            Class.forName("androidx.pdf.viewer.fragment.PdfViewerFragment")
            true
        } catch (_: Throwable) { false }

    private fun isPdfViewerDeclared(): Boolean =
        try {
            packageManager.getActivityInfo(
                ComponentName(this, PdfViewer::class.java),
                0
            )
            true
        } catch (_: Exception) { false }

    private fun cleanupCacheExceptTemps() {
        val keep = setOf(SOURCE_TEMP_NAME, NUP_TEMP_NAME)
        cacheDir.listFiles()?.forEach { f ->
            if (f.name !in keep) {
                runCatching { f.deleteRecursively() }
            }
        }
    }

    private fun handleEditedPdfFromViewer(uri: Uri) {
        val namedExport = lastNamedExport
        lastNamedExport = null
        val selected = selectedNupIndices()

        val operation = PdfViewerEditOperation(
            files = operationFiles(),
            saveSelection = selectionSaver(selected),
            input = PdfViewerEditOperation.Input(
                config = currentNupConfigFromInputs(),
                builtConfig = lastBuiltNupConfig,
                crops = sourceCrop,
                rotation = sourceRotation.copyOf(),
                position = sourcePosition.copyOf(),
                defaultPosition = defaultPosition,
                batchSize = PageBatch.PAGES,
                selected = selected
            ),
            progress = operationProgress(),
            copyEdited = { work -> copyEditedFromViewer(uri, namedExport, work) },
            warn = { warning, error -> Log.w("PdfMuPdfFitBounds", warning, error) }
        )

        startOperation(
            PdfOperationKind.VIEWER_EDIT,
            getString(R.string.in_progress_page_layout),
            onCancelled = operation::restoreIfBackedUp,
            work = operation::execute
        )
    }

    @WorkerThread
    private fun copyEditedFromViewer(uri: Uri, namedExport: File?, work: File) {
        val editedFile = ownCacheFileFor(uri)
        namedExport?.let { named ->
            if (named.canonicalPath != editedFile?.canonicalPath) runCatching { named.delete() }
        }

        if (editedFile == null || !editedFile.renameTo(work)) {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(work).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            } ?: throw IOException(getString(R.string.input_stream_is_null))
        }
    }

    private fun scheduleCacheCleanupAfterExternalReturn() {
        pendingCacheCleanupAfterExternalReturn = true
        didPauseForExternalAction = false
    }

    private fun openPdfWithDriveOrOther(initialPageIndex: Int) {
        val operation = outputOperation(password = null)
        startOperation(
            PdfOperationKind.OPEN,
            getString(R.string.in_progress_pdf_reading),
            onCancelled = operation::discard,
            work = { report -> OpenResult(operation.execute(report).file, initialPageIndex) }
        )
    }

    private fun openExported(named: File, initialPageIndex: Int) {
        val job = lifecycleScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    FileProvider.getUriForFile(this@MainActivity, "${packageName}.provider", named)
                }

                val shouldUseInternal: Boolean =
                    Build.VERSION.SDK_INT >= VERSION_CODES.P &&
                        runCatching {
                            val prefs = getSharedPreferences(PREFS_NAME_FRAGMENT, MODE_PRIVATE)
                            synchronized(prefsBackupLock) {
                                prefs.getBoolean(KEY_LAST_INNER_PDF_READER, true)
                            }
                        }.getOrDefault(true)

                if (shouldUseInternal && isAndroidxPdfAvailable() && isPdfViewerDeclared()) {
                    try {
                        val i = Intent(this@MainActivity, PdfViewer::class.java).apply {
                            putExtra(PdfViewer.EXTRA_URI, uri.toString())
                            putExtra(PdfViewer.EXTRA_INITIAL_PAGE_INDEX, initialPageIndex)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        pdfViewerLauncher.launch(i)
                        return@launch
                    } catch (_: Exception) {
                    }
                }

                val apps = withContext(Dispatchers.IO) { pdfViewerApps(uri) }
                if (apps.isEmpty()) {
                    Toast.makeText(this@MainActivity, getString(R.string.no_app_can_open_pdf), Toast.LENGTH_LONG).show()
                    return@launch
                }

                showOpenWithDialog(uri, apps)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                hideInProgressIfShown()
                unlockScreenOrientation()
                Toast.makeText(this@MainActivity, getString(R.string.failed_to_open_pdf_with_msg, e.message), Toast.LENGTH_LONG).show()
            }
        }
        activeJobs.add(job)
        job.invokeOnCompletion { activeJobs.remove(job) }
    }

    private class OpenWithApp(
        val label: CharSequence,
        val icon: Drawable,
        val packageName: String,
        val activityName: String
    )

    private class OpenWithPagerAdapter(
        private val pages: List<List<OpenWithApp>>,
        private val rows: Int,
        private val onPick: (OpenWithApp) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val page = LayoutInflater.from(parent.context).inflate(R.layout.item_open_with_page, parent, false)
            return object : RecyclerView.ViewHolder(page) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val page = holder.itemView as LinearLayout
            page.removeAllViews()
            val apps = pages[position]
            val inflater = LayoutInflater.from(page.context)

            for (r in 0 until rows) {
                val row = LinearLayout(page.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                for (c in 0 until OPEN_WITH_COLUMNS) {
                    val cell = inflater.inflate(R.layout.item_open_with_app, row, false)
                    cell.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    val app = apps.getOrNull(r * OPEN_WITH_COLUMNS + c)
                    if (app == null) {
                        cell.visibility = View.INVISIBLE
                    } else {
                        cell.findViewById<AppCompatImageView>(R.id.open_with_icon).setImageDrawable(app.icon)
                        cell.findViewById<MaterialTextView>(R.id.open_with_label).text = app.label
                        cell.setOnClickListener { onPick(app) }
                    }
                    row.addView(cell)
                }
                page.addView(row)
            }
        }
    }

    @WorkerThread
    private fun pdfViewerApps(uri: Uri): List<OpenWithApp> {
        val viewIntent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
        val collator = Collator.getInstance(Locale.getDefault())
        return packageManager.queryIntentActivities(viewIntent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map {
                OpenWithApp(
                    label = it.loadLabel(packageManager),
                    icon = it.loadIcon(packageManager),
                    packageName = it.activityInfo.packageName,
                    activityName = it.activityInfo.name
                )
            }
            .sortedWith(compareBy(collator) { it.label.toString() })
    }

    private fun showOpenWithDialog(uri: Uri, apps: List<OpenWithApp>, animate: Boolean = true) {
        val overlay = binding.overlayDialogOpenWith
        val panel = binding.overlayDialogOpenWithPanel

        openWithRequest = uri to apps

        panel.removeAllViews()
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_open_with, panel, false)
        panel.addView(dialogView)

        fun close() {
            openWithRequest = null
            overlay.visibility = View.GONE
            overlay.setOnClickListener(null)
            panel.removeAllViews()
        }

        fun cancel() {
            close()
            lifecycleScope.launch(Dispatchers.IO) { cleanupCacheExceptTemps() }
        }

        overlay.setOnClickListener { cancel() }
        panel.setOnClickListener {
        }
        dialogView.findViewById<Button>(R.id.open_with_btn_cancel).setOnClickListener { cancel() }

        val config = resources.configuration
        val phoneLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                config.smallestScreenWidthDp < OPEN_WITH_TABLET_MIN_DP
        val rows = if (apps.size <= OPEN_WITH_COLUMNS || phoneLandscape) 1 else OPEN_WITH_ROWS
        val pages = apps.chunked(rows * OPEN_WITH_COLUMNS)

        val pager = RecyclerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            overScrollMode = View.OVER_SCROLL_NEVER
            isNestedScrollingEnabled = false
        }
        dialogView.findViewById<FrameLayout>(R.id.open_with_pager_container).addView(pager)

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        pager.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            private var downX = 0f
            private var downY = 0f

            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.x
                        downY = e.y
                        rv.parent.requestDisallowInterceptTouchEvent(rv.canScrollHorizontally(1) || rv.canScrollHorizontally(-1))
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = abs(e.x - downX)
                        val dy = abs(e.y - downY)
                        if (dy > touchSlop && dy > dx) rv.parent.requestDisallowInterceptTouchEvent(false)
                    }
                }
                return false
            }
        })
        pager.adapter = OpenWithPagerAdapter(pages, rows) { app ->
            close()
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                setClassName(app.packageName, app.activityName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(viewIntent)
                scheduleCacheCleanupAfterExternalReturn()
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.no_app_can_open_pdf), Toast.LENGTH_LONG).show()
                lifecycleScope.launch(Dispatchers.IO) { cleanupCacheExceptTemps() }
            }
        }
        val snapHelper = PagerSnapHelper()
        snapHelper.attachToRecyclerView(pager)

        val dots = dialogView.findViewById<LinearLayout>(R.id.open_with_dots)
        dots.isVisible = pages.size > 1
        repeat(pages.size) {
            LayoutInflater.from(this).inflate(R.layout.item_open_with_dot, dots, true)
        }

        fun selectDot(index: Int) {
            for (i in 0 until dots.childCount) dots.getChildAt(i).isSelected = (i == index)
        }
        selectDot(0)

        pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                val lm = recyclerView.layoutManager ?: return
                val snapView = snapHelper.findSnapView(lm) ?: return
                selectDot(lm.getPosition(snapView))
            }
        })

        if (!animate) {
            overlay.alpha = 1f
            panel.translationY = 0f
            overlay.visibility = View.VISIBLE
            return
        }

        overlay.alpha = 0f
        overlay.visibility = View.VISIBLE
        panel.doOnPreDraw {
            panel.translationY = panel.height.toFloat()
            overlay.animate().alpha(1f).setDuration(OPEN_WITH_ANIMATION_MS).start()
            panel.animate().translationY(0f).setDuration(OPEN_WITH_ANIMATION_MS).start()
        }
    }

    private class OpenResult(val file: File, val pageIndex: Int) : PdfOperationResult

    private fun outputOperation(password: ByteArray?): PdfOutputOperation =
        PdfOutputOperation(
            files = PdfOutputOperation.Files(
                cacheDir,
                nupTempFile(),
                setOf(NUP_TEMP_NAME, SOURCE_TEMP_NAME, SOURCE_TEMP_COPY_NAME, NUP_TEMP_COPY_NAME)
            ),
            saveSelection = selectionSaver(selectedNupIndices()),
            input = PdfOutputOperation.Input(
                baseName = buildOutputFileName().ifBlank { OutputFileName.defaultDate() },
                password = password,
                batchSize = PageBatch.PAGES,
                messages = PdfOutputOperation.Messages(
                    layoutMissing = getString(R.string.temporary_pdf_not_found),
                    nameReserved = getString(R.string.output_name_reserved),
                    replaceFailed = getString(R.string.failed_to_replace_original_pdf_with_encrypted_version),
                    renameFailed = getString(R.string.failed_to_rename_encrypted_pdf)
                )
            ),
            progress = operationProgress()
        )

    private fun startOutput(kind: PdfOperationKind, message: String, password: ByteArray?) {
        val operation = outputOperation(password)
        startOperation(kind, message, onCancelled = operation::discard, work = operation::execute)
    }

    private fun finishOutput(result: PdfOperationResult?, restore: ThumbRestore?, kind: PdfOperationKind?) {
        hideInProgressIfShown()
        unlockScreenOrientation()
        restore?.let { reloadAfterOperation(it) }

        if (kind == PdfOperationKind.SAVE_WRITE) {
            Toast.makeText(this, getString(R.string.pdf_saved_successfully), Toast.LENGTH_LONG).show()
            maybeRunDeferredUiModeRecreate()
            return
        }

        val file = when (result) {
            is OpenResult -> result.file
            is PdfOutputOperation.Result -> result.file
            else -> return
        }
        lastNamedExport = file

        when (kind) {
            PdfOperationKind.PRINT -> printExported(file)
            PdfOperationKind.SEND -> shareExported(file)
            PdfOperationKind.OPEN -> openExported(file, (result as? OpenResult)?.pageIndex ?: 0)
            PdfOperationKind.SAVE_EXPORT -> askSaveLocation(file)
            else -> Unit
        }
    }

    private fun printNupPdf() {
        startOutput(PdfOperationKind.PRINT, getString(R.string.in_progress_print), password = null)
    }

    private fun printExported(named: File) {
        try {
            val printManager = getSystemService(PrintManager::class.java)
            if (printManager == null) {
                Toast.makeText(this, getString(R.string.print_service_not_available), Toast.LENGTH_LONG).show()
                return
            }

            val adapter = PdfDocumentAdapter(named.absolutePath, named.name)
            printManager.print(named.name, adapter, PrintAttributes.Builder().build())
            scheduleCacheCleanupAfterExternalReturn()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.failed_to_generate_pdf_with_msg, e.message),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun saveNupPdf() {
        if (!passwordOutCb.isChecked) {
            startOutput(PdfOperationKind.SAVE_EXPORT, getString(R.string.in_progress_save), password = null)
            return
        }

        val job = lifecycleScope.launch {
            val password = getConfirmedOutputPasswordOrNull() ?: return@launch
            startOutput(PdfOperationKind.SAVE_EXPORT, getString(R.string.in_progress_save), password)
        }
        activeJobs.add(job)
        job.invokeOnCompletion { activeJobs.remove(job) }
    }

    private fun askSaveLocation(named: File) {
        pendingSaveNamedPath = named.absolutePath
        pendingSaveNamedDisplayName = named.name
        try {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/pdf"
                putExtra(Intent.EXTRA_TITLE, named.name)
            }
            saveDocumentLauncher.launch(intent)
        } catch (e: Exception) {
            pendingSaveNamedPath = null
            pendingSaveNamedDisplayName = null
            Toast.makeText(
                this,
                getString(R.string.failed_to_generate_pdf_with_msg, e.message),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun startSaveWrite(uri: Uri) {
        val source = pendingSaveNamedPath?.takeIf { it.isNotEmpty() }?.let(::File)
        val resolver = applicationContext.contentResolver
        val notFound = getString(R.string.temporary_pdf_not_found)
        val streamNull = getString(R.string.input_stream_is_null)
        val saveSelection = selectionSaver(selectedNupIndices())
        val discardTarget: suspend () -> Unit = {
            withContext(Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(resolver, uri) } }
        }

        startOperation(PdfOperationKind.SAVE_WRITE, getString(R.string.in_progress_save), onCancelled = discardTarget) {
            withContext(Dispatchers.IO) {
                try {
                    saveSelection()
                    if (source == null || !source.exists()) throw IllegalStateException(notFound)

                    resolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(source).use { inp ->
                            val buffer = ByteArray(16 * 1024)
                            var read: Int
                            while (inp.read(buffer).also { read = it } > 0) {
                                ensureActive()
                                out.write(buffer, 0, read)
                            }
                            out.flush()
                        }
                    } ?: throw IOException(streamNull)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    discardTarget()
                    throw e
                }
                null
            }
        }
    }

    private fun endSaveWrite() {
        pendingSaveNamedPath = null
        pendingSaveNamedDisplayName = null
        lifecycleScope.launch(Dispatchers.IO) { runCatching { cleanupCacheExceptTemps() } }
    }

    private fun shareNupPdf() {
        if (!passwordOutCb.isChecked) {
            startOutput(PdfOperationKind.SEND, getString(R.string.in_progress_send), password = null)
            return
        }

        val job = lifecycleScope.launch {
            val password = getConfirmedOutputPasswordOrNull() ?: return@launch
            startOutput(PdfOperationKind.SEND, getString(R.string.in_progress_send), password)
        }
        activeJobs.add(job)
        job.invokeOnCompletion { activeJobs.remove(job) }
    }

    private fun shareExported(named: File) {
        val job = lifecycleScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    FileProvider.getUriForFile(this@MainActivity, "$packageName.provider", named)
                }
                sharePdfWithViewOptions(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.failed_to_generate_share_pdf_with_msg, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        activeJobs.add(job)
        job.invokeOnCompletion { activeJobs.remove(job) }
    }

    private suspend fun promptForPasswordForOutput(titleResId: Int, errorHintResId: Int? = null): String? =
        suspendCancellableCoroutine { cont ->

            applySoftInputModeForPasswordOverlay()

            val overlay = binding.overlayDialogPassword
            val panel = binding.overlayDialogPasswordPanel

            panel.removeAllViews()
            val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_pdf_password, panel, false)
            panel.addView(dialogView)

            overlay.visibility = View.VISIBLE

            overlay.bringToFront()

            overlay.setOnClickListener {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                val token = currentFocus?.windowToken ?: runCatching { overlay.windowToken }.getOrNull()
                runCatching { imm?.hideSoftInputFromWindow(token, 0) }

                overlay.visibility = View.GONE
                overlay.setOnClickListener(null)
                restoreSoftInputModeAfterPasswordOverlay()
                if (!cont.isCompleted) cont.resume(null)
            }
            panel.setOnClickListener {
            }

            (dialogView as? ViewGroup)?.let { vg ->
                (vg.getChildAt(0) as? TextView)?.setText(titleResId)
            }

            val et = setupPasswordInputForDialog(
                dialogView = dialogView,
                showError = (errorHintResId != null),
                errorHintResId = errorHintResId
            )

            val btnOk     = dialogView.findViewById<Button>(R.id.pwd_ok)
            val btnCancel = dialogView.findViewById<Button>(R.id.pwd_cancel)

            btnOk.setOnClickListener {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                val token = currentFocus?.windowToken ?: runCatching { overlay.windowToken }.getOrNull()
                runCatching { imm?.hideSoftInputFromWindow(token, 0) }

                val pwd = et.text?.toString()?.trim().orEmpty()

                overlay.visibility = View.GONE
                overlay.setOnClickListener(null)

                restoreSoftInputModeAfterPasswordOverlay()

                if (!cont.isCompleted) cont.resume(pwd)
            }

            btnCancel.setOnClickListener {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                val token = currentFocus?.windowToken ?: runCatching { overlay.windowToken }.getOrNull()
                runCatching { imm?.hideSoftInputFromWindow(token, 0) }

                overlay.visibility = View.GONE
                overlay.setOnClickListener(null)

                restoreSoftInputModeAfterPasswordOverlay()

                if (!cont.isCompleted) cont.resume(null)
            }

            cont.invokeOnCancellation {
                runCatching {
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                    val token = currentFocus?.windowToken ?: runCatching { overlay.windowToken }.getOrNull()
                    runCatching { imm?.hideSoftInputFromWindow(token, 0) }

                    overlay.visibility = View.GONE
                    overlay.setOnClickListener(null)

                    restoreSoftInputModeAfterPasswordOverlay()
                }.onFailure {
                }
            }

            runCatching {
                et.requestFocus()
            }.onFailure {
            }

            et.post {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                val token = currentFocus?.windowToken ?: runCatching { overlay.windowToken }.getOrNull()
                runCatching { imm?.hideSoftInputFromWindow(token, 0) }
            }
        }

    private suspend fun getConfirmedOutputPasswordOrNull(): ByteArray? {
        var needErrorOnFirst = false
        while (true) {
            val first = promptForPasswordForOutput(
                titleResId = R.string.enter_pdf_password,
                errorHintResId = if (needErrorOnFirst) R.string.password_do_not_match else null
            ) ?: return null
            val second = promptForPasswordForOutput(
                titleResId = R.string.password_again,
                errorHintResId = null
            ) ?: return null

            if (first == second) {
                return first.toByteArray(Charsets.UTF_8)
            } else {
                needErrorOnFirst = true
            }
        }
    }

    @SuppressLint("RtlHardcoded")
    private fun showTimestampDialog() {

        val overlay = binding.overlayDialogTimestamp
        val panel = binding.overlayDialogTimestampPanel

        panel.removeAllViews()
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_timestamp, panel, false)
        panel.addView(dialogView)

        overlay.visibility = View.VISIBLE

        overlay.setOnClickListener {
            overlay.visibility = View.GONE
            overlay.setOnClickListener(null)
        }
        panel.setOnClickListener {
        }

        val listView = dialogView.findViewById<ListView>(R.id.dts_listview)
        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE

        val options = listOf(
            getString(R.string.opt_year_yyyy) to "[YYYY]",
            getString(R.string.opt_year_yy)   to "[YY]",
            getString(R.string.opt_month_mm)  to "[MM]",
            getString(R.string.opt_day_dd)    to "[DD]",
            getString(R.string.opt_hour_hh)   to "[hh]",
            getString(R.string.opt_minute_mm) to "[mm]",
            getString(R.string.opt_second_ss) to "[ss]"
        )
        val labels = options.map { it.first }
        val checked = BooleanArray(labels.size) { false }

        run {
            data class RowViews(
                val text: MaterialTextView,
                val cb: MaterialCheckBox
            )
            fun Int.dp(parent: ViewGroup) = (this * parent.resources.displayMetrics.density).toInt()

            val adapter = object : BaseAdapter() {
                override fun getCount(): Int = labels.size
                override fun getItem(position: Int): Any = labels[position]
                override fun getItemId(position: Int): Long = position.toLong()

                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val row = if (convertView == null) {
                        val container = LinearLayout(parent.context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                            setPadding(16.dp(parent), 12.dp(parent), 16.dp(parent), 12.dp(parent))
                            isClickable = true
                            isFocusable = true
                        }
                        val tv = MaterialTextView(parent.context).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                            )
                            textAlignment = View.TEXT_ALIGNMENT_GRAVITY
                            gravity = Gravity.LEFT
                            TextViewCompat.setTextAppearance(
                                this, R.style.TextAppearance_ReePDF_ListItem
                            )
                        }
                        val cb = MaterialCheckBox(parent.context).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { gravity = Gravity.RIGHT }
                            text = ""
                            isClickable = true
                            isFocusable = false

                            isUseMaterialThemeColors = false
                            buttonDrawable = AppCompatResources.getDrawable(context, R.drawable.ic_checkbox)
                            buttonTintList = AppCompatResources.getColorStateList(context, R.color.ic_checkbox_filename_color)

                            background = null
                            contentDescription = null
                        }
                        container.addView(tv)
                        container.addView(cb)
                        container.tag = RowViews(tv, cb)
                        container
                    } else convertView

                    val holder = row.tag as RowViews
                    holder.text.text = labels[position]
                    holder.cb.isChecked = checked[position]

                    val onToggle: (Int) -> Unit = { idx ->
                        checked[idx] = !checked[idx]
                        holder.cb.isChecked = checked[idx]
                    }
                    row.setOnClickListener { onToggle(position) }
                    holder.cb.setOnClickListener { onToggle(position) }

                    return row
                }
            }

            listView.adapter = adapter
        }

        val btnOk     = dialogView.findViewById<Button>(R.id.dts_btn_ok)
        val btnCancel = dialogView.findViewById<Button>(R.id.dts_btn_cancel)

        val fileNameEt: EditText = findViewById(R.id.file_name)

        btnOk?.setOnClickListener {
            val tokens = buildList {
                for (i in labels.indices) if (checked[i]) add(options[i].second)
            }
            if (tokens.isNotEmpty()) {
                val insert = tokens.joinToString(separator = "")
                val editable = fileNameEt.text ?: Editable.Factory.getInstance().newEditable("")
                val start = fileNameEt.selectionStart.coerceAtLeast(0).coerceAtMost(editable.length)
                editable.insert(start, insert)
                fileNameEt.text = editable
                fileNameEt.setSelection((start + insert.length).coerceAtMost(editable.length))
            }

            overlay.visibility = View.GONE
            overlay.setOnClickListener(null)
        }

        btnCancel?.setOnClickListener {
            overlay.visibility = View.GONE
            overlay.setOnClickListener(null)
        }
    }

    private fun buildOutputFileName(): String {
        val fileNameEt: EditText = findViewById(R.id.file_name)
        val tmpl = fileNameEt.text?.toString().orEmpty().trim()

        if (tmpl.isNotBlank()) return OutputFileName.replaceTimeTokens(tmpl)

        val rowsStr = numberOfRows.text?.toString()?.trim().orEmpty()
        val colsStr = numberOfColumns.text?.toString()?.trim().orEmpty()
        val rows = rowsStr.toIntOrNull() ?: 1
        val cols = colsStr.toIntOrNull() ?: 1
        val isReeProfile1ByRowsCols = (rows in 0..1) && (cols in 0..1)

        val defTemplate = if (isReeProfile1ByRowsCols) {
            getString(R.string.default_tmpl_reeprofile1)
        } else {
            getString(R.string.default_tmpl_reeprofile2)
        }

        return OutputFileName.replaceTimeTokens(defTemplate)
    }

    private fun sharePdfWithViewOptions(uri: Uri) {
        val chooserTitle = getString(R.string.send_pdf_file)

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val displayName = (buildOutputFileName().ifBlank { OutputFileName.defaultDate() }) + ".pdf"
            putExtra(Intent.EXTRA_TITLE, displayName)
            putExtra(Intent.EXTRA_SUBJECT, displayName)
        }

        val pm = packageManager

        if (Build.VERSION.SDK_INT >= VERSION_CODES.S) {
            val resolves = pm.queryIntentActivities(sendIntent, 0)
            val ownComponents = resolves
                .filter { it.activityInfo.packageName == packageName }
                .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
                .toTypedArray()

            val chooser = Intent.createChooser(sendIntent, chooserTitle).apply {
                if (ownComponents.isNotEmpty()) {
                    putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, ownComponents)
                }
            }
            try {
                startActivity(chooser)
                scheduleCacheCleanupAfterExternalReturn()
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.no_app_can_share_pdf), Toast.LENGTH_LONG).show()
                lifecycleScope.launch(Dispatchers.IO) { cleanupCacheExceptTemps() }
            }
        } else {
            val resolves = pm.queryIntentActivities(sendIntent, 0)
            val ownComponents = resolves
                .filter { it.activityInfo.packageName == packageName }
                .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }

            ownComponents.forEach {
                pm.setComponentEnabledSetting(
                    it,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }

            try {
                startActivity(Intent.createChooser(sendIntent, chooserTitle))
                scheduleCacheCleanupAfterExternalReturn()
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.no_app_can_share_pdf), Toast.LENGTH_LONG).show()
                lifecycleScope.launch(Dispatchers.IO) { cleanupCacheExceptTemps() }
            } finally {
                Handler(Looper.getMainLooper()).postDelayed({
                    ownComponents.forEach { comp ->
                        pm.setComponentEnabledSetting(
                            comp,
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            PackageManager.DONT_KILL_APP
                        )
                    }
                }, 500)
            }
        }
    }

    private fun signSelectedPagesOnSourceTemp(
        signatureFile: File,
        signatureWidth: Int,
        signatureHeight: Int,
        inputXMM: Float,
        inputYMM: Float,
        applyScanEffect: Boolean,
        applyGrayscalePage: Boolean
    ) {
        if (signatureWidth <= 0 || signatureHeight <= 0) {
            Toast.makeText(this, getString(R.string.signature_image_invalid), Toast.LENGTH_LONG).show()
            return
        }

        val selected = selectedNupIndices().sorted()
        if (selected.isEmpty() || !nupTempFile().exists() || !PdfSourceParts.exists(sourceDir())) {
            Toast.makeText(this, getString(R.string.select_a_page), Toast.LENGTH_SHORT).show()
            return
        }

        isSigningCancelled = false

        val operation = PdfSignOperation(
            files = operationFiles(),
            saveSelection = selectionSaver(selected),
            input = PdfSignOperation.Input(
                signature = SignedPageRenderer.Signature(
                    file = signatureFile,
                    width = signatureWidth,
                    height = signatureHeight,
                    refPx = currentSignatureRefPx(),
                    xMm = inputXMM,
                    yMm = inputYMM,
                    isLandscape = landscape.isChecked,
                    scanEffect = applyScanEffect,
                    grayscale = applyGrayscalePage
                ),
                highDpi = selectedDpiIndexForSignature == 1,
                nupIndices = selected,
                perGroup = perGroup(),
                config = currentNupConfigFromInputs(),
                crops = sourceCrop,
                rotation = sourceRotation.copyOf(),
                position = sourcePosition.copyOf(),
                defaultPosition = defaultPosition,
                batchSize = PageBatch.PAGES
            ),
            progress = operationProgress(),
            renderer = MuPdfSignedPageRenderer(warn = { warning, error -> Log.w("MainActivity", warning, error) }),
            warn = { warning, error -> Log.w("PdfMuPdfFitBounds", warning, error) }
        )

        startOperation(
            PdfOperationKind.SIGN,
            getString(R.string.in_progress_signing),
            onCancelled = operation::restoreIfBackedUp,
            work = operation::execute
        )
    }

    private class SimpleTextWatcher(private val cb: () -> Unit) : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { cb() }
    }

    private fun setThemedBackArrow() {
        binding.toolbar.navigationIcon = AppCompatResources.getDrawable(this, R.drawable.ic_arrow_back_24)
    }

    override fun onResume() {
        super.onResume()
        setThemedBackArrow()
        suppressNup = false
        runCatching { snapshotInitialPrefsIfNeeded() }

        runCatching {
            val showing = (
                    binding.overlayProgressDialog.isVisible ||
                            binding.overlayProgressDialogUpdateUi.isVisible
                    )

            if (showing) {
                var indicator = binding.overlayProgressDialogPanel.findViewById<BaseProgressIndicator<*>>(R.id.progress_bar_indicator)

                if (indicator == null) {
                    indicator = binding.overlayProgressDialogUpdateUiPanel.findViewById(R.id.progress_bar_indicator_update_ui)
                }

                indicator?.post {
                    indicator.hide()
                    indicator.show()
                }
            }
        }.onFailure {
        }

        if (Build.VERSION.SDK_INT <= VERSION_CODES.N_MR1 && !didRunNScrollColdStartFix && isFirstCreate) {
            val sv = binding.rootScroll

            sv.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            sv.isFocusable = true
            sv.isFocusableInTouchMode = true

            sv.post {
                sv.scrollTo(0, 0)
                sv.fullScroll(View.FOCUS_UP)

                sv.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
                sv.requestFocusFromTouch()

                didRunNScrollColdStartFix = true
            }
        }

        if (Build.VERSION.SDK_INT <= VERSION_CODES.N_MR1 && !isFirstCreate) {
            val sv = binding.rootScroll

            sv.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

            sv.post {
                sv.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS

                sv.isFocusable = true
                sv.isFocusableInTouchMode = true
                sv.requestFocusFromTouch()
            }
        }

        if (pendingCacheCleanupAfterExternalReturn && didPauseForExternalAction) {
            pendingCacheCleanupAfterExternalReturn = false
            didPauseForExternalAction = false
            lifecycleScope.launch(Dispatchers.IO) {
                cleanupCacheExceptTemps()
            }
        }

        binding.root.removeCallbacks(workspaceFilesCheck)
        binding.root.post(workspaceFilesCheck)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        MenuCompat.setGroupDividerEnabled(menu, true)

        runCatching {
            val item = menu.findItem(R.id.action_inner_pdf_reader)
            if (item != null) {
                val prefs = getSharedPreferences(PREFS_NAME_FRAGMENT, MODE_PRIVATE)
                val enabled = synchronized(prefsBackupLock) {
                    prefs.getBoolean(KEY_LAST_INNER_PDF_READER, true)
                }
                item.isCheckable = true
                item.isChecked = enabled
                item.isEnabled = Build.VERSION.SDK_INT >= VERSION_CODES.P
            }
        }

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_theme_light -> { setThemeMode("light"); true }
            R.id.action_theme_dark  -> { setThemeMode("dark");  true }
            R.id.action_theme_system-> { setThemeMode("system");true }

            R.id.action_inner_pdf_reader -> {
                val newChecked = !item.isChecked
                item.isChecked = newChecked
                synchronized(prefsBackupLock) {
                    getSharedPreferences(PREFS_NAME_FRAGMENT, MODE_PRIVATE)
                        .edit { putBoolean(KEY_LAST_INNER_PDF_READER, newChecked) }
                }
                true
            }

            R.id.action_battery_optimization -> {
                launchBatteryOptimizationSettings()
                true
            }

            R.id.action_save -> {
                launchSaveSharedPreferences()
                true
            }

            R.id.action_load -> {
                launchLoadSharedPreferences()
                true
            }

            R.id.action_about -> {
                showAboutDialog()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showAboutDialog() {
        val overlay = binding.overlayDialogAbout
        val panel = binding.overlayDialogAboutPanel

        panel.removeAllViews()
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_about, panel, false)
        panel.addView(dialogView)

        overlay.visibility = View.VISIBLE

        overlay.setOnClickListener {
            overlay.visibility = View.GONE
            overlay.setOnClickListener(null)
        }
        panel.setOnClickListener {
        }

        dialogView.findViewById<MaterialTextView>(R.id.about_title).text =
            getString(R.string.about_title, getString(R.string.app_name), BuildConfig.VERSION_NAME)

        listOf(
            R.id.about_readme_link to R.string.about_readme_url,
            R.id.about_privacy_link to R.string.about_privacy_url,
            R.id.about_license_link to R.string.about_license_url,
            R.id.about_third_party_link to R.string.about_third_party_url,
            R.id.about_source_link to R.string.about_source_url
        ).forEach { (viewId, urlId) ->
            dialogView.findViewById<MaterialTextView>(viewId).setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, getString(urlId).toUri()))
                } catch (_: Exception) {
                    Toast.makeText(this, getString(R.string.no_app_can_open_link), Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialogView.findViewById<MaterialTextView>(R.id.about_contact_link).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_SENDTO, ("mailto:" + getString(R.string.about_contact_email)).toUri()))
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.no_email_app), Toast.LENGTH_SHORT).show()
            }
        }

        dialogView.findViewById<Button>(R.id.about_btn_ok)?.setOnClickListener {
            overlay.visibility = View.GONE
            overlay.setOnClickListener(null)
        }
    }

    private fun launchBatteryOptimizationSettings() {

        val overlay = binding.overlayDialogBatteryOptimization
        val panel = binding.overlayDialogBatteryOptimizationPanel

        panel.removeAllViews()
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_battery_optimization, panel, false)
        panel.addView(dialogView)

        overlay.visibility = View.VISIBLE

        overlay.setOnClickListener {
            overlay.visibility = View.GONE
            overlay.setOnClickListener(null)
        }
        panel.setOnClickListener {
        }

        val btnOk     = dialogView.findViewById<Button>(R.id.bo_btn_ok)
        val btnCancel = dialogView.findViewById<Button>(R.id.bo_btn_cancel)

        btnCancel?.setOnClickListener {

            overlay.visibility = View.GONE
            overlay.setOnClickListener(null)
        }

        btnOk?.setOnClickListener {

            overlay.visibility = View.GONE
            overlay.setOnClickListener(null)

            try {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
                startActivity(intent)
            } catch (_: Exception) {

            }
        }
    }

    private fun launchSaveSharedPreferences() {
        val suggestedName = "ReePDF_settings.xml"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/xml"
            putExtra(Intent.EXTRA_TITLE, suggestedName)
        }
        savePrefsLauncher.launch(intent)
    }

    private fun launchLoadSharedPreferences() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/xml",
                    "text/xml",
                    "text/plain",
                    "application/octet-stream"
                )
            )
        }
        loadPrefsLauncher.launch(intent)
    }

    private fun exportAllSharedPreferencesXml(): String {
        val serializer = Xml.newSerializer()
        val sw = StringWriter()

        serializer.setOutput(sw)
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)
        serializer.startDocument("UTF-8", true)
        serializer.startTag(null, "preferences")

        fun writeMap(name: String) {
            val all = synchronized(prefsBackupLock) {
                getSharedPreferences(name, MODE_PRIVATE).all
            }
            serializer.startTag(null, "map")
            serializer.attribute(null, "name", name)

            val allowedKeys = setOf(
                KEY_REEPROFILE1_ROWS, KEY_REEPROFILE2_ROWS,
                KEY_REEPROFILE1_COLUMNS, KEY_REEPROFILE2_COLUMNS,
                KEY_REEPROFILE1_SCALE, KEY_REEPROFILE2_SCALE,
                KEY_REEPROFILE1_FIT, KEY_REEPROFILE2_FIT, KEY_REEPROFILE1_POSITION, KEY_REEPROFILE2_POSITION,
                KEY_REEPROFILE1_CROP, KEY_REEPROFILE2_CROP,
                KEY_REEPROFILE1_MARGIN, KEY_REEPROFILE2_MARGIN,
                KEY_REEPROFILE1_ORIENTATION, KEY_REEPROFILE2_ORIENTATION,
                KEY_REEPROFILE1_DRAW_FRAME, KEY_REEPROFILE2_DRAW_FRAME,
                KEY_REEPROFILE1_SIG_POS_PORTRAIT_X, KEY_REEPROFILE2_SIG_POS_PORTRAIT_X,
                KEY_REEPROFILE1_SIG_POS_PORTRAIT_Y, KEY_REEPROFILE2_SIG_POS_PORTRAIT_Y,
                KEY_REEPROFILE1_SIG_POS_LANDSCAPE_X, KEY_REEPROFILE2_SIG_POS_LANDSCAPE_X,
                KEY_REEPROFILE1_SIG_POS_LANDSCAPE_Y, KEY_REEPROFILE2_SIG_POS_LANDSCAPE_Y,
                KEY_REEPROFILE1_PASSWORD_OUT, KEY_REEPROFILE2_PASSWORD_OUT,
                KEY_REEPROFILE1_FILE_NAME_TEMPLATE, KEY_REEPROFILE2_FILE_NAME_TEMPLATE,
                KEY_REEPROFILE1_SIG_DPI_INDEX, KEY_REEPROFILE2_SIG_DPI_INDEX,
                KEY_REEPROFILE1_SCAN_EFFECT, KEY_REEPROFILE2_SCAN_EFFECT,
                KEY_REEPROFILE1_GRAYSCALE, KEY_REEPROFILE2_GRAYSCALE,
                KEY_LAST_SIG_DPI_INDEX, KEY_LAST_SCAN_EFFECT, KEY_LAST_GRAYSCALE,
                KEY_LAST_INNER_PDF_READER, KEY_LAST_LOADED_PROFILE,
                KEY_RESTORE_LOCK_UNTIL_RELOAD, KEY_FIRST_RUN_INITIALIZED
            )

            for ((key, value) in all) {
                if (!allowedKeys.contains(key)) continue

                serializer.startTag(null, "entry")
                serializer.attribute(null, "key", key)
                when (value) {
                    is String -> { serializer.attribute(null, "type", "string"); serializer.text(value) }
                    is Int -> { serializer.attribute(null, "type", "int"); serializer.text(value.toString()) }
                    is Long -> { serializer.attribute(null, "type", "long"); serializer.text(value.toString()) }
                    is Float -> { serializer.attribute(null, "type", "float"); serializer.text(value.toString()) }
                    is Boolean -> { serializer.attribute(null, "type", "boolean"); serializer.text(value.toString()) }
                    is Set<*> -> {
                        val items = value.filterIsInstance<String>()
                        serializer.attribute(null, "type", "string_set")
                        serializer.text(items.joinToString("\u0001"))
                    }
                    else -> {

                    }
                }
                serializer.endTag(null, "entry")
            }
            serializer.endTag(null, "map")
        }

        writeMap(PREFS_NAME_FRAGMENT)

        serializer.endTag(null, "preferences")
        serializer.endDocument()

        return sw.toString()
    }

    private fun importAllSharedPreferencesFromXml(xml: String) {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))

        val spFragment = getSharedPreferences(PREFS_NAME_FRAGMENT, MODE_PRIVATE)

        var editor: SharedPreferences.Editor? = null
        var inTargetMap = false

        var currentKey: String? = null
        var currentType: String? = null
        val textBuf = StringBuilder()

        var foundTargetMap = false

        val stringKeys = setOf(
            KEY_REEPROFILE1_ROWS, KEY_REEPROFILE2_ROWS,
            KEY_REEPROFILE1_COLUMNS, KEY_REEPROFILE2_COLUMNS,
            KEY_REEPROFILE1_MARGIN, KEY_REEPROFILE2_MARGIN,
            KEY_REEPROFILE1_SIG_POS_PORTRAIT_X, KEY_REEPROFILE2_SIG_POS_PORTRAIT_X,
            KEY_REEPROFILE1_SIG_POS_PORTRAIT_Y, KEY_REEPROFILE2_SIG_POS_PORTRAIT_Y,
            KEY_REEPROFILE1_SIG_POS_LANDSCAPE_X, KEY_REEPROFILE2_SIG_POS_LANDSCAPE_X,
            KEY_REEPROFILE1_SIG_POS_LANDSCAPE_Y, KEY_REEPROFILE2_SIG_POS_LANDSCAPE_Y,
            KEY_REEPROFILE1_FILE_NAME_TEMPLATE, KEY_REEPROFILE2_FILE_NAME_TEMPLATE,
            KEY_LAST_LOADED_PROFILE
        )
        val booleanKeys = setOf(
            KEY_REEPROFILE1_SCALE, KEY_REEPROFILE2_SCALE,
            KEY_REEPROFILE1_FIT, KEY_REEPROFILE2_FIT,
            KEY_REEPROFILE1_CROP, KEY_REEPROFILE2_CROP,
            KEY_REEPROFILE1_ORIENTATION, KEY_REEPROFILE2_ORIENTATION,
            KEY_REEPROFILE1_DRAW_FRAME, KEY_REEPROFILE2_DRAW_FRAME,
            KEY_REEPROFILE1_PASSWORD_OUT, KEY_REEPROFILE2_PASSWORD_OUT,
            KEY_REEPROFILE1_SCAN_EFFECT, KEY_REEPROFILE2_SCAN_EFFECT,
            KEY_REEPROFILE1_GRAYSCALE, KEY_REEPROFILE2_GRAYSCALE,
            KEY_LAST_SCAN_EFFECT, KEY_LAST_GRAYSCALE,
            KEY_LAST_INNER_PDF_READER, KEY_RESTORE_LOCK_UNTIL_RELOAD, KEY_FIRST_RUN_INITIALIZED
        )
        val positionKeys = setOf(KEY_REEPROFILE1_POSITION, KEY_REEPROFILE2_POSITION)
        val dpiIndexKeys = setOf(KEY_REEPROFILE1_SIG_DPI_INDEX, KEY_REEPROFILE2_SIG_DPI_INDEX, KEY_LAST_SIG_DPI_INDEX)

        fun invalid(key: String): Nothing =
            throw IllegalArgumentException(getString(R.string.error_invalid_prefs_value, key))

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "map" -> {
                            val nameAttr = (0 until parser.attributeCount)
                                .firstOrNull { parser.getAttributeName(it) == "name" }
                                ?.let { parser.getAttributeValue(it) }
                            inTargetMap = (nameAttr == PREFS_NAME_FRAGMENT)
                            if (inTargetMap) {
                                foundTargetMap = true
                                synchronized(prefsBackupLock) {
                                    editor = spFragment.edit()
                                }
                            }
                        }
                        "entry" -> {
                            if (inTargetMap) {
                                currentKey = (0 until parser.attributeCount)
                                    .firstOrNull { parser.getAttributeName(it) == "key" }
                                    ?.let { parser.getAttributeValue(it) }
                                currentType = (0 until parser.attributeCount)
                                    .firstOrNull { parser.getAttributeName(it) == "type" }
                                    ?.let { parser.getAttributeValue(it) }
                                textBuf.setLength(0)
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inTargetMap) {
                        textBuf.append(parser.text)
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "entry" -> {
                            if (inTargetMap) {
                                val key = currentKey
                                val type = currentType
                                val valueText = textBuf.toString()

                                if (key != null && type != null && editor != null) {
                                    when (key) {
                                        in stringKeys -> {
                                            if (type != "string") invalid(key)
                                            editor.putString(key, valueText)
                                        }
                                        in booleanKeys -> {
                                            val value = valueText.lowercase(Locale.ROOT).toBooleanStrictOrNull()
                                            if (type != "boolean" || value == null) invalid(key)
                                            editor.putBoolean(key, value)
                                        }
                                        in positionKeys -> {
                                            val value = valueText.toIntOrNull()
                                            if (type != "int" || value == null || validPosition(value) != value) invalid(key)
                                            editor.putInt(key, value)
                                        }
                                        in dpiIndexKeys -> {
                                            val value = valueText.toIntOrNull()
                                            if (type != "int" || value == null || value !in 0..1) invalid(key)
                                            editor.putInt(key, value)
                                        }
                                    }
                                }
                                currentKey = null
                                currentType = null
                                textBuf.setLength(0)
                            }
                        }
                        "map" -> {
                            if (inTargetMap) {
                                inTargetMap = false
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }

        if (foundTargetMap) {
            synchronized(prefsBackupLock) {
                editor?.apply()
            }
        } else {
            throw IllegalArgumentException(getString(R.string.error_invalid_prefs_file, PREFS_NAME_FRAGMENT))
        }
    }

    private fun setThemeMode(mode: String) {
        val prefs = getSharedPreferences(PREFS_NAME_FRAGMENT, MODE_PRIVATE)
        synchronized(prefsBackupLock) {
            prefs.edit { putString(KEY_THEME_MODE, mode) }
        }

        val nightMode = when (mode) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }

        runCatching {
            val lm = binding.rvThumbnails.layoutManager as? LinearLayoutManager
            val first = lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
            val offset = if (first != RecyclerView.NO_POSITION) {
                lm?.findViewByPosition(first)?.top ?: 0
            } else 0
            val selectedIdx = viewModel.pageItems
                .mapIndexedNotNull { idx, it -> if (it.isSelected) idx else null }

            val nsv = findViewById<NestedScrollView>(R.id.root_scroll)
            val nsvY = nsv?.scrollY ?: -1

            val csv = selectedIdx.joinToString(",")
            val rotationCsv = sourceRotation.joinToString(",")

            val sp = getSharedPreferences(PREFS_NAME_FRAGMENT, MODE_PRIVATE)
            synchronized(prefsBackupLock) {
                sp.edit {
                    putInt(KEY_THEME_RESTORE_FIRST, first)
                    putInt(KEY_THEME_RESTORE_OFFSET, offset)
                    putString(KEY_THEME_RESTORE_SELECTED_CSV, csv)
                    putString(KEY_THEME_RESTORE_ROTATION_CSV, rotationCsv)
                    putString(KEY_THEME_RESTORE_POSITION_CSV, sourcePosition.joinToString(","))
                    putString(KEY_THEME_RESTORE_CROP, PdfCrops.encode(sourceCrop))
                    putInt(KEY_THEME_RESTORE_NSV_Y, nsvY)
                }
            }
        }

        runCatching {
            val sp2 = getSharedPreferences(PREFS_NAME_FRAGMENT, MODE_PRIVATE)
            synchronized(prefsBackupLock) {
                sp2.edit { putBoolean(KEY_RESTORE_LOCK_UNTIL_RELOAD, true) }
            }
        }.onFailure {

        }

        if (Build.VERSION.SDK_INT >= VERSION_CODES.S) {
            val uiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
            val appNightMode = when (mode) {
                "light" -> UiModeManager.MODE_NIGHT_NO
                "dark" -> UiModeManager.MODE_NIGHT_YES
                else -> UiModeManager.MODE_NIGHT_AUTO
            }
            uiModeManager.setApplicationNightMode(appNightMode)

            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        } else {
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }
    }
}

@Suppress("DEPRECATION")
fun Intent.getParcelableCompat(name: String): Uri? =
    if (Build.VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) getParcelableExtra(name, Uri::class.java)
    else getParcelableExtra(name)

@Suppress("DEPRECATION")
fun Intent.getParcelableArrayListCompat(name: String): ArrayList<Uri>? =
    if (Build.VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) getParcelableArrayListExtra(name, Uri::class.java)
    else getParcelableArrayListExtra(name)
