package hu.reelee81.reepdf

import android.content.SharedPreferences
import hu.reelee81.reepdf.core.NupConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongOperationStateBackupTest {
    private val prefs = MemoryPreferences()
    private val backup = LongOperationStateBackup(prefs, Any())

    @Test
    fun theLayoutAndSelectionAreStoredUnderTheExistingKeys() {
        backup.saveSelection("1,3", 7, -12)
        backup.saveLayout(NupConfig(2, 3, 5, isLandscape = true, drawFrame = true, scaleIndividually = true, fitIndividually = false), "0,1", "5,9", 4, "0:0.1,0.2,0.9,0.8")

        assertEquals(
            mapOf(
                "backup_selected" to "1,3",
                "backup_thumb_first" to 7,
                "backup_thumb_offset" to -12,
                "backup_rows" to "2",
                "backup_columns" to "3",
                "backup_margin" to "5",
                "backup_orientation" to false,
                "backup_draw_frame" to true,
                "backup_scale" to true,
                "backup_fit" to false,
                "backup_rotation" to "0,1",
                "backup_position" to "5,9",
                "backup_default_position" to 4,
                "backup_crop" to "0:0.1,0.2,0.9,0.8"
            ),
            prefs.values
        )
    }

    @Test
    fun aSavedStateIsReadBack() {
        backup.saveSelection("1,x,3", 7, -12)
        backup.saveLayout(NupConfig(2, 3, 5, isLandscape = true, drawFrame = true, scaleIndividually = true, fitIndividually = true), "0,1", "5,9", 4, "crop")

        val saved = backup.read(defaultPosition = 6)

        assertEquals(setOf(1, 3), saved.selected)
        assertEquals(7, saved.thumbFirst)
        assertEquals(-12, saved.thumbOffset)
        assertEquals("2", saved.rows)
        assertEquals("3", saved.columns)
        assertEquals("5", saved.margin)
        assertFalse(saved.isPortrait)
        assertTrue(saved.drawFrame)
        assertTrue(saved.scale)
        assertTrue(saved.fit)
        assertEquals("0,1", saved.rotationCsv)
        assertEquals("5,9", saved.positionCsv)
        assertEquals(4, saved.defaultPosition)
        assertEquals("crop", saved.crop)
    }

    @Test
    fun anEmptyStoreGivesTheDefaults() {
        val saved = backup.read(defaultPosition = 6)

        assertEquals(emptySet<Int>(), saved.selected)
        assertEquals(-1, saved.thumbFirst)
        assertEquals(0, saved.thumbOffset)
        assertEquals("1", saved.rows)
        assertEquals("1", saved.columns)
        assertEquals("0", saved.margin)
        assertTrue(saved.isPortrait)
        assertFalse(saved.drawFrame)
        assertFalse(saved.scale)
        assertFalse(saved.fit)
        assertEquals("", saved.rotationCsv)
        assertEquals("", saved.positionCsv)
        assertEquals(6, saved.defaultPosition)
        assertEquals("", saved.crop)
    }

    @Test
    fun clearingRemovesOnlyTheBackupKeys() {
        prefs.values["rows"] = "4"
        backup.saveSelection("1", 0, 0)
        backup.saveLayout(NupConfig(1, 1, 0, isLandscape = false, drawFrame = false, scaleIndividually = false, fitIndividually = false), "", "", 5, "")

        backup.clear()

        assertEquals(mapOf<String, Any?>("rows" to "4"), prefs.values)
    }

    private class MemoryPreferences : SharedPreferences {
        val values = HashMap<String, Any?>()

        override fun getAll(): Map<String, *> = HashMap(values)
        override fun getString(key: String, defValue: String?): String? = values[key] as String? ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as Int? ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as Long? ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as Float? ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as Boolean? ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val changes = LinkedHashMap<String, Any?>()
            private val removals = HashSet<String>()

            override fun putString(key: String, value: String?): SharedPreferences.Editor = put(key, value)
            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = put(key, values)
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = put(key, value)
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = put(key, value)
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = put(key, value)
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = put(key, value)

            override fun remove(key: String): SharedPreferences.Editor {
                removals += key
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                removals += values.keys
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                for (key in removals) values.remove(key)
                values.putAll(changes)
            }

            private fun put(key: String, value: Any?): SharedPreferences.Editor {
                changes[key] = value
                return this
            }
        }
    }
}
