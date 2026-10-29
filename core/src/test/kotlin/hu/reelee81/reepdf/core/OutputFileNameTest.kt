package hu.reelee81.reepdf.core

import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class OutputFileNameTest {
    private val now: Date = GregorianCalendar(2026, Calendar.MARCH, 7, 21, 5, 4).time
    private val locale = Locale.ROOT

    @Test
    fun allTimeTokensAreReplacedAndTheHourUsesTwentyFourHours() {
        assertEquals(
            "2026-26-03-07_21-05-04",
            OutputFileName.replaceTimeTokens("[YYYY]-[YY]-[MM]-[DD]_[hh]-[mm]-[ss]", now, locale)
        )
    }

    @Test
    fun theFourDigitYearIsNotSplitIntoTwoShortYears() {
        assertEquals("2026", OutputFileName.replaceTimeTokens("[YYYY]", now, locale))
        assertEquals("26", OutputFileName.replaceTimeTokens("[YY]", now, locale))
    }

    @Test
    fun unknownOrDifferentlyCasedTokensStayUnchanged() {
        assertEquals(
            "Custom [yyyy] [dd] [HH] [M]",
            OutputFileName.replaceTimeTokens("Custom [yyyy] [dd] [HH] [M]", now, locale)
        )
        assertEquals("Custom_03_05", OutputFileName.replaceTimeTokens("Custom_[MM]_[mm]", now, locale))
    }

    @Test
    fun theDefaultDateHasNoSeparators() {
        assertEquals("20260307", OutputFileName.defaultDate(now, locale))
    }

    @Test
    fun forbiddenAndControlCharactersBecomeUnderscores() {
        assertEquals(
            "a_b_c_d_e_f_g_h_i_j",
            OutputFileName.sanitizeBase("a/b:c*d?e\"f<g>h|i\\j", now, locale)
        )
        assertEquals("_x_y", OutputFileName.sanitizeBase("\u0001x\ty", now, locale))
    }

    @Test
    fun trailingDotsAndSpacesAreRemoved() {
        assertEquals("name", OutputFileName.sanitizeBase("name. . ", now, locale))
        assertEquals(" lead.ing", OutputFileName.sanitizeBase(" lead.ing", now, locale))
        assertEquals("_", OutputFileName.sanitizeBase("...", now, locale))
        assertEquals("_", OutputFileName.sanitizeBase(". .", now, locale))
    }

    @Test
    fun aBlankNameBecomesTheDefaultDate() {
        assertEquals("20260307", OutputFileName.sanitizeBase("", now, locale))
        assertEquals("20260307", OutputFileName.sanitizeBase("   ", now, locale))
    }

    @Test
    fun reservedDeviceNamesGetAPrefixInAnyCase() {
        assertEquals("_con", OutputFileName.sanitizeBase("con", now, locale))
        assertEquals("_LPT9", OutputFileName.sanitizeBase("LPT9", now, locale))
        assertEquals("_nul", OutputFileName.sanitizeBase("nul.", now, locale))
        assertEquals("COM10", OutputFileName.sanitizeBase("COM10", now, locale))
        assertEquals("CONsole", OutputFileName.sanitizeBase("CONsole", now, locale))
    }

    @Test
    fun longNamesAreCutTo251Characters() {
        val result = OutputFileName.sanitizeBase("a".repeat(300), now, locale)
        assertEquals("a".repeat(251), result)
        assertEquals("_" + "b".repeat(250), OutputFileName.sanitizeBase("/" + "b".repeat(300), now, locale))
    }

    @Test
    fun accentedLettersAreKept() {
        assertEquals("Számla őszi ÁRVÍZTŰRŐ", OutputFileName.sanitizeBase("Számla őszi ÁRVÍZTŰRŐ", now, locale))
    }
}
