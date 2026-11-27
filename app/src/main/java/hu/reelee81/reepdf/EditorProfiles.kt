package hu.reelee81.reepdf

import android.content.Context
import android.content.SharedPreferences
import hu.reelee81.reepdf.core.EditorTransfer
import hu.reelee81.reepdf.core.EditorTransfers
import hu.reelee81.reepdf.core.VectorSignatureFiles
import hu.reelee81.reepdf.viewer.ViewerEditSettings
import hu.reelee81.reepdf.viewer.ViewerEditSettingsFormat
import hu.reelee81.reepdf.viewer.ViewerSignatureFormat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EditorProfiles private constructor(
    private val appPrefs: SharedPreferences,
    val liveSettingsPrefs: SharedPreferences,
    files: File
) {
    class Profile(val settings: String, val signatures: List<String?>)

    class Saved(val values: Map<String, *>, val editor: Profile?)

    private val lock = Mutex()
    private val liveFiles = VectorSignatureFiles(File(files, "Signatures"))
    private val profileFiles = VectorSignatureFiles(File(files, "ProfileSignatures"))
    private val transfers = EditorTransfers(File(files, "editor_transfer"), liveFiles, profileFiles, ::applySettings)

    suspend fun liveSettings(): ViewerEditSettings = locked { EditSettingsPrefs.read(liveSettingsPrefs) }

    suspend fun <T> liveSignatures(block: (VectorSignatureFiles) -> T): T = locked { block(liveFiles) }

    suspend fun saveProfile(values: Map<String, EditorTransfer.Value>) = locked {
        val settings = ViewerEditSettingsFormat.encode(EditSettingsPrefs.read(liveSettingsPrefs))
        transfers.run(EditorTransfer(EditorTransfer.Target.PROFILE, settings, liveFiles.readAll(::isSignature), values))
    }

    suspend fun loadProfile() = locked {
        val settings = appPrefs.getString(KEY, null) ?: return@locked
        transfers.run(EditorTransfer(EditorTransfer.Target.LIVE, settings, profileFiles.readAll(::isSignature)))
    }

    suspend fun <T> readProfiles(block: () -> T): T = locked(block)

    suspend fun saved(): Saved = locked {
        Saved(appPrefs.all, appPrefs.getString(KEY, null)?.let { Profile(it, profileFiles.readAll(::isSignature)) })
    }

    suspend fun importProfiles(values: Map<String, EditorTransfer.Value>, editor: Profile?) = locked {
        val signatures = editor?.signatures ?: List(VectorSignatureFiles.SLOTS) { null }
        transfers.run(EditorTransfer(EditorTransfer.Target.PROFILE, editor?.settings, signatures, values))
        if (editor != null) transfers.run(EditorTransfer(EditorTransfer.Target.LIVE, editor.settings, editor.signatures))
    }

    private suspend fun <T> locked(block: () -> T): T = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                transfers.recover()
            } catch (_: IOException) {
            }
            block()
        }
    }

    private fun applySettings(transfer: EditorTransfer) {
        val editor = when (transfer.target) {
            EditorTransfer.Target.PROFILE -> appPrefs.edit().also { changes ->
                transfer.settings?.let { changes.putString(KEY, it) }
                for ((key, value) in transfer.values) {
                    when (value) {
                        is EditorTransfer.Value.Text -> changes.putString(key, value.text)
                        is EditorTransfer.Value.Number -> changes.putInt(key, value.number)
                        is EditorTransfer.Value.Flag -> changes.putBoolean(key, value.flag)
                    }
                }
            }
            EditorTransfer.Target.LIVE -> liveSettingsPrefs.edit().also { changes ->
                val current = EditSettingsPrefs.read(liveSettingsPrefs)
                EditSettingsPrefs.put(changes, ViewerEditSettingsFormat.decode(transfer.settings.orEmpty(), current).settings)
            }
        }
        if (!editor.commit()) throw IOException("Cannot write the editor settings")
    }

    private fun isSignature(text: String): Boolean = ViewerSignatureFormat.decode(text) != null

    companion object {
        const val APP_PREFS = "ReePDFPrefs"
        const val KEY = "reeprofile_editor"

        private val creating = Mutex()

        @Volatile private var instance: EditorProfiles? = null

        suspend fun of(context: Context): EditorProfiles =
            instance ?: creating.withLock {
                instance ?: withContext(Dispatchers.IO) {
                    val app = context.applicationContext
                    EditorProfiles(
                        app.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE),
                        EditSettingsPrefs.of(app),
                        File(app.applicationInfo.dataDir, "files")
                    )
                }.also { instance = it }
            }
    }
}
