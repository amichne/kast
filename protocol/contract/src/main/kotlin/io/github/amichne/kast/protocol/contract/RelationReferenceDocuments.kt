package io.github.amichne.kast.protocol.contract

@kotlinx.serialization.Serializable
enum class RelationReferenceContextDocument {
    IMPORT,
    ALIASED_IMPORT,
    TYPE,
    CODE,
    FILE_ANNOTATION,
}

@kotlinx.serialization.Serializable
enum class RelationOwnershipUnavailableCauseDocument {
    UNSUPPORTED_DECLARATION,
    UNRESOLVED_DECLARATION,
}

sealed interface RelationReferenceOwnershipDocument {
    data class DeclarationOwned(val declaration: SymbolDocument) : RelationReferenceOwnershipDocument

    data class FileScoped(val context: RelationReferenceContextDocument) : RelationReferenceOwnershipDocument

    data class Unavailable(val cause: RelationOwnershipUnavailableCauseDocument) : RelationReferenceOwnershipDocument
}

data class RelationReferenceOccurrenceDocument(
    val target: SymbolDocument,
    val meaning: RelationKindDocument,
    val occurrence: RelationOccurrenceDocument,
    val context: RelationReferenceContextDocument,
    val ownership: RelationReferenceOwnershipDocument,
    val provenance: RelationProvenanceDocument,
    val coverage: RelationFactCoverageDocument,
)

val EmptyReferenceObservations: BoundedProtocolList<RelationReferenceOccurrenceDocument> =
    (BoundedProtocolList.create(emptyList<RelationReferenceOccurrenceDocument>())
            as io.github.amichne.kast.kernel.Refinement.Refined)
        .value
