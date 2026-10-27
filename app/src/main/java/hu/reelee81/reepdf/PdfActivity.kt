package hu.reelee81.reepdf

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import com.google.android.material.progressindicator.LinearProgressIndicator
import hu.reelee81.reepdf.pdf.PdfProgress
import hu.reelee81.reepdf.pdf.ProgressTracker
import kotlin.math.ceil

abstract class PdfActivity : AppCompatActivity() {
    private var lastConfiguration: Configuration? = null
    private var pendingRecreation = false
    private var recreationPosted = false
    private var waitOverlay: View? = null
    private var waitActive = false
    private var waitStartedAt = 0L
    private var serviceMessage: String? = null
    private var focusBeforeNavigation: View? = null
    private var finishDeferred = false
    private val finishAfterDeferLimit = Runnable { finishDeferredNow() }
    private val blockedContent = mutableListOf<BlockedContentState>()
    private val ignoreBackWhileWaiting = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {}
    }
    private val revealWaitOverlay = Runnable { onPdfOperationStateChanged() }
    private val progressTicker = object : Runnable {
        override fun run() {
            if (renderProgress()) waitOverlay?.postDelayed(this, PROGRESS_TICK_MS)
        }
    }
    private val recreateWhenIdle = Runnable {
        recreationPosted = false
        if (pendingRecreation && !isFinishing && !isDestroyed &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            !supportFragmentManager.isStateSaved && !isPdfOperationInProgress()
        ) {
            pendingRecreation = false
            recreate()
        }
    }

    private val progressTracker = ProgressTracker(SystemClock::uptimeMillis)
    private var progressNotified = PdfProcessingForegroundService.PROGRESS_NONE
    private var progressNotifiedAt = 0L

    protected fun progressStage(remaining: Long): PdfProgress = progressTracker.stage(remaining)

    protected abstract fun isPdfNavigationInProgress(): Boolean

    protected open fun isPdfSavingInProgress(): Boolean = false

    protected open fun isPdfOperationInProgress(): Boolean = isPdfNavigationInProgress()

    protected open fun onPdfNavigationStateChanged(inProgress: Boolean) {}

    protected open fun isPdfWaitOverlayDeferred(): Boolean = false

    protected open fun cancelablePdfOperationCaption(): Int? = null

    protected open fun pdfNavigationCaption(): Int = R.string.pdf_navigation_wait

    protected open fun cancelPdfOperation() {}

    protected open fun isPdfDocumentLoading(): Boolean = false

    protected open fun onPdfLoadCancelled() {
        finish()
    }

    protected open fun leavesOnPdfWaitCancel(): Boolean = false

    protected fun isPdfWaitActive(): Boolean = waitActive

    protected open fun isPdfDocumentOpening(): Boolean = false

    override fun finish() {
        if (!isFinishing && !isDestroyed && isPdfDocumentOpening()) {
            if (!finishDeferred) {
                finishDeferred = true
                window.decorView.postDelayed(finishAfterDeferLimit, FINISH_DEFER_LIMIT_MS)
            }
            return
        }
        finishDeferred = false
        window.decorView.removeCallbacks(finishAfterDeferLimit)
        super.finish()
    }

    private fun finishDeferredNow() {
        if (!finishDeferred) return
        finishDeferred = false
        window.decorView.removeCallbacks(finishAfterDeferLimit)
        super.finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, ignoreBackWhileWaiting)
        lastConfiguration = Configuration(resources.configuration)
        pendingRecreation = savedInstanceState?.getBoolean(STATE_PENDING_RECREATION) ?: false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_PENDING_RECREATION, pendingRecreation)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        onPdfOperationStateChanged()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        val changes = lastConfiguration?.diff(newConfig) ?: 0
        lastConfiguration = Configuration(newConfig)
        super.onConfigurationChanged(newConfig)

        for (id in listOf(
            R.id.overlay_dialog_pdf_editor_discard_panel,
            R.id.overlay_dialog_pdf_editor_erase_all_panel,
            R.id.overlay_pdf_navigation_wait_panel
        )) {
            val panel = findViewById<View>(id) ?: continue
            val params = panel.layoutParams as ConstraintLayout.LayoutParams
            params.matchConstraintMaxWidth = resources.getDimensionPixelSize(R.dimen.dp_9999_overlay)
            panel.layoutParams = params
        }

        var resourceChanges = ActivityInfo.CONFIG_ORIENTATION or ActivityInfo.CONFIG_UI_MODE or
            ActivityInfo.CONFIG_LOCALE or ActivityInfo.CONFIG_LAYOUT_DIRECTION or ActivityInfo.CONFIG_FONT_SCALE or
            ActivityInfo.CONFIG_DENSITY or ActivityInfo.CONFIG_MCC or ActivityInfo.CONFIG_MNC or
            ActivityInfo.CONFIG_KEYBOARD or ActivityInfo.CONFIG_NAVIGATION or ActivityInfo.CONFIG_TOUCHSCREEN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            resourceChanges = resourceChanges or ActivityInfo.CONFIG_COLOR_MODE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            resourceChanges = resourceChanges or ActivityInfo.CONFIG_FONT_WEIGHT_ADJUSTMENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resourceChanges = resourceChanges or ActivityInfo.CONFIG_GRAMMATICAL_GENDER
        }
        if (changes and resourceChanges != 0) pendingRecreation = true
        onPdfOperationStateChanged()
    }

    fun onPdfOperationStateChanged() {
        if (isFinishing || isDestroyed) return
        if (finishDeferred && !isPdfDocumentOpening()) {
            finishDeferredNow()
            return
        }
        val navigating = isPdfNavigationInProgress()
        val saving = isPdfSavingInProgress()
        val active = navigating || saving
        if (active && !waitActive) {
            waitStartedAt = SystemClock.uptimeMillis()
            resetProgress()
        }
        waitActive = active
        ignoreBackWhileWaiting.isEnabled = active
        onPdfNavigationStateChanged(navigating)
        updateWaitOverlay(saving)
        if (!pendingRecreation || recreationPosted || isFinishing || isDestroyed) return
        recreationPosted = true
        window.decorView.post(recreateWhenIdle)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = when {
        findViewById<View>(R.id.overlay_dialog_pdf_editor_discard)?.isVisible == true ||
            findViewById<View>(R.id.overlay_dialog_pdf_editor_erase_all)?.isVisible == true ||
            waitOverlay?.isVisible == true -> super.dispatchTouchEvent(event)
        waitActive -> true
        else -> super.dispatchTouchEvent(event)
    }

    private fun isWaitCancelable(saving: Boolean): Boolean =
        leavesOnPdfWaitCancel() || cancelablePdfOperationCaption() != null ||
            (!saving && isPdfNavigationInProgress() && isPdfDocumentLoading())

    private fun onWaitCancel() {
        when {
            leavesOnPdfWaitCancel() -> finish()
            cancelablePdfOperationCaption() != null -> cancelPdfOperation()
            isPdfSavingInProgress() -> Unit
            isPdfDocumentLoading() -> onPdfLoadCancelled()
        }
    }

    private fun updateWaitOverlay(saving: Boolean) {
        val overlay = waitOverlay ?: findViewById<View>(R.id.overlay_pdf_navigation_wait)
            ?.also { view ->
                waitOverlay = view
                view.findViewById<View>(R.id.progress_bar_cancel)?.setOnClickListener { onWaitCancel() }
                view.findViewById<TextView>(R.id.progress_bar_percent)?.let { percent ->
                    percent.minWidth = ceil(percent.paint.measureText(getString(R.string.progress_percent, 100))).toInt()
                }
            } ?: return

        val elapsed = SystemClock.uptimeMillis() - waitStartedAt
        overlay.removeCallbacks(revealWaitOverlay)
        val waiting = waitActive && !isPdfWaitOverlayDeferred() && elapsed >= WAIT_REVEAL_MS
        if (waitActive && !waiting && !isPdfWaitOverlayDeferred()) {
            overlay.postDelayed(revealWaitOverlay, WAIT_REVEAL_MS - elapsed)
        }

        val caption = getString(
            cancelablePdfOperationCaption() ?: if (saving) R.string.pdf_edits_saving else pdfNavigationCaption()
        )
        overlay.findViewById<TextView>(R.id.progress_bar_cancel)?.let { button ->
            val label = getString(if (leavesOnPdfWaitCancel()) R.string.back_to_main_screen else R.string.cancel_text)
            if (button.text.toString() != label) button.text = label
            button.isEnabled = isWaitCancelable(saving)
        }
        overlay.findViewById<TextView>(R.id.progress_bar_simple_text)?.let { view ->
            if (view.text.toString() != caption) {
                view.text = caption
                view.requestLayout()
            }
        }
        overlay.contentDescription = caption

        if (waiting && blockedContent.isEmpty()) {
            focusBeforeNavigation = currentFocus
            for (id in listOf(R.id.appbar, R.id.pdfFragContainer)) {
                val content = findViewById<ViewGroup>(id)
                blockedContent.add(BlockedContentState(content, content.importantForAccessibility, content.descendantFocusability))
                content.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
            overlay.isVisible = true
            overlay.requestFocus()
            WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
            overlay.removeCallbacks(progressTicker)
            overlay.postDelayed(progressTicker, PROGRESS_TICK_MS)
        } else if (!waiting) {
            overlay.isVisible = false
            overlay.removeCallbacks(progressTicker)
            for ((view, accessibility, focusability) in blockedContent) {
                view.importantForAccessibility = accessibility
                view.descendantFocusability = focusability
            }
            blockedContent.clear()
            focusBeforeNavigation?.takeIf { it.isAttachedToWindow }?.requestFocus()
            focusBeforeNavigation = null
        }

        if (waiting && serviceMessage != caption) {
            serviceMessage = caption
            progressNotified = PdfProcessingForegroundService.PROGRESS_NONE
            PdfProcessingForegroundService.start(this, caption)
        } else if (!waiting && serviceMessage != null) {
            serviceMessage = null
            PdfProcessingForegroundService.stop(this)
        }
    }

    private fun resetProgress() {
        progressTracker.reset()
        progressNotified = PdfProcessingForegroundService.PROGRESS_NONE
        progressNotifiedAt = 0L
        waitOverlay?.findViewById<LinearProgressIndicator>(R.id.progress_bar_indicator)?.let { indicator ->
            if (!indicator.isIndeterminate) indicator.isIndeterminate = true
        }
        waitOverlay?.findViewById<View>(R.id.progress_bar_percent)?.visibility = View.GONE
    }

    private fun renderProgress(): Boolean {
        val overlay = waitOverlay ?: return false
        if (isFinishing || isDestroyed || !overlay.isVisible) return false

        val now = SystemClock.uptimeMillis()
        if (now - waitStartedAt < PROGRESS_REVEAL_MS) return true

        val percent = progressTracker.percent(now, PROGRESS_IDLE_MS)
            ?: PdfProcessingForegroundService.PROGRESS_INDETERMINATE

        val indicator = overlay.findViewById<LinearProgressIndicator>(R.id.progress_bar_indicator)
        val label = overlay.findViewById<TextView>(R.id.progress_bar_percent)
        if (percent >= 0) {
            if (indicator?.isIndeterminate == true) indicator.isIndeterminate = false
            indicator?.setProgressCompat(percent, true)
            label?.text = getString(R.string.progress_percent, percent)
            label?.visibility = View.VISIBLE
        } else {
            if (indicator?.isIndeterminate == false) indicator.isIndeterminate = true
            label?.visibility = View.GONE
        }

        val message = serviceMessage
        if (message != null && percent != progressNotified &&
            (percent < 0 || progressNotified < 0 || now - progressNotifiedAt >= PROGRESS_NOTIFY_MS)
        ) {
            progressNotified = percent
            progressNotifiedAt = now
            PdfProcessingForegroundService.start(this, message, percent)
        }
        return true
    }

    override fun onDestroy() {
        waitOverlay?.removeCallbacks(revealWaitOverlay)
        waitOverlay?.removeCallbacks(progressTicker)
        if (serviceMessage != null) {
            serviceMessage = null
            PdfProcessingForegroundService.stop(this)
        }
        blockedContent.clear()
        focusBeforeNavigation = null
        window.decorView.removeCallbacks(recreateWhenIdle)
        window.decorView.removeCallbacks(finishAfterDeferLimit)
        super.onDestroy()
    }

    private data class BlockedContentState(val view: ViewGroup, val accessibility: Int, val focusability: Int)

    private companion object {
        const val STATE_PENDING_RECREATION = "pdf_pending_recreation"
        const val WAIT_REVEAL_MS = 300L
        const val FINISH_DEFER_LIMIT_MS = 10_000L
        const val PROGRESS_TICK_MS = 500L
        const val PROGRESS_REVEAL_MS = 5_000L
        const val PROGRESS_IDLE_MS = 1_000L
        const val PROGRESS_NOTIFY_MS = 1_000L
    }
}
