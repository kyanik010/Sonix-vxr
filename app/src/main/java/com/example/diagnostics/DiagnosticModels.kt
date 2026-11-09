package com.example.diagnostics

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class DiagnosticSeverity {
    DEBUG,
    INFO,
    WARN,
    ERROR,
    FATAL
}

enum class DiagnosticComponent {
    SUBTITLE,
    PLAYER,
    NETWORK,
    XTREAM,
    EXTERNAL_AUDIO,
    WEBVIEW,
    NATIVE_BRIDGE,
    STORAGE,
    APP,
    SYSTEM
}

enum class ErrorCategory {
    NETWORK_ERROR,
    HTTP_ERROR,
    PARSE_ERROR,
    SUBTITLE_ERROR,
    PLAYER_ERROR,
    DECODER_ERROR,
    STORAGE_ERROR,
    AUTH_ERROR,
    WEBVIEW_ERROR,
    NATIVE_BRIDGE_ERROR,
    UNKNOWN_ERROR
}

enum class ExportScope {
    FULL,
    LAST_ERROR,
    SUBTITLES,
    PLAYER
}

enum class ExportFormat {
    JSON,
    TXT
}

data class DiagnosticEvent(
    val id: String,
    val sequence: Long = sequenceCounter.incrementAndGet(),
    val timestamp: Long = System.currentTimeMillis(),
    val severity: DiagnosticSeverity,
    val component: DiagnosticComponent,
    val operationId: String? = null,
    val operationName: String? = null,
    val stage: String? = null,
    val message: String,
    val category: ErrorCategory? = null,
    val exceptionClass: String? = null,
    val exceptionMessage: String? = null,
    val stackTrace: String? = null,
    val fileName: String? = null,
    val lineNumber: Int? = null,
    val metadata: Map<String, String> = emptyMap()
) {
    fun toIsoTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(timestamp))
    }

    fun toDisplayTime(): String {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun toJsonObject(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("sequence", sequence)
        obj.put("timestamp", timestamp)
        obj.put("iso_time", toIsoTimestamp())
        obj.put("severity", severity.name)
        obj.put("component", component.name)
        operationId?.let { obj.put("operation_id", it) }
        operationName?.let { obj.put("operation_name", it) }
        stage?.let { obj.put("stage", it) }
        obj.put("message", message)
        category?.let { obj.put("category", it.name) }
        exceptionClass?.let { obj.put("exception_class", it) }
        exceptionMessage?.let { obj.put("exception_message", it) }
        stackTrace?.let { obj.put("stack_trace", it) }
        fileName?.let { obj.put("file_name", it) }
        lineNumber?.let { obj.put("line_number", it) }
        if (metadata.isNotEmpty()) {
            val metaObj = JSONObject()
            metadata.forEach { (k, v) -> metaObj.put(k, v) }
            obj.put("metadata", metaObj)
        }
        return obj
    }

    companion object {
        private val sequenceCounter = java.util.concurrent.atomic.AtomicLong(0)

        fun fromJsonObject(json: JSONObject): DiagnosticEvent {
            val metaMap = mutableMapOf<String, String>()
            json.optJSONObject("metadata")?.let { meta ->
                meta.keys().forEach { key ->
                    metaMap[key] = meta.optString(key, "")
                }
            }
            return DiagnosticEvent(
                id = json.optString("id", ""),
                sequence = json.optLong("sequence", 0L),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                severity = try {
                    DiagnosticSeverity.valueOf(json.optString("severity", "INFO"))
                } catch (_: Exception) { DiagnosticSeverity.INFO },
                component = try {
                    DiagnosticComponent.valueOf(json.optString("component", "APP"))
                } catch (_: Exception) { DiagnosticComponent.APP },
                operationId = json.optString("operation_id").takeIf { it.isNotBlank() },
                operationName = json.optString("operation_name").takeIf { it.isNotBlank() },
                stage = json.optString("stage").takeIf { it.isNotBlank() },
                message = json.optString("message", ""),
                category = json.optString("category").takeIf { it.isNotBlank() }?.let {
                    try { ErrorCategory.valueOf(it) } catch (_: Exception) { null }
                },
                exceptionClass = json.optString("exception_class").takeIf { it.isNotBlank() },
                exceptionMessage = json.optString("exception_message").takeIf { it.isNotBlank() },
                stackTrace = json.optString("stack_trace").takeIf { it.isNotBlank() },
                fileName = json.optString("file_name").takeIf { it.isNotBlank() },
                lineNumber = if (json.has("line_number")) json.optInt("line_number") else null,
                metadata = metaMap
            )
        }
    }
}

data class DashboardStats(
    val isEnabled: Boolean,
    val isVerbose: Boolean,
    val errorCount: Int,
    val warningCount: Int,
    val successCount: Int,
    val totalEventCount: Int,
    val lastErrorEvent: DiagnosticEvent?,
    val logStorageSizeBytes: Long,
    val activeOperationCount: Int
) {
    fun toJsonObject(): JSONObject {
        val obj = JSONObject()
        obj.put("is_enabled", isEnabled)
        obj.put("is_verbose", isVerbose)
        obj.put("error_count", errorCount)
        obj.put("warning_count", warningCount)
        obj.put("success_count", successCount)
        obj.put("total_event_count", totalEventCount)
        obj.put("log_storage_size_bytes", logStorageSizeBytes)
        obj.put("active_operation_count", activeOperationCount)
        lastErrorEvent?.let { obj.put("last_error", it.toJsonObject()) }
        return obj
    }
}
