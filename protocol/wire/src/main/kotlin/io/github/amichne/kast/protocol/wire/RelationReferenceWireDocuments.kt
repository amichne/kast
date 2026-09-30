package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.RelationOwnershipUnavailableCauseDocument
import io.github.amichne.kast.protocol.contract.RelationReferenceContextDocument
import io.github.amichne.kast.protocol.contract.RelationReferenceOccurrenceDocument
import io.github.amichne.kast.protocol.contract.RelationReferenceOwnershipDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class RelationReferenceOccurrenceWireDocument(
    val target: SymbolWireDocument,
    val meaning: RelationKindWireDocument,
    val occurrence: RelationOccurrenceWireDocument,
    val context: RelationReferenceContextDocument,
    val ownership: RelationReferenceOwnershipWireDocument,
    val provenance: RelationProvenanceWireDocument,
    val coverage: RelationFactCoverageWireDocument,
)

@Serializable
internal sealed interface RelationReferenceOwnershipWireDocument {
    @Serializable
    @SerialName("declaration-owned")
    data class DeclarationOwned(val declaration: SymbolWireDocument) : RelationReferenceOwnershipWireDocument

    @Serializable
    @SerialName("file-scoped")
    data class FileScoped(val context: RelationReferenceContextDocument) : RelationReferenceOwnershipWireDocument

    @Serializable
    @SerialName("unavailable")
    data class Unavailable(val cause: RelationOwnershipUnavailableCauseDocument) :
        RelationReferenceOwnershipWireDocument
}

internal fun RelationReferenceOccurrenceDocument.toWireDocument() =
    RelationReferenceOccurrenceWireDocument(
        target.toWireDocument(),
        meaning.toRelationWireDocument(),
        RelationOccurrenceWireDocument(
            occurrence.candidateSelector.value,
            occurrence.file.value,
            occurrence.range.toWireDocument(),
        ),
        context,
        when (val proof = ownership) {
            is RelationReferenceOwnershipDocument.DeclarationOwned ->
                RelationReferenceOwnershipWireDocument.DeclarationOwned(proof.declaration.toWireDocument())
            is RelationReferenceOwnershipDocument.FileScoped ->
                RelationReferenceOwnershipWireDocument.FileScoped(proof.context)
            is RelationReferenceOwnershipDocument.Unavailable ->
                RelationReferenceOwnershipWireDocument.Unavailable(proof.cause)
        },
        provenance.toWireDocument(),
        coverage.toWireDocument(),
    )

internal fun RelationReferenceOccurrenceWireDocument.toContract():
    WireDocumentConversion<RelationReferenceOccurrenceDocument> =
    combineConverted(target.toContract(), occurrence.toContract(), ownership.toContract()) { target, location, owner ->
        RelationReferenceOccurrenceDocument(
            target,
            meaning.toRelationContract(),
            location,
            context,
            owner,
            provenance.toContract(),
            coverage.toContract(),
        )
    }

private fun RelationReferenceOwnershipWireDocument.toContract():
    WireDocumentConversion<RelationReferenceOwnershipDocument> =
    when (this) {
        is RelationReferenceOwnershipWireDocument.DeclarationOwned ->
            declaration.toContract().mapConverted {
                RelationReferenceOwnershipDocument.DeclarationOwned(it)
            }
        is RelationReferenceOwnershipWireDocument.FileScoped ->
            WireDocumentConversion.Converted(RelationReferenceOwnershipDocument.FileScoped(context))
        is RelationReferenceOwnershipWireDocument.Unavailable ->
            WireDocumentConversion.Converted(RelationReferenceOwnershipDocument.Unavailable(cause))
    }
