package com.example

import com.example.diagnostics.DiagnosticComponent
import com.example.diagnostics.DiagnosticEvent
import com.example.diagnostics.DiagnosticLogStorage
import com.example.diagnostics.DiagnosticRedactor
import com.example.diagnostics.DiagnosticSeverity
import com.example.diagnostics.ErrorCategory
import com.example.diagnostics.ExportFormat
import com.example.diagnostics.ExportScope
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class SonixDiagnosticsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var storage: DiagnosticLogStorage

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("test_diagnostics")
        storage = DiagnosticLogStorage(
            storageDir = storageDir,
            maxFileSizeBytes = 10 * 1024L, // 10KB for rapid rotation testing
            maxRotatedFiles = 3,
            maxRecordSizeBytes = 4 * 1024
        )
    }

    @Test
    fun testRecordInfoWarningError() {
        val infoEvent = DiagnosticEvent(
            id = "test-1",
            severity = DiagnosticSeverity.INFO,
            component = DiagnosticComponent.APP,
            message = "Application booted successfully"
        )
        val warnEvent = DiagnosticEvent(
            id = "test-2",
            severity = DiagnosticSeverity.WARN,
            component = DiagnosticComponent.PLAYER,
            message = "Buffering took longer than expected"
        )
        val errorEvent = DiagnosticEvent(
            id = "test-3",
            severity = DiagnosticSeverity.ERROR,
            component = DiagnosticComponent.SUBTITLE,
            message = "Subtitle decoding failed",
            category = ErrorCategory.SUBTITLE_ERROR
        )

        storage.recordEvent(infoEvent)
        storage.recordEvent(warnEvent)
        storage.recordEvent(errorEvent)

        val recent = storage.getRecentEvents(10)
        assertEquals(3, recent.size)

        val stats = storage.getStats(isEnabled = true, isVerbose = true, activeOpsCount = 0)
        assertEquals(1, stats.errorCount)
        assertEquals(1, stats.warningCount)
        assertEquals(3, stats.totalEventCount)
        assertEquals("test-3", stats.lastErrorEvent?.id)
    }

    @Test
    fun testStackTracePreservation() {
        val sampleException = IOException("Simulated network drop")
        val errorEvent = DiagnosticEvent(
            id = "stack-1",
            severity = DiagnosticSeverity.ERROR,
            component = DiagnosticComponent.NETWORK,
            message = "Network request failed",
            category = ErrorCategory.NETWORK_ERROR,
            exceptionClass = sampleException.javaClass.name,
            exceptionMessage = sampleException.message,
            stackTrace = "java.io.IOException: Simulated network drop\n\tat com.example.Test.run(Test.kt:42)"
        )

        storage.recordEvent(errorEvent)
        val retrieved = storage.getRecentEvents(1).first()
        assertNotNull(retrieved.stackTrace)
        assertTrue(retrieved.stackTrace!!.contains("Simulated network drop"))
        assertEquals("java.io.IOException", retrieved.exceptionClass)
    }

    @Test
    fun testOperationIdCorrelation() {
        val opId = "op_test_999"

        val startEvent = DiagnosticEvent(
            id = "step-1",
            severity = DiagnosticSeverity.INFO,
            component = DiagnosticComponent.SUBTITLE,
            operationId = opId,
            operationName = "APPLY_SUBTITLE",
            stage = "START",
            message = "Started subtitle download"
        )
        val intermediateEvent = DiagnosticEvent(
            id = "step-2",
            severity = DiagnosticSeverity.INFO,
            component = DiagnosticComponent.SUBTITLE,
            operationId = opId,
            operationName = "APPLY_SUBTITLE",
            stage = "PARSED",
            message = "Decompressed and parsed 120 cues"
        )
        val completeEvent = DiagnosticEvent(
            id = "step-3",
            severity = DiagnosticSeverity.INFO,
            component = DiagnosticComponent.SUBTITLE,
            operationId = opId,
            operationName = "APPLY_SUBTITLE",
            stage = "SUCCESS",
            message = "Attached subtitle to PlayerView"
        )

        storage.recordEvent(startEvent)
        storage.recordEvent(intermediateEvent)
        storage.recordEvent(completeEvent)

        val timeline = storage.getOperationTimeline(opId)
        assertEquals(3, timeline.size)
        assertEquals("step-1", timeline[0].id)
        assertEquals("step-2", timeline[1].id)
        assertEquals("step-3", timeline[2].id)
        assertEquals("START", timeline[0].stage)
        assertEquals("PARSED", timeline[1].stage)
        assertEquals("SUCCESS", timeline[2].stage)
    }

    @Test
    fun testLogRotationAndPruning() {
        // Record enough events to exceed maxFileSizeBytes (10KB) and trigger rotation
        val largeMessage = "A".repeat(500)
        for (i in 1..40) {
            val ev = DiagnosticEvent(
                id = "rot-$i",
                severity = DiagnosticSeverity.INFO,
                component = DiagnosticComponent.SYSTEM,
                message = "Log entry #$i: $largeMessage"
            )
            storage.recordEvent(ev)
        }

        storage.flush()

        val files = storageDir.listFiles()?.filter { it.name.startsWith("sonix_diag_") } ?: emptyList()
        assertTrue("Log files should be created and rotated", files.isNotEmpty())
        // Files count must not exceed maxRotatedFiles (3)
        assertTrue("Rotated files should be capped at 3, got ${files.size}", files.size <= 3)
    }

    @Test
    fun testRedactionOfSensitiveCredentials() {
        // Query param password
        val inputUrl = "https://provider.stream:8080/player_api.php?username=vip_user&password=super_secret_password&action=get_live"
        val cleanUrl = DiagnosticRedactor.redact(inputUrl)
        assertFalse("Password must be redacted", cleanUrl.contains("super_secret_password"))
        assertTrue("Redaction tag must be present", cleanUrl.contains("password=***REDACTED***"))

        // Xtream stream URL format: http://domain:port/user/pass/123.m3u8
        val xtreamStreamUrl = "http://iptv.server.net:8080/john_doe/mySecretPass123/54321.ts"
        val cleanStreamUrl = DiagnosticRedactor.redact(xtreamStreamUrl)
        assertFalse("Stream URL username must be redacted", cleanStreamUrl.contains("john_doe"))
        assertFalse("Stream URL password must be redacted", cleanStreamUrl.contains("mySecretPass123"))
        assertTrue("Stream ID and format preserved", cleanStreamUrl.endsWith("54321.ts"))

        // Bearer token
        val authHeader = "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.secret"
        val cleanAuth = DiagnosticRedactor.redact(authHeader)
        assertFalse("Bearer token must be redacted", cleanAuth.contains("eyJhbGciOi"))
        assertTrue(cleanAuth.contains("Authorization: Bearer ***REDACTED***"))

        // JSON key redaction
        val jsonString = """{"username":"admin","password":"rawPassword","token":"secretToken123"}"""
        val cleanJson = DiagnosticRedactor.redact(jsonString)
        assertFalse("JSON password redacted", cleanJson.contains("rawPassword"))
        assertFalse("JSON token redacted", cleanJson.contains("secretToken123"))
    }

    @Test
    fun testRedactionInMetadata() {
        val meta = mapOf(
            "stream_url" to "http://host:80/live/user1/pass2/100.m3u8",
            "api_key" to "key_abcdef123456",
            "safe_property" to "1080p_60fps"
        )
        val cleanMeta = DiagnosticRedactor.redactMap(meta)
        assertEquals("***REDACTED***", cleanMeta["api_key"])
        assertEquals("1080p_60fps", cleanMeta["safe_property"])
        assertFalse(cleanMeta["stream_url"]!!.contains("user1"))
        assertFalse(cleanMeta["stream_url"]!!.contains("pass2"))
    }

    @Test
    fun testSafeHandlingOfDiskIoErrors() {
        // Storage pointing to a non-existent or read-only directory should not throw
        val invalidDir = File(tempFolder.root, "invalid_dir")
        invalidDir.createNewFile() // It's a file, not a directory!
        val faultyStorage = DiagnosticLogStorage(invalidDir)

        val event = DiagnosticEvent(
            id = "err-1",
            severity = DiagnosticSeverity.INFO,
            component = DiagnosticComponent.APP,
            message = "This should not crash"
        )
        // Should not throw exception
        faultyStorage.recordEvent(event)
        faultyStorage.shutdown()
    }

    @Test
    fun testLargeLogsTruncation() {
        val hugeMessage = "Z".repeat(100_000)
        val event = DiagnosticEvent(
            id = "large-1",
            severity = DiagnosticSeverity.INFO,
            component = DiagnosticComponent.SYSTEM,
            message = hugeMessage
        )
        storage.recordEvent(event)
        storage.flush()
        val files = storageDir.listFiles() ?: emptyArray()
        assertTrue(files.isNotEmpty())
    }

    @Test
    fun testSubtitlePipelineErrorDiagnostics() {
        // Attempting to decode HTML error page as subtitle must fail with clear descriptive message
        val htmlContent = "<!DOCTYPE html><html><body>404 Not Found</body></html>".toByteArray(Charsets.UTF_8)
        var caught: IllegalArgumentException? = null
        try {
            SubtitlePipeline.decodePayload(htmlContent, null, "http://subs.org/sub.srt")
        } catch (e: IllegalArgumentException) {
            caught = e
        }
        assertNotNull("Must reject HTML error page", caught)
        assertTrue(
            "Exception message must specify that source returned HTML instead of subtitle",
            caught!!.message!!.contains("صفحة ويب") || caught.message!!.contains("JSON")
        )
    }

    @Test
    fun testSubtitlePipelineGzipDecompression() {
        // Gzip magic bytes test
        val fakeGzipBytes = byteArrayOf(0x1f.toByte(), 0x8b.toByte(), 0x08, 0x00)
        // Pipeline recognizes gzip header
        val isGzipMethod = SubtitlePipeline::class.java.getDeclaredMethod("isGzip", ByteArray::class.java)
        isGzipMethod.isAccessible = true
        val result = isGzipMethod.invoke(SubtitlePipeline, fakeGzipBytes) as Boolean
        assertTrue("Should recognize GZIP magic bytes", result)
    }

    @Test
    fun testSubtitleDelayShiftingUnchanged() {
        val srtText = "1\n00:00:01,000 --> 00:00:04,000\nHello World\n"
        val shifted = SubtitlePipeline.applyDelay(srtText, "srt", 1000L)
        assertTrue("Timestamp must shift by 1000ms", shifted.contains("00:00:02,000 --> 00:00:05,000"))
    }

    @Test
    fun testClearLogs() {
        storage.recordEvent(DiagnosticEvent(id = "c-1", severity = DiagnosticSeverity.INFO, component = DiagnosticComponent.APP, message = "First"))
        storage.recordEvent(DiagnosticEvent(id = "c-2", severity = DiagnosticSeverity.ERROR, component = DiagnosticComponent.APP, message = "Second"))

        storage.clearLogs()
        val recent = storage.getRecentEvents(10)
        assertTrue(recent.isEmpty())
        val stats = storage.getStats(true, true, 0)
        assertEquals(0, stats.totalEventCount)
        assertEquals(0, stats.errorCount)
    }
}
