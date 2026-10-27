package hu.reelee81.reepdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import hu.reelee81.reepdf.core.PdfCropRect
import kotlin.math.abs
import kotlin.math.min

class PdfCropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private enum class Grip { NONE, MOVE, LEFT, TOP, RIGHT, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    private var bitmap: Bitmap? = null
    private val image = RectF()
    private val frame = RectF(0f, 0f, 1f, 1f)
    private val start = RectF()
    private val screen = RectF()
    private var grip = Grip.NONE
    private var downX = 0f
    private var downY = 0f

    private val density = resources.displayMetrics.density
    private val touchRadius = 24f * density
    private val markLength = 22f * density

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint().apply { color = ContextCompat.getColor(context, R.color.crop_shade_color) }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.crop_frame_color)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.crop_frame_color)
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.SQUARE
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.crop_grid_color)
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    fun setPage(page: Bitmap) {
        bitmap = page
        requestLayout()
        invalidate()
    }

    fun setFrame(left: Float, top: Float, right: Float, bottom: Float) {
        frame.set(left, top, right, bottom)
        invalidate()
    }

    fun resetFrame() = setFrame(0f, 0f, 1f, 1f)

    fun frame(): RectF = RectF(frame)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            resources.displayMetrics.widthPixels
        } else {
            MeasureSpec.getSize(widthMeasureSpec)
        }
        val page = bitmap
        val aspect = if (page != null && page.width > 0) page.height.toFloat() / page.width else 1.414f
        val height = resolveSize((width * aspect).toInt(), heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutImage()
    }

    private fun layoutImage() {
        val page = bitmap
        val margin = touchRadius / 2f
        val availableW = width - 2 * margin
        val availableH = height - 2 * margin
        if (page == null || availableW <= 0f || availableH <= 0f) {
            image.setEmpty()
            return
        }
        val scale = min(availableW / page.width, availableH / page.height)
        val w = page.width * scale
        val h = page.height * scale
        image.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val page = bitmap ?: return
        if (image.isEmpty) layoutImage()
        canvas.drawBitmap(page, null, image, bitmapPaint)

        val f = screenFrame()
        canvas.drawRect(image.left, image.top, image.right, f.top, shadePaint)
        canvas.drawRect(image.left, f.bottom, image.right, image.bottom, shadePaint)
        canvas.drawRect(image.left, f.top, f.left, f.bottom, shadePaint)
        canvas.drawRect(f.right, f.top, image.right, f.bottom, shadePaint)
        for (step in 1..2) {
            val x = f.left + f.width() * step / 3f
            val y = f.top + f.height() * step / 3f
            canvas.drawLine(x, f.top, x, f.bottom, gridPaint)
            canvas.drawLine(f.left, y, f.right, y, gridPaint)
        }
        canvas.drawRect(f, framePaint)

        val length = min(markLength, min(f.width(), f.height()) / 3f)
        canvas.drawLine(f.left, f.top, f.left + length, f.top, markPaint)
        canvas.drawLine(f.left, f.top, f.left, f.top + length, markPaint)
        canvas.drawLine(f.right, f.top, f.right - length, f.top, markPaint)
        canvas.drawLine(f.right, f.top, f.right, f.top + length, markPaint)
        canvas.drawLine(f.left, f.bottom, f.left + length, f.bottom, markPaint)
        canvas.drawLine(f.left, f.bottom, f.left, f.bottom - length, markPaint)
        canvas.drawLine(f.right, f.bottom, f.right - length, f.bottom, markPaint)
        canvas.drawLine(f.right, f.bottom, f.right, f.bottom - length, markPaint)

        val half = length / 2f
        canvas.drawLine(f.centerX() - half, f.top, f.centerX() + half, f.top, markPaint)
        canvas.drawLine(f.centerX() - half, f.bottom, f.centerX() + half, f.bottom, markPaint)
        canvas.drawLine(f.left, f.centerY() - half, f.left, f.centerY() + half, markPaint)
        canvas.drawLine(f.right, f.centerY() - half, f.right, f.centerY() + half, markPaint)
    }

    private fun screenFrame(): RectF {
        screen.set(
            image.left + frame.left * image.width(),
            image.top + frame.top * image.height(),
            image.left + frame.right * image.width(),
            image.top + frame.bottom * image.height()
        )
        return screen
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null || image.isEmpty) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                grip = gripAt(event.x, event.y)
                if (grip == Grip.NONE) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                downY = event.y
                start.set(frame)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (grip == Grip.NONE) return false
                drag((event.x - downX) / image.width(), (event.y - downY) / image.height())
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (grip != Grip.NONE) performClick()
                grip = Grip.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                grip = Grip.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun gripAt(x: Float, y: Float): Grip {
        val f = screenFrame()
        val nearLeft = abs(x - f.left) <= touchRadius
        val nearRight = abs(x - f.right) <= touchRadius
        val nearTop = abs(y - f.top) <= touchRadius
        val nearBottom = abs(y - f.bottom) <= touchRadius
        val withinX = x >= f.left - touchRadius && x <= f.right + touchRadius
        val withinY = y >= f.top - touchRadius && y <= f.bottom + touchRadius
        return when {
            nearLeft && nearTop -> Grip.TOP_LEFT
            nearRight && nearTop -> Grip.TOP_RIGHT
            nearLeft && nearBottom -> Grip.BOTTOM_LEFT
            nearRight && nearBottom -> Grip.BOTTOM_RIGHT
            nearLeft && withinY -> Grip.LEFT
            nearRight && withinY -> Grip.RIGHT
            nearTop && withinX -> Grip.TOP
            nearBottom && withinX -> Grip.BOTTOM
            f.contains(x, y) -> Grip.MOVE
            else -> Grip.NONE
        }
    }

    private fun drag(dx: Float, dy: Float) {
        val minSize = PdfCropRect.MIN_SIZE
        var left = start.left
        var top = start.top
        var right = start.right
        var bottom = start.bottom
        when (grip) {
            Grip.MOVE -> {
                val moveX = dx.coerceIn(-start.left, 1f - start.right)
                val moveY = dy.coerceIn(-start.top, 1f - start.bottom)
                left += moveX
                right += moveX
                top += moveY
                bottom += moveY
            }
            else -> {
                if (grip == Grip.LEFT || grip == Grip.TOP_LEFT || grip == Grip.BOTTOM_LEFT) {
                    left = (start.left + dx).coerceIn(0f, start.right - minSize)
                }
                if (grip == Grip.RIGHT || grip == Grip.TOP_RIGHT || grip == Grip.BOTTOM_RIGHT) {
                    right = (start.right + dx).coerceIn(start.left + minSize, 1f)
                }
                if (grip == Grip.TOP || grip == Grip.TOP_LEFT || grip == Grip.TOP_RIGHT) {
                    top = (start.top + dy).coerceIn(0f, start.bottom - minSize)
                }
                if (grip == Grip.BOTTOM || grip == Grip.BOTTOM_LEFT || grip == Grip.BOTTOM_RIGHT) {
                    bottom = (start.bottom + dy).coerceIn(start.top + minSize, 1f)
                }
            }
        }
        frame.set(left, top, right, bottom)
    }
}
