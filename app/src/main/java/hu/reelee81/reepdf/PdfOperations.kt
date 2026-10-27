package hu.reelee81.reepdf

import android.content.Context
import hu.reelee81.reepdf.core.PdfOperationKeepAlive
import hu.reelee81.reepdf.core.PdfOperationRequest
import hu.reelee81.reepdf.core.PdfOperationRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object PdfOperations {
    @Volatile private var instance: PdfOperationRunner? = null

    fun runner(context: Context): PdfOperationRunner =
        instance ?: synchronized(this) {
            instance ?: PdfOperationRunner(
                CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                ForegroundServiceKeepAlive(context.applicationContext)
            ).also { instance = it }
        }

    private class ForegroundServiceKeepAlive(private val context: Context) : PdfOperationKeepAlive {
        override fun begin(request: PdfOperationRequest) {
            PdfProcessingForegroundService.startForOperation(context)
        }

        override fun end() {
            PdfProcessingForegroundService.stopForOperation(context)
        }
    }
}
