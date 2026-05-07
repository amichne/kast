package io.amichne.konditional.core.ops

/**
 * Operational hooks for observability.
 *
 * These hooks are intentionally dependency-free:
 * - no logging framework dependency (callers can bridge to SLF4J, Log4j, etc.)
 * - no metrics framework dependency (callers can bridge to Micrometer, OpenTelemetry, etc.)
 */
@ConsistentCopyVisibility
data class RegistryHooks internal constructor(
    val logger: KonditionalLogger = KonditionalLogger.NoOp,
    val metrics: MetricsCollector = MetricsCollector.NoOp,
) {
    companion object {
        val None: RegistryHooks = RegistryHooks()
        fun of(
            logger: KonditionalLogger = KonditionalLogger.NoOp,
            metrics: MetricsCollector = MetricsCollector.NoOp,
        ): RegistryHooks = RegistryHooks(logger, metrics)
    }
}
