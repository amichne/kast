@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

enum class QueryDiscoveryMeasureFailure {
    NEGATIVE
}

@JvmInline
@Serializable(with = QueryDiscoveryCountSerializer::class)
value class QueryDiscoveryCountDocument private constructor(val value: Long) {
    companion object {
        fun parse(value: Long): Refinement<QueryDiscoveryCountDocument, QueryDiscoveryMeasureFailure> =
            if (value >= 0L) Refinement.Refined(QueryDiscoveryCountDocument(value))
            else Refinement.Rejected(QueryDiscoveryMeasureFailure.NEGATIVE)
    }
}

internal object QueryDiscoveryCountSerializer :
    RefiningLongSerializer<QueryDiscoveryCountDocument>("QueryDiscoveryCount", 0) {
    override fun raw(value: QueryDiscoveryCountDocument): Long = value.value

    override fun refine(raw: Long): Refinement<QueryDiscoveryCountDocument, *> = QueryDiscoveryCountDocument.parse(raw)
}

@JvmInline
@Serializable(with = QueryDiscoveryNanosecondsSerializer::class)
value class QueryDiscoveryNanosecondsDocument private constructor(val value: Long) {
    companion object {
        fun parse(value: Long): Refinement<QueryDiscoveryNanosecondsDocument, QueryDiscoveryMeasureFailure> =
            if (value >= 0L) Refinement.Refined(QueryDiscoveryNanosecondsDocument(value))
            else Refinement.Rejected(QueryDiscoveryMeasureFailure.NEGATIVE)
    }
}

internal object QueryDiscoveryNanosecondsSerializer :
    RefiningLongSerializer<QueryDiscoveryNanosecondsDocument>("QueryDiscoveryNanoseconds", 0) {
    override fun raw(value: QueryDiscoveryNanosecondsDocument): Long = value.value

    override fun refine(raw: Long): Refinement<QueryDiscoveryNanosecondsDocument, *> =
        QueryDiscoveryNanosecondsDocument.parse(raw)
}

@JvmInline
@Serializable(with = QueryDiscoveryBytesSerializer::class)
value class QueryDiscoveryBytesDocument private constructor(val value: Long) {
    companion object {
        fun parse(value: Long): Refinement<QueryDiscoveryBytesDocument, QueryDiscoveryMeasureFailure> =
            if (value >= 0L) Refinement.Refined(QueryDiscoveryBytesDocument(value))
            else Refinement.Rejected(QueryDiscoveryMeasureFailure.NEGATIVE)
    }
}

internal object QueryDiscoveryBytesSerializer :
    RefiningLongSerializer<QueryDiscoveryBytesDocument>("QueryDiscoveryBytes", 0) {
    override fun raw(value: QueryDiscoveryBytesDocument): Long = value.value

    override fun refine(raw: Long): Refinement<QueryDiscoveryBytesDocument, *> = QueryDiscoveryBytesDocument.parse(raw)
}

@Serializable
enum class QueryDiscoveryOrderDocument {
    @SerialName("kotlin-file-source-v2") KOTLIN_FILE_SOURCE_V2,
    @SerialName("named-candidate-v1") NAMED_CANDIDATE_V1,
}

@Serializable
enum class QueryDiscoveryBlockDocument {
    @SerialName("partition-unavailable") PARTITION_UNAVAILABLE,
    @SerialName("partition-capacity") PARTITION_CAPACITY,
    @SerialName("retention-limit") RETENTION_LIMIT,
    @SerialName("provider-unavailable") PROVIDER_UNAVAILABLE,
    @SerialName("qualified-provider") QUALIFIED_PROVIDER,
    @SerialName("insufficient-execution-grant") INSUFFICIENT_EXECUTION_GRANT,
    @SerialName("item-byte-limit") ITEM_BYTE_LIMIT,
    @SerialName("input-revision-exhausted") INPUT_REVISION_EXHAUSTED,
}

@Serializable
enum class QueryDiscoveryStopDocument {
    @SerialName("result-limit-reached") RESULT_LIMIT_REACHED,
    @SerialName("byte-limit-reached") BYTE_LIMIT_REACHED,
    @SerialName("work-limit-reached") WORK_LIMIT_REACHED,
    @SerialName("time-limit-reached") TIME_LIMIT_REACHED,
    @SerialName("dumb-mode-transition") DUMB_MODE_TRANSITION,
    @SerialName("provider-failure") PROVIDER_FAILURE,
    @SerialName("unscoped-provider") UNSCOPED_PROVIDER,
    @SerialName("unsupported-item") UNSUPPORTED_ITEM,
    @SerialName("exact-definition-unavailable") EXACT_DEFINITION_UNAVAILABLE,
}

@Serializable
@JsonClassDiscriminator("kind")
sealed interface QueryDiscoveryProgressDocument {
    @Serializable @SerialName("exhausted") data object Exhausted : QueryDiscoveryProgressDocument

    @Serializable
    @SerialName("resumable")
    data class Resumable(
        @SerialName("completed_files") val completedFiles: QueryDiscoveryCountDocument,
        @SerialName("next_offset") val nextOffset: ProtocolOffset,
        @SerialName("discovered_files") val discoveredFiles: QueryDiscoveryCountDocument,
        @SerialName("pending_partitions") val pendingPartitions: QueryDiscoveryCountDocument,
        @SerialName("retained_bytes") val retainedBytes: QueryDiscoveryBytesDocument,
    ) : QueryDiscoveryProgressDocument

    @Serializable
    @SerialName("blocked")
    data class Blocked(val cause: QueryDiscoveryBlockDocument) : QueryDiscoveryProgressDocument
}

@Serializable
@JsonClassDiscriminator("kind")
sealed interface QueryDiscoverySourceSetsDocument {
    @Serializable @SerialName("all") data object All : QueryDiscoverySourceSetsDocument

    @Serializable
    @SerialName("exact")
    data class Exact(
        @ProtocolCollectionConstraint(minimumItems = 1, uniqueItems = true) val names: BoundedProtocolList<ProtocolText>
    ) : QueryDiscoverySourceSetsDocument
}

@Serializable
enum class QueryDiscoverySourcePolicyDocument {
    @SerialName("production-only") PRODUCTION_ONLY,
    @SerialName("test-only") TEST_ONLY,
    @SerialName("production-and-test") PRODUCTION_AND_TEST,
}

@Serializable
enum class QueryDiscoveryInclusionPolicyDocument {
    @SerialName("exclude") EXCLUDE,
    @SerialName("include") INCLUDE,
}

@Serializable
enum class QueryDiscoveryDeclarationLanguageDocument {
    @SerialName("kotlin") KOTLIN
}

/** The observed universe remains separate from page measurements and exhaustion. */
@Serializable
data class QueryDiscoveryUniverseDocument(
    @SerialName("declaration_language") val declarationLanguage: QueryDiscoveryDeclarationLanguageDocument,
    val match: QueryMatchDocument,
    @SerialName("declaration_kinds") val declarationKinds: BoundedProtocolList<QueryDeclarationKindDocument>,
    @SerialName("source_policy") val sourcePolicy: QueryDiscoverySourcePolicyDocument,
    @SerialName("generated_sources") val generatedSources: QueryDiscoveryInclusionPolicyDocument,
    val libraries: QueryDiscoveryInclusionPolicyDocument,
    @SerialName("source_sets") val sourceSets: QueryDiscoverySourceSetsDocument,
    val directory: QueryDirectoryScopeDocument?,
    @SerialName("package_name") val packageName: QueryPackageScopeDocument?,
)

@Serializable
data class QueryDiscoveryObservationDocument(
    val universe: QueryDiscoveryUniverseDocument,
    val ordering: QueryDiscoveryOrderDocument,
    val progress: QueryDiscoveryProgressDocument,
    @SerialName("observed_stops") val observedStops: BoundedProtocolList<QueryDiscoveryStopDocument>,
    @SerialName("examined_work_units") val examinedWorkUnits: QueryDiscoveryCountDocument,
    @SerialName("inventory_files") val inventoryFiles: QueryDiscoveryCountDocument,
    @SerialName("reacquired_files") val reacquiredFiles: QueryDiscoveryCountDocument,
    @SerialName("examined_leaves") val examinedLeaves: QueryDiscoveryCountDocument,
    @SerialName("inventory_nanoseconds") val inventoryNanoseconds: QueryDiscoveryNanosecondsDocument,
    @SerialName("declaration_scan_nanoseconds") val declarationScanNanoseconds: QueryDiscoveryNanosecondsDocument,
    @SerialName("projection_nanoseconds") val projectionNanoseconds: QueryDiscoveryNanosecondsDocument,
) {
    companion object {
        val Empty: BoundedProtocolList<QueryDiscoveryObservationDocument> =
            (BoundedProtocolList.create(emptyList<QueryDiscoveryObservationDocument>()) as Refinement.Refined).value
    }
}
