package io.github.amichne.kast.api.validation

import java.util.UUID

@JvmInline
value class DiagnosticPageToken private constructor(val value: String) {
    companion object {
        fun parse(value: String): DiagnosticPageToken {
            val parsed = UUID.fromString(value)
            require(parsed.toString() == value) { "Diagnostic page token must be a canonical UUID" }
            return DiagnosticPageToken(value)
        }

        fun random(): DiagnosticPageToken = DiagnosticPageToken(UUID.randomUUID().toString())
    }
}
