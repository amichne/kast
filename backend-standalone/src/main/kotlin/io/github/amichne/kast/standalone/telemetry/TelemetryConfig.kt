package io.github.amichne.kast.standalone.telemetry

import java.nio.file.Path

internal data class TelemetryConfig(
    val enabled: Boolean,
    val scopes: Set<TelemetryScope>,
    val detail: TelemetryDetail,
    val outputFile: Path,
)
