package hu.reelee81.reepdf

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.ext.SdkExtensions
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import android.widget.ImageButton
import androidx.annotation.RequiresExtension
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.pdf.PdfWriteHandle
import androidx.pdf.view.PdfView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
@RequiresExtension(extension = Build.VERSION_CODES.S, version = 18)
class PdfRotationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun requireSupportedPdfPlatform() {
        assumeTrue(SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 18)
        instrumentation.uiAutomation.rootInActiveWindow
        waitUntil {
            context.getSystemService(AccessibilityManager::class.java).isEnabled
        }
    }

    @Test
    fun viewerScrubberSurvivesRotationWithoutLosingPage() {
        verifyRotation(PdfViewer::class.java, PdfViewer.EXTRA_URI, PdfViewer.EXTRA_INITIAL_PAGE_INDEX)
    }

    @Test
    fun editorScrubberSurvivesRotationWithoutLosingPage() {
        verifyRotation(
            PdfEditorActivity::class.java,
            PdfEditorActivity.EXTRA_INPUT_URI,
            PdfEditorActivity.EXTRA_INITIAL_PAGE_INDEX,
            editBeforeRotation = true
        )
    }

    @Test
    fun viewerWaitBlocksCommandsAnimatesOnlyWhilePendingAndAllowsBack() {
        withPdfActivity(PdfViewer::class.java, PdfViewer.EXTRA_URI) { scenario, _ ->
            awaitNavigation(scenario)
            var parent: ViewGroup? = null
            try {
                scenario.onActivity { activity ->
                    val view = findPdfView(activity)
                    val content = view.parent as ViewGroup
                    parent = content
                    content.suppressLayout(true)
                    view.requestLayout()
                    val fragment = activity.supportFragmentManager.fragments.first() as ReadOnlyPdfViewerFragment
                    fragment.scrollToPage(TARGET_PAGE / 2)
                    assertWaitBlocksContent(activity)
                    val searchButtons = ArrayList<View>()
                    activity.findViewById<View>(R.id.toolbar).findViewsWithText(
                        searchButtons, activity.getString(R.string.search_pdf), View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION
                    )
                    assertEquals(1, searchButtons.size)
                    searchButtons.single().performClick()
                    assertFalse(fragment.isTextSearchActive)
                    assertWaitIndicatorShown(activity)
                    saveWaitPreview(activity)
                }
                SystemClock.sleep(600)
                scenario.onActivity { activity ->
                    assertWaitBlocksContent(activity)
                    assertWaitIndicatorShown(activity)
                }
            } finally {
                scenario.onActivity { parent?.suppressLayout(false) }
            }
            awaitNavigation(scenario)
            scenario.onActivity { activity ->
                assertEquals(View.GONE, activity.findViewById<View>(R.id.overlay_pdf_navigation_wait).visibility)
            }
            SystemClock.sleep(600)
            scenario.onActivity { activity ->
                assertEquals(View.GONE, activity.findViewById<View>(R.id.overlay_pdf_navigation_wait).visibility)
                val view = findPdfView(activity)
                val content = view.parent as ViewGroup
                content.suppressLayout(true)
                view.requestLayout()
                (activity.supportFragmentManager.fragments.first() as ReadOnlyPdfViewerFragment).scrollToPage(TARGET_PAGE)
                assertWaitBlocksContent(activity)
                activity.onBackPressedDispatcher.onBackPressed()
                assertTrue(activity.isFinishing)
                content.suppressLayout(false)
            }
            waitUntil { scenario.state == Lifecycle.State.DESTROYED }
        }
    }

    @Test
    fun editorEnablesEditingOnlyAfterInitialNavigation() {
        var parent: ViewGroup? = null
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity is PdfEditorActivity && stage == Stage.CREATED) {
                parent = activity.findViewById(android.R.id.content)
                parent?.suppressLayout(true)
            }
        }
        instrumentation.runOnMainSync { monitor.addLifecycleCallback(callback) }
        try {
            withPdfActivity(PdfEditorActivity::class.java, PdfEditorActivity.EXTRA_INPUT_URI) { scenario, _ ->
                try {
                    scenario.onActivity { activity ->
                        assertTrue(requireNotNull(parent).isLayoutSuppressed)
                        assertWaitBlocksContent(activity)
                        val fragment = activity.supportFragmentManager.fragments.first() as PdfEditorFragment
                        assertFalse(fragment.isEditModeEnabled)
                        assertFalse(findSaveButton(activity).isEnabled)
                    }
                    SystemClock.sleep(500)
                    scenario.onActivity { activity ->
                        assertWaitBlocksContent(activity)
                        assertFalse((activity.supportFragmentManager.fragments.first() as PdfEditorFragment).isEditModeEnabled)
                    }
                } finally {
                    scenario.onActivity { parent?.suppressLayout(false) }
                }
                awaitNavigation(scenario)
                scenario.onActivity { activity ->
                    assertEquals(TARGET_PAGE, pageAtViewportCenter(findPdfView(activity)))
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.overlay_pdf_navigation_wait).visibility)
                    assertTrue((activity.supportFragmentManager.fragments.first() as PdfEditorFragment).isEditModeEnabled)
                    assertTrue(findSaveButton(activity).isEnabled)
                    activity.onBackPressedDispatcher.onBackPressed()
                }
                waitUntil { scenario.state == Lifecycle.State.DESTROYED }
            }
        } finally {
            instrumentation.runOnMainSync { monitor.removeLifecycleCallback(callback) }
        }
    }

    @Test
    fun editorWaitInterceptsDrawingAndAllowsBackWithoutDiscardDialog() {
        withPdfActivity(PdfEditorActivity::class.java, PdfEditorActivity.EXTRA_INPUT_URI) { scenario, uri ->
            awaitNavigation(scenario)
            scenario.onActivity { activity ->
                val view = findPdfView(activity)
                val parent = view.parent as ViewGroup
                parent.suppressLayout(true)
                view.requestLayout()
                val fragment = activity.supportFragmentManager.fragments.first() as PdfEditorFragment
                fragment.loadDocumentAtPage(uri, TARGET_PAGE / 2)
                assertWaitBlocksContent(activity)
                assertFalse(findSaveButton(activity).isEnabled)
                val position = IntArray(2).also(view::getLocationInWindow)
                val downTime = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(
                        downTime, SystemClock.uptimeMillis(), action,
                        position[0] + view.width / 2f + action * 20f, position[1] + view.height / 2f, 0
                    )
                    try {
                        assertTrue(activity.dispatchTouchEvent(event))
                    } finally {
                        event.recycle()
                    }
                }
                assertFalse(fragment.hasUnsavedChanges)
                activity.onBackPressedDispatcher.onBackPressed()
                assertTrue(activity.isFinishing)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.overlay_dialog_pdf_editor_discard).visibility)
                parent.suppressLayout(false)
            }
            waitUntil { scenario.state == Lifecycle.State.DESTROYED }
        }
    }

    @Test
    fun editorWaitPreservesDraftAndKeepsDiscardConfirmationUsable() {
        withPdfActivity(PdfEditorActivity::class.java, PdfEditorActivity.EXTRA_INPUT_URI) { scenario, uri ->
            awaitNavigation(scenario)
            drawAnnotation(scenario)
            var parent: ViewGroup? = null
            scenario.onActivity { activity ->
                val view = findPdfView(activity)
                val content = view.parent as ViewGroup
                parent = content
                content.suppressLayout(true)
                view.requestLayout()
                val fragment = activity.supportFragmentManager.fragments.first() as PdfEditorFragment
                fragment.loadDocumentAtPage(uri, TARGET_PAGE / 2)
                assertWaitBlocksContent(activity)
                assertTrue(fragment.hasUnsavedChanges)
                activity.onBackPressedDispatcher.onBackPressed()
                assertFalse(activity.isFinishing)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.overlay_dialog_pdf_editor_discard).visibility)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { tapView(it, it.findViewById(R.id.pdf_editor_discard_cancel)) }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertWaitBlocksContent(activity)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.overlay_dialog_pdf_editor_discard).visibility)
                assertTrue((activity.supportFragmentManager.fragments.first() as PdfEditorFragment).hasUnsavedChanges)
                activity.onBackPressedDispatcher.onBackPressed()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                tapView(activity, activity.findViewById(R.id.pdf_editor_discard_confirm))
                parent?.suppressLayout(false)
            }
            waitUntil { scenario.state == Lifecycle.State.DESTROYED }
        }
    }

    @Test
    fun editorLoadErrorReleasesWaitingOverlay() {
        val missingFile = File(context.cacheDir, "missing_${System.nanoTime()}.pdf")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", missingFile)
        val intent = Intent(context, PdfEditorActivity::class.java)
            .putExtra(PdfEditorActivity.EXTRA_INPUT_URI, uri.toString())
        ActivityScenario.launch<PdfEditorActivity>(intent).use { scenario ->
            awaitNavigation(scenario)
            scenario.onActivity { activity ->
                assertEquals(View.GONE, activity.findViewById<View>(R.id.overlay_pdf_navigation_wait).visibility)
                assertFalse(findSaveButton(activity).isEnabled)
                assertFalse((activity.supportFragmentManager.fragments.first() as PdfEditorFragment).isEditModeEnabled)
                activity.onBackPressedDispatcher.onBackPressed()
                assertTrue(activity.isFinishing)
            }
            waitUntil { scenario.state == Lifecycle.State.DESTROYED }
        }
    }

    @Test
    fun editorSaveBlocksBackFromButtonPressUntilSavedDocumentCanBeReopened() {
        withPdfActivity(PdfEditorActivity::class.java, PdfEditorActivity.EXTRA_INPUT_URI, forResult = true) { scenario, _ ->
            awaitNavigation(scenario)
            drawAnnotation(scenario)
            scenario.onActivity { activity ->
                findSaveButton(activity).performClick()
                assertSaveBlocksBack(activity)
            }
            waitUntil { scenario.state == Lifecycle.State.DESTROYED }
            val result = scenario.result
            assertEquals(android.app.Activity.RESULT_OK, result.resultCode)
            verifySavedDocument(requireNotNull(result.resultData.getStringExtra(PdfEditorActivity.EXTRA_OUTPUT_URI)).toUri())
        }
    }

    @Test
    fun editorSaveWaitStaysAnimatedAndBlocksBackDuringFileWrite() {
        verifySaveWait(writeFails = false)
    }

    @Test
    fun editorSaveFailureReleasesWaitAndRestoresBackNavigation() {
        verifySaveWait(writeFails = true)
    }

    private fun verifySaveWait(writeFails: Boolean) {
        val writingStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val handleClosed = AtomicBoolean(false)
        withPdfActivity(PdfEditorActivity::class.java, PdfEditorActivity.EXTRA_INPUT_URI, forResult = true) { scenario, uri ->
            awaitNavigation(scenario)
            try {
                scenario.onActivity { activity ->
                    activity.onApplyEditsSuccess(object : PdfWriteHandle {
                        override suspend fun writeTo(destination: ParcelFileDescriptor) {
                            writingStarted.complete(Unit)
                            releaseWrite.await()
                            if (writeFails) throw IOException("Test PDF write failure")
                            requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                                ParcelFileDescriptor.AutoCloseOutputStream(
                                    ParcelFileDescriptor.dup(destination.fileDescriptor)
                                ).use { input.copyTo(it) }
                            }
                        }

                        override fun close() {
                            handleClosed.set(true)
                        }
                    })
                    assertSaveBlocksBack(activity)
                    assertWaitIndicatorShown(activity)
                }
                waitUntil { writingStarted.isCompleted }
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity { activity ->
                    assertSaveBlocksBack(activity)
                    assertWaitIndicatorShown(activity)
                    saveWaitPreview(activity, "saving_wait_preview.png")
                }
                releaseWrite.complete(Unit)
                if (writeFails) {
                    waitUntil {
                        var released = false
                        scenario.onActivity { activity ->
                            released = activity.findViewById<View>(R.id.overlay_pdf_navigation_wait).visibility == View.GONE
                        }
                        released
                    }
                    scenario.onActivity { activity ->
                        assertTrue(findSaveButton(activity).isEnabled)
                        assertFalse(activity.isFinishing)
                        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, activity.findViewById<View>(R.id.appbar).importantForAccessibility)
                        activity.onBackPressedDispatcher.onBackPressed()
                        assertTrue(activity.isFinishing)
                    }
                }
                waitUntil { scenario.state == Lifecycle.State.DESTROYED }
                assertTrue(handleClosed.get())
                if (!writeFails) {
                    val result = scenario.result
                    assertEquals(android.app.Activity.RESULT_OK, result.resultCode)
                    verifySavedDocument(requireNotNull(result.resultData.getStringExtra(PdfEditorActivity.EXTRA_OUTPUT_URI)).toUri())
                }
            } finally {
                releaseWrite.complete(Unit)
            }
        }
    }

    private fun assertSaveBlocksBack(activity: PdfEditorActivity) {
        assertWaitBlocksContent(activity)
        assertEquals(activity.getString(R.string.pdf_edits_saving), activity.findViewById<TextView>(R.id.progress_bar_simple_text).text)
        assertFalse(findSaveButton(activity).isEnabled)
        activity.onBackPressedDispatcher.onBackPressed()
        val toolbar = activity.findViewById<MaterialToolbar>(R.id.toolbar)
        (0 until toolbar.childCount).map(toolbar::getChildAt).filterIsInstance<ImageButton>().single().performClick()
        tapView(activity, findPdfView(activity))
        assertFalse(activity.isFinishing)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.overlay_dialog_pdf_editor_discard).visibility)
        assertWaitBlocksContent(activity)
    }

    private fun verifySavedDocument(uri: Uri) {
        try {
            val descriptor = requireNotNull(context.contentResolver.openFileDescriptor(uri, "r"))
            android.graphics.pdf.PdfRenderer(descriptor).use { assertEquals(1000, it.pageCount) }
            val intent = Intent(context, PdfEditorActivity::class.java)
                .putExtra(PdfEditorActivity.EXTRA_INPUT_URI, uri.toString())
                .putExtra(PdfEditorActivity.EXTRA_INITIAL_PAGE_INDEX, TARGET_PAGE)
            ActivityScenario.launch<PdfEditorActivity>(intent).use { scenario ->
                awaitNavigation(scenario)
                scenario.onActivity { activity ->
                    assertTrue(findSaveButton(activity).isEnabled)
                    assertEquals(TARGET_PAGE, pageAtViewportCenter(findPdfView(activity)))
                    activity.onBackPressedDispatcher.onBackPressed()
                }
                waitUntil { scenario.state == Lifecycle.State.DESTROYED }
            }
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    @Test
    fun viewerReturnFromEditorWithoutScrollingReleasesWait() {
        verifyEditorReturn(cancelWhileLoading = false)
    }

    @Test
    fun viewerReturnFromCanceledEditorNavigationReleasesWait() {
        verifyEditorReturn(cancelWhileLoading = true)
    }

    @Test
    fun viewerReturnRestartsWaitAnimationUntilLayoutIsReady() {
        verifyEditorReturn(cancelWhileLoading = true, holdViewerLayout = true)
    }

    private fun verifyEditorReturn(cancelWhileLoading: Boolean, holdViewerLayout: Boolean = false) {
        var editor: PdfEditorActivity? = null
        var editorRoot: ViewGroup? = null
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity is PdfEditorActivity && stage == Stage.CREATED) {
                editor = activity
                if (cancelWhileLoading) {
                    editorRoot = activity.findViewById(android.R.id.content)
                    editorRoot?.suppressLayout(true)
                }
            }
        }
        instrumentation.runOnMainSync { monitor.addLifecycleCallback(callback) }
        try {
            withPdfActivity(PdfViewer::class.java, PdfViewer.EXTRA_URI) { scenario, _ ->
                awaitNavigation(scenario)
                var viewerParent: ViewGroup? = null
                try {
                    scenario.onActivity { viewer ->
                        if (holdViewerLayout) {
                            val view = findPdfView(viewer)
                            val content = view.parent as ViewGroup
                            viewerParent = content
                            content.suppressLayout(true)
                            view.requestLayout()
                        }
                        val buttons = ArrayList<View>()
                        viewer.findViewById<View>(R.id.toolbar).findViewsWithText(
                            buttons, viewer.getString(R.string.edit_pdf), View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION
                        )
                        buttons.single().performClick()
                    }
                    waitUntil {
                        var ready = false
                        instrumentation.runOnMainSync {
                            editor?.let {
                                ready = it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                                    (cancelWhileLoading ||
                                        !(it.supportFragmentManager.fragments.first() as PdfEditorFragment).isPageNavigationInProgress)
                            }
                        }
                        ready
                    }
                    instrumentation.runOnMainSync {
                        val activity = requireNotNull(editor)
                        if (cancelWhileLoading) assertWaitBlocksContent(activity)
                        else assertEquals(TARGET_PAGE, pageAtViewportCenter(findPdfView(activity)))
                        activity.onBackPressedDispatcher.onBackPressed()
                        assertTrue(activity.isFinishing)
                        editorRoot?.suppressLayout(false)
                    }
                    waitUntil { scenario.state == Lifecycle.State.RESUMED }
                    if (holdViewerLayout) {
                        scenario.onActivity { viewer ->
                            assertWaitBlocksContent(viewer)
                            assertWaitIndicatorShown(viewer)
                        }
                    }
                } finally {
                    scenario.onActivity { viewerParent?.suppressLayout(false) }
                }
                waitUntil(timeoutMillis = 5_000) {
                    var idle = false
                    scenario.onActivity { viewer ->
                        idle = !(viewer.supportFragmentManager.fragments.first() as ReadOnlyPdfViewerFragment).isPageNavigationInProgress
                    }
                    idle
                }
                scenario.onActivity { viewer ->
                    assertEquals(TARGET_PAGE, pageAtViewportCenter(findPdfView(viewer)))
                    assertEquals(View.GONE, viewer.findViewById<View>(R.id.overlay_pdf_navigation_wait).visibility)
                    assertFalse(viewer.findViewById<View>(R.id.pdfFragContainer).importantForAccessibility ==
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                editorRoot?.suppressLayout(false)
                editor?.takeUnless { it.isFinishing || it.isDestroyed }?.onBackPressedDispatcher?.onBackPressed()
                monitor.removeLifecycleCallback(callback)
            }
        }
    }

    private fun <T : PdfActivity> withPdfActivity(
        activityClass: Class<T>,
        uriExtra: String,
        forResult: Boolean = false,
        block: (ActivityScenario<T>, Uri) -> Unit
    ) {
        val file = createPdf()
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val intent = Intent(context, activityClass)
                .putExtra(uriExtra, uri.toString())
                .putExtra(PdfViewer.EXTRA_INITIAL_PAGE_INDEX, TARGET_PAGE)
            val scenario = if (forResult) ActivityScenario.launchActivityForResult<T>(intent) else ActivityScenario.launch<T>(intent)
            scenario.use { block(it, uri) }
        } finally {
            file.delete()
        }
    }

    private fun assertWaitBlocksContent(activity: PdfActivity) {
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.overlay_pdf_navigation_wait).visibility)
        for (id in listOf(R.id.appbar, R.id.pdfFragContainer)) {
            val content = activity.findViewById<ViewGroup>(id)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, content.importantForAccessibility)
            assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, content.descendantFocusability)
        }
    }

    private fun assertWaitIndicatorShown(activity: PdfActivity) {
        assertTrue(activity.findViewById<LinearProgressIndicator>(R.id.progress_bar_indicator).isShown)
    }

    private fun findSaveButton(activity: PdfEditorActivity): View {
        val buttons = ArrayList<View>()
        activity.findViewById<View>(R.id.toolbar).findViewsWithText(
            buttons, activity.getString(R.string.save_pdf_edits), View.FIND_VIEWS_WITH_TEXT
        )
        return buttons.single()
    }

    private fun saveWaitPreview(activity: PdfActivity, fileName: String = "navigation_wait_preview.png") {
        val root = activity.findViewById<View>(android.R.id.content)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            File(context.getExternalFilesDir(null), fileName).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun tapView(activity: PdfActivity, view: View) {
        val position = IntArray(2).also(view::getLocationInWindow)
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action,
                position[0] + view.width / 2f, position[1] + view.height / 2f, 0
            )
            try {
                assertTrue(activity.dispatchTouchEvent(event))
            } finally {
                event.recycle()
            }
        }
    }

    private fun <T : PdfActivity> verifyRotation(
        activityClass: Class<T>,
        uriExtra: String,
        pageExtra: String,
        editBeforeRotation: Boolean = false
    ) {
        val file = createPdf()
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val intent = Intent(context, activityClass)
                .putExtra(uriExtra, uri.toString())
                .putExtra(pageExtra, TARGET_PAGE)
            ActivityScenario.launch<T>(intent).use { scenario ->
                awaitNavigation(scenario)
                scenario.onActivity { activity ->
                    assertEquals(TARGET_PAGE, pageAtViewportCenter(findPdfView(activity)))
                    assertScrubberDrawn(activity)
                }
                if (editBeforeRotation) drawAnnotation(scenario)
                for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
                    scenario.onActivity { it.requestedOrientation = orientation }
                    val expectedConfiguration = if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                        Configuration.ORIENTATION_LANDSCAPE
                    } else Configuration.ORIENTATION_PORTRAIT
                    waitUntil {
                        var rotated = false
                        scenario.onActivity {
                            rotated = it.resources.configuration.orientation == expectedConfiguration
                        }
                        rotated
                    }
                    awaitNavigation(scenario)
                    SystemClock.sleep(1800)
                    scenario.onActivity { activity ->
                        val view = findPdfView(activity)
                        assertEquals(TARGET_PAGE, pageAtViewportCenter(view))
                        view.scrollBy(0, 8)
                        assertScrubberDrawn(activity)
                        if (editBeforeRotation) {
                            assertTrue((activity.supportFragmentManager.fragments.first() as PdfEditorFragment).hasUnsavedChanges)
                        }
                    }
                }
            }
        } finally {
            file.delete()
        }
    }

    private fun <T : PdfActivity> drawAnnotation(scenario: ActivityScenario<T>) {
        var startX = 0f
        var endX = 0f
        var y = 0f
        scenario.onActivity { activity ->
            val view = findPdfView(activity)
            val position = IntArray(2).also(view::getLocationOnScreen)
            startX = position[0] + view.width * 0.3f
            endX = position[0] + view.width * 0.65f
            y = position[1] + view.height / 2f
        }
        SystemClock.sleep(300)
        val downTime = SystemClock.uptimeMillis()
        for (step in 0..20) {
            val action = when (step) {
                0 -> MotionEvent.ACTION_DOWN
                20 -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action,
                startX + (endX - startX) * step / 20f, y, 0
            )
            try {
                instrumentation.sendPointerSync(event)
            } finally {
                event.recycle()
            }
            SystemClock.sleep(16)
        }
        waitUntil {
            var edited = false
            scenario.onActivity {
                edited = (it.supportFragmentManager.fragments.first() as PdfEditorFragment).hasUnsavedChanges
            }
            edited
        }
    }

    private fun <T : PdfActivity> awaitNavigation(scenario: ActivityScenario<T>) {
        waitUntil {
            var ready = false
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.fragments.firstOrNull()
                ready = when (fragment) {
                    is ReadOnlyPdfViewerFragment -> !fragment.isPageNavigationInProgress
                    is PdfEditorFragment -> !fragment.isPageNavigationInProgress
                    else -> false
                }
            }
            ready
        }
        instrumentation.waitForIdleSync()
    }

    private fun findPdfView(activity: PdfActivity): PdfView =
        requireNotNull(findPdfView(activity.findViewById(android.R.id.content)))

    private fun findPdfView(view: View): PdfView? {
        if (view is PdfView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findPdfView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun pageAtViewportCenter(view: PdfView): Int? =
        view.viewToPdfPoint(view.width / 2f, view.height / 2f)?.pageNum

    private fun assertScrubberDrawn(activity: PdfActivity) {
        val root = activity.supportFragmentManager.fragments.first().requireView()
        val view = findPdfView(activity)
        val rootLocation = IntArray(2).also(root::getLocationInWindow)
        val viewLocation = IntArray(2).also(view::getLocationInWindow)
        val viewLeft = viewLocation[0] - rootLocation[0]
        val viewTop = viewLocation[1] - rootLocation[1]
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val hiddenBitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val thumb = view.fastScrollVerticalThumbDrawable
        val alpha = thumb.alpha
        try {
            root.draw(Canvas(bitmap))
            thumb.alpha = 0
            root.draw(Canvas(hiddenBitmap))
            val right = viewLeft + view.width - view.fastScrollVerticalThumbMarginEnd
            val left = (right - thumb.intrinsicWidth).coerceAtLeast(0)
            var changedPixels = 0
            for (y in viewTop.coerceAtLeast(0) until (viewTop + view.height).coerceAtMost(root.height)) {
                for (x in left until right.coerceAtMost(root.width)) {
                    if (bitmap.getPixel(x, y) != hiddenBitmap.getPixel(x, y)) changedPixels++
                }
            }
            assertTrue("The scrubber did not render: $changedPixels changed pixels", changedPixels > 10)
        } finally {
            thumb.alpha = alpha
            bitmap.recycle()
            hiddenBitmap.recycle()
        }
    }

    private fun createPdf(): File {
        val file = File(context.cacheDir, "rotation_test_${System.nanoTime()}.pdf")
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 20f
        }
        val document = PdfDocument()
        try {
            for (index in 0 until 1000) {
                val info = PdfDocument.PageInfo.Builder(595, 842, index + 1).create()
                val page = document.startPage(info)
                page.canvas.drawText("Page ${index + 1}", 40f, 50f, paint)
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
        return file
    }

    private fun waitUntil(timeoutMillis: Long = 60_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (!condition()) {
            assertTrue("Timed out waiting for PDF state", SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(100)
        }
    }

    private companion object {
        const val TARGET_PAGE = 750
    }
}
