package hu.reelee81.reepdf

import android.app.NotificationManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingServiceGateTest {
    @Test
    fun notificationsAreOnOnlyWithTheAppAndTheChannelEnabled() {
        assertTrue(ProcessingServiceGate.notificationsOn(true, NotificationManager.IMPORTANCE_DEFAULT))
        assertTrue(ProcessingServiceGate.notificationsOn(true, NotificationManager.IMPORTANCE_LOW))
        assertFalse(ProcessingServiceGate.notificationsOn(true, NotificationManager.IMPORTANCE_NONE))
        assertFalse(ProcessingServiceGate.notificationsOn(false, NotificationManager.IMPORTANCE_DEFAULT))
    }

    @Test
    fun aChannelNotCreatedYetFollowsTheAppSetting() {
        assertTrue(ProcessingServiceGate.notificationsOn(true, null))
        assertFalse(ProcessingServiceGate.notificationsOn(false, null))
    }

    @Test
    fun aNewServiceStartsOnlyFromAShownScreen() {
        assertTrue(ProcessingServiceGate.maySend(running = false, screenShown = true) { true })
        assertTrue(ProcessingServiceGate.maySend(running = true, screenShown = false) { true })
        assertFalse(ProcessingServiceGate.maySend(running = false, screenShown = false) { true })
    }

    @Test
    fun withoutNotificationsNothingIsSent() {
        assertFalse(ProcessingServiceGate.maySend(running = false, screenShown = true) { false })
        assertFalse(ProcessingServiceGate.maySend(running = true, screenShown = true) { false })
    }

    @Test
    fun theNotificationSettingIsNotReadWhenNothingMaySend() {
        var asked = false
        ProcessingServiceGate.maySend(running = false, screenShown = false) { asked = true; true }
        assertFalse(asked)
    }

    @Test
    fun aScreenCountsOnceHoweverOftenItIsShown() {
        val screens = ShownScreens()
        val main = screens.screen()
        main.show()
        main.show()
        assertTrue(screens.any)
        main.hide()
        assertFalse(screens.any)
    }

    @Test
    fun hidingAScreenNeverShownChangesNothing() {
        val screens = ShownScreens()
        val main = screens.screen()
        val viewer = screens.screen()
        viewer.hide()
        main.show()
        viewer.hide()
        assertTrue(screens.any)
    }

    @Test
    fun anotherShownScreenKeepsTheAppShown() {
        val screens = ShownScreens()
        val main = screens.screen()
        val viewer = screens.screen()
        main.show()
        viewer.show()
        main.hide()
        assertTrue(screens.any)
        viewer.hide()
        assertFalse(screens.any)
    }
}
