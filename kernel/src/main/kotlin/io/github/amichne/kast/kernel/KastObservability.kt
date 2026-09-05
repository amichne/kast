package io.github.amichne.kast.kernel

/** Stable, bounded span identities for the first instrumented orchestration surfaces. */
enum class KastSpanName(
    val wireName: String,
) {
    RELATION_READ("kast.relation.read"),
    SYMBOL_DISCOVERY("kast.symbol.discovery"),
    TOPOLOGY_BUILD("kast.topology.build"),
    TOPOLOGY_SNAPSHOT_ELIGIBILITY("kast.topology.snapshot.eligibility"),
    TOPOLOGY_CANDIDATE_ENUMERATION("kast.topology.candidates.enumeration"),
    TOPOLOGY_SNAPSHOT_READ("kast.topology.snapshot.read"),
    TOPOLOGY_EXTRACTION("kast.topology.extraction"),
    TOPOLOGY_REVALIDATION("kast.topology.revalidation"),
    TOPOLOGY_PUBLICATION("kast.topology.publication"),
    TRAVERSAL_RUN("kast.traversal.run"),
    TRAVERSAL_WORKSPACE("kast.traversal.workspace"),
    TRAVERSAL_SNAPSHOT_ELIGIBILITY("kast.traversal.snapshot.eligibility"),
    TRAVERSAL_SNAPSHOT_OPEN("kast.traversal.snapshot.open"),
    TRAVERSAL_EXPANSION("kast.traversal.expansion"),
}
/** Low-cardinality terminal failure families; request values can never enter this type. */
enum class KastSpanFailure {
    RELATION_WORKSPACE_NOT_READY,
    RELATION_WORKSPACE_MOVED,
    RELATION_QUERY_REJECTED,
    SYMBOL_WORKSPACE_NOT_READY,
    SYMBOL_STALE_GENERATION,
    SYMBOL_QUERY_REJECTED,
    TOPOLOGY_WORKSPACE_NOT_READY,
    TOPOLOGY_WORKSPACE_MOVED,
    TOPOLOGY_SNAPSHOT,
    TOPOLOGY_ENUMERATION,
    TOPOLOGY_EXTRACTION,
    TOPOLOGY_COVERAGE,
    TOPOLOGY_PUBLICATION,
    TRAVERSAL_WORKSPACE_NOT_READY,
    TRAVERSAL_STALE_GENERATION,
    TRAVERSAL_EVIDENCE_STALE,
    TRAVERSAL_EVIDENCE_UNAVAILABLE,
    TRAVERSAL_ONE_HOP,
    TRAVERSAL_READER_CONTRACT,
    TRAVERSAL_CONTRACT,
}

enum class KastSpanCountFailure {
    NEGATIVE,
}

/** A trace count proven non-negative before it may become an attribute. */
@JvmInline
value class KastSpanCount private constructor(
    val value: Long,
) {
    companion object {
        fun parse(raw: Long): Refinement<KastSpanCount, KastSpanCountFailure> =
            if (raw < 0L) {
                Refinement.Rejected(KastSpanCountFailure.NEGATIVE)
            } else {
                Refinement.Refined(KastSpanCount(raw))
            }
    }
}

/** Bounded measurements intentionally retained on spans, never as metric dimensions. */
sealed interface KastSpanMeasurement {
    data class FileCount(val count: KastSpanCount) : KastSpanMeasurement
    data class RecordCount(val count: KastSpanCount) : KastSpanMeasurement
    data class WorkUnitCount(val count: KastSpanCount) : KastSpanMeasurement
}

/** Closed topology stage names projected into the host-neutral trace boundary. */
enum class KastTopologyIdentityStage {
    REFERENCE_TARGET,
    DIRECT_OVERRIDE,
}

/** Closed read-epoch cache outcomes projected into the host-neutral trace boundary. */
enum class KastTopologyCacheDisposition {
    COMPUTED,
    REUSED,
}

/** Closed native declaration-binding failures, with no compiler renderings. */
enum class KastTopologyBindingFailure {
    EPOCH_CHANGED, DECLARATION_UNAVAILABLE, ORIGIN_NOT_ADMITTED,
    ROLE_MISMATCH, MODULE_MISMATCH, DECLARATION_MISMATCH,
}

/** Detached non-empty source range carried only by a topology diagnostic event. */
data class KastTopologySourceRange(
    val startInclusive: Int,
    val endExclusive: Int,
) {
    init {
        require(startInclusive in 0 until endExclusive)
    }
}

/** Bounded, typed span events; values remain trace data and never become metric dimensions. */
sealed interface KastSpanEvent {
    /** One complete detached record for a failed topology compiler-identity comparison. */
    data class TopologyIdentityMismatch(
        val stage: KastTopologyIdentityStage,
        val cacheDisposition: KastTopologyCacheDisposition,
        val sourceFile: String,
        val sourceOccurrence: KastTopologySourceRange,
        val targetFile: String,
        val targetDeclaration: KastTopologySourceRange,
        val reason: KastTopologyBindingFailure,
    ) : KastSpanEvent {
        init {
            require(sourceFile.isNotBlank())
            require(targetFile.isNotBlank())
        }
    }
}

/** Expected terminal classification. Rejection remains ordinary span data, not an exception. */
sealed interface KastSpanCompletion {
    data object Complete : KastSpanCompletion
    data object Qualified : KastSpanCompletion
    data class Rejected(val failure: KastSpanFailure) : KastSpanCompletion
}

/** One total terminal observation with an optional bounded measurement set. */
data class KastSpanObservation(
    val completion: KastSpanCompletion,
    val measurements: Set<KastSpanMeasurement> = emptySet(),
    val events: Set<KastSpanEvent> = emptySet(),
)

/** One active trace span whose children retain explicit parentage across coroutine suspension. */
interface KastTraceSpan {
    suspend fun <Value> child(
        name: KastSpanName,
        operation: suspend (KastTraceSpan) -> Value,
    ): Value

    fun observe(observation: KastSpanObservation)
}

/** Host-neutral trace boundary. OpenTelemetry types are confined to the runtime adapter. */
interface KastObservability {
    suspend fun <Value> inSpan(
        name: KastSpanName,
        operation: suspend (KastTraceSpan) -> Value,
    ): Value

    data object Disabled : KastObservability {
        override suspend fun <Value> inSpan(
            name: KastSpanName,
            operation: suspend (KastTraceSpan) -> Value,
        ): Value = operation(DisabledSpan)
    }
}

private data object DisabledSpan : KastTraceSpan {
    override suspend fun <Value> child(
        name: KastSpanName,
        operation: suspend (KastTraceSpan) -> Value,
    ): Value = operation(this)

    override fun observe(observation: KastSpanObservation) = Unit
}
