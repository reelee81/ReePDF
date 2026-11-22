package hu.reelee81.reepdf

import android.app.NotificationManager
import java.util.concurrent.atomic.AtomicInteger

object ProcessingServiceGate {
    fun notificationsOn(appEnabled: Boolean, channelImportance: Int?): Boolean =
        appEnabled && channelImportance != NotificationManager.IMPORTANCE_NONE

    fun maySend(running: Boolean, screenShown: Boolean, notificationsOn: () -> Boolean): Boolean =
        (running || screenShown) && notificationsOn()
}

class ShownScreens {
    private val count = AtomicInteger()

    val any: Boolean
        get() = count.get() > 0

    fun screen(): Screen = Screen()

    inner class Screen {
        private var shown = false

        fun show() {
            if (shown) return
            shown = true
            count.incrementAndGet()
        }

        fun hide() {
            if (!shown) return
            shown = false
            count.decrementAndGet()
        }
    }
}
