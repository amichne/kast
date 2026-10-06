@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonClassDiscriminator

enum class QueryRelationDomainFailure {
    INVALID_FINGERPRINT
}

@JvmInline
@Serializable(with = QueryRelationDomainFingerprintSerializer::class)
value class QueryRelationDomainFingerprint private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<QueryRelationDomainFingerprint, QueryRelationDomainFailure> =
            if (raw.length == DOMAIN_FINGERPRINT_HEX_LENGTH && raw.all { it in "0123456789abcdef" })
                Refinement.Refined(QueryRelationDomainFingerprint(raw))
            else Refinement.Rejected(QueryRelationDomainFailure.INVALID_FINGERPRINT)
    }
}

internal object QueryRelationDomainFingerprintSerializer :
    RefiningStringSerializer<QueryRelationDomainFingerprint>(
        "QueryRelationDomainFingerprint",
        DOMAIN_FINGERPRINT_HEX_LENGTH,
        DOMAIN_FINGERPRINT_HEX_LENGTH,
        "^[0-9a-f]{64}$",
    ) {
    override fun raw(value: QueryRelationDomainFingerprint): String = value.value

    override fun refine(raw: String): Refinement<QueryRelationDomainFingerprint, *> =
        QueryRelationDomainFingerprint.parse(raw)
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface QuerySemanticScopeDocument {
    @Serializable @SerialName("WORKSPACE") data object Workspace : QuerySemanticScopeDocument

    @Serializable @SerialName("EXACT_FILE") data class ExactFile(val file: ProtocolText) : QuerySemanticScopeDocument

    @Serializable @SerialName("MODULE") data class Module(val module: ProtocolText) : QuerySemanticScopeDocument

    @Serializable
    @SerialName("GRADLE_PROJECT")
    data class GradleProject(
        @SerialName("build_root") val buildRoot: ProtocolText,
        @SerialName("project_path") val projectPath: ProtocolText,
    ) : QuerySemanticScopeDocument

    @Serializable
    @SerialName("SOURCE_SET")
    data class SourceSet(
        @SerialName("build_root") val buildRoot: ProtocolText,
        @SerialName("project_path") val projectPath: ProtocolText,
        @SerialName("source_set") val sourceSet: ProtocolText,
    ) : QuerySemanticScopeDocument
}

/** Actual native source membership, kept separate from the question's requested expansion policy. */
@Serializable
data class QueryRelationDomainDocument(
    val scope: QuerySemanticScopeDocument,
    @SerialName("source_policy") val sourcePolicy: QueryDiscoverySourcePolicyDocument,
    @SerialName("generated_sources") val generatedSources: QueryDiscoveryInclusionPolicyDocument,
    val libraries: QueryDiscoveryInclusionPolicyDocument,
    @SerialName("source_sets") val sourceSets: QueryDiscoverySourceSetsDocument,
    val directory: QueryDirectoryScopeDocument?,
    @SerialName("package_name") val packageName: QueryPackageScopeDocument?,
    @SerialName("declaration_kinds") val declarationKinds: BoundedProtocolList<QueryDeclarationKindDocument>,
)

@Serializable
enum class QueryRelationRequestedDomainDocument {
    RETAINED_SEED,
    WORKSPACE,
    SOURCE_DOMAIN,
}

enum class QueryRelationLimitationsFailure {
    EMPTY,
    NOT_CANONICAL,
}

/** Incomplete coverage always names at least one finite, canonical unresolved condition. */
@Serializable(with = QueryRelationLimitationsSerializer::class)
class QueryRelationLimitationsDocument private constructor(val values: List<RelationLimitationDocument>) {
    companion object {
        fun from(
            raw: List<RelationLimitationDocument>
        ): Refinement<QueryRelationLimitationsDocument, QueryRelationLimitationsFailure> =
            when {
                raw.isEmpty() -> Refinement.Rejected(QueryRelationLimitationsFailure.EMPTY)
                raw != raw.distinct().sortedBy { it.ordinal } ->
                    Refinement.Rejected(QueryRelationLimitationsFailure.NOT_CANONICAL)
                else ->
                    Refinement.Refined(
                        QueryRelationLimitationsDocument(java.util.Collections.unmodifiableList(raw.toList()))
                    )
            }
    }

    override fun equals(other: Any?): Boolean = other is QueryRelationLimitationsDocument && values == other.values

    override fun hashCode(): Int = values.hashCode()
}

internal object QueryRelationLimitationsSerializer : KSerializer<QueryRelationLimitationsDocument> {
    private val delegate = ListSerializer(RelationLimitationDocument.serializer())
    override val descriptor: SerialDescriptor =
        annotatedDescriptor(
            delegate.descriptor,
            ProtocolCollectionConstraint(minimumItems = 1, maximumItems = 13, uniqueItems = true),
        )

    override fun serialize(encoder: Encoder, value: QueryRelationLimitationsDocument) =
        delegate.serialize(encoder, value.values)

    override fun deserialize(decoder: Decoder): QueryRelationLimitationsDocument =
        when (val parsed = QueryRelationLimitationsDocument.from(delegate.deserialize(decoder))) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> throw SerializationException("Relation limitations rejected ${parsed.failure}")
        }
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface QueryRelationCoverageDocument {
    @Serializable @SerialName("EXHAUSTED") data object Exhausted : QueryRelationCoverageDocument

    @Serializable
    @SerialName("RESUMABLE")
    data class Resumable(val limitations: QueryRelationLimitationsDocument) : QueryRelationCoverageDocument

    @Serializable
    @SerialName("TERMINAL_INCOMPLETE")
    data class TerminalIncomplete(val limitations: QueryRelationLimitationsDocument) : QueryRelationCoverageDocument
}

/** Enumeration evidence certifies this relation question, never the absence of rows removed by presentation. */
data class QueryRelationObservationDocument(
    val subject: QueryReferenceDocument.ExactSymbol,
    val relation: RelationKindDocument,
    val provider: RelationProviderDocument,
    @SerialName("requested_domain") val requestedDomain: QueryRelationRequestedDomainDocument,
    @SerialName("effective_domain") val effectiveDomain: QueryRelationDomainDocument,
    @SerialName("domain_fingerprint") val domainFingerprint: QueryRelationDomainFingerprint,
    val coverage: QueryRelationCoverageDocument,
    val scopeExclusions: BoundedProtocolList<QueryScopeExclusionDocument>,
    val callbackObservations: BoundedProtocolList<QueryCallbackObservationDocument> =
        (BoundedProtocolList.create(emptyList<QueryCallbackObservationDocument>()) as Refinement.Refined).value,
    val callableObservations: BoundedProtocolList<QueryCallableObservationDocument> =
        (BoundedProtocolList.create(emptyList<QueryCallableObservationDocument>()) as Refinement.Refined).value,
) {
    companion object {
        val Empty: BoundedProtocolList<QueryRelationObservationDocument> =
            (BoundedProtocolList.create(emptyList<QueryRelationObservationDocument>()) as Refinement.Refined).value
    }
}

private const val DOMAIN_FINGERPRINT_HEX_LENGTH = 64
