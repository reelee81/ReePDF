package hu.reelee81.reepdf

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle

class NotificationReturn : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isTaskRoot) {
            startActivity(Intent.makeRestartActivityTask(ComponentName(this, MainActivity::class.java)))
        }
        finish()
    }
}
