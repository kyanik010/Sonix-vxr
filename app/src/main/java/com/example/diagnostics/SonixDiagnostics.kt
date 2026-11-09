package com.example.diagnostics

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class SonixDiagnostics private constructor(
    private val appContext: Context,
    storageDir: File? = null
) {
    private val prefs: SharedPreferences = appContext.getSharedPreferences("sonix_diagnostics_prefs", Context.MODE_PRIVATE)
    private val isEnabled = AtomicBoolean(prefs.getBoolean("diag_enabled", true))
    private val isVerbose = AtomicBoolean(prefs.getBoolean("diag_verbose", false))

    private val storage: DiagnosticLogStorage = DiagnosticLogStorage(
        storageDir = storageDir ?: File(appContext.filesDir, "sonix_diagnostics")
    )

    private val exporter: DiagnosticExporter = DiagnosticExporter(appContext, storage)

    private data class ActiveOperation(
        val id: String,
        val name: String,
        val component: DiagnosticComponent,
        val startedAt: Long,
        val parentId: String? = null
    )

    private val activeOperations = ConcurrentHashMap<String, ActiveOperation>()

    init {
        SonixCrashHandler.install(this)
    }

    fun isDiagnosticsEnabled(): Boolean = isEnabled.get()

    fun setDiagnosticsEnabled(enabled: Boolean) {
        isEnabled.set(enabled)
        prefs.edit().putBoolean("diag_enabled", enabled).apply()
        logInfo(DiagnosticComponent.APP, "تم ${if (enabled) "تفعيل" else "تعطيل"} نظام تشخيص SONIX DIAGNOSTICS")
    }

    fun isVerboseLoggingEnabled(): Boolean = isVerbose.get()

    fun setVerboseLoggingEnabled(enabled: Boolean) {
        isVerbose.set(enabled)
        prefs.edit().putBoolean("diag_verbose", enabled).apply()
        logInfo(DiagnosticComponent.APP, "تم ${if (enabled) "تفعيل" else "تعطيل"} التسجيل التفصيلي (Verbose Logging)")
    }

    fun beginOperation(
        component: DiagnosticComponent,
        operationName: String,
        parentOpId: String? = null,
        metadata: Map<String, String> = emptyMap()
    ): String {
        val opId = "op_${System.currentTimeMillis()}_${(1000..9999).random()}"
        val op = ActiveOperation(
            id = opId,
            name = operationName,
            component = component,
            startedAt = System.currentTimeMillis(),
            parentId = parentOpId
        )
        activeOperations[opId] = op

        val safeMeta = metadata.toMutableMap()
        parentOpId?.let { safeMeta["parent_operation_id"] = it }

        if (isVerbose.get() || component == DiagnosticComponent.SUBTITLE || component == DiagnosticComponent.PLAYER) {
            logEvent(
                severity = DiagnosticSeverity.INFO,
                component = component,
                message = "بدء العملية: $operationName",
                operationId = opId,
                operationName = operationName,
                stage = "START",
                metadata = safeMeta
            )
        }
        return opId
    }

    fun endOperation(
        operationId: String,
        success: Boolean = true,
        summary: String = "",
        metadata: Map<String, String> = emptyMap()
    ) {
        val op = activeOperations.remove(operationId)
        val durationMs = if (op != null) System.currentTimeMillis() - op.startedAt else null
        val safeMeta = metadata.toMutableMap()
        durationMs?.let { safeMeta["duration_ms"] = it.toString() }

        val msg = if (summary.isNotBlank()) summary else {
            "اكتملت العملية ${op?.name ?: operationId} ${if (success) "بنجاح" else "بفشل"} (${durationMs ?: 0}ms)"
        }

        logEvent(
            severity = if (success) DiagnosticSeverity.INFO else DiagnosticSeverity.WARN,
            component = op?.component ?: DiagnosticComponent.APP,
            message = msg,
            operationId = operationId,
            operationName = op?.name,
            stage = if (success) "COMPLETED_SUCCESS" else "COMPLETED_WITH_WARNING",
            metadata = safeMeta
        )
    }

    fun failOperation(
        operationId: String,
        stage: String,
        error: Throwable,
        category: ErrorCategory? = null,
        metadata: Map<String, String> = emptyMap()
    ) {
        val op = activeOperations.remove(operationId)
        val durationMs = if (op != null) System.currentTimeMillis() - op.startedAt else null
        val safeMeta = metadata.toMutableMap()
        durationMs?.let { safeMeta["duration_ms"] = it.toString() }

        logException(
            component = op?.component ?: DiagnosticComponent.APP,
            stage = stage,
            error = error,
            operationId = operationId,
            operationName = op?.name,
            category = category ?: determineErrorCategory(error),
            metadata = safeMeta
        )
    }

    fun logDebug(
        component: DiagnosticComponent,
        message: String,
        operationId: String? = null,
        metadata: Map<String, String> = emptyMap()
    ) {
        if (!isEnabled.get() || !isVerbose.get()) return
        logEvent(
            severity = DiagnosticSeverity.DEBUG,
            component = component,
            message = message,
            operationId = operationId,
            metadata = metadata
        )
    }

    fun logInfo(
        component: DiagnosticComponent,
        message: String,
        operationId: String? = null,
        metadata: Map<String, String> = emptyMap()
    ) {
        if (!isEnabled.get()) return
        logEvent(
            severity = DiagnosticSeverity.INFO,
            component = component,
            message = message,
            operationId = operationId,
            metadata = metadata
        )
    }

    fun logWarning(
        component: DiagnosticComponent,
        message: String,
        operationId: String? = null,
        metadata: Map<String, String> = emptyMap(),
        error: Throwable? = null
    ) {
        if (!isEnabled.get()) return
        val (fileName, line) = extractCallerInfo(error)
        logEvent(
            severity = DiagnosticSeverity.WARN,
            component = component,
            message = message,
            operationId = operationId,
            exceptionClass = error?.javaClass?.name,
            exceptionMessage = error?.message,
            stackTrace = error?.let { getStackTraceString(it) },
            fileName = fileName,
            lineNumber = line,
            metadata = metadata
        )
    }

    fun logError(
        component: DiagnosticComponent,
        message: String,
        operationId: String? = null,
        category: ErrorCategory? = null,
        error: Throwable? = null,
        stage: String? = null,
        fileName: String? = null,
        lineNumber: Int? = null,
        metadata: Map<String, String> = emptyMap()
    ) {
        if (!isEnabled.get()) return
        val callerInfo = if (fileName != null) fileName to lineNumber else extractCallerInfo(error)
        logEvent(
            severity = DiagnosticSeverity.ERROR,
            component = component,
            message = message,
            operationId = operationId,
            category = category ?: error?.let { determineErrorCategory(it) },
            exceptionClass = error?.javaClass?.name,
            exceptionMessage = error?.message,
            stackTrace = error?.let { getStackTraceString(it) },
            stage = stage,
            fileName = callerInfo.first,
            lineNumber = callerInfo.second,
            metadata = metadata
        )
    }

    fun logException(
        component: DiagnosticComponent,
        stage: String,
        error: Throwable,
        operationId: String? = null,
        operationName: String? = null,
        category: ErrorCategory? = null,
        metadata: Map<String, String> = emptyMap()
    ) {
        if (!isEnabled.get()) return
        val (fileName, line) = extractCallerInfo(error)
        val resolvedCategory = category ?: determineErrorCategory(error)
        val msg = "فشل في مرحلة [$stage]: ${error.message ?: error.javaClass.simpleName}"

        logEvent(
            severity = DiagnosticSeverity.ERROR,
            component = component,
            message = msg,
            operationId = operationId,
            operationName = operationName,
            stage = stage,
            category = resolvedCategory,
            exceptionClass = error.javaClass.name,
            exceptionMessage = error.message,
            stackTrace = getStackTraceString(error),
            fileName = fileName,
            lineNumber = line,
            metadata = metadata
        )
    }

    fun logFatal(
        component: DiagnosticComponent,
        message: String,
        throwable: Throwable,
        fileName: String? = null,
        lineNumber: Int? = null
    ) {
        logEvent(
            severity = DiagnosticSeverity.FATAL,
            component = component,
            message = message,
            category = determineErrorCategory(throwable),
            exceptionClass = throwable.javaClass.name,
            exceptionMessage = throwable.message,
            stackTrace = getStackTraceString(throwable),
            fileName = fileName,
            lineNumber = lineNumber
        )
    }

    private fun logEvent(
        severity: DiagnosticSeverity,
        component: DiagnosticComponent,
        message: String,
        operationId: String? = null,
        operationName: String? = null,
        stage: String? = null,
        category: ErrorCategory? = null,
        exceptionClass: String? = null,
        exceptionMessage: String? = null,
        stackTrace: String? = null,
        fileName: String? = null,
        lineNumber: Int? = null,
        metadata: Map<String, String> = emptyMap()
    ) {
        try {
            // Apply Redaction to protect sensitive credentials and URLs
            val cleanMessage = DiagnosticRedactor.redact(message)
            val cleanExceptionMsg = DiagnosticRedactor.redact(exceptionMessage)
            val cleanStackTrace = DiagnosticRedactor.redact(stackTrace)
            val cleanMetadata = DiagnosticRedactor.redactMap(metadata)

            val event = DiagnosticEvent(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                severity = severity,
                component = component,
                operationId = operationId,
                operationName = operationName,
                stage = stage,
                message = cleanMessage,
                category = category,
                exceptionClass = exceptionClass,
                exceptionMessage = cleanExceptionMsg,
                stackTrace = cleanStackTrace,
                fileName = fileName,
                lineNumber = lineNumber,
                metadata = cleanMetadata
            )

            // Logcat bridge for real-time development visibility
            val logTag = "SONIX_DIAG_${component.name}"
            when (severity) {
                DiagnosticSeverity.DEBUG -> Log.d(logTag, cleanMessage)
                DiagnosticSeverity.INFO -> Log.i(logTag, cleanMessage)
                DiagnosticSeverity.WARN -> Log.w(logTag, cleanMessage)
                DiagnosticSeverity.ERROR -> Log.e(logTag, cleanMessage)
                DiagnosticSeverity.FATAL -> Log.wtf(logTag, cleanMessage)
            }

            storage.recordEvent(event)
        } catch (t: Throwable) {
            // Failsafe: system error in diagnostics should never crash the app
            Log.e("SONIX_DIAGNOSTICS", "Diagnostic logging error", t)
        }
    }

    fun getRecentEvents(
        limit: Int = 100,
        component: DiagnosticComponent? = null,
        severity: DiagnosticSeverity? = null,
        category: ErrorCategory? = null
    ): List<DiagnosticEvent> {
        return storage.getRecentEvents(limit, component, severity, category)
    }

    fun getOperationTimeline(operationId: String): List<DiagnosticEvent> {
        return storage.getOperationTimeline(operationId)
    }

    fun getDashboardStats(): DashboardStats {
        return storage.getStats(
            isEnabled = isEnabled.get(),
            isVerbose = isVerbose.get(),
            activeOpsCount = activeOperations.size
        )
    }

    fun clearLogs() {
        storage.clearLogs()
        logInfo(DiagnosticComponent.SYSTEM, "تم مسح جميع سجلات التشخيص بنجاح")
    }

    fun exportDiagnosticReport(scope: ExportScope = ExportScope.FULL, format: ExportFormat = ExportFormat.JSON): String {
        return exporter.exportReport(scope, format)
    }

    fun exportReportToFile(scope: ExportScope = ExportScope.FULL, format: ExportFormat = ExportFormat.TXT): File {
        return exporter.exportToFile(scope, format)
    }

    fun saveCrashDump(crashData: String) {
        storage.saveCrashDump(crashData)
    }

    fun getActiveOperations(): List<String> {
        return activeOperations.values.map { "${it.id}: ${it.name} (${it.component.name}) running for ${System.currentTimeMillis() - it.startedAt}ms" }
    }

    private fun determineErrorCategory(throwable: Throwable): ErrorCategory {
        val name = throwable.javaClass.simpleName.lowercase()
        val msg = (throwable.message ?: "").lowercase()
        return when {
            name.contains("socket") || name.contains("connect") || name.contains("unknownhost") ||
            name.contains("timeout") || msg.contains("timeout") || msg.contains("connection") -> ErrorCategory.NETWORK_ERROR

            name.contains("http") || msg.contains("http 4") || msg.contains("http 5") -> ErrorCategory.HTTP_ERROR

            name.contains("json") || name.contains("parse") || msg.contains("parse") || msg.contains("malformed") -> ErrorCategory.PARSE_ERROR

            name.contains("subtitle") || msg.contains("ترجمة") || msg.contains("subtitle") -> ErrorCategory.SUBTITLE_ERROR

            name.contains("decoder") || name.contains("codec") || msg.contains("decoder") || msg.contains("codec") -> ErrorCategory.DECODER_ERROR

            name.contains("playback") || name.contains("player") || msg.contains("exoplayer") -> ErrorCategory.PLAYER_ERROR

            name.contains("auth") || msg.contains("auth") || msg.contains("401") || msg.contains("403") || msg.contains("اشتراك") -> ErrorCategory.AUTH_ERROR

            name.contains("io") || name.contains("file") || msg.contains("storage") || msg.contains("space") -> ErrorCategory.STORAGE_ERROR

            else -> ErrorCategory.UNKNOWN_ERROR
        }
    }

    private fun extractCallerInfo(throwable: Throwable?): Pair<String?, Int?> {
        val trace = throwable?.stackTrace ?: Thread.currentThread().stackTrace
        for (element in trace) {
            val cls = element.className
            if (!cls.contains("SonixDiagnostics") && !cls.contains("Diagnostic") &&
                !cls.contains("java.lang.Thread") && !cls.contains("dalvik.")
            ) {
                return element.fileName to element.lineNumber
            }
        }
        return null to null
    }

    private fun getStackTraceString(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        return sw.toString()
    }

    companion object {
        @Volatile
        private var instance: SonixDiagnostics? = null

        fun init(context: Context, storageDir: File? = null): SonixDiagnostics {
            return instance ?: synchronized(this) {
                instance ?: SonixDiagnostics(context.applicationContext, storageDir).also { instance = it }
            }
        }

        fun getInstance(): SonixDiagnostics {
            return instance ?: throw IllegalStateException("SonixDiagnostics is not initialized. Call init(context) first.")
        }

        fun get(): SonixDiagnostics? = instance
    }
}
