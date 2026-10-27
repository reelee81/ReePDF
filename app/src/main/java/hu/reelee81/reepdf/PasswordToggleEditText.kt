package hu.reelee81.reepdf

import android.content.Context
import android.text.method.PasswordTransformationMethod
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.TextViewCompat
import com.google.android.material.textfield.TextInputEditText
import java.io.Closeable

class PasswordToggleEditText @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : TextInputEditText(ctx, attrs, defStyleAttr) {

    private var passwordVisible: Boolean = false
    private var iconTouchActive: Boolean = false

    private val asteriskTransformation = object : PasswordTransformationMethod() {
        private inner class AsteriskCharSequence(private val source: CharSequence) : CharSequence {
            override val length: Int get() = source.length
            override fun get(index: Int): Char = '*'
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
                AsteriskCharSequence(source.subSequence(startIndex, endIndex))
            override fun toString(): String = "*".repeat(source.length)
        }

        override fun getTransformation(source: CharSequence?, view: View?): CharSequence {
            return if (source == null) "" else AsteriskCharSequence(source)
        }
    }

    private inner class SavedSelection(private val edit: TextInputEditText) : Closeable {
        private val start = edit.selectionStart
        private val end = edit.selectionEnd
        override fun close() {
            try {
                edit.setSelection(start, end.coerceAtLeast(start))
            } catch (_: Throwable) {
            }
        }
    }

    init {
        setInitialStateMasked()
    }

    fun setInitialStateMasked() {
        passwordVisible = false
        transformationMethod = asteriskTransformation

        if (compoundDrawablePadding == 0) {
            compoundDrawablePadding = (8 * resources.displayMetrics.density).toInt()
        }

        if (contentDescription.isNullOrBlank()) {
            contentDescription = resources.getString(R.string.toggle_password_visibility)
        }

        TextViewCompat.setCompoundDrawableTintList(this, null)

        updateIcon()
    }

    fun toggleVisibility() {
        SavedSelection(this).use {
            passwordVisible = !passwordVisible
            transformationMethod = if (passwordVisible) null else asteriskTransformation
        }

        updateIcon()
        playSoundEffect(SoundEffectConstants.CLICK)
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
    }

    private fun updateIcon() {
        runCatching {
            val resId = if (passwordVisible) R.drawable.ic_visibility_on_24 else R.drawable.ic_visibility_off_24
            val d = AppCompatResources.getDrawable(context, resId)?.mutate()
            TextViewCompat.setCompoundDrawableTintList(this, null)
            setCompoundDrawablesWithIntrinsicBounds(null, null, d, null)
            refreshDrawableState()
            invalidate()
        }.onFailure {
            runCatching {
                val fallback = AppCompatResources.getDrawable(context, R.drawable.ic_visibility_off_24)?.mutate()
                setCompoundDrawablesWithIntrinsicBounds(null, null, fallback, null)
                refreshDrawableState()
                invalidate()
            }
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val endAbs = compoundDrawables.getOrNull(2)
        val endDrawable = endAbs ?: return super.onTouchEvent(ev)

        val iconW = endDrawable.bounds.width().takeIf { it > 0 } ?: return super.onTouchEvent(ev)
        val x = ev.x

        fun isInsideIcon(): Boolean {
            val iconLeft = width - paddingRight - iconW
            val iconRight = width - paddingRight
            return x >= iconLeft && x <= iconRight
        }

        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                if (isInsideIcon()) {
                    iconTouchActive = true
                    cancelLongPress()
                    cancelPendingInputEvents()
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (iconTouchActive) {
                    if (!isInsideIcon()) iconTouchActive = false
                    return true
                }
            }

            MotionEvent.ACTION_UP -> {
                if (iconTouchActive && isInsideIcon()) {
                    toggleVisibility()
                    performClick()
                    cancelPendingInputEvents()
                    iconTouchActive = false
                    return true
                }
                iconTouchActive = false
            }

            MotionEvent.ACTION_CANCEL -> {
                iconTouchActive = false
            }
        }

        return super.onTouchEvent(ev)
    }
}
