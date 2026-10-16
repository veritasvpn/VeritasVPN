package cloud.veritasvpn.support

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import java.io.File

/**
 * Persisted diagnostic ring. Process-exit facts are read on the next start.
 * An uncaught exception is written and flushed before the previous handler runs.
 */
object DiagnosticLog {
    private const val FILE_NAME = "diagnostic-ring.txt"
    private const val CAPACITY = 40
    private val lock = Any()
    @Volatile private var application: Application? = null

    fun install(app: Application) {
        application = app
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error ->
            runCatching { recordCrash(error) }
            if (previous != null) {
                previous.uncaughtException(Thread.currentThread(), error)
            } else {
                throw error
            }
        }
    }

    fun recordExitReasons(app: Application) {
        application = app
        if (Build.VERSION.SDK_INT < 30) {
            record("exit proc=main reason=UNKNOWN importance=0 ts=0 desc=api-below-30")
            return
        }
        val manager = app.getSystemService(ActivityManager::class.java)
        if (manager == null) {
            record("exit proc=main reason=UNKNOWN importance=0 ts=0 desc=unread")
            return
        }
        val infos = try {
            manager.getHistoricalProcessExitReasons(app.packageName, 0, 16)
        } catch (error: RuntimeException) {
            record("exit proc=main reason=UNKNOWN importance=0 ts=0 desc=unread")
            return
        }
        val main = infos.firstOrNull { ProcessExitLog.processLabel(it.processName, app.packageName) == "main" }
        val vpn = infos.firstOrNull { ProcessExitLog.processLabel(it.processName, app.packageName) == "vpn" }
        if (main == null) {
            record("exit proc=main reason=UNKNOWN importance=0 ts=0 desc=none")
        } else {
            record(
                ProcessExitLog.exitLine(
                    process = "main",
                    reason = ProcessExitLog.reasonName(main.reason),
                    description = main.description,
                    importance = main.importance,
                    timestampMs = main.timestamp,
                ),
            )
        }
        if (vpn != null) {
            record(
                ProcessExitLog.exitLine(
                    process = "vpn",
                    reason = ProcessExitLog.reasonName(vpn.reason),
                    description = vpn.description,
                    importance = vpn.importance,
                    timestampMs = vpn.timestamp,
                ),
            )
        }
    }

    fun record(line: String?) {
        val safe = line?.trim()?.takeIf { accept(it) } ?: return
        synchronized(lock) {
            val next = readLines().toMutableList()
            next.add(safe)
            while (next.size > CAPACITY) next.removeAt(0)
            writeLines(next)
        }
    }

    fun recordCrash(error: Throwable) {
        val frames = error.stackTrace.mapNotNull { element ->
            ProcessExitLog.frame(
                className = element.className,
                method = element.methodName,
                file = element.fileName,
                line = element.lineNumber,
            )
        }
        for (line in ProcessExitLog.crashLines(
            className = error.javaClass.name,
            frames = frames,
            causes = ProcessExitLog.causeNames(error),
        )) {
            record(line)
        }
    }

    fun snapshot(): String {
        val lines = synchronized(lock) { readLines() }
        return if (lines.isEmpty()) "—" else lines.joinToString("\n")
    }

    private fun accept(line: String): Boolean {
        if (line.isEmpty() || line.length > 160) return false
        if (line.any { it == '\n' || it == '\r' }) return false
        return line.all { it.isLetterOrDigit() || it == ' ' || it in "=._\$:-" }
    }

    private fun readLines(): List<String> {
        val file = file() ?: return emptyList()
        if (!file.isFile) return emptyList()
        return runCatching {
            file.readLines(Charsets.UTF_8).map { it.trim() }.filter { accept(it) }
        }.getOrDefault(emptyList())
    }

    private fun writeLines(lines: List<String>) {
        val file = file() ?: return
        runCatching {
            file.outputStream().use { out ->
                out.write(lines.joinToString("\n").toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
        }
    }

    private fun file(): File? {
        val dir = application?.filesDir ?: return null
        return File(dir, FILE_NAME)
    }
}
