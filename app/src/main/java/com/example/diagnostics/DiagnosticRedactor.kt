package com.example.diagnostics

import java.util.regex.Pattern

object DiagnosticRedactor {
    private const val REDACTED = "***REDACTED***"

    private val QUERY_PARAM_PATTERNS = listOf(
        Pattern.compile("(?i)(password|passwd|pwd|pass)=([^&\\s]+)"),
        Pattern.compile("(?i)(username|user|usr)=([^&\\s]+)"),
        Pattern.compile("(?i)(token|access_token|refresh_token|api_key|apikey|secret|auth|bearer)=([^&\\s]+)")
    )

    private val JSON_KEY_PATTERN = Pattern.compile(
        """(?i)"(password|passwd|pwd|pass|username|user|token|access_token|refresh_token|api_key|apikey|secret|auth)"\s*:\s*"[^"]*""""
    )

    private val HTTP_AUTH_HEADER_PATTERN = Pattern.compile(
        """(?i)(Authorization|Proxy-Authorization)\s*:\s*(Bearer|Basic)\s+[^\s,;]+"""
    )

    private val URL_EMBEDDED_CREDENTIALS = Pattern.compile(
        """https?://([^:\s]+):([^@\s]+)@"""
    )

    // Xtream stream URL: http(s)://host:port/username/password/streamId(.ext)
    private val XTREAM_STREAM_URL_PATTERN = Pattern.compile(
        """(https?://[^/\s]+)/([^/\s]+)/([^/\s]+)/([0-9]+(?:\.[a-zA-Z0-9]+)?)"""
    )

    // Xtream VOD/Series/Live URL: http(s)://host:port/live|movie|series/username/password/streamId(.ext)
    private val XTREAM_MEDIA_URL_PATTERN = Pattern.compile(
        """(https?://[^/\s]+)/(live|movie|series)/([^/\s]+)/([^/\s]+)/([0-9]+(?:\.[a-zA-Z0-9]+)?)"""
    )

    fun redact(input: String?): String {
        if (input.isNullOrBlank()) return ""
        var result = input

        // 1. Redact URL embedded credentials (user:pass@host)
        result = URL_EMBEDDED_CREDENTIALS.matcher(result).replaceAll("http://$REDACTED:$REDACTED@")

        // 2. Redact Xtream media URLs
        result = XTREAM_MEDIA_URL_PATTERN.matcher(result).replaceAll("$1/$2/$REDACTED/$REDACTED/$5")

        // 3. Redact Xtream stream URLs
        result = XTREAM_STREAM_URL_PATTERN.matcher(result).replaceAll("$1/$REDACTED/$REDACTED/$4")

        // 4. Redact query parameters
        for (pattern in QUERY_PARAM_PATTERNS) {
            val matcher = pattern.matcher(result)
            val sb = StringBuffer()
            while (matcher.find()) {
                val key = matcher.group(1)
                matcher.appendReplacement(sb, "$key=$REDACTED")
            }
            matcher.appendTail(sb)
            result = sb.toString()
        }

        // 5. Redact JSON fields
        val jsonMatcher = JSON_KEY_PATTERN.matcher(result)
        val sbJson = StringBuffer()
        while (jsonMatcher.find()) {
            val key = jsonMatcher.group(1)
            jsonMatcher.appendReplacement(sbJson, """"$key":"$REDACTED"""")
        }
        jsonMatcher.appendTail(sbJson)
        result = sbJson.toString()

        // 6. Redact Auth headers
        result = HTTP_AUTH_HEADER_PATTERN.matcher(result).replaceAll("$1: $2 $REDACTED")

        return result
    }

    fun redactMap(map: Map<String, String>): Map<String, String> {
        if (map.isEmpty()) return emptyMap()
        return map.mapValues { (k, v) ->
            val lower = k.lowercase()
            if (lower.contains("pass") || lower.contains("user") || lower.contains("token") ||
                lower.contains("secret") || lower.contains("key") || lower.contains("auth") ||
                lower.contains("credential")
            ) {
                REDACTED
            } else {
                redact(v)
            }
        }
    }
}
