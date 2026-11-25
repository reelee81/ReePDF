package hu.reelee81.reepdf.viewer

import java.text.Normalizer

internal actual fun composedText(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
