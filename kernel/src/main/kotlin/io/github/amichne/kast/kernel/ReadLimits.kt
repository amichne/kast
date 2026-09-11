package io.github.amichne.kast.kernel

/** Operational capacities, separate from compiler identities and protocol grammar. */
enum class ReadLimitUnit { COUNT, CHARACTERS, BYTES, MILLISECONDS }
enum class ReadLimitParameter(val defaultValue: Int, val unit: ReadLimitUnit, val minimum: Int = 1) {
    MODEL_CACHED_GRADLE_MODELS(8, ReadLimitUnit.COUNT),
    MODEL_MODULES(256, ReadLimitUnit.COUNT),
    MODEL_SOURCE_ROOTS_PER_MODULE(256, ReadLimitUnit.COUNT),
    MODEL_CLASSPATH_ENTRIES_PER_MODULE(2_048, ReadLimitUnit.COUNT),
    MODEL_IDENTITY_CHARACTERS(512, ReadLimitUnit.CHARACTERS),
    MODEL_PATH_CHARACTERS(4_096, ReadLimitUnit.CHARACTERS),
    MODEL_CLASSPATH_URL_CHARACTERS(8_192, ReadLimitUnit.CHARACTERS),
    EPOCH_CACHED_GRADLE_MODELS(16, ReadLimitUnit.COUNT),
    EPOCH_VFS_EVENTS(4_096, ReadLimitUnit.COUNT),
    EPOCH_PATH_CHARACTERS(4_096, ReadLimitUnit.CHARACTERS),
    EPOCH_PATH_BYTES(8_192, ReadLimitUnit.BYTES),
    HOST_QUERY_MILLIS(2_000, ReadLimitUnit.MILLISECONDS),
    SEMANTIC_MILLIS(2_000, ReadLimitUnit.MILLISECONDS),
    SEMANTIC_WORK(100_000, ReadLimitUnit.COUNT),
    SEMANTIC_RESULTS(128, ReadLimitUnit.COUNT),
    SEMANTIC_RETURNED_BYTES(49_152, ReadLimitUnit.BYTES),
    DISCOVERY_NAMES(10_000, ReadLimitUnit.COUNT),
    DISCOVERY_CANDIDATES(10_000, ReadLimitUnit.COUNT),
    RELATION_CANDIDATES(10_000, ReadLimitUnit.COUNT),
    SOURCE_ENTITY_WORK(10_000, ReadLimitUnit.COUNT),
    SOURCE_CONTINUATIONS(1_024, ReadLimitUnit.COUNT),
    SOURCE_ENTITIES(128, ReadLimitUnit.COUNT),
    SOURCE_RETURNED_BYTES(49_152, ReadLimitUnit.BYTES),
    TRAVERSAL_DEPTH(16, ReadLimitUnit.COUNT),
    TRAVERSAL_FRONTIER(128, ReadLimitUnit.COUNT),
    HOST_REQUEST_BYTES(16_384, ReadLimitUnit.BYTES, 256),
    HOST_RESPONSE_BYTES(65_536, ReadLimitUnit.BYTES, 256),
    HOST_DESCRIPTOR_BYTES(16_384, ReadLimitUnit.BYTES, 256),
    HOST_CONNECTION_MILLIS(5_000, ReadLimitUnit.MILLISECONDS),
    CLIENT_EXCHANGE_MILLIS(6_000, ReadLimitUnit.MILLISECONDS),
    HOST_FILE_CHARACTERS(262_144, ReadLimitUnit.CHARACTERS),
    HOST_CLASS_CANDIDATES(32, ReadLimitUnit.COUNT),
    DIAGNOSTIC_SCOPE_FILES(256, ReadLimitUnit.COUNT),
    DIAGNOSTIC_SCOPE_WORK(20_000, ReadLimitUnit.COUNT),
    DIAGNOSTIC_SCOPE_MILLIS(2_000, ReadLimitUnit.MILLISECONDS),
    DIAGNOSTIC_COUNT(1_000_000_000, ReadLimitUnit.COUNT),
    DIAGNOSTIC_FAILURES(16, ReadLimitUnit.COUNT),
    DIAGNOSTIC_FRAMES(8, ReadLimitUnit.COUNT),
    DIAGNOSTIC_TEXT_CHARACTERS(256, ReadLimitUnit.CHARACTERS),
    PROVIDER_OUTPUT_BYTES(512 * 1_024, ReadLimitUnit.BYTES, 256),
    PROVIDER_INVOCATION_MILLIS(1_080_000, ReadLimitUnit.MILLISECONDS),
    PROVIDER_GRAPH_INVOCATION_MILLIS(1_260_000, ReadLimitUnit.MILLISECONDS),
    PROCESS_INPUT_BYTES(4 * 1_024 * 1_024, ReadLimitUnit.BYTES, 256),
    PROCESS_OUTPUT_BYTES(64 * 1_024 * 1_024, ReadLimitUnit.BYTES, 256),
    PROCESS_TIMEOUT_MILLIS(1_260_000, ReadLimitUnit.MILLISECONDS),
    ;

    val environmentKey: String get() = "KAST_READ_$name"
    val propertyKey: String get() = "kast.read.${name.lowercase().replace('_', '.')}"
    // Consumers use signed JVM array/count APIs, including a one-unit overflow probe.
    val maximum: Int get() = Int.MAX_VALUE - 1
}

enum class ReadLimitSource { DEFAULT, ENVIRONMENT, JVM_PROPERTY, COMMAND_LINE, SAVED_WORKSPACE, SAVED_INSTALLATION }
enum class ReadLimitValueFailure { INVALID_NUMBER, OUT_OF_RANGE }
sealed interface ReadLimitFailure {
    data object UnknownParameter : ReadLimitFailure
    data class InvalidValue(val parameter: ReadLimitParameter, val kind: ReadLimitValueFailure) : ReadLimitFailure
    data class InconsistentBounds(val inner: ReadLimitParameter, val outer: ReadLimitParameter) : ReadLimitFailure
}

/** Every value retains its identity, admitted range, and selected source. */
class ReadLimitValue private constructor(
    val parameter: ReadLimitParameter,
    val value: Int,
    val source: ReadLimitSource,
) {
    companion object {
        fun admit(parameter: ReadLimitParameter, raw: String, source: ReadLimitSource): Refinement<ReadLimitValue, ReadLimitFailure> {
            if (raw.isEmpty() || raw.any { it !in '0'..'9' }) {
                return Refinement.Rejected(ReadLimitFailure.InvalidValue(parameter, ReadLimitValueFailure.INVALID_NUMBER))
            }
            val value = raw.toIntOrNull()
            if (value == null || value !in parameter.minimum..parameter.maximum) {
                return Refinement.Rejected(ReadLimitFailure.InvalidValue(parameter, ReadLimitValueFailure.OUT_OF_RANGE))
            }
            return Refinement.Refined(ReadLimitValue(parameter, value, source))
        }
    }
}

/** Immutable request/lifetime policy. No environment, file, time, or global mutable state. */
class ReadLimits private constructor(private val limits: Map<ReadLimitParameter, ReadLimitValue>) {
    val values: List<ReadLimitValue> get() = ReadLimitParameter.entries.map(limits::getValue)
    operator fun get(parameter: ReadLimitParameter): ReadLimitValue = limits.getValue(parameter)

    companion object {
        val Default: ReadLimits = ReadLimits(ReadLimitParameter.entries.associateWith {
            (ReadLimitValue.admit(it, it.defaultValue.toString(), ReadLimitSource.DEFAULT) as Refinement.Refined).value
        })

        /** All supplied values are checked, including shadowed inputs; raw values never enter failure data. */
        fun resolve(
            environment: Map<String, String> = emptyMap(),
            properties: Map<String, String> = emptyMap(),
        ): Refinement<ReadLimits, ReadLimitFailure> {
            val selected = Default.limits.toMutableMap()
            for ((inputs, source) in listOf(environment to ReadLimitSource.ENVIRONMENT, properties to ReadLimitSource.JVM_PROPERTY)) {
                val prefix = if (source == ReadLimitSource.ENVIRONMENT) "KAST_READ_" else "kast.read."
                for ((key, raw) in inputs) {
                    if (!key.startsWith(prefix)) continue
                    val parameter = ReadLimitParameter.entries.singleOrNull {
                        key == if (source == ReadLimitSource.ENVIRONMENT) it.environmentKey else it.propertyKey
                    } ?: return Refinement.Rejected(ReadLimitFailure.UnknownParameter)
                    when (val admitted = ReadLimitValue.admit(parameter, raw, source)) {
                        is Refinement.Refined -> selected[parameter] = admitted.value
                        is Refinement.Rejected -> return admitted
                    }
                }
            }
            return admit(selected.values.toList())
        }

        /** Retains provenance from an already admitted configuration source. */
        fun admit(values: List<ReadLimitValue>): Refinement<ReadLimits, ReadLimitFailure> {
            if (values.map { it.parameter }.distinct().size != values.size) {
                return Refinement.Rejected(ReadLimitFailure.UnknownParameter)
            }
            val selected = Default.limits + values.associateBy { it.parameter }
            for ((inner, outer) in listOf(
                ReadLimitParameter.SEMANTIC_MILLIS to ReadLimitParameter.HOST_QUERY_MILLIS,
                ReadLimitParameter.DIAGNOSTIC_SCOPE_MILLIS to ReadLimitParameter.HOST_QUERY_MILLIS,
                ReadLimitParameter.HOST_QUERY_MILLIS to ReadLimitParameter.HOST_CONNECTION_MILLIS,
                ReadLimitParameter.HOST_CONNECTION_MILLIS to ReadLimitParameter.CLIENT_EXCHANGE_MILLIS,
                ReadLimitParameter.CLIENT_EXCHANGE_MILLIS to ReadLimitParameter.PROVIDER_INVOCATION_MILLIS,
                ReadLimitParameter.SEMANTIC_RETURNED_BYTES to ReadLimitParameter.HOST_RESPONSE_BYTES,
                ReadLimitParameter.SOURCE_RETURNED_BYTES to ReadLimitParameter.HOST_RESPONSE_BYTES,
                ReadLimitParameter.HOST_RESPONSE_BYTES to ReadLimitParameter.PROVIDER_OUTPUT_BYTES,
                ReadLimitParameter.CLIENT_EXCHANGE_MILLIS to ReadLimitParameter.PROVIDER_GRAPH_INVOCATION_MILLIS,
                ReadLimitParameter.PROVIDER_OUTPUT_BYTES to ReadLimitParameter.PROCESS_OUTPUT_BYTES,
                ReadLimitParameter.HOST_REQUEST_BYTES to ReadLimitParameter.PROCESS_INPUT_BYTES,
                ReadLimitParameter.PROVIDER_INVOCATION_MILLIS to ReadLimitParameter.PROCESS_TIMEOUT_MILLIS,
                ReadLimitParameter.PROVIDER_GRAPH_INVOCATION_MILLIS to ReadLimitParameter.PROCESS_TIMEOUT_MILLIS,
            )) {
                if (selected.getValue(inner).value > selected.getValue(outer).value) {
                    return Refinement.Rejected(ReadLimitFailure.InconsistentBounds(inner, outer))
                }
            }
            return Refinement.Refined(ReadLimits(selected.toMap()))
        }
    }
}
