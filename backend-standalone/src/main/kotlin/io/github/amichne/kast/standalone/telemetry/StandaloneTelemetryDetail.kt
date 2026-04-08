package io.github.amichne.kast.standalone.telemetry

internal enum class StandaloneTelemetryDetail {
    BASIC,
    VERBOSE,
    ;

    companion object {
        fun parse(rawValue: String?): StandaloneTelemetryDetail = when (rawValue?.trim()?.lowercase()) {
            "verbose" -> VERBOSE
            else -> BASIC
        }
    }
}
