package io.github.amichne.kast.intellij

/**
 * Telemetry detail level for the settings UI dropdown.
 * Mirrors IntelliJTelemetryDetail but is used for settings persistence/display.
 */
internal enum class KastTelemetryDetailLevel(val configValue: String) {
    BASIC("basic"),
    VERBOSE("verbose"),
    ;

    override fun toString(): String = configValue

    companion object {
        fun fromConfigValue(value: String?): KastTelemetryDetailLevel =
            entries.firstOrNull { it.configValue.equals(value?.trim(), ignoreCase = true) } ?: BASIC
    }
}
