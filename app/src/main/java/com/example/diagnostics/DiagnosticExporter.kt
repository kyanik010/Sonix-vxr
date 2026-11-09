package com.example.diagnostics

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DiagnosticExporter(
    private val context: Context,
    private val storage: DiagnosticLogStorage
) {
    companion object {
        const val GIT_COMMIT = "b63a379"
        const val MEDIA3_VERSION = "1.5.1"
    }

    fun buildSystemMetadata(): JSONObject {
        val meta = JSONObject()
        val appInfo = JSONObject().apply {
            put("app_name", "SONIX TV")
            put("package_name", context.packageName)
            try {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                put("version_name", pInfo.versionName ?: "1.0.0")
                put("version_code", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode else @Suppress("DEPRECATION") pInfo.versionCode.toLong())
            } catch (_: Exception) {
                put("version_name", "1.0.0")
                put("version_code", 1L)
            }
            put("git_commit", GIT_COMMIT)
            put("target_sdk", context.applicationInfo.targetSdkVersion)
        }
        meta.put("app", appInfo)

        val deviceInfo = JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("brand", Build.BRAND)
            put("device", Build.DEVICE)
            put("android_release", Build.VERSION.RELEASE)
            put("sdk_int", Build.VERSION.SDK_INT)
            put("supported_abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
        }
        meta.put("device", deviceInfo)

        val mediaInfo = JSONObject().apply {
            put("media3_version", MEDIA3_VERSION)
            put("exoplayer_engine", "AndroidX Media3")
        }
        meta.put("media3", mediaInfo)

        return meta
    }

    fun exportReport(scope: ExportScope, format: ExportFormat): String {
        val events = when (scope) {
            ExportScope.FULL -> storage.getAllDiskEvents(1000)
            ExportScope.LAST_ERROR -> {
                val last = storage.getRecentEvents(1, severity = DiagnosticSeverity.ERROR).firstOrNull()
                    ?: storage.getRecentEvents(1, severity = DiagnosticSeverity.FATAL).firstOrNull()
                if (last != null && !last.operationId.isNullOrBlank()) {
                    storage.getOperationTimeline(last.operationId)
                } else if (last != null) {
                    listOf(last)
                } else {
                    emptyList()
                }
            }
            ExportScope.SUBTITLES -> storage.getAllDiskEvents(500).filter { it.component == DiagnosticComponent.SUBTITLE }
            ExportScope.PLAYER -> storage.getAllDiskEvents(500).filter {
                it.component == DiagnosticComponent.PLAYER || it.component == DiagnosticComponent.EXTERNAL_AUDIO
            }
        }

        return if (format == ExportFormat.JSON) {
            exportAsJson(scope, events)
        } else {
            exportAsText(scope, events)
        }
    }

    private fun exportAsJson(scope: ExportScope, events: List<DiagnosticEvent>): String {
        val root = JSONObject()
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        root.put("report_name", "SONIX DIAGNOSTICS REPORT")
        root.put("generated_at", sdf.format(Date()))
        root.put("scope", scope.name)
        root.put("system_metadata", buildSystemMetadata())

        val summary = JSONObject().apply {
            put("total_events_in_report", events.size)
            put("error_count", events.count { it.severity == DiagnosticSeverity.ERROR || it.severity == DiagnosticSeverity.FATAL })
            put("warning_count", events.count { it.severity == DiagnosticSeverity.WARN })
            put("info_count", events.count { it.severity == DiagnosticSeverity.INFO })
        }
        root.put("summary", summary)

        val eventsArray = JSONArray()
        events.forEach { event ->
            eventsArray.put(event.toJsonObject())
        }
        root.put("events", eventsArray)

        return root.toString(2)
    }

    private fun exportAsText(scope: ExportScope, events: List<DiagnosticEvent>): String {
        val sb = StringBuilder()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS z", Locale.getDefault())
        val dateStr = sdf.format(Date())

        sb.append("================================================================================\n")
        sb.append("             تقرير تشخيص النظام — SONIX DIAGNOSTICS REPORT\n")
        sb.append("================================================================================\n")
        sb.append("تاريخ الإنشاء: $dateStr\n")
        sb.append("نطاق التقرير: ${scope.name}\n")
        sb.append("--------------------------------------------------------------------------------\n")
        sb.append("[1] معلومات التطبيق والنظام (APP & SYSTEM INFORMATION)\n")
        sb.append("--------------------------------------------------------------------------------\n")
        sb.append("• التطبيق: SONIX TV (ID: ${context.packageName})\n")
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            sb.append("• إصدار التطبيق: ${pInfo.versionName} (كود: ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode else @Suppress("DEPRECATION") pInfo.versionCode})\n")
        } catch (_: Exception) {
            sb.append("• إصدار التطبيق: 1.0.0 (1)\n")
        }
        sb.append("• رقم Commit: $GIT_COMMIT\n")
        sb.append("• محرك Media3: $MEDIA3_VERSION\n")
        sb.append("• الجهاز: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.BRAND})\n")
        sb.append("• إصدار Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("• المعمارية (ABIs): ${Build.SUPPORTED_ABIS.joinToString(", ")}\n\n")

        sb.append("--------------------------------------------------------------------------------\n")
        sb.append("[2] ملخص الإحصائيات (SUMMARY STATISTICS)\n")
        sb.append("--------------------------------------------------------------------------------\n")
        val errorCount = events.count { it.severity == DiagnosticSeverity.ERROR || it.severity == DiagnosticSeverity.FATAL }
        val warnCount = events.count { it.severity == DiagnosticSeverity.WARN }
        val infoCount = events.count { it.severity == DiagnosticSeverity.INFO }
        sb.append("• إجمالي السجلات في هذا التقرير: ${events.size}\n")
        sb.append("• عدد الأخطاء (Errors): $errorCount\n")
        sb.append("• عدد التحذيرات (Warnings): $warnCount\n")
        sb.append("• أحداث عادية (Info/Debug): $infoCount\n\n")

        sb.append("--------------------------------------------------------------------------------\n")
        sb.append("[3] سجل الأحداث بالتفصيل (DETAILED EVENT LOG)\n")
        sb.append("--------------------------------------------------------------------------------\n")

        if (events.isEmpty()) {
            sb.append("لا توجد أحداث مسجلة ضمن النطاق المحدد.\n")
        } else {
            events.forEachIndexed { index, event ->
                val severityTag = when (event.severity) {
                    DiagnosticSeverity.FATAL -> "[FATAL ❌]"
                    DiagnosticSeverity.ERROR -> "[ERROR 🔴]"
                    DiagnosticSeverity.WARN -> "[WARN  ⚠️]"
                    DiagnosticSeverity.INFO -> "[INFO  ℹ️]"
                    DiagnosticSeverity.DEBUG -> "[DEBUG 🔍]"
                }
                sb.append("#${index + 1} $severityTag [${event.toDisplayTime()}] [${event.component.name}]\n")
                if (!event.operationName.isNullOrBlank() || !event.operationId.isNullOrBlank()) {
                    sb.append("    العملية: ${event.operationName ?: "N/A"} (OpID: ${event.operationId ?: "N/A"})\n")
                }
                if (!event.stage.isNullOrBlank()) {
                    sb.append("    المرحلة: ${event.stage}\n")
                }
                if (event.category != null) {
                    sb.append("    تصنيف الخطأ: ${event.category.name}\n")
                }
                sb.append("    الرسالة: ${event.message}\n")

                if (!event.fileName.isNullOrBlank() || event.lineNumber != null) {
                    sb.append("    الموقع: ${event.fileName ?: "Unknown"}:${event.lineNumber ?: 0}\n")
                }
                if (!event.exceptionClass.isNullOrBlank()) {
                    sb.append("    الاستثناء: ${event.exceptionClass}: ${event.exceptionMessage ?: ""}\n")
                }
                if (!event.stackTrace.isNullOrBlank()) {
                    sb.append("    Stack Trace:\n")
                    event.stackTrace.lines().take(15).forEach { line ->
                        sb.append("        $line\n")
                    }
                }
                if (event.metadata.isNotEmpty()) {
                    sb.append("    بيانات إضافية: ${event.metadata}\n")
                }
                sb.append("\n")
            }
        }

        sb.append("================================================================================\n")
        sb.append("نهاية تقرير SONIX DIAGNOSTICS (تم إخفاء كلمات المرور والتوكنات تلقائياً)\n")
        sb.append("================================================================================\n")

        return sb.toString()
    }

    fun exportToFile(scope: ExportScope, format: ExportFormat): File {
        val report = exportReport(scope, format)
        val ext = if (format == ExportFormat.JSON) "json" else "txt"
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "sonix_diag_${scope.name.lowercase(Locale.ROOT)}_$timestamp.$ext"
        val exportDir = File(context.cacheDir, "diagnostic_reports").apply { mkdirs() }
        val file = File(exportDir, fileName)
        FileWriter(file, false).use { writer ->
            writer.write(report)
        }
        return file
    }
}
