package com.example.diagnostics

import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.PrintWriter
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class DiagnosticLogStorage(
    private val storageDir: File,
    private val maxFileSizeBytes: Long = 2 * 1024 * 1024L, // 2MB
    private val maxRotatedFiles: Int = 5,
    private val maxRecordSizeBytes: Int = 64 * 1024, // 64KB
    private val inMemoryCapacity: Int = 500
) {
    private val diskExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sonix-diag-writer").apply { isDaemon = true }
    }

    private val inMemoryEvents = ConcurrentLinkedDeque<DiagnosticEvent>()
    private val totalEventsRecorded = AtomicLong(0)
    private val totalErrorsRecorded = AtomicLong(0)
    private val totalWarningsRecorded = AtomicLong(0)
    private val totalSuccessRecorded = AtomicLong(0)

    @Volatile
    private var lastErrorEvent: DiagnosticEvent? = null

    init {
        try {
            if (!storageDir.exists()) {
                storageDir.mkdirs()
            }
        } catch (_: Exception) {
            // Failsafe: avoid startup crash if directory creation fails
        }
    }

    fun recordEvent(event: DiagnosticEvent) {
        // Update stats
        totalEventsRecorded.incrementAndGet()
        when (event.severity) {
            DiagnosticSeverity.ERROR, DiagnosticSeverity.FATAL -> {
                totalErrorsRecorded.incrementAndGet()
                lastErrorEvent = event
            }
            DiagnosticSeverity.WARN -> totalWarningsRecorded.incrementAndGet()
            DiagnosticSeverity.INFO -> {
                if (event.stage?.contains("SUCCESS", ignoreCase = true) == true ||
                    event.message.contains("نجاح", ignoreCase = true) ||
                    event.message.contains("success", ignoreCase = true)
                ) {
                    totalSuccessRecorded.incrementAndGet()
                }
            }
            DiagnosticSeverity.DEBUG -> {}
        }

        // Store in memory ring buffer
        inMemoryEvents.addFirst(event)
        while (inMemoryEvents.size > inMemoryCapacity) {
            inMemoryEvents.pollLast()
        }

        // Write to disk asynchronously
        diskExecutor.execute {
            writeEventToDisk(event)
        }
    }

    private fun writeEventToDisk(event: DiagnosticEvent) {
        try {
            if (!storageDir.exists()) {
                storageDir.mkdirs()
            }

            val currentFile = File(storageDir, "sonix_diag_0.log")
            if (currentFile.exists() && currentFile.length() >= maxFileSizeBytes) {
                rotateFiles()
            }

            var line = event.toJsonObject().toString()
            if (line.toByteArray(Charsets.UTF_8).size > maxRecordSizeBytes) {
                // Truncate safely
                line = line.take(maxRecordSizeBytes / 2) + "...[TRUNCATED]}"
            }

            FileWriter(currentFile, true).use { writer ->
                writer.write(line)
                writer.write("\n")
            }
        } catch (_: Throwable) {
            // Failsafe: Never allow log write failure to propagate
        }
    }

    @Synchronized
    private fun rotateFiles() {
        try {
            // Delete the oldest file if it exceeds the limit
            val oldest = File(storageDir, "sonix_diag_${maxRotatedFiles - 1}.log")
            if (oldest.exists()) {
                oldest.delete()
            }

            // Shift older files
            for (i in (maxRotatedFiles - 2) downTo 0) {
                val f = File(storageDir, "sonix_diag_$i.log")
                if (f.exists()) {
                    val dest = File(storageDir, "sonix_diag_${i + 1}.log")
                    f.renameTo(dest)
                }
            }
        } catch (_: Exception) {
            // Ignore rotation errors safely
        }
    }

    fun getRecentEvents(
        limit: Int = 100,
        component: DiagnosticComponent? = null,
        severity: DiagnosticSeverity? = null,
        category: ErrorCategory? = null
    ): List<DiagnosticEvent> {
        val filtered = inMemoryEvents.filter { event ->
            (component == null || event.component == component) &&
            (severity == null || event.severity == severity) &&
            (category == null || event.category == category)
        }
        return filtered.take(limit)
    }

    fun getOperationTimeline(operationId: String): List<DiagnosticEvent> {
        if (operationId.isBlank()) return emptyList()
        val fromMemory = inMemoryEvents.filter { it.operationId == operationId }
        val allMatching = mutableListOf<DiagnosticEvent>()
        allMatching.addAll(fromMemory)
        val seenIds = fromMemory.map { it.id }.toMutableSet()

        try {
            for (i in 0 until maxRotatedFiles) {
                val file = File(storageDir, "sonix_diag_$i.log")
                if (!file.exists()) continue
                BufferedReader(FileReader(file)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (line.isNullOrBlank() || !line!!.contains(operationId)) continue
                        try {
                            val json = JSONObject(line!!)
                            val event = DiagnosticEvent.fromJsonObject(json)
                            if (event.operationId == operationId && seenIds.add(event.id)) {
                                allMatching.add(event)
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}

        return allMatching.sortedWith(compareBy({ it.timestamp }, { it.sequence }))
    }

    fun getAllDiskEvents(maxEvents: Int = 2000): List<DiagnosticEvent> {
        val result = mutableListOf<DiagnosticEvent>()
        val seenIds = mutableSetOf<String>()

        // Recent from memory first
        inMemoryEvents.forEach {
            if (seenIds.add(it.id)) result.add(it)
        }

        // Then from disk files in chronological order
        try {
            for (i in (maxRotatedFiles - 1) downTo 0) {
                val file = File(storageDir, "sonix_diag_$i.log")
                if (!file.exists()) continue
                BufferedReader(FileReader(file)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (line.isNullOrBlank()) continue
                        try {
                            val json = JSONObject(line!!)
                            val event = DiagnosticEvent.fromJsonObject(json)
                            if (seenIds.add(event.id)) {
                                result.add(event)
                            }
                        } catch (_: Exception) {}
                    }
                }
                if (result.size >= maxEvents) break
            }
        } catch (_: Exception) {}

        return result.sortedByDescending { it.timestamp }.take(maxEvents)
    }

    fun getStorageSizeBytes(): Long {
        var total = 0L
        try {
            storageDir.listFiles()?.forEach { file ->
                if (file.isFile) total += file.length()
            }
        } catch (_: Exception) {}
        return total
    }

    fun clearLogs() {
        inMemoryEvents.clear()
        totalEventsRecorded.set(0)
        totalErrorsRecorded.set(0)
        totalWarningsRecorded.set(0)
        totalSuccessRecorded.set(0)
        lastErrorEvent = null

        diskExecutor.execute {
            try {
                storageDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.name.startsWith("sonix_diag_")) {
                        file.delete()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun getStats(isEnabled: Boolean, isVerbose: Boolean, activeOpsCount: Int): DashboardStats {
        return DashboardStats(
            isEnabled = isEnabled,
            isVerbose = isVerbose,
            errorCount = totalErrorsRecorded.get().toInt(),
            warningCount = totalWarningsRecorded.get().toInt(),
            successCount = totalSuccessRecorded.get().toInt(),
            totalEventCount = totalEventsRecorded.get().toInt(),
            lastErrorEvent = lastErrorEvent,
            logStorageSizeBytes = getStorageSizeBytes(),
            activeOperationCount = activeOpsCount
        )
    }

    fun saveCrashDump(crashData: String) {
        try {
            val crashFile = File(storageDir, "crash_dump.json")
            FileWriter(crashFile, false).use { writer ->
                writer.write(crashData)
            }
        } catch (_: Exception) {}
    }

    fun flush() {
        try {
            diskExecutor.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {}
    }

    fun shutdown() {
        try {
            diskExecutor.shutdown()
        } catch (_: Exception) {}
    }
}
