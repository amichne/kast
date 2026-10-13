package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.protocol.contract.ProtocolStringConstraint
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.wire.RelationFactCoverageWireDocument
import io.github.amichne.kast.protocol.wire.RelationKindWireDocument
import io.github.amichne.kast.protocol.wire.RelationOccurrenceWireDocument
import io.github.amichne.kast.protocol.wire.RelationProvenanceWireDocument
import io.github.amichne.kast.protocol.wire.SourceRangeWireDocument
import io.github.amichne.kast.protocol.wire.SymbolKindWireDocument
import io.github.amichne.kast.protocol.wire.SymbolWireDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** IDs name descriptions inside this retained basis. They do not select live declarations. */
@Serializable
internal data class QueryRelationGraphCliDocument(
    val basis: QueryResultReference,
    val nodes: List<QueryRelationGraphNodeCliDocument>,
    val witnesses: List<QueryRelationGraphWitnessCliDocument>,
    @SerialName("reference_observations") val referenceObservations: List<String>,
    val detail: QueryRelationGraphDetailCliDocument,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("connection_witnesses")
    val connectionWitnesses: List<QueryRelationGraphConnectionCliDocument> = emptyList(),
)

@Serializable
internal sealed interface QueryRelationGraphConnectionCliDocument {
    val id: String

    @Serializable
    @SerialName("direct")
    data class Direct(
        @ProtocolStringConstraint(pattern = "^connection:v1:[0-9a-f]{64}$") override val id: String,
        @ProtocolStringConstraint(pattern = "^node:v1:[0-9a-f]{64}$") val source: String,
        @ProtocolStringConstraint(pattern = "^node:v1:[0-9a-f]{64}$") val target: String,
        val meaning: RelationKindWireDocument,
        val direction: QueryRelationGraphConnectionDirectionCliDocument,
        val occurrence: RelationOccurrenceWireDocument,
        val provenance: RelationProvenanceWireDocument,
        val coverage: RelationFactCoverageWireDocument,
    ) : QueryRelationGraphConnectionCliDocument

    @Serializable
    @SerialName("reference-backed")
    data class ReferenceBacked(
        @ProtocolStringConstraint(pattern = "^connection:v1:[0-9a-f]{64}$") override val id: String,
        @ProtocolStringConstraint(pattern = "^witness:v1:[0-9a-f]{64}$") val witness: String,
    ) : QueryRelationGraphConnectionCliDocument
}

@Serializable
internal enum class QueryRelationGraphConnectionDirectionCliDocument {
    SOURCE_TO_TARGET
}

@Serializable
internal data class QueryRelationGraphNodeCliDocument(
    @ProtocolStringConstraint(pattern = "^node:v1:[0-9a-f]{64}$") val id: String,
    val symbol: QueryRelationGraphSymbolCliDocument,
)

/** Full signatures remain available through the graph's retained READ_RESULT detail request. */
@Serializable
internal data class QueryRelationGraphSymbolCliDocument
private constructor(
    @ProtocolStringConstraint(pattern = "^exact:v[2345]:") val selector: String,
    val kind: SymbolKindWireDocument,
    val name: String,
    val qualifiedIdentity: String?,
    val file: String,
    val range: SourceRangeWireDocument,
    @SerialName("compiler_identity") val compilerIdentity: String,
) {
    companion object {
        fun from(symbol: SymbolWireDocument): QueryRelationGraphSymbolCliDocument =
            QueryRelationGraphSymbolCliDocument(
                symbol.selector,
                symbol.kind,
                symbol.name,
                symbol.qualifiedIdentity,
                symbol.file,
                symbol.range,
                symbol.compilerEvidence.identity,
            )
    }
}

@Serializable
internal data class QueryRelationGraphWitnessCliDocument(
    @ProtocolStringConstraint(pattern = "^witness:v1:[0-9a-f]{64}$") val id: String,
    @ProtocolStringConstraint(pattern = "^node:v1:[0-9a-f]{64}$") val target: String,
    val ownership: QueryRelationGraphOwnershipCliDocument,
    val meaning: RelationKindWireDocument,
    val direction: QueryRelationGraphDirectionCliDocument,
    val occurrence: RelationOccurrenceWireDocument,
    val context: io.github.amichne.kast.protocol.contract.RelationReferenceContextDocument,
    val provenance: RelationProvenanceWireDocument,
    val coverage: RelationFactCoverageWireDocument,
)

@Serializable
internal enum class QueryRelationGraphDirectionCliDocument {
    DECLARATION_TO_TARGET,
    FILE_CONTEXT_TO_TARGET,
    OWNER_UNPROVEN,
}

@Serializable
internal sealed interface QueryRelationGraphOwnershipCliDocument {
    @Serializable
    @SerialName("declaration-owned")
    data class DeclarationOwned(@ProtocolStringConstraint(pattern = "^node:v1:[0-9a-f]{64}$") val declaration: String) :
        QueryRelationGraphOwnershipCliDocument

    @Serializable
    @SerialName("file-scoped")
    data class FileScoped(val context: io.github.amichne.kast.protocol.contract.RelationReferenceContextDocument) :
        QueryRelationGraphOwnershipCliDocument

    @Serializable
    @SerialName("unavailable")
    data class Unavailable(
        val cause: io.github.amichne.kast.protocol.contract.RelationOwnershipUnavailableCauseDocument
    ) : QueryRelationGraphOwnershipCliDocument
}

@Serializable
internal data class QueryRelationGraphDetailCliDocument(
    val request: QueryRelationGraphReadCliDocument,
    val verbose: Boolean,
)

@Serializable
internal data class QueryRelationGraphReadCliDocument(
    val type: QueryRelationGraphReadKindCliDocument,
    val result: QueryResultReference,
    val cursor: Int,
    @SerialName("evidence_cursor") val evidenceCursor: Int,
    val output: QueryRelationGraphOutputCliDocument,
)

@Serializable
internal enum class QueryRelationGraphReadKindCliDocument {
    READ_RESULT
}

@Serializable
internal sealed interface QueryRelationGraphOutputCliDocument {
    @Serializable @SerialName("OCCURRENCES") data object Occurrences : QueryRelationGraphOutputCliDocument

    @Serializable
    @SerialName("SYMBOLS")
    data class Symbols(val fields: List<QueryRelationGraphSymbolFieldCliDocument>) : QueryRelationGraphOutputCliDocument
}

@Serializable
internal enum class QueryRelationGraphSymbolFieldCliDocument {
    NAME,
    LOCATION,
    SIGNATURE,
    SOURCE,
}

internal sealed interface QueryRelationGraphProjection {
    data class Projected(
        val items: List<QueryResultItemCliDocument>,
        val graph: QueryRelationGraphCliDocument,
    ) : QueryRelationGraphProjection

    data class Ineligible(val reason: QueryRelationGraphIneligibility) : QueryRelationGraphProjection
}

internal enum class QueryRelationGraphIneligibility {
    ROW_KIND,
    ROW_ID_ABSENT,
    TARGET_REFERENCE_MISMATCH,
    IDENTITY_CONFLICT,
    PRESENTATION_OUTPUT_ABSENT,
    ROW_IDENTITY_MISMATCH,
    ROW_NODE_UNAVAILABLE,
    CONNECTION_PROOF_ABSENT,
}
