package com.example.debug

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.example.bluetooth.HidProtocolTelemetry
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class DebugLogLevel(val shortLabel: String, val severity: Int) {
    DEBUG("DBG", 1),
    INFO("INF", 2),
    WARN("WRN", 3),
    ERROR("ERR", 4),
    FATAL("FTL", 5)
}

data class DebugLogEntry(
    val id: Long,
    val timestampMs: Long,
    val level: DebugLogLevel,
    val tag: String,
    val threadName: String,
    val message: String,
    val stackTrace: String? = null
)

/**
 * Central, thread-safe in-app exception & diagnostic event logger.
 * - Captures uncaught JVM crashes via [Thread.setDefaultUncaughtExceptionHandler] and persists
 *   them synchronously so stack traces survive app restarts.
 * - Captures caught exceptions, warnings, Bluetooth HID protocol negotiations, camera errors,
 *   and IME/UI events into a live [StateFlow] ring buffer viewable in Settings -> Debug Console.
 * - Safe in pure JVM unit tests (never crashes if Android framework APIs are unmocked).
 */
object AppDebugLogger {

    private const val MAX_BUFFER_ENTRIES = 500
    private const val MAX_PERSISTED_ERRORS = 40
    private const val PREFS_NAME = "alvetak_debug_console_prefs"
    private const val KEY_LAST_CRASH = "last_uncaught_crash_report"
    private const val KEY_PERSISTED_ERRORS = "persisted_error_entries"

    private val lock = Any()
    private val idGenerator = AtomicLong(1L)
    private val initialized = AtomicBoolean(false)
    private val handlerInstalled = AtomicBoolean(false)
    private val entriesBuffer = ArrayDeque<DebugLogEntry>(MAX_BUFFER_ENTRIES)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var appVersionLabel: String = "Bilinmiyor"

    private val _logs = MutableStateFlow<List<DebugLogEntry>>(emptyList())
    val logs: StateFlow<List<DebugLogEntry>> = _logs.asStateFlow()

    private val _lastCrashReport = MutableStateFlow<String?>(null)
    val lastCrashReport: StateFlow<String?> = _lastCrashReport.asStateFlow()

    fun installGlobalExceptionHandler(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (initialized.compareAndSet(false, true)) {
            runCatching {
                val p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs = p
                val versionInfo = runCatching {
                    val pkgInfo = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        pkgInfo.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        pkgInfo.versionCode.toLong()
                    }
                    "${pkgInfo.versionName ?: "?"} ($code)"
                }.getOrDefault("?")
                appVersionLabel = versionInfo

                val savedCrash = p.getString(KEY_LAST_CRASH, null)
                if (!savedCrash.isNullOrBlank()) {
                    _lastCrashReport.value = savedCrash
                }
                restorePersistedErrors(p)
            }
            i(
                "APP",
                "Debug konsolu başlatıldı · Sürüm: $appVersionLabel · Cihaz: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})"
            )
        }

        if (handlerInstalled.compareAndSet(false, true)) {
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                recordUncaughtCrashSync(thread, throwable)
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    fun coroutineHandler(tag: String): CoroutineExceptionHandler {
        return CoroutineExceptionHandler { _, throwable ->
            e(tag, "Yakalanmamış coroutine hatası: ${throwable.message ?: throwable::class.simpleName}", throwable)
        }
    }

    fun d(tag: String, message: String) {
        append(DebugLogLevel.DEBUG, tag, message, null)
    }

    fun i(tag: String, message: String) {
        append(DebugLogLevel.INFO, tag, message, null)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        append(DebugLogLevel.WARN, tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        append(DebugLogLevel.ERROR, tag, message, throwable)
    }

    fun fatal(tag: String, message: String, throwable: Throwable? = null) {
        append(DebugLogLevel.FATAL, tag, message, throwable)
    }

    fun clearLogs() {
        synchronized(lock) {
            entriesBuffer.clear()
            _logs.value = emptyList()
        }
        runCatching {
            prefs?.edit()?.remove(KEY_PERSISTED_ERRORS)?.apply()
        }
    }

    fun clearCrashBanner() {
        _lastCrashReport.value = null
        runCatching {
            prefs?.edit()?.remove(KEY_LAST_CRASH)?.apply()
        }
    }

    fun throwableToString(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        pw.flush()
        return sw.toString().trimEnd()
    }

    fun buildDeviceSummary(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) | App $appVersionLabel"
    }

    fun buildShareableReport(
        hidStatus: String? = null,
        connectedHost: String? = null,
        hidTelemetry: HidProtocolTelemetry? = null,
        pairedCount: Int? = null,
        entries: List<DebugLogEntry> = _logs.value
    ): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val sb = StringBuilder()
        sb.appendLine("=== ALVETAK QR DEBUG RAPORU ===")
        sb.appendLine("Oluşturulma: ${sdf.format(Date())}")
        sb.appendLine("Cihaz: ${buildDeviceSummary()}")
        if (hidStatus != null || hidTelemetry != null) {
            sb.appendLine("--- Bluetooth HID Durumu ---")
            if (hidStatus != null) sb.appendLine("Durum: $hidStatus (${connectedHost ?: "Bağlı cihaz yok"})")
            if (pairedCount != null) sb.appendLine("Eşleşmiş cihaz sayısı: $pairedCount")
            if (hidTelemetry != null) {
                sb.appendLine("Protokol: ${if (hidTelemetry.isBootMode) "BOOT MODE" else "REPORT MODE"}")
                sb.appendLine("QoS: ${hidTelemetry.qosLabel}")
                sb.appendLine("Klavye TX: ${hidTelemetry.keyboardReportsSent} (Hata: ${hidTelemetry.keyboardReportsFailed})")
                sb.appendLine("Mouse TX: ${hidTelemetry.mouseReportsSent} (Hata: ${hidTelemetry.mouseReportsFailed})")
                if (!hidTelemetry.lastFailureReason.isNullOrBlank()) {
                    sb.appendLine("Son HID Hatası: ${hidTelemetry.lastFailureReason}")
                }
            }
        }
        val crash = _lastCrashReport.value
        if (!crash.isNullOrBlank()) {
            sb.appendLine("--- Önceki Yakalanmamış Çökme ---")
            sb.appendLine(crash)
        }
        sb.appendLine("--- Log Kayıtları (${entries.size}) ---")
        val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        for (entry in entries) {
            sb.append('[')
                .append(timeFmt.format(Date(entry.timestampMs)))
                .append("] [")
                .append(entry.level.shortLabel)
                .append('/')
                .append(entry.tag)
                .append("] (")
                .append(entry.threadName)
                .append(") ")
                .appendLine(entry.message)
            if (!entry.stackTrace.isNullOrBlank()) {
                sb.appendLine(entry.stackTrace)
            }
        }
        return sb.toString().trimEnd()
    }

    private fun recordUncaughtCrashSync(thread: Thread, throwable: Throwable) {
        val trace = throwableToString(throwable)
        val msg = "Yakalanmamış Çökme (${thread.name}): ${throwable::class.simpleName}: ${throwable.message ?: "Detay yok"}"
        val entry = DebugLogEntry(
            id = idGenerator.getAndIncrement(),
            timestampMs = System.currentTimeMillis(),
            level = DebugLogLevel.FATAL,
            tag = "CRASH",
            threadName = thread.name,
            message = msg,
            stackTrace = trace
        )
        val snapshot: List<DebugLogEntry>
        synchronized(lock) {
            if (entriesBuffer.size >= MAX_BUFFER_ENTRIES) {
                entriesBuffer.removeLastOrNull()
            }
            entriesBuffer.addFirst(entry)
            snapshot = entriesBuffer.toList()
            _logs.value = snapshot
        }
        val fullCrashReport = buildString {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            appendLine("Zaman: ${sdf.format(Date(entry.timestampMs))}")
            appendLine("Cihaz: ${buildDeviceSummary()}")
            appendLine("Thread: ${thread.name}")
            appendLine(trace)
        }.trimEnd()
        _lastCrashReport.value = fullCrashReport
        mirrorToLogcat(DebugLogLevel.FATAL, "CRASH", msg, throwable)

        runCatching {
            val p = prefs ?: appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (p != null) {
                val errJson = serializeErrorEntries(snapshot)
                p.edit()
                    .putString(KEY_LAST_CRASH, fullCrashReport)
                    .putString(KEY_PERSISTED_ERRORS, errJson)
                    .commit()
            }
        }
    }

    private fun append(level: DebugLogLevel, tag: String, message: String, throwable: Throwable?) {
        val trace = throwable?.let { throwableToString(it) }
        val fullMessage = if (throwable != null && !throwable.message.isNullOrBlank() && !message.contains(throwable.message!!)) {
            "$message (${throwable::class.simpleName}: ${throwable.message})"
        } else if (throwable != null && throwable.message.isNullOrBlank() && !message.contains(throwable::class.simpleName ?: "")) {
            "$message (${throwable::class.simpleName})"
        } else {
            message
        }
        val entry = DebugLogEntry(
            id = idGenerator.getAndIncrement(),
            timestampMs = System.currentTimeMillis(),
            level = level,
            tag = tag,
            threadName = Thread.currentThread().name,
            message = fullMessage,
            stackTrace = trace
        )
        val snapshot: List<DebugLogEntry>
        synchronized(lock) {
            if (entriesBuffer.size >= MAX_BUFFER_ENTRIES) {
                entriesBuffer.removeLastOrNull()
            }
            entriesBuffer.addFirst(entry)
            snapshot = entriesBuffer.toList()
            _logs.value = snapshot
        }
        mirrorToLogcat(level, tag, fullMessage, throwable)

        if (level.severity >= DebugLogLevel.ERROR.severity) {
            runCatching {
                prefs?.edit()?.putString(KEY_PERSISTED_ERRORS, serializeErrorEntries(snapshot))?.apply()
            }
        }
    }

    private fun serializeErrorEntries(snapshot: List<DebugLogEntry>): String {
        val array = JSONArray()
        snapshot
            .filter { it.level.severity >= DebugLogLevel.WARN.severity }
            .take(MAX_PERSISTED_ERRORS)
            .forEach { entry ->
                val obj = JSONObject().apply {
                    put("ts", entry.timestampMs)
                    put("lvl", entry.level.name)
                    put("tag", entry.tag)
                    put("thr", entry.threadName)
                    put("msg", entry.message)
                    if (entry.stackTrace != null) put("st", entry.stackTrace)
                }
                array.put(obj)
            }
        return array.toString()
    }

    private fun restorePersistedErrors(p: SharedPreferences) {
        val raw = p.getString(KEY_PERSISTED_ERRORS, null) ?: return
        runCatching {
            val array = JSONArray(raw)
            val restored = mutableListOf<DebugLogEntry>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val level = runCatching {
                    DebugLogLevel.valueOf(obj.optString("lvl", DebugLogLevel.ERROR.name))
                }.getOrDefault(DebugLogLevel.ERROR)
                restored.add(
                    DebugLogEntry(
                        id = idGenerator.getAndIncrement(),
                        timestampMs = obj.optLong("ts", System.currentTimeMillis()),
                        level = level,
                        tag = obj.optString("tag", "OLD"),
                        threadName = obj.optString("thr", "?"),
                        message = "[Önceki Oturum] ${obj.optString("msg", "")}",
                        stackTrace = obj.optString("st", "").ifBlank { null }
                    )
                )
            }
            if (restored.isNotEmpty()) {
                synchronized(lock) {
                    restored.forEach { entry ->
                        if (entriesBuffer.size < MAX_BUFFER_ENTRIES) {
                            entriesBuffer.addLast(entry)
                        }
                    }
                    _logs.value = entriesBuffer.toList()
                }
            }
        }
    }

    private fun mirrorToLogcat(level: DebugLogLevel, tag: String, message: String, throwable: Throwable?) {
        try {
            val fullTag = "ALVETAK_$tag"
            when (level) {
                DebugLogLevel.DEBUG -> Log.d(fullTag, message, throwable)
                DebugLogLevel.INFO -> Log.i(fullTag, message, throwable)
                DebugLogLevel.WARN -> Log.w(fullTag, message, throwable)
                DebugLogLevel.ERROR, DebugLogLevel.FATAL -> Log.e(fullTag, message, throwable)
            }
        } catch (_: Throwable) {
            // Ignored when running inside pure JVM unit tests without Android Log mock
        }
    }
}
