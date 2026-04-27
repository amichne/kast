package io.github.amichne.kast.standalone.telemetry

internal enum class TelemetryDetail {
    BASIC,
    VERBOSE,
    ;

    companion object {
        fun parse(rawValue: String?): TelemetryDetail = when (rawValue?.trim()?.lowercase()) {
            "verbose" -> VERBOSE
            else -> BASIC
        }
    }
}
