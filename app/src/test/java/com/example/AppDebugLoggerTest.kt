package com.example

import com.example.bluetooth.HidProtocolTelemetry
import com.example.debug.AppDebugLogger
import com.example.debug.DebugLogLevel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AppDebugLoggerTest {

    @Before
    fun setUp() {
        AppDebugLogger.clearLogs()
        AppDebugLogger.clearCrashBanner()
    }

    @After
    fun tearDown() {
        AppDebugLogger.clearLogs()
        AppDebugLogger.clearCrashBanner()
    }

    @Test
    fun logsLevelsAndNewestFirstOrder() {
        AppDebugLogger.d("BT-HID", "debug step")
        AppDebugLogger.i("BT-HID", "info step")
        AppDebugLogger.w("CAM-QR", "warn step")
        AppDebugLogger.e("BT-MOUSE", "error step", IllegalStateException("mouse failed"))
        AppDebugLogger.fatal("CRASH", "fatal step", RuntimeException("boom"))

        val logs = AppDebugLogger.logs.value
        assertEquals(5, logs.size)

        // Newest entry must be first
        assertEquals(DebugLogLevel.FATAL, logs[0].level)
        assertEquals("CRASH", logs[0].tag)
        assertTrue(logs[0].message.contains("fatal step"))
        assertTrue(logs[0].stackTrace?.contains("RuntimeException: boom") == true)

        assertEquals(DebugLogLevel.ERROR, logs[1].level)
        assertEquals("BT-MOUSE", logs[1].tag)
        assertTrue(logs[1].message.contains("error step"))
        assertTrue(logs[1].stackTrace?.contains("IllegalStateException: mouse failed") == true)

        assertEquals(DebugLogLevel.WARN, logs[2].level)
        assertEquals(DebugLogLevel.INFO, logs[3].level)
        assertEquals(DebugLogLevel.DEBUG, logs[4].level)
        assertNull(logs[4].stackTrace)
    }

    @Test
    fun ringBufferCapsAt500EntriesAndKeepsLatest() {
        for (i in 1..550) {
            AppDebugLogger.i("STRESS", "msg #$i")
        }

        val logs = AppDebugLogger.logs.value
        assertEquals(500, logs.size)
        assertTrue(logs.first().message.contains("msg #550"))
        assertTrue(logs.last().message.contains("msg #51"))
    }

    @Test
    fun buildShareableReportIncludesHidTelemetryAndEntries() {
        AppDebugLogger.e("BT-HID", "QoS rejected", SecurityException("BLUETOOTH_CONNECT missing"))

        val telemetry = HidProtocolTelemetry(
            isBootMode = true,
            qosLabel = "QOS_RELAXED",
            keyboardReportsSent = 42,
            keyboardReportsFailed = 1,
            mouseReportsSent = 18,
            mouseReportsFailed = 3,
            lastFailureReason = "Mouse TX reddedildi"
        )

        val report = AppDebugLogger.buildShareableReport(
            hidStatus = "CONNECTED",
            connectedHost = "Desktop-PC",
            hidTelemetry = telemetry,
            pairedCount = 2
        )

        assertTrue(report.contains("ALVETAK QR DEBUG RAPORU"))
        assertTrue(report.contains("CONNECTED (Desktop-PC)"))
        assertTrue(report.contains("BOOT MODE"))
        assertTrue(report.contains("QOS_RELAXED"))
        assertTrue(report.contains("Klavye TX: 42 (Hata: 1)"))
        assertTrue(report.contains("Mouse TX: 18 (Hata: 3)"))
        assertTrue(report.contains("Mouse TX reddedildi"))
        assertTrue(report.contains("[ERR/BT-HID]"))
        assertTrue(report.contains("SecurityException: BLUETOOTH_CONNECT missing"))
    }

    @Test
    fun clearLogsEmptiesBuffer() {
        AppDebugLogger.w("CAM-X", "camera warning")
        assertFalse(AppDebugLogger.logs.value.isEmpty())

        AppDebugLogger.clearLogs()
        assertTrue(AppDebugLogger.logs.value.isEmpty())
    }
}
