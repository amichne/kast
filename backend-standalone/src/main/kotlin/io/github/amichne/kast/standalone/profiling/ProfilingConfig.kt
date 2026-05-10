package io.github.amichne.kast.standalone.profiling

import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.fields.ProfilingMode
import java.nio.file.Path

internal data class ProfilingConfig(
    val enabled: Boolean,
    val modes: Set<ProfilingMode>,
    val durationSeconds: Long,
    val outputDir: Path,
    val otlpEndpoint: String?,
    val emitManifest: Boolean,
) {
    companion object {
        fun fromConfig(
            config: KastConfig,
            logsDir: Path = Path.of(config.paths.logsDir.value),
        ): ProfilingConfig {
            val profiling = config.profiling
            val outputDir = Path.of(
                profiling.outputDir.value.replace("{logsDir}", logsDir.toString()),
            ).toAbsolutePath().normalize()
            return ProfilingConfig(
                enabled = profiling.enabled.value,
                modes = profiling.modes.parseModes(),
                durationSeconds = profiling.durationSeconds.value,
                outputDir = outputDir,
                otlpEndpoint = profiling.otlpEndpoint.value.orNull,
                emitManifest = profiling.emitManifest.value,
            )
        }
    }
}
