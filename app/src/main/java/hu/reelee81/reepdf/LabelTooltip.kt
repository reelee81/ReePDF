package hu.reelee81.reepdf

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class LabelTooltip {
    private val handler = Handler(Looper.getMainLooper())
    private val hide = Runnable { dismiss() }
    private var popup: PopupWindow? = null

    fun attach(views: List<View>) {
        views.forEach { view -> view.setOnLongClickListener { show(it) } }
    }

    fun onTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> dismiss()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (popup != null) {
                handler.removeCallbacks(hide)
                handler.postDelayed(hide, HIDE_AFTER_RELEASE_MS)
            }
        }
    }

    fun dismiss() {
        handler.removeCallbacks(hide)
        popup?.dismiss()
        popup = null
    }

    private fun show(anchor: View): Boolean {
        val text = anchor.contentDescription
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
        val x = (location[0] + (anchor.width - bubble.measuredWidth) / 2)
            .coerceIn(left, (right - bubble.measuredWidth).coerceAtLeast(left))
        val above = location[1] - gap - bubble.measuredHeight
        val y = if (above >= top) above else location[1] + anchor.height + gap
        popup = PopupWindow(bubble, bubble.measuredWidth, bubble.measuredHeight, false).apply {
            isTouchable = false
            elevation = resources.getDimension(R.dimen.dp_3)
            animationStyle = android.R.style.Animation_Toast
            showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        }
        return true
    }

    private companion object {
        const val HIDE_AFTER_RELEASE_MS = 1500L
    }
}
