package io.github.amichne.kast.standalone.telemetry

import java.nio.file.Path

internal data class StandaloneTelemetryConfig(
    val enabled: Boolean,
    val scopes: Set<StandaloneTelemetryScope>,
    val detail: StandaloneTelemetryDetail,
    val outputFile: Path,
)
