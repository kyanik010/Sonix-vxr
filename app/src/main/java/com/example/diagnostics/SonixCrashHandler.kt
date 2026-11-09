package com.example.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter

class SonixCrashHandler(
    private val diagnostics: SonixDiagnostics,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            throwable.printStackTrace(pw)
            val stack = sw.toString()

            val topFrame = throwable.stackTrace.firstOrNull()
            val fileName = topFrame?.fileName
            val lineNumber = topFrame?.lineNumber

            // Record fatal event
            diagnostics.logFatal(
                component = DiagnosticComponent.APP,
                message = "Uncaught Exception in thread '${thread.name}': ${throwable.message ?: throwable.javaClass.simpleName}",
                throwable = throwable,
                fileName = fileName,
                lineNumber = lineNumber
            )

            // Create crash dump
            val crashDump = JSONObject().apply {
                put("thread_name", thread.name)
                put("thread_id", thread.id)
                put("exception_class", throwable.javaClass.name)
                put("exception_message", throwable.message ?: "")
                put("stack_trace", DiagnosticRedactor.redact(stack))
                put("timestamp", System.currentTimeMillis())

                val recentEvents = diagnostics.getRecentEvents(20)
                val eventsArr = JSONArray()
                recentEvents.forEach { eventsArr.put(it.toJsonObject()) }
                put("recent_events", eventsArr)

                val activeOps = diagnostics.getActiveOperations()
                val opsArr = JSONArray()
                activeOps.forEach { opsArr.put(it) }
                put("active_operations", opsArr)
            }

            diagnostics.saveCrashDump(crashDump.toString(2))
        } catch (_: Throwable) {
            // Failsafe
        } finally {
            // Forward to original handler so Android default crash dialog / process exit works as expected
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        fun install(diagnostics: SonixDiagnostics) {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current !is SonixCrashHandler) {
                Thread.setDefaultUncaughtExceptionHandler(SonixCrashHandler(diagnostics, current))
            }
        }
    }
}
