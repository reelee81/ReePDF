package hu.reelee81.reepdf

import android.content.Context
import androidx.annotation.WorkerThread
import hu.reelee81.reepdf.core.EditJournalFile
import hu.reelee81.reepdf.viewer.ViewerEditJournal
import hu.reelee81.reepdf.viewer.ViewerEditJournalFormat
import hu.reelee81.reepdf.viewer.ViewerEditJournalRead
import hu.reelee81.reepdf.viewer.ViewerEditRecord
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AndroidEditJournal private constructor(private val file: EditJournalFile) : ViewerEditJournal {
    fun begin(identity: String) = file.begin(ViewerEditJournalFormat.header(identity))

    override fun append(record: ViewerEditRecord) = file.append { ViewerEditJournalFormat.encode(record) }

    override fun rewrite(records: List<ViewerEditRecord>) = file.rewrite { records.asSequence().map(ViewerEditJournalFormat::encode) }

    override fun discard() = file.discard()

    suspend fun read(identity: String): ViewerEditJournalRead =
        file.read { input -> ViewerEditJournalFormat.read(input::read, identity) } ?: ViewerEditJournalRead.None

    companion object {
        private const val NAME = "viewer_edits.journal"

        @Volatile
        private var instance: AndroidEditJournal? = null

        fun of(context: Context): AndroidEditJournal {
            val app = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: AndroidEditJournal(EditJournalFile(CoroutineScope(SupervisorJob() + Dispatchers.IO)) { File(app.cacheDir, NAME) })
                    .also { instance = it }
            }
        }

        @WorkerThread
        fun identity(file: File): String = "${file.absolutePath}\n${file.length()}\n${file.lastModified()}"
    }
}
