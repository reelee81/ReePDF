package hu.reelee81.reepdf

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat

object SystemBarsCompat {

    fun applyNavBarColor(activity: Activity, navColor: Int) {
        val night = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

        val insetsCtl = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        insetsCtl.isAppearanceLightNavigationBars = !night

        try {
            val setContrast = activity.window.javaClass.getMethod(
                "setNavigationBarContrastEnforced", java.lang.Boolean.TYPE
            )
            setContrast.invoke(activity.window, false)
        } catch (_: Throwable) {

        }

        if (Build.VERSION.SDK_INT < 35) {
            try {
                val setColor = activity.window.javaClass.getMethod("setNavigationBarColor", Integer.TYPE)
                setColor.invoke(activity.window, navColor)
            } catch (_: Throwable) { }
        }
    }

    fun needsGestureGap(sdk: Int, buttonLeft: Int, buttonBottom: Int, buttonRight: Int): Boolean =
        sdk < Build.VERSION_CODES.Q || (buttonLeft == 0 && buttonBottom == 0 && buttonRight == 0)

    fun keyboardAboveNavigation(keyboardBottom: Int, navigationBottom: Int): Int =
        (keyboardBottom - navigationBottom).coerceAtLeast(0)

    fun applyViewerBars(activity: Activity) {
        val night = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val background = ContextCompat.getColor(activity, R.color.pdf_viewer_sb_bg_color)
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars = !night
        activity.window.setBackgroundDrawable(background.toDrawable())
        applyStatusBarColor(activity, background)
        applyNavBarColor(activity, background)
    }

    fun applyStatusBarColor(activity: Activity, statusColor: Int) {
        if (Build.VERSION.SDK_INT < 35) {
            try {
                val setColor = activity.window.javaClass.getMethod("setStatusBarColor", Integer.TYPE)
                setColor.invoke(activity.window, statusColor)
            } catch (_: Throwable) { }
        }
    }
}
