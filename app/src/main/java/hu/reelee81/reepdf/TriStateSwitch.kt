package hu.reelee81.reepdf

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Parcelable
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import androidx.core.content.ContextCompat
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.os.BundleCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import hu.reelee81.reepdf.core.SwitchStep
import kotlin.math.abs
import kotlin.math.roundToInt

class TriStateSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onPositionChanged: ((Int) -> Unit)? = null

    var lastPosition: Int = 2
        set(value) {
            field = value.coerceAtLeast(0)
            if (committed > field) commit(field, animate = false)
        }

    var position: Int
        get() = committed
        set(value) {
            rising = true
            commit(value.coerceIn(0, lastPosition), animate = isAttachedToWindow && isLaidOut)
        }

    private val probe = SwitchMaterial(context)
    private val scratch = Canvas(createBitmap(1, 1))
    private var text: CharSequence = ""
    private val textPaint: TextPaint
    private val thumb: Drawable
    private val track: Drawable
    private val thumbOff = Rect()
    private val trackBounds = Rect()
    private var thumbRange = 0
    private var textX = 0f
    private var textBaseline = 0f
    private val markRadius = resources.getDimension(R.dimen.dp_2)
    private val markColors = requireNotNull(ContextCompat.getColorStateList(context, R.color.switch_mark_color))
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var committed = 0
    private var offset = 0f
    private var rising = true
    private var animator: ValueAnimator? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downOffset = 0f
    private var downOnThumb = false
    private var dragging = false

    private val offState = intArrayOf(android.R.attr.state_enabled)
    private val onState = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked)
    private val disabledOffState = intArrayOf()
    private val disabledOnState = intArrayOf(android.R.attr.state_checked)

    init {
        context.withStyledAttributes(attrs, R.styleable.TriStateSwitch) {
            text = getText(R.styleable.TriStateSwitch_android_text) ?: ""
            probe.switchPadding = getDimensionPixelSize(R.styleable.TriStateSwitch_switchPadding, probe.switchPadding)
        }
        probe.text = text
        textPaint = TextPaint(probe.paint)
        thumb = requireNotNull(probe.thumbDrawable)
        track = requireNotNull(probe.trackDrawable)
        isClickable = true
        isFocusable = true
    }

    private fun syncGeometry(probeWidth: Int, height: Int) {
        probe.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
        probe.measure(
            MeasureSpec.makeMeasureSpec(probeWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        probe.layout(0, 0, probeWidth, height)
        probe.isChecked = false
        probe.draw(scratch)
        thumbOff.set(thumb.bounds)
        trackBounds.set(track.bounds)
        probe.isChecked = true
        probe.draw(scratch)
        thumbRange = thumb.bounds.left - thumbOff.left
        probe.isChecked = false
        textX = probe.compoundPaddingLeft.toFloat()
        textBaseline = probe.baseline.toFloat()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        probe.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
        probe.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), heightMeasureSpec)
        if (thumbRange == 0) syncGeometry(probe.measuredWidth, probe.measuredHeight)
        setMeasuredDimension(
            resolveSize(probe.measuredWidth + thumbRange, widthMeasureSpec),
            resolveSize(probe.measuredHeight, heightMeasureSpec)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        syncGeometry((w - thumbRange).coerceAtLeast(0), h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val on = committed > 0

        textPaint.color = probe.textColors.getColorForState(drawableState, probe.currentTextColor)
        canvas.drawText(text, 0, text.length, textX, textBaseline, textPaint)

        val shift = (offset * thumbRange).roundToInt()
        val thumbCenter = thumbOff.centerX() + shift
        track.setBounds(trackBounds.left, trackBounds.top, trackBounds.right + thumbRange, trackBounds.bottom)

        drawTrackPart(canvas, if (on) thumbCenter else trackBounds.left, track.bounds.right, checked = false)
        if (on) drawTrackPart(canvas, trackBounds.left, thumbCenter, checked = true)
        val markX = thumbOff.centerX() + thumbRange
        markPaint.color = markColors.getColorForState(stateOf(on && thumbCenter >= markX), markColors.defaultColor)
        canvas.drawCircle(markX.toFloat(), track.bounds.exactCenterY(), markRadius, markPaint)

        thumb.setBounds(thumbOff.left + shift, thumbOff.top, thumbOff.right + shift, thumbOff.bottom)
        thumb.state = stateOf(on)
        thumb.jumpToCurrentState()
        thumb.draw(canvas)
    }

    private fun drawTrackPart(canvas: Canvas, left: Int, right: Int, checked: Boolean) {
        if (right <= left) return
        canvas.withClip(left, track.bounds.top, right, track.bounds.bottom) {
            track.state = stateOf(checked)
            track.jumpToCurrentState()
            track.draw(this)
        }
    }

    private fun stateOf(checked: Boolean): IntArray = when {
        isEnabled && checked -> onState
        isEnabled -> offState
        checked -> disabledOnState
        else -> disabledOffState
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downOffset = offset
                dragging = false
                val thumbLeft = thumbOff.left + offset * thumbRange
                downOnThumb = event.x in (thumbLeft - touchSlop)..(thumbLeft + thumbOff.width() + touchSlop) &&
                    event.y in (thumbOff.top - touchSlop).toFloat()..(thumbOff.bottom + touchSlop).toFloat()
                isPressed = true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                if (!dragging && downOnThumb && thumbRange > 0 && abs(dx) > touchSlop) {
                    dragging = true
                    animator?.cancel()
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    offset = SwitchStep.detent(downOffset + dx / thumbRange, lastPosition, DETENT_BAND)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                isPressed = false
                if (dragging) {
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    val target = SwitchStep.nearest(offset, lastPosition)
                    if (target != committed) rising = target > committed
                    commit(target, animate = true)
                } else if (abs(event.x - downX) <= touchSlop) {
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                if (dragging) {
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    commit(committed, animate = true)
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        val next = SwitchStep.next(committed, lastPosition, rising)
        rising = next.rising
        commit(next.position, animate = isAttachedToWindow && isLaidOut)
        return true
    }

    private fun commit(target: Int, animate: Boolean) {
        animator?.cancel()
        animator = null
        val changed = target != committed
        committed = target
        if (animate && offset != target.toFloat()) {
            animator = ValueAnimator.ofFloat(offset, target.toFloat()).apply {
                duration = ANIMATION_MS
                addUpdateListener {
                    offset = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            offset = target.toFloat()
            invalidate()
        }
        if (changed) onPositionChanged?.invoke(target)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        offset = committed.toFloat()
        super.onDetachedFromWindow()
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    override fun onSaveInstanceState(): Parcelable = Bundle().apply {
        putParcelable(STATE_SUPER, super.onSaveInstanceState())
        putInt(STATE_POSITION, committed)
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is Bundle) {
            super.onRestoreInstanceState(state)
            return
        }
        super.onRestoreInstanceState(BundleCompat.getParcelable(state, STATE_SUPER, Parcelable::class.java))
        position = state.getInt(STATE_POSITION, committed)
    }

    private companion object {
        const val ANIMATION_MS = 250L
        const val DETENT_BAND = 0.25f
        const val STATE_SUPER = "state_super"
        const val STATE_POSITION = "state_position"
    }
}
