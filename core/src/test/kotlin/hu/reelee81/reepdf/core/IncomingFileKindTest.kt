package hu.reelee81.reepdf.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingFileKindTest {
    @Test
    fun aPdfTypeOrAPdfNameIsAccepted() {
        assertTrue(IncomingFileKind.looksLikePdf("application/pdf", "document"))
        assertTrue(IncomingFileKind.looksLikePdf("application/x-pdf", "document"))
        assertTrue(IncomingFileKind.looksLikePdf("binary/octet-stream", "invoice.pdf"))
    }

    @Test
    fun aMissingOrGenericApplicationTypeIsTriedAsPdf() {
        assertTrue(IncomingFileKind.looksLikePdf(null, ""))
        assertTrue(IncomingFileKind.looksLikePdf(null, "attachment"))
        assertTrue(IncomingFileKind.looksLikePdf("application/octet-stream", "download"))
        assertTrue(IncomingFileKind.looksLikePdf("application/x-download", "download"))
        assertTrue(IncomingFileKind.looksLikePdf("application/zip", "archive.zip"))
        assertTrue(
            IncomingFileKind.looksLikePdf(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "letter.docx"
            )
        )
    }

    @Test
    fun imageTextAudioAndVideoTypesAreNeverPdfEvenWithAPdfName() {
        assertFalse(IncomingFileKind.looksLikePdf("image/png", "scan.png"))
        assertFalse(IncomingFileKind.looksLikePdf("image/jpeg", "scan.pdf"))
        assertFalse(IncomingFileKind.looksLikePdf("text/plain", "notes.pdf"))
        assertFalse(IncomingFileKind.looksLikePdf("audio/mpeg", "song.mp3"))
        assertFalse(IncomingFileKind.looksLikePdf("video/mp4", "clip.mp4"))
    }

    @Test
    fun anOtherTypeWithoutAPdfNameIsNotPdf() {
        assertFalse(IncomingFileKind.looksLikePdf("binary/octet-stream", "data.bin"))
    }

    @Test
    fun onlyJpegAndPngImageTypesAreSupported() {
        assertTrue(IncomingFileKind.isSupportedImageMime("image/jpeg"))
        assertTrue(IncomingFileKind.isSupportedImageMime("image/jpg"))
        assertTrue(IncomingFileKind.isSupportedImageMime("image/png"))
        assertFalse(IncomingFileKind.isSupportedImageMime("image/webp"))
        assertFalse(IncomingFileKind.isSupportedImageMime("image/PNG"))
        assertFalse(IncomingFileKind.isSupportedImageMime(null))
    }

    @Test
    fun onlyLowerCaseJpegAndPngFileNamesAreSupported() {
        assertTrue(IncomingFileKind.isSupportedImageFileName("photo.jpg"))
        assertTrue(IncomingFileKind.isSupportedImageFileName("photo.jpeg"))
        assertTrue(IncomingFileKind.isSupportedImageFileName("photo.png"))
        assertFalse(IncomingFileKind.isSupportedImageFileName("photo.webp"))
        assertFalse(IncomingFileKind.isSupportedImageFileName("photo.PNG"))
        assertFalse(IncomingFileKind.isSupportedImageFileName("png"))
    }
}
