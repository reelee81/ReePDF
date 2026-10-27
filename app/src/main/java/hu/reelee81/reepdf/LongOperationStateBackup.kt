package hu.reelee81.reepdf

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.recyclerview.widget.RecyclerView
import hu.reelee81.reepdf.core.NupConfig

class LongOperationStateBackup(private val prefs: SharedPreferences, private val lock: Any) {
    class Snapshot(
        val selected: Set<Int>,
        val thumbFirst: Int,
        val thumbOffset: Int,
        val rows: String,
        val columns: String,
        val margin: String,
        val isPortrait: Boolean,
        val drawFrame: Boolean,
        val scale: Boolean,
        val fit: Boolean,
        val rotationCsv: String,
        val positionCsv: String,
        val defaultPosition: Int,
        val crop: String
    )

    fun saveSelection(selectedCsv: String, firstPos: Int, topOffset: Int) {
        synchronized(lock) {
            prefs.edit {
                remove(KEY_BACKUP_SELECTED)
                remove(KEY_BACKUP_THUMB_FIRST)
                remove(KEY_BACKUP_THUMB_OFFSET)

                putString(KEY_BACKUP_SELECTED, selectedCsv)
                putInt(KEY_BACKUP_THUMB_FIRST, firstPos)
                putInt(KEY_BACKUP_THUMB_OFFSET, topOffset)
            }
        }
    }

    fun saveLayout(cfg: NupConfig, rotationCsv: String, positionCsv: String, defaultPosition: Int, crop: String) {
        synchronized(lock) {
            prefs.edit {
                remove(KEY_BACKUP_ROWS)
                remove(KEY_BACKUP_COLUMNS)
                remove(KEY_BACKUP_MARGIN)
                remove(KEY_BACKUP_ORIENTATION)
                remove(KEY_BACKUP_DRAW_FRAME)
                remove(KEY_BACKUP_SCALE)
                remove(KEY_BACKUP_FIT)
                remove(KEY_BACKUP_ROTATION)
                remove(KEY_BACKUP_POSITION)
                remove(KEY_BACKUP_DEFAULT_POSITION)
                remove(KEY_BACKUP_CROP)

                putString(KEY_BACKUP_ROWS, cfg.rows.toString())
                putString(KEY_BACKUP_COLUMNS, cfg.cols.toString())
                putString(KEY_BACKUP_MARGIN, cfg.marginMm.toString())
                putBoolean(KEY_BACKUP_ORIENTATION, !cfg.isLandscape)
                putBoolean(KEY_BACKUP_DRAW_FRAME, cfg.drawFrame)
                putBoolean(KEY_BACKUP_SCALE, cfg.scaleIndividually)
                putBoolean(KEY_BACKUP_FIT, cfg.fitIndividually)
                putString(KEY_BACKUP_ROTATION, rotationCsv)
                putString(KEY_BACKUP_POSITION, positionCsv)
                putInt(KEY_BACKUP_DEFAULT_POSITION, defaultPosition)
                putString(KEY_BACKUP_CROP, crop)
            }
        }
    }

    fun read(defaultPosition: Int): Snapshot =
        synchronized(lock) {
            Snapshot(
                selected = prefs.getString(KEY_BACKUP_SELECTED, "")
                    ?.split(',')
                    ?.mapNotNull { it.toIntOrNull() }
                    ?.toSet()
                    ?: emptySet(),
                thumbFirst = prefs.getInt(KEY_BACKUP_THUMB_FIRST, RecyclerView.NO_POSITION),
                thumbOffset = prefs.getInt(KEY_BACKUP_THUMB_OFFSET, 0),
                rows = prefs.getString(KEY_BACKUP_ROWS, "1") ?: "1",
                columns = prefs.getString(KEY_BACKUP_COLUMNS, "1") ?: "1",
                margin = prefs.getString(KEY_BACKUP_MARGIN, "0") ?: "0",
                isPortrait = prefs.getBoolean(KEY_BACKUP_ORIENTATION, true),
                drawFrame = prefs.getBoolean(KEY_BACKUP_DRAW_FRAME, false),
                scale = prefs.getBoolean(KEY_BACKUP_SCALE, false),
                fit = prefs.getBoolean(KEY_BACKUP_FIT, false),
                rotationCsv = prefs.getString(KEY_BACKUP_ROTATION, "") ?: "",
                positionCsv = prefs.getString(KEY_BACKUP_POSITION, "") ?: "",
                defaultPosition = prefs.getInt(KEY_BACKUP_DEFAULT_POSITION, defaultPosition),
                crop = prefs.getString(KEY_BACKUP_CROP, "") ?: ""
            )
        }

    fun clear() {
        synchronized(lock) {
            prefs.edit {
                remove(KEY_BACKUP_SELECTED)
                remove(KEY_BACKUP_THUMB_FIRST)
                remove(KEY_BACKUP_THUMB_OFFSET)
                remove(KEY_BACKUP_ROWS)
                remove(KEY_BACKUP_COLUMNS)
                remove(KEY_BACKUP_MARGIN)
                remove(KEY_BACKUP_ORIENTATION)
                remove(KEY_BACKUP_DRAW_FRAME)
                remove(KEY_BACKUP_SCALE)
                remove(KEY_BACKUP_FIT)
                remove(KEY_BACKUP_ROTATION)
                remove(KEY_BACKUP_POSITION)
                remove(KEY_BACKUP_DEFAULT_POSITION)
                remove(KEY_BACKUP_CROP)
            }
        }
    }

    private companion object {
        const val KEY_BACKUP_SELECTED = "backup_selected"
        const val KEY_BACKUP_THUMB_FIRST = "backup_thumb_first"
        const val KEY_BACKUP_THUMB_OFFSET = "backup_thumb_offset"
        const val KEY_BACKUP_ROWS = "backup_rows"
        const val KEY_BACKUP_COLUMNS = "backup_columns"
        const val KEY_BACKUP_MARGIN = "backup_margin"
        const val KEY_BACKUP_ORIENTATION = "backup_orientation"
        const val KEY_BACKUP_DRAW_FRAME = "backup_draw_frame"
        const val KEY_BACKUP_SCALE = "backup_scale"
        const val KEY_BACKUP_FIT = "backup_fit"
        const val KEY_BACKUP_ROTATION = "backup_rotation"
        const val KEY_BACKUP_POSITION = "backup_position"
        const val KEY_BACKUP_DEFAULT_POSITION = "backup_default_position"
        const val KEY_BACKUP_CROP = "backup_crop"
    }
}
