package hu.reelee81.reepdf

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
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
}
