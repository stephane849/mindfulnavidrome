package com.stephane.naviplayer

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the stack trace of a fatal exception so it can be read on the phone.
 *
 * This app is built in CI and sideloaded, so there is no adb and no crash
 * reporting behind it. Without this, "it keeps crashing" is the entire bug
 * report - which is not enough to fix anything.
 */
object CrashLog {

    private const val FILE_NAME = "last_crash.txt"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /**
     * Installed from [NaviApp], so it covers the playback service as well as
     * the activity - they share one process, and a crash in the service is the
     * harder one to catch.
     */
    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
                file(app).writeText(
                    "Navi player ${BuildConfig.VERSION_NAME} - $stamp\n" +
                        "on thread ${thread.name}\n\n$trace"
                )
            } catch (e: Throwable) {
                // There is nothing useful left to do while the process is dying
            }
            // Still let Android kill the process the way it normally would:
            // swallowing this would leave the app wedged rather than crashed
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String = try {
        file(context).takeIf { it.exists() }?.readText().orEmpty()
    } catch (e: Exception) {
        ""
    }

    fun clear(context: Context) {
        try {
            file(context).delete()
        } catch (e: Exception) {
            // A crash report we cannot delete is not worth crashing over
        }
    }
}

class NaviApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
