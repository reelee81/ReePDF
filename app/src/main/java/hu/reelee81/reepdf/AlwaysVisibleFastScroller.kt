package hu.reelee81.reepdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_CANCEL
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_UP
import android.view.View
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver
import androidx.recyclerview.widget.RecyclerView.ItemDecoration
import androidx.recyclerview.widget.RecyclerView.OnItemTouchListener
import androidx.recyclerview.widget.RecyclerView.OnScrollListener
import androidx.recyclerview.widget.RecyclerView.State
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class AlwaysVisibleFastScroller(
    private val recyclerView: RecyclerView,
    context: Context
) : ItemDecoration(), OnItemTouchListener {

    private val verticalThumbDrawable: StateListDrawable
    private val baseThumbDrawable: Drawable? =
        ContextCompat.getDrawable(context, R.drawable.ic_scroll_button_24)
    private val thumbAspectRatio: Float = run {
        val iw = (baseThumbDrawable?.intrinsicWidth ?: 0).coerceAtLeast(1)
        val ih = (baseThumbDrawable?.intrinsicHeight ?: 0).coerceAtLeast(1)
        ih.toFloat() / iw.toFloat()
    }

    private var density = recyclerView.resources.displayMetrics.density
    private var verticalMarginPx = (4f * density).toInt()
    private var rightMarginPx = (7f * density).toInt()
    private var preferredThumbWidthPx = (18f * density).toInt().coerceAtLeast(1)
    private var recyclerViewWidth = 0
    private var recyclerViewHeight = 0
    private var dragging = false
    private var lastTouchY = 0f
    private var enabled = false
    private var hardDisabled = false

    @VisibleForTesting
    var verticalThumbCenterY: Int = 0
        private set

    @VisibleForTesting
    var verticalThumbWidth: Int = preferredThumbWidthPx
        private set

    @VisibleForTesting
    var verticalThumbHeight: Int = preferredThumbWidthPx
        private set

    init {
        val normal = baseThumbDrawable?.constantState?.newDrawable()?.mutate()
        val pressed = baseThumbDrawable?.constantState?.newDrawable()?.mutate()

        verticalThumbDrawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }

        setupCallbacks()
    }

    private fun forceAllowParentIntercept() {
        try {
            recyclerView.parent?.requestDisallowInterceptTouchEvent(false)
        } catch (_: Exception) {
        }
    }

    fun setEnabledWhen(hasItems: Boolean) {
        if (hardDisabled) {
            if (enabled) {
                if (dragging) {
                    forceAllowParentIntercept()
                }
                enabled = false
                dragging = false
                recyclerView.invalidate()
            }
            return
        }

        if (enabled == hasItems) return
        if (!hasItems && dragging) {
            forceAllowParentIntercept()
        }

        enabled = hasItems
        if (!enabled) {
            dragging = false
        }
        recyclerView.invalidate()
    }

    fun setTemporarilyDisabled(disabled: Boolean) {
        hardDisabled = disabled
        if (hardDisabled) {
            if (dragging) {
                forceAllowParentIntercept()
            }
            if (enabled) {
                enabled = false
                dragging = false
            } else {
                dragging = false
            }
            recyclerView.invalidate()
        } else {
            recyclerView.invalidate()
        }
    }

    private fun setupCallbacks() {
        recyclerView.addItemDecoration(this)
        recyclerView.addOnItemTouchListener(this)

        recyclerView.addOnScrollListener(object : OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                updateThumbPosition()
            }

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (!enabled) return
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    rv.post {
                        updateThumbPosition()
                    }
                }
            }
        })

        recyclerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (enabled && !dragging) {
                recyclerView.post { updateThumbPosition() }
            }
        }

        recyclerView.adapter?.registerAdapterDataObserver(object : AdapterDataObserver() {
            override fun onChanged() {
                recyclerView.post { updateThumbPosition() }
            }
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                recyclerView.post { updateThumbPosition() }
            }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                recyclerView.post { updateThumbPosition() }
            }
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
                recyclerView.post { updateThumbPosition() }
            }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
                recyclerView.post { updateThumbPosition() }
            }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                recyclerView.post { updateThumbPosition() }
            }
        })

        recyclerView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
            }

            override fun onViewDetachedFromWindow(v: View) {
                if (dragging) {
                    dragging = false
                    forceAllowParentIntercept()
                    recyclerView.invalidate()
                }
            }
        })
    }

    fun refreshDisplayMetrics() {
        val newDensity = recyclerView.resources.displayMetrics.density
        if (newDensity == density) return

        density = newDensity
        verticalMarginPx = (4f * density).toInt()
        rightMarginPx = (7f * density).toInt()
        preferredThumbWidthPx = (18f * density).toInt().coerceAtLeast(1)

        if (!dragging) {
            updateThumbPosition()
        } else {
            recyclerView.invalidate()
        }
    }

    private fun updateThumbPosition() {
        if (!enabled) return

        recyclerViewWidth = recyclerView.width
        recyclerViewHeight = recyclerView.height

        if (dragging) {
            recyclerView.invalidate()
            return
        }

        val range = recyclerView.computeVerticalScrollRange()
        val offset = recyclerView.computeVerticalScrollOffset()
        val extent = recyclerView.computeVerticalScrollExtent()

        val needScrollbar = range - extent > 0

        val trackTop = verticalMarginPx
        val trackBottom = recyclerViewHeight - verticalMarginPx
        val trackHeight = max(0, trackBottom - trackTop)

        val desiredW = preferredThumbWidthPx
        val desiredH = max(1, (desiredW * thumbAspectRatio).roundToInt())

        var w = desiredW
        var h = desiredH

        if (trackHeight in 1 until h) {
            val scale = trackHeight.toFloat() / h.toFloat()
            w = max(1, (w * scale).roundToInt())
            h = max(1, (h * scale).roundToInt())
        }

        verticalThumbWidth = w
        verticalThumbHeight = h

        val denom = (range - extent).coerceAtLeast(1)
        val scrollProportion = if (!needScrollbar) 0f else offset.toFloat() / denom.toFloat()

        val available = (trackHeight - verticalThumbHeight).coerceAtLeast(0)
        verticalThumbCenterY = trackTop + (verticalThumbHeight / 2) + (available * scrollProportion).toInt()

        recyclerView.invalidate()
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: State) {
        if (!enabled) return

        if (recyclerViewWidth != parent.width || recyclerViewHeight != parent.height) {
            recyclerViewWidth = parent.width
            recyclerViewHeight = parent.height
            updateThumbPosition()
        }

        val thumbW = verticalThumbWidth
        val thumbH = verticalThumbHeight

        val right = recyclerViewWidth - rightMarginPx
        val left = right - thumbW

        val top = (verticalThumbCenterY - thumbH / 2).coerceAtLeast(verticalMarginPx)
        val bottom = (top + thumbH).coerceAtMost(recyclerViewHeight - verticalMarginPx)

        verticalThumbDrawable.setState(if (dragging) intArrayOf(android.R.attr.state_pressed) else intArrayOf())
        verticalThumbDrawable.setBounds(left, top, right, bottom)
        verticalThumbDrawable.draw(c)
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        if (!enabled) return false
        if (e.actionMasked == ACTION_DOWN) {
            if (recyclerViewWidth == 0 || recyclerViewHeight == 0) {
                recyclerViewWidth = rv.width
                recyclerViewHeight = rv.height
                updateThumbPosition()
            }
            if (isPointInsideVerticalThumb(e.x, e.y)) {
                dragging = true
                lastTouchY = e.y
                rv.parent?.requestDisallowInterceptTouchEvent(true)
                rv.stopScroll()
                return true
            }
        }
        return false
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        if (!enabled) {
            if (dragging) {
                dragging = false
                forceAllowParentIntercept()
                rv.invalidate()
            }
            return
        }

        when (e.actionMasked) {
            ACTION_MOVE -> {
                if (!dragging) return
                rv.parent?.requestDisallowInterceptTouchEvent(true)
                verticalScrollTo(e.y)
                lastTouchY = e.y
            }
            ACTION_UP, ACTION_CANCEL -> {
                dragging = false
                rv.parent?.requestDisallowInterceptTouchEvent(false)
                rv.invalidate()
            }
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
    }

    private fun isPointInsideVerticalThumb(x: Float, y: Float): Boolean {
        if (recyclerViewWidth == 0 || recyclerViewHeight == 0) {
            recyclerViewWidth = recyclerView.width
            recyclerViewHeight = recyclerView.height
        }

        val thumbW = verticalThumbWidth
        val thumbH = verticalThumbHeight

        val right = (recyclerViewWidth - rightMarginPx).toFloat()
        val left = right - thumbW

        val top = (verticalThumbCenterY - thumbH / 2).toFloat()
        val bottom = (top + thumbH)

        return (x in left..right) && (y in top..bottom)
    }

    private fun verticalScrollTo(y: Float) {
        val range = recyclerView.computeVerticalScrollRange()
        val offset = recyclerView.computeVerticalScrollOffset()
        val extent = recyclerView.computeVerticalScrollExtent()

        val trackTop = verticalMarginPx.toFloat()
        val trackBottom = (recyclerViewHeight - verticalMarginPx).toFloat()

        val thumbHalf = verticalThumbHeight / 2f
        val start = trackTop + thumbHalf
        val end = trackBottom - thumbHalf

        val clampedY = min(max(y, start), end)
        verticalThumbCenterY = clampedY.toInt()

        val denom = (end - start).coerceAtLeast(1f)
        val proportion = (clampedY - start) / denom

        val maxScroll = (range - extent).coerceAtLeast(0)
        val targetOffset = (proportion * maxScroll).toInt()

        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
        val itemCount = recyclerView.adapter?.itemCount ?: 0
        if (layoutManager != null && itemCount > 0 && range > 0) {
            val span = (layoutManager as? GridLayoutManager)?.spanCount?.coerceAtLeast(1) ?: 1
            val rows = (itemCount + span - 1) / span
            val rowHeight = range.toFloat() / rows
            val row = (targetOffset / rowHeight).toInt().coerceIn(0, rows - 1)
            val withinRow = (targetOffset - row * rowHeight).roundToInt()
            layoutManager.scrollToPositionWithOffset(row * span, -withinRow)
        } else {
            recyclerView.scrollBy(0, targetOffset - offset)
        }

        recyclerView.invalidate()
    }
}
