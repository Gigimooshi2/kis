package com.gigimooshi.kis

import android.app.Application
import java.time.ZonedDateTime

/** Saves the stack trace of any crash so the app can show it on next launch (no logcat on the phone). */
class KisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                getSharedPreferences("crash", MODE_PRIVATE).edit()
                    .putString(
                        "trace",
                        "Kis build ${runCatching { Updater.installedVersion(this) }.getOrDefault(0L)} · " +
                            "${ZonedDateTime.now()}\nthread: ${thread.name}\n\n${error.stackTraceToString()}"
                    )
                    .commit() // synchronous: the process is about to die
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        fun takeLastCrash(app: Application): String? {
            val prefs = app.getSharedPreferences("crash", MODE_PRIVATE)
            val trace = prefs.getString("trace", null) ?: return null
            prefs.edit().remove("trace").apply()
            return trace
        }
    }
}
