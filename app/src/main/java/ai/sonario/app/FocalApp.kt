package ai.sonario.app

import android.app.Application

class FocalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Catch uncaught exceptions and log them to a file so a crash isn't silent.
        CrashReporter.install(this)
    }
}
