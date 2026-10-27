package hu.reelee81.reepdf

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.os.ext.SdkExtensions
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.pdf.PdfFeature
import androidx.pdf.view.PdfView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.N)
class PdfViewerCompatibilityInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun mainMenuOffersInternalReaderFromAndroid9() {
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val activity = requireNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10_000)) as MainActivity
            instrumentation.runOnMainSync {
                val menu = PopupMenu(activity, activity.findViewById(android.R.id.content)).menu
                activity.onCreateOptionsMenu(menu)
                assertEquals(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P, menu.findItem(R.id.action_inner_pdf_reader).isEnabled)
                activity.finish()
            }
            instrumentation.waitForIdleSync()
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.P)
    fun viewerRendersAndNavigatesWithoutRequiringAnSdkExtension() {
        withPdf { uri ->
            ActivityScenario.launch<PdfViewer>(viewerIntent(uri)).use { scenario ->
                var lastState = "not loaded"
                waitUntil {
                    var ready = false
                    scenario.onActivity { activity ->
                        val view = findPdfView(activity.findViewById(android.R.id.content))
                        val fragment = activity.supportFragmentManager.fragments.firstOrNull() as? ReadOnlyPdfViewerFragment
                        val state = "pages=${view?.pdfDocument?.pageCount}, navigating=${fragment?.isPageNavigationInProgress}, " +
                            "page=${fragment?.currentPageIndex}, center=${view?.viewToPdfPoint(view.width / 2f, view.height / 2f)?.pageNum}"
                        if (lastState != state) {
                            lastState = state
                            android.util.Log.i("PdfViewerCompatibility", state)
                        }
                        ready = view != null && view.pdfDocument?.pageCount == PAGE_COUNT &&
                            fragment?.isPageNavigationInProgress == false &&
                            fragment.currentPageIndex == TARGET_PAGE &&
                            isPageRendered(view)
                    }
                    ready
                }
                scenario.onActivity { activity ->
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.overlay_pdf_navigation_wait).visibility)
                    val editorButtons = ArrayList<View>()
                    activity.findViewById<View>(R.id.toolbar).findViewsWithText(
                        editorButtons, activity.getString(R.string.edit_pdf), View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION
                    )
                    val supportsEditing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 18
                    assertEquals(if (supportsEditing) 1 else 0, editorButtons.size)
                    val view = requireNotNull(findPdfView(activity.findViewById(android.R.id.content)))
                    val fragment = activity.supportFragmentManager.fragments.first() as ReadOnlyPdfViewerFragment
                    if (view.pdfDocument?.isFeatureSupported(PdfFeature.SEARCH) == false) {
                        fragment.isTextSearchActive = true
                        assertFalse(fragment.isTextSearchActive)
                    }
                }
            }
        }
    }

    @Test
    @SdkSuppress(maxSdkVersion = Build.VERSION_CODES.O_MR1)
    fun viewerRejectsAndroidVersionsBelow9WithoutLoadingTheFragment() {
        withPdf { uri ->
            ActivityScenario.launchActivityForResult<PdfViewer>(viewerIntent(uri)).use { scenario ->
                waitUntil { scenario.state == Lifecycle.State.DESTROYED }
                assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
            }
        }
    }

    private fun viewerIntent(uri: Uri) = Intent(context, PdfViewer::class.java)
        .putExtra(PdfViewer.EXTRA_URI, uri.toString())
        .putExtra(PdfViewer.EXTRA_INITIAL_PAGE_INDEX, TARGET_PAGE)

    private fun withPdf(block: (Uri) -> Unit) {
        val file = File(context.cacheDir, "viewer_compatibility_${System.nanoTime()}.pdf")
        val document = PdfDocument()
        try {
            val paint = Paint().apply { color = Color.MAGENTA }
            for (index in 0 until PAGE_COUNT) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                page.canvas.drawRect(40f, 100f, 555f, 802f, paint)
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
        try {
            block(FileProvider.getUriForFile(context, "${context.packageName}.provider", file))
        } finally {
            file.delete()
        }
    }

    private fun findPdfView(view: View): PdfView? {
        if (view is PdfView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findPdfView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun isPageRendered(view: PdfView): Boolean {
        if (view.width <= 0 || view.height <= 0) return false
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.translate(-view.scrollX.toFloat(), -view.scrollY.toFloat())
            view.draw(canvas)
            for (y in view.height / 10 until view.height step maxOf(1, view.height / 10)) {
                val color = bitmap.getPixel(view.width / 2, y)
                if (Color.red(color) > 150 && Color.green(color) < 100 && Color.blue(color) > 150) return true
            }
            return false
        } finally {
            bitmap.recycle()
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (!condition()) {
            assertTrue("Timed out waiting for the viewer", SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(100)
        }
    }

    private companion object {
        const val PAGE_COUNT = 12
        const val TARGET_PAGE = 6
    }
}
