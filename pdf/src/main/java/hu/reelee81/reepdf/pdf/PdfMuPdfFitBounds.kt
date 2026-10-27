package hu.reelee81.reepdf.pdf

import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Context
import com.artifex.mupdf.fitz.DefaultColorSpaces
import com.artifex.mupdf.fitz.Device
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Path
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.Shade
import com.artifex.mupdf.fitz.StrokeState
import com.artifex.mupdf.fitz.Text
import com.itextpdf.kernel.geom.Rectangle
import kotlin.math.max
import kotlin.math.min

class PdfMuPdfFitBounds(path: String) : AutoCloseable {
    private val document: Document = Document.openDocument(path)

    private val fillStroke = StrokeState(StrokeState.LINE_CAP_BUTT, StrokeState.LINE_JOIN_BEVEL, 1e-6f, 1f)

    fun contentBox(pageIndex: Int, visibleBox: Rectangle): Rectangle? {
        if (pageIndex !in 0 until document.countPages()) return null
        val page = document.loadPage(pageIndex)
        val device = VisibleBoundsDevice(fillStroke)
        var keepStore = false
        try {
            val pdfPage = page as? PDFPage ?: return null
            pdfPage.runPageContents(device, Matrix.Inverted(pdfPage.transform), null)
            keepStore = !device.hadImage
            val bounds = device.bounds ?: return null
            val left = max(bounds.x0, visibleBox.left)
            val bottom = max(bounds.y0, visibleBox.bottom)
            val right = min(bounds.x1, visibleBox.right)
            val top = min(bounds.y1, visibleBox.top)
            if (!(right > left && top > bottom)) return null
            return Rectangle(left, bottom, right - left, top - bottom)
        } finally {
            device.destroy()
            page.destroy()
            if (!keepStore) Context.emptyStore()
        }
    }

    override fun close() {
        fillStroke.destroy()
        document.destroy()
        Context.emptyStore()
    }

    private class VisibleBoundsDevice(private val fillStroke: StrokeState) : Device() {
        private val clips = ArrayList<Rect>()
        private var ignored = 0

        var bounds: Rect? = null
            private set

        var hadImage = false
            private set

        private fun add(rect: Rect?, clip: Boolean) {
            val current = clips.lastOrNull()
            if (rect == null) {
                if (clip) clips.add(current?.let(::Rect) ?: Rect.Infinite())
                return
            }
            val r = Rect(rect)
            if (current != null) {
                r.x0 = max(r.x0, current.x0)
                r.y0 = max(r.y0, current.y0)
                r.x1 = min(r.x1, current.x1)
                r.y1 = min(r.y1, current.y1)
            }
            if (clip) {
                clips.add(r)
                return
            }
            if (ignored > 0 || !r.isValid) return
            if (!r.x0.isFinite() || !r.y0.isFinite() || !r.x1.isFinite() || !r.y1.isFinite()) return
            val b = bounds
            if (b == null) {
                bounds = r
            } else {
                b.x0 = min(b.x0, r.x0)
                b.y0 = min(b.y0, r.y0)
                b.x1 = max(b.x1, r.x1)
                b.y1 = max(b.y1, r.y1)
            }
        }

        private fun mark(rect: Rect?, alpha: Float) {
            if (alpha > 0f) add(rect, false)
        }

        private fun unitRect(ctm: Matrix?): Rect? = ctm?.let { Rect(0f, 0f, 1f, 1f).transform(it) }

        private fun release(cs: ColorSpace?) {
            if (cs == null || cs === ColorSpace.DeviceGray || cs === ColorSpace.DeviceRGB ||
                cs === ColorSpace.DeviceBGR || cs === ColorSpace.DeviceCMYK
            ) return
            cs.destroy()
        }

        override fun close() {}

        override fun fillPath(path: Path?, evenOdd: Boolean, ctm: Matrix?, cs: ColorSpace?, color: FloatArray?, alpha: Float, cp: Int) {
            try {
                mark(path?.getBounds(fillStroke, ctm), alpha)
            } finally {
                path?.destroy()
                release(cs)
            }
        }

        override fun strokePath(path: Path?, stroke: StrokeState?, ctm: Matrix?, cs: ColorSpace?, color: FloatArray?, alpha: Float, cp: Int) {
            try {
                mark(path?.getBounds(stroke ?: fillStroke, ctm), alpha)
            } finally {
                path?.destroy()
                stroke?.destroy()
                release(cs)
            }
        }

        override fun clipPath(path: Path?, evenOdd: Boolean, ctm: Matrix?) {
            try {
                add(path?.getBounds(fillStroke, ctm), true)
            } finally {
                path?.destroy()
            }
        }

        override fun clipStrokePath(path: Path?, stroke: StrokeState?, ctm: Matrix?) {
            try {
                add(path?.getBounds(stroke ?: fillStroke, ctm), true)
            } finally {
                path?.destroy()
                stroke?.destroy()
            }
        }

        override fun fillText(text: Text?, ctm: Matrix?, cs: ColorSpace?, color: FloatArray?, alpha: Float, cp: Int) {
            try {
                mark(text?.getBounds(fillStroke, ctm), alpha)
            } finally {
                text?.destroy()
                release(cs)
            }
        }

        override fun strokeText(text: Text?, stroke: StrokeState?, ctm: Matrix?, cs: ColorSpace?, color: FloatArray?, alpha: Float, cp: Int) {
            try {
                mark(text?.getBounds(stroke ?: fillStroke, ctm), alpha)
            } finally {
                text?.destroy()
                stroke?.destroy()
                release(cs)
            }
        }

        override fun clipText(text: Text?, ctm: Matrix?) {
            try {
                add(text?.getBounds(fillStroke, ctm), true)
            } finally {
                text?.destroy()
            }
        }

        override fun clipStrokeText(text: Text?, stroke: StrokeState?, ctm: Matrix?) {
            try {
                add(text?.getBounds(stroke ?: fillStroke, ctm), true)
            } finally {
                text?.destroy()
                stroke?.destroy()
            }
        }

        override fun ignoreText(text: Text?, ctm: Matrix?) {
            text?.destroy()
        }

        override fun fillShade(shd: Shade?, ctm: Matrix?, alpha: Float, cp: Int) {
            try {
                mark(shd?.getBounds(ctm), alpha)
            } finally {
                shd?.destroy()
            }
        }

        override fun fillImage(img: Image?, ctm: Matrix?, alpha: Float, cp: Int) {
            hadImage = true
            try {
                mark(unitRect(ctm), alpha)
            } finally {
                img?.destroy()
            }
        }

        override fun fillImageMask(img: Image?, ctm: Matrix?, cs: ColorSpace?, color: FloatArray?, alpha: Float, cp: Int) {
            hadImage = true
            try {
                mark(unitRect(ctm), alpha)
            } finally {
                img?.destroy()
                release(cs)
            }
        }

        override fun clipImageMask(img: Image?, ctm: Matrix?) {
            hadImage = true
            try {
                add(unitRect(ctm), true)
            } finally {
                img?.destroy()
            }
        }

        override fun popClip() {
            if (clips.isNotEmpty()) clips.removeAt(clips.lastIndex)
        }

        override fun beginMask(area: Rect?, luminosity: Boolean, cs: ColorSpace?, bc: FloatArray?, cp: Int) {
            try {
                add(area, true)
                ignored++
            } finally {
                release(cs)
            }
        }

        override fun endMask() {
            if (ignored > 0) ignored--
        }

        override fun beginGroup(area: Rect?, cs: ColorSpace?, isolated: Boolean, knockout: Boolean, blendmode: Int, alpha: Float) {
            try {
                add(area, true)
            } finally {
                release(cs)
            }
        }

        override fun endGroup() = popClip()

        override fun beginTile(area: Rect?, view: Rect?, xstep: Float, ystep: Float, ctm: Matrix?, id: Int, docId: Int): Int {
            add(if (area != null && ctm != null) Rect(area).transform(ctm) else null, false)
            ignored++
            return 0
        }

        override fun endTile() {
            if (ignored > 0) ignored--
        }

        override fun renderFlags(set: Int, clear: Int) {}

        override fun setDefaultColorSpaces(dcs: DefaultColorSpaces?) {
            dcs?.destroy()
        }

        override fun beginLayer(name: String?) {}
        override fun endLayer() {}
        override fun beginStructure(standard: Int, raw: String?, idx: Int) {}
        override fun endStructure() {}
        override fun beginMetatext(meta: Int, text: String?) {}
        override fun endMetatext() {}
    }
}
