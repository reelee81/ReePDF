package hu.reelee81.reepdf.viewer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

class ReeColorsTest {
    @Test
    fun lightColorsMatchTheAppColorFiles() {
        assertEquals(appColors("values", "color"), kotlinColors(ReeColors.light))
    }

    @Test
    fun darkColorsMatchTheAppNightColorFiles() {
        assertEquals(appColors("values-night", "color-night"), kotlinColors(ReeColors.dark))
    }

    @Test
    fun stateRulesApplyInOrderBeforeTheDefault() {
        val color = ReeColors.light.edittextBgColor
        assertEquals(ReePalette.light.darkLight000, color.of())
        assertEquals(ReePalette.light.teal100, color.of(ReeColorState.FOCUSED))
        assertEquals(ReePalette.light.darkLight065, color.of(ReeColorState.FOCUSED, ReeColorState.DISABLED))
    }

    private fun kotlinColors(colors: ReeColors): Map<String, List<String>> =
        ReeColors::class.java.declaredMethods
            .filter { it.parameterCount == 0 && it.returnType == ReeColor::class.java && it.name.startsWith("get") }
            .associate { method ->
                val color = method.invoke(colors) as ReeColor
                snakeCase(method.name.removePrefix("get")) to
                    color.rules.map { (state, value) -> "$state:${hex(value)}" } + hex(color.default)
            }

    private fun appColors(valuesFolder: String, colorFolder: String): Map<String, List<String>> {
        val palette = palette(valuesFolder)
        val files = File("../app/src/main/res/$colorFolder").listFiles { file -> file.extension == "xml" }.orEmpty()
        return files.associate { file -> file.nameWithoutExtension to selector(file, palette) }
    }

    private fun selector(file: File, palette: Map<String, Long>): List<String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("item")
        return (0 until nodes.length).map { index ->
            val item = nodes.item(index) as Element
            val base = palette.getValue(item.getAttribute("android:color").removePrefix("@color/"))
            val alpha = item.getAttribute("android:alpha").takeIf { it.isNotEmpty() }?.toFloat()
            val argb = if (alpha == null) base else {
                val modulated = ((base ushr 24) * alpha + 0.5f).toLong()
                (modulated shl 24) or (base and 0xFFFFFFL)
            }
            val states = (0 until item.attributes.length).map { item.attributes.item(it) }
                .filter { it.nodeName != "android:color" && it.nodeName != "android:alpha" }
                .map { STATES.getValue("${it.nodeName}=${it.nodeValue}") }
            states.joinToString("") { "$it:" } + "#%08X".format(argb)
        }
    }

    private fun palette(folder: String): Map<String, Long> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("../app/src/main/res/$folder/colors.xml"))
            .getElementsByTagName("color")
        return (0 until nodes.length).associate { index ->
            val element = nodes.item(index) as Element
            element.getAttribute("name") to element.textContent.trim().removePrefix("#").toLong(16)
        }
    }

    private fun hex(color: Color): String = "#%08X".format(color.toArgb())

    private fun snakeCase(name: String): String =
        name.replaceFirstChar { it.lowercase() }.replace(Regex("[A-Z]")) { "_" + it.value.lowercase() }

    private companion object {
        val STATES = mapOf(
            "android:state_enabled=false" to ReeColorState.DISABLED,
            "android:state_checked=true" to ReeColorState.CHECKED,
            "android:state_focused=true" to ReeColorState.FOCUSED,
            "android:state_pressed=true" to ReeColorState.PRESSED,
            "android:state_selected=true" to ReeColorState.SELECTED
        )
    }
}
