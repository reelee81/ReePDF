package hu.reelee81.reepdf.pdf

import com.artifex.mupdf.fitz.Context
import com.artifex.mupdf.fitz.Cookie
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.StructuredText
import com.artifex.mupdf.fitz.StructuredTextWalker
import java.io.IOException

class MuPdfViewerEngine private constructor(
    private val document: Document,
    override val pageCount: Int,
    private val emptiesStoreOnClose: Boolean
) : PdfViewerEngine<PdfRasterTarget> {
    private val pdf = document as? PDFDocument
    private val texts = LinkedHashMap<Int, PageText>(TEXT_PAGES + 1, 0.75f, true)

    override fun pageSize(pageIndex: Int): PdfViewerEngine.PageSize {
        val page = document.loadPage(pageIndex)
        try {
            val bounds = page.bounds
            return PdfViewerEngine.PageSize(bounds.x1 - bounds.x0, bounds.y1 - bounds.y0)
        } finally {
            page.destroy()
        }
    }

    override fun render(
        pageIndex: Int,
        zoom: Float,
        left: Int,
        top: Int,
        target: PdfRasterTarget,
        abort: PdfRenderAbort
    ) {
        val page = document.loadPage(pageIndex)
        try {
            val bounds = page.bounds
            val ctm = Matrix(zoom, 0f, 0f, zoom, -bounds.x0 * zoom, -bounds.y0 * zoom)
            val device = target.whiteDevice(left, top)
            try {
                val cookie = Cookie()
                try {
                    abort.whileBound(cookie::abort) { page.run(device, ctm, cookie) }
                } finally {
                    cookie.destroy()
                }
                device.close()
            } finally {
                device.destroy()
            }
        } finally {
            page.destroy()
        }
    }

    override fun search(pageIndex: Int, needle: String): PdfTextHits {
        val page = document.loadPage(pageIndex)
        try {
            val bounds = page.bounds
            val hits = page.search(needle, StructuredText.SEARCH_IGNORE_CASE) ?: return PdfTextHits.NONE
            val starts = IntArray(hits.size + 1)
            var count = 0
            hits.forEachIndexed { index, hit ->
                starts[index] = count
                count += hit.size
            }
            starts[hits.size] = count
            val rects = FloatArray(count * PdfTextHits.RECT_SIZE)
            var next = 0
            for (hit in hits) {
                for (quad in hit) PdfTextQuads.put(rects, next++, quad, bounds.x0, bounds.y0)
            }
            return PdfTextHits(rects, starts)
        } finally {
            page.destroy()
        }
    }

    override fun count(pageIndex: Int, needle: String): Int {
        val page = document.loadPage(pageIndex)
        try {
            return page.search(needle, StructuredText.SEARCH_IGNORE_CASE)?.size ?: 0
        } finally {
            page.destroy()
        }
    }

    override fun word(pageIndex: Int, point: PdfTextPoint, tolerance: Float): PdfTextSpan? {
        val page = pageText(pageIndex)
        val x = point.x + page.left
        val y = point.y + page.top
        val word = page.letters.word(x, y, tolerance) ?: return null
        val (from, to) = word.anchors({ a, b -> page.text.copy(a, b) }) {
            val a = Point(x, y)
            val b = Point(x, y)
            page.text.snapSelection(a, b, StructuredText.SELECT_WORDS)?.let { a to b }
        }
        val quads = page.text.highlight(from, to) ?: return null
        if (quads.none { word.touches(it, tolerance) }) return null
        val rects = rects(page, quads)
        if (rects.isEmpty()) return null
        return PdfTextSpan(page.relative(from), page.relative(to), rects)
    }

    override fun caret(pageIndex: Int, point: PdfTextPoint): PdfTextPoint? {
        val page = pageText(pageIndex)
        val finger = Point(point.x + page.left, point.y + page.top)
        val caret = page.letters.caret(finger.x, finger.y) ?: return null
        val chosen = caret.choose(finger) { candidate ->
            val handles = page.text.snapSelection(
                Point(candidate.x, candidate.y),
                Point(candidate.x, candidate.y),
                StructuredText.SELECT_CHARS
            )
            handles != null && page.letters.snapsTo(caret.index, handles, candidate)
        }
        return page.relative(chosen)
    }

    override fun highlight(pageIndex: Int, from: PdfTextPoint?, to: PdfTextPoint?): FloatArray {
        val page = pageText(pageIndex)
        val ends = page.letters.ends() ?: return FloatArray(0)
        return rects(page, page.text.highlight(page.absolute(from) ?: ends.first, page.absolute(to) ?: ends.second))
    }

    override fun copy(pageIndex: Int, from: PdfTextPoint?, to: PdfTextPoint?): String {
        val page = pageText(pageIndex)
        val ends = page.letters.ends() ?: return ""
        return page.text.copy(page.absolute(from) ?: ends.first, page.absolute(to) ?: ends.second) ?: ""
    }

    override fun text(pageIndex: Int): String {
        val page = document.loadPage(pageIndex)
        try {
            val text = page.toStructuredText()
            try {
                return text.asText() ?: ""
            } finally {
                text.destroy()
            }
        } finally {
            page.destroy()
        }
    }

    override fun emptyStore() {
        dropTexts()
        Context.emptyStore()
    }

    override fun dropLoadedObjects() {
        pdf?.takeUnless { it.wasRepaired() }?.clearXref()
    }

    override fun releaseFreeMemory() {
        Context.releaseFreeMemory()
    }

    override fun close() {
        dropTexts()
        document.destroy()
        if (emptiesStoreOnClose) Context.emptyStore()
        Context.releaseFreeMemory()
    }

    private fun pageText(pageIndex: Int): PageText {
        texts[pageIndex]?.let { return it }
        val page = document.loadPage(pageIndex)
        val created = try {
            val bounds = page.bounds
            PageText(page.toStructuredText(), bounds.x0, bounds.y0)
        } finally {
            page.destroy()
        }
        texts[pageIndex] = created
        if (texts.size > TEXT_PAGES) {
            val eldest = texts.entries.first()
            texts.remove(eldest.key)
            eldest.value.text.destroy()
        }
        return created
    }

    private fun dropTexts() {
        texts.values.forEach { it.text.destroy() }
        texts.clear()
    }

    private fun rects(page: PageText, quads: Array<Quad>?): FloatArray {
        if (quads == null) return FloatArray(0)
        val rects = FloatArray(quads.size * PdfTextHits.RECT_SIZE)
        quads.forEachIndexed { index, quad -> PdfTextQuads.put(rects, index, quad, page.left, page.top) }
        return rects
    }

    private class PageText(val text: StructuredText, val left: Float, val top: Float) {
        val letters: PdfTextLetters by lazy(LazyThreadSafetyMode.NONE) { readLetters() }

        fun absolute(point: PdfTextPoint?): Point? = point?.let { Point(it.x + left, it.y + top) }

        fun relative(point: Point) = PdfTextPoint(point.x - left, point.y - top)

        private fun readLetters(): PdfTextLetters {
            val builder = PdfTextLetters.Builder()
            text.walk(LetterWalker(builder))
            return builder.build()
        }
    }

    private class LetterWalker(private val letters: PdfTextLetters.Builder) : StructuredTextWalker {
        override fun onImageBlock(bbox: Rect?, transform: Matrix?, image: Image?) {
            image?.destroy()
        }

        override fun beginTextBlock(bbox: Rect?, flags: Int) = Unit

        override fun endTextBlock() = Unit

        override fun beginLine(bbox: Rect?, wmode: Int, dir: Point) {
            letters.startLine(dir.x, dir.y)
        }

        override fun endLine() {
            letters.endLine()
        }

        override fun onChar(c: Int, origin: Point?, font: Font?, size: Float, q: Quad, argb: Int, flags: Int, bidi: Int) {
            font?.destroy()
            letters.add(c, q, size, (bidi and 1) != 0)
        }

        override fun beginStruct(standard: String?, raw: String?, index: Int) = Unit

        override fun endStruct() = Unit

        override fun onVector(bbox: Rect?, info: StructuredTextWalker.VectorInfo?, argb: Int) = Unit
    }

    companion object {
        private const val TEXT_PAGES = 2

        fun open(path: String, pageTreeCache: Boolean = true, emptiesStoreOnClose: Boolean = true): MuPdfViewerEngine {
            val document = Document.openDocument(path)
            try {
                if (document.needsPassword()) throw IOException("The document needs a password")
                if (!pageTreeCache) (document as? PDFDocument)?.setPageTreeCache(false)
                return MuPdfViewerEngine(document, document.countPages(), emptiesStoreOnClose)
            } catch (e: Throwable) {
                document.destroy()
                releaseMemory()
                throw e
            }
        }

        private fun releaseMemory() {
            Context.emptyStore()
            Context.releaseFreeMemory()
        }
    }
}
