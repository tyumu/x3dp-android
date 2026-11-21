package net.x3dp

import android.app.Application
import android.util.Log

class X3App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                getSharedPreferences("x3dp", MODE_PRIVATE).edit()
                    .putString("lastCrash", Log.getStackTraceString(e))
                    .apply()
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }
}
