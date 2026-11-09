package com.example.diagnostics

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject

class SonixDiagnosticsBridge(
    private val activity: Activity,
    private val diagnostics: SonixDiagnostics
) {
    @JavascriptInterface
    fun getDashboardStats(): String {
        return try {
            val stats = diagnostics.getDashboardStats()
            stats.toJsonObject().toString()
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: "Failed to get stats").toString()
        }
    }

    @JavascriptInterface
    fun getRecentEvents(limit: Int, componentFilter: String?, severityFilter: String?, categoryFilter: String?): String {
        return try {
            val comp = componentFilter?.takeIf { it.isNotBlank() && it != "ALL" }?.let {
                try { DiagnosticComponent.valueOf(it) } catch (_: Exception) { null }
            }
            val sev = severityFilter?.takeIf { it.isNotBlank() && it != "ALL" }?.let {
                try { DiagnosticSeverity.valueOf(it) } catch (_: Exception) { null }
            }
            val cat = categoryFilter?.takeIf { it.isNotBlank() && it != "ALL" }?.let {
                try { ErrorCategory.valueOf(it) } catch (_: Exception) { null }
            }

            val events = diagnostics.getRecentEvents(
                limit = if (limit in 1..500) limit else 100,
                component = comp,
                severity = sev,
                category = cat
            )

            val arr = JSONArray()
            events.forEach { arr.put(it.toJsonObject()) }
            arr.toString()
        } catch (e: Exception) {
            JSONArray().toString()
        }
    }

    @JavascriptInterface
    fun getOperationTimeline(operationId: String): String {
        return try {
            val events = diagnostics.getOperationTimeline(operationId)
            val arr = JSONArray()
            events.forEach { arr.put(it.toJsonObject()) }
            arr.toString()
        } catch (e: Exception) {
            JSONArray().toString()
        }
    }

    @JavascriptInterface
    fun clearLogs(): String {
        return try {
            diagnostics.clearLogs()
            activity.runOnUiThread {
                Toast.makeText(activity, "تم مسح سجلات التشخيص بنجاح", Toast.LENGTH_SHORT).show()
            }
            JSONObject().put("success", true).toString()
        } catch (e: Exception) {
            JSONObject().put("success", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun exportReport(scopeStr: String?, formatStr: String?): String {
        return try {
            val scope = try {
                ExportScope.valueOf(scopeStr ?: "FULL")
            } catch (_: Exception) { ExportScope.FULL }

            val format = try {
                ExportFormat.valueOf(formatStr ?: "TXT")
            } catch (_: Exception) { ExportFormat.TXT }

            val reportContent = diagnostics.exportDiagnosticReport(scope, format)
            JSONObject()
                .put("success", true)
                .put("scope", scope.name)
                .put("format", format.name)
                .put("content", reportContent)
                .toString()
        } catch (e: Exception) {
            JSONObject().put("success", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun shareReport(scopeStr: String?, formatStr: String?) {
        try {
            val scope = try {
                ExportScope.valueOf(scopeStr ?: "FULL")
            } catch (_: Exception) { ExportScope.FULL }

            val format = try {
                ExportFormat.valueOf(formatStr ?: "TXT")
            } catch (_: Exception) { ExportFormat.TXT }

            val file = diagnostics.exportReportToFile(scope, format)
            activity.runOnUiThread {
                try {
                    val uri = FileProvider.getUriForFile(
                        activity,
                        "${activity.packageName}.fileprovider",
                        file
                    )
                    val mime = if (format == ExportFormat.JSON) "application/json" else "text/plain"
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "تقرير تشخيص SONIX TV (${scope.name})")
                        putExtra(Intent.EXTRA_TEXT, "مرفق تقرير تشخيص تطبيق SONIX TV بصيغة ${format.name}.")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(Intent.createChooser(shareIntent, "مشاركة تقرير التشخيص"))
                } catch (intentErr: Exception) {
                    // Fallback to text sharing
                    val content = diagnostics.exportDiagnosticReport(scope, format)
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "تقرير تشخيص SONIX TV")
                        putExtra(Intent.EXTRA_TEXT, content.take(100_000))
                    }
                    activity.startActivity(Intent.createChooser(shareIntent, "مشاركة تقرير التشخيص"))
                }
            }
        } catch (e: Exception) {
            activity.runOnUiThread {
                Toast.makeText(activity, "تعذر مشاركة التقرير: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @JavascriptInterface
    fun copyToClipboard(text: String, label: String? = null): String {
        return try {
            activity.runOnUiThread {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(label ?: "SONIX Diagnostics", text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(activity, "تم النسخ إلى الحافظة بنجاح", Toast.LENGTH_SHORT).show()
            }
            JSONObject().put("success", true).toString()
        } catch (e: Exception) {
            JSONObject().put("success", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun setDiagnosticsEnabled(enabled: Boolean): String {
        diagnostics.setDiagnosticsEnabled(enabled)
        return JSONObject().put("success", true).put("enabled", enabled).toString()
    }

    @JavascriptInterface
    fun setVerboseLogging(enabled: Boolean): String {
        diagnostics.setVerboseLoggingEnabled(enabled)
        return JSONObject().put("success", true).put("verbose", enabled).toString()
    }

    @JavascriptInterface
    fun logClientError(component: String, stage: String, message: String, stack: String?) {
        try {
            val comp = try {
                DiagnosticComponent.valueOf(component)
            } catch (_: Exception) { DiagnosticComponent.WEBVIEW }

            diagnostics.logError(
                component = comp,
                message = message,
                category = ErrorCategory.WEBVIEW_ERROR,
                stage = stage,
                metadata = mapOf("client_stack" to (stack ?: ""))
            )
        } catch (_: Exception) {}
    }
}
