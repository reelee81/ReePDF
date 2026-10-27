package hu.reelee81.reepdf

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import hu.reelee81.reepdf.core.PdfOperationRunner
import hu.reelee81.reepdf.core.PdfOperationState
import hu.reelee81.reepdf.core.ProgressNotifyPacing
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PdfProcessingForegroundService : Service() {

    companion object {
        private const val EXTRA_MESSAGE = "EXTRA_MESSAGE"
        private const val EXTRA_PROGRESS = "EXTRA_PROGRESS"
        private const val ACTION_OPERATION = "hu.reelee81.reepdf.action.OPERATION"
        const val PROGRESS_NONE = -2
        const val PROGRESS_INDETERMINATE = -1

        fun start(context: Context, message: String, progress: Int = PROGRESS_NONE) {
            if (PdfOperations.runner(context).state.value.isBusy) return
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, PdfProcessingForegroundService::class.java).apply {
                putExtra(EXTRA_MESSAGE, message)
                putExtra(EXTRA_PROGRESS, progress)
            }
            send(appCtx, intent)
        }

        fun startForOperation(context: Context) {
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, PdfProcessingForegroundService::class.java).setAction(ACTION_OPERATION)
            send(appCtx, intent)
        }

        fun stop(context: Context) {
            if (PdfOperations.runner(context).isHolding) return
            stopForOperation(context)
        }

        fun stopForOperation(context: Context) {
            val intent = Intent(context, PdfProcessingForegroundService::class.java)
            context.stopService(intent)
        }

        private fun send(appCtx: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    appCtx.startService(intent)
                } catch (_: IllegalStateException) {
                    ContextCompat.startForegroundService(appCtx, intent)
                }
            } else {
                appCtx.startService(intent)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    override fun onCreate() {
        super.onCreate()
        PdfProcessingNotifications.createChannelIfNeeded(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_OPERATION) {
            val runner = PdfOperations.runner(this)
            val shown = showInForeground(
                PdfProcessingNotifications.build(this, messageFor(runner.state.value), PROGRESS_NONE)
            )
            if (shown && observer == null) {
                observer = scope.launch { observe(runner) }
            }
            return START_NOT_STICKY
        }

        val message = intent?.getStringExtra(EXTRA_MESSAGE) ?: getString(R.string.in_progress_my)
        val progress = intent?.getIntExtra(EXTRA_PROGRESS, PROGRESS_NONE) ?: PROGRESS_NONE
        showInForeground(PdfProcessingNotifications.build(this, message, progress))

        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val runner = PdfOperations.runner(this)
        val state = runner.state.value
        val request = state.request
        if (state.isBusy && request != null) {
            runner.cancel(request.id)
        } else if (request != null) {
            runner.abandon(request.id)
        }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showInForeground(notification: Notification): Boolean =
        try {
            startForeground(PdfProcessingNotifications.NOTIFICATION_ID, notification)
            true
        } catch (_: IllegalStateException) {
            stopSelf()
            false
        }

    private fun messageFor(state: PdfOperationState): String =
        when (state) {
            is PdfOperationState.Cancelling -> getString(R.string.in_progress_ui)
            else -> state.request?.message?.ifBlank { null } ?: getString(R.string.in_progress_my)
        }

    private suspend fun observe(runner: PdfOperationRunner) {
        var phase: Pair<Long, Boolean>? = null
        var phaseStartedAt = 0L
        var notified = PROGRESS_NONE
        var notifiedAt = 0L

        runner.state.collectLatest { state ->
            val request = state.request
            if (!state.isBusy || request == null) return@collectLatest

            val message = messageFor(state)
            val current = request.id to (state is PdfOperationState.Cancelling)
            if (current != phase) {
                phase = current
                phaseStartedAt = SystemClock.uptimeMillis()
                notified = PROGRESS_NONE
                notifiedAt = phaseStartedAt
                showInForeground(PdfProcessingNotifications.build(this, message, PROGRESS_NONE))
            }

            val percent = (state as? PdfOperationState.Running)?.percent ?: PROGRESS_INDETERMINATE
            val wait = ProgressNotifyPacing.waitMs(
                phaseStartedAt, notified, notifiedAt, percent, SystemClock.uptimeMillis()
            ) ?: return@collectLatest
            if (wait > 0) delay(wait.milliseconds)

            notified = percent
            notifiedAt = SystemClock.uptimeMillis()
            showInForeground(PdfProcessingNotifications.build(this, message, percent))
        }
    }
}
