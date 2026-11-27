package hu.reelee81.reepdf

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import hu.reelee81.reepdf.viewer.ViewerTooltipPlacement
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.hypot

class LabelTooltip {
    private val handler = Handler(Looper.getMainLooper())
    private val hide = Runnable { dismiss() }
    private val longPress = Runnable { pressed?.let(::longPressDisabled) }
    private val labeled: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
    private var popup: PopupWindow? = null
    private var pressed: View? = null
    private var downX = 0f
    private var downY = 0f

    fun attach(views: List<View>) {
        views.forEach { view ->
            view.setOnLongClickListener { show(it) }
            labeled.add(view)
        }
    }

    fun onTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dismiss()
                pressDisabled(event)
            }
            MotionEvent.ACTION_MOVE -> pressed?.let { view ->
                if (hypot(event.rawX - downX, event.rawY - downY) > ViewConfiguration.get(view.context).scaledTouchSlop) cancelPress()
            }
            MotionEvent.ACTION_POINTER_DOWN -> cancelPress()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelPress()
                if (popup != null) {
                    handler.removeCallbacks(hide)
                    handler.postDelayed(hide, HIDE_AFTER_RELEASE_MS)
                }
            }
        }
    }

    fun dismiss() {
        cancelPress()
        handler.removeCallbacks(hide)
        popup?.dismiss()
        popup = null
    }

    fun show(anchor: View, text: CharSequence?, area: Rect): Boolean {
        if (text.isNullOrEmpty() || !anchor.isAttachedToWindow) return false
        dismiss()
        val context = anchor.context
        val resources = context.resources
        val bubble = LayoutInflater.from(context).inflate(R.layout.label_tooltip, FrameLayout(context), false) as TextView
        bubble.text = text
        val root = anchor.rootView
        val insets = ViewCompat.getRootWindowInsets(anchor)?.getInsets(WindowInsetsCompat.Type.systemBars())
        val edge = resources.getDimensionPixelSize(R.dimen.dp_16)
        val gap = resources.getDimensionPixelSize(R.dimen.dp_8)
        val left = edge + (insets?.left ?: 0)
        val right = root.width - edge - (insets?.right ?: 0)
        val top = edge + (insets?.top ?: 0)
        bubble.measure(
            View.MeasureSpec.makeMeasureSpec((right - left).coerceAtLeast(0), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        val bottom = root.height - edge - (insets?.bottom ?: 0)
        val place = ViewerTooltipPlacement.place(
            IntRect(location[0] + area.left, location[1] + area.top, location[0] + area.right, location[1] + area.bottom),
            IntSize(bubble.measuredWidth, bubble.measuredHeight),
            IntRect(left, top, right, bottom),
            gap
        )
        popup = PopupWindow(bubble, bubble.measuredWidth, bubble.measuredHeight, false).apply {
            isTouchable = false
            elevation = resources.getDimension(R.dimen.dp_3)
            animationStyle = android.R.style.Animation_Toast
            showAtLocation(anchor, Gravity.NO_GRAVITY, place.x, place.y)
        }
        return true
    }

    private fun show(anchor: View): Boolean = show(anchor, anchor.contentDescription, Rect(0, 0, anchor.width, anchor.height))

    private fun pressDisabled(event: MotionEvent) {
        val x = event.rawX
        val y = event.rawY
        val view = labeled.firstOrNull { !it.isEnabled && it.isShown && it.isAttachedToWindow && contains(it, x, y) } ?: return
        val root = view.rootView as? ViewGroup ?: return
        if (touchTarget(root, x, y) !== view) return
        pressed = view
        downX = x
        downY = y
        handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun longPressDisabled(view: View) {
        pressed = null
        if (!view.isEnabled && view.isShown && show(view)) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun cancelPress() {
        handler.removeCallbacks(longPress)
        pressed = null
    }

    private fun touchTarget(group: ViewGroup, x: Float, y: Float): View? {
        val children = (0 until group.childCount).map(group::getChildAt).withIndex()
            .sortedWith(compareByDescending<IndexedValue<View>> { it.value.z }.thenByDescending { it.index })
        for ((_, child) in children) {
            if (child.visibility != View.VISIBLE || !contains(child, x, y)) continue
            if (child is ViewGroup) touchTarget(child, x, y)?.let { return it }
            if (child.isClickable || child.isLongClickable) return child
        }
        return null
    }

    private fun contains(view: View, x: Float, y: Float): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return TouchHit.isInside(x, y, location[0], location[1], view.width, view.height)
    }

    private companion object {
        const val HIDE_AFTER_RELEASE_MS = 1500L
    }
}
