package hu.reelee81.reepdf.core

object IncomingFileKind {
    fun looksLikePdf(mimeLc: String?, nameLc: String): Boolean {
        val explicitNonPdf =
            (mimeLc?.startsWith("image/") ?: false) ||
                    (mimeLc?.startsWith("text/")  ?: false) ||
                    (mimeLc?.startsWith("audio/") ?: false) ||
                    (mimeLc?.startsWith("video/") ?: false)
        if (explicitNonPdf) return false

        val isPdfMime = mimeLc?.contains("pdf") ?: false
        val isPdfName = nameLc.endsWith(".pdf")

        val isUnknownApp = when (mimeLc) {
            null -> true
            "application/octet-stream" -> true
            else -> mimeLc.startsWith("application/")
        }

        return isPdfMime || isPdfName || isUnknownApp
    }

    fun isSupportedImageMime(mtLc: String?): Boolean {
        return when (mtLc) {
            "image/jpeg", "image/jpg", "image/png" -> true
            else -> false
        }
    }

    fun isSupportedImageFileName(nameLc: String): Boolean {
        return nameLc.endsWith(".jpg") ||
                nameLc.endsWith(".jpeg") ||
                nameLc.endsWith(".png")
    }
}
