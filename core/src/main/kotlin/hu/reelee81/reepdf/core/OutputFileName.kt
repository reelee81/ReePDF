package hu.reelee81.reepdf.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object OutputFileName {
    private const val MAX_BASE_BYTES = 251

    fun replaceTimeTokens(template: String, now: Date = Date(), locale: Locale = Locale.getDefault()): String {
        var s = template
        fun fmt(pat: String): String = SimpleDateFormat(pat, locale).format(now)
        s = s.replace("[YYYY]", fmt("yyyy"))
        s = s.replace("[YY]",   fmt("yy"))
        s = s.replace("[MM]",   fmt("MM"))
        s = s.replace("[DD]",   fmt("dd"))
        s = s.replace("[hh]",   fmt("HH"))
        s = s.replace("[mm]",   fmt("mm"))
        s = s.replace("[ss]",   fmt("ss"))
        return s
    }

    fun defaultDate(now: Date = Date(), locale: Locale = Locale.getDefault()): String =
        SimpleDateFormat("yyyyMMdd", locale).format(now)

    fun sanitizeBase(name: String, now: Date = Date(), locale: Locale = Locale.getDefault()): String {
        if (name.isBlank()) return defaultDate(now, locale)

        val cleaned = buildString(name.length) {
            for (ch in name) {
                if (ch.code in 0x00..0x1F ||
                    ch == '"' || ch == '<' || ch == '>' || ch == '|' ||
                    ch == ':' || ch == '*' || ch == '?' || ch == '/' || ch == '\\'
                ) append('_') else append(ch)
            }
        }

        var trimmed = cleaned.trimEnd { it == ' ' || it == '.' }
        if (trimmed.isEmpty()) trimmed = "_"

        if (trimmed == "." || trimmed == "..") trimmed = "_$trimmed"
        val reserved = setOf(
            "CON","PRN","AUX","NUL",
            "COM1","COM2","COM3","COM4","COM5","COM6","COM7","COM8","COM9",
            "LPT1","LPT2","LPT3","LPT4","LPT5","LPT6","LPT7","LPT8","LPT9"
        )
        if (trimmed.uppercase() in reserved) trimmed = "_$trimmed"

        return takeBaseBytes(trimmed)
    }

    private fun takeBaseBytes(text: String): String {
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val codePoint = text.codePointAt(end)
            val size = when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
            if (bytes + size > MAX_BASE_BYTES) break
            bytes += size
            end += Character.charCount(codePoint)
        }
        return text.substring(0, end)
    }
}
