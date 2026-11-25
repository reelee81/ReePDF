package hu.reelee81.reepdf

object FieldAbovePanel {
    fun scrollFor(fieldTop: Int, fieldBottom: Int, panelTop: Int, panelBottom: Int): Int =
        if (fieldBottom > panelTop && fieldTop < panelBottom) fieldBottom - panelTop else 0
}
