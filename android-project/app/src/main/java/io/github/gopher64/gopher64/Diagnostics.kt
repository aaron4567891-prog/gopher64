package io.github.gopher64.gopher64

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.util.concurrent.Executors

class GopherApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.start(this)
    }
}

object Diagnostics {
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var process: java.lang.Process? = null
    @Volatile private var active = false
    private var hooked = false
    private fun dir(context: Context) = File(context.filesDir, "diagnostics").apply { mkdirs() }
    fun enabled(context: Context) = context.getSharedPreferences("diagnostics", 0).getBoolean("enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("diagnostics", 0).edit().putBoolean("enabled", enabled).apply()
        if (enabled) start(context.applicationContext) else {
            active = false
            process?.destroy()
            process = null
        }
    }

    private fun append(context: Context, text: String) = synchronized(lock) {
        val folder = dir(context)
        val current = File(folder, "current.txt")
        if (current.length() > 1024 * 1024) {
            val previous = File(folder, "previous.txt")
            previous.delete()
            current.renameTo(previous)
        }
        current.appendText(text.take(32 * 1024) + "\n")
    }

    @Synchronized fun start(context: Context) {
        if (!enabled(context) || active) return
        active = true
        if (!hooked) {
            hooked = true
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                if (enabled(context)) runCatching {
                    append(context, "Uncaught exception on ${thread.name}:\n${error.stackTraceToString()}")
                }
                previous?.uncaughtException(thread, error)
            }
        }
        worker.execute {
            if (!active) return@execute
            runCatching {
                append(context, "\nSession ${java.util.Date()}\nDevice: ${Build.MANUFACTURER} ${Build.MODEL}\nAndroid: ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}\nApp: ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}")
                val manager = context.getSystemService(ActivityManager::class.java)
                manager.getHistoricalProcessExitReasons(context.packageName, 0, 3).forEach {
                    append(context, "Previous exit: time=${it.timestamp} reason=${it.reason} status=${it.status} description=${it.description}")
                }
                val readerProcess = ProcessBuilder("logcat", "--pid=${Process.myPid()}",
                    "-b", "main", "-b", "system", "-b", "crash", "-v", "threadtime", "-T", "1")
                    .redirectErrorStream(true).start()
                process = readerProcess
                if (!active) readerProcess.destroy()
                try {
                    readerProcess.inputStream.bufferedReader().use { reader ->
                        while (active) {
                            val line = reader.readLine() ?: break
                            if (active) append(context, line)
                        }
                    }
                } finally {
                    readerProcess.destroy()
                    if (process === readerProcess) process = null
                }
            }
        }
    }

    fun snapshot(context: Context): File = synchronized(lock) {
        val folder = File(context.cacheDir, "diagnostic-share").apply { mkdirs() }
        File(folder, "gopher64-diagnostics.txt").apply {
            bufferedWriter().use { writer ->
                writer.write("Gopher64 diagnostic report\nLogs may contain game filenames and paths.\n")
                for (name in listOf("previous.txt", "current.txt")) {
                    val log = File(dir(context), name)
                    if (log.exists()) writer.write(log.readText())
                }
            }
        }
    }

    fun clear(context: Context) = synchronized(lock) {
        for (name in listOf("previous.txt", "current.txt")) File(dir(context), name).delete()
        File(context.cacheDir, "diagnostic-share/gopher64-diagnostics.txt").delete()
    }
}
