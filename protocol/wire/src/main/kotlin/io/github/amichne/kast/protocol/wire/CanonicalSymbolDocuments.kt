@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceWireDocument
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationFactCoverageDocument
import io.github.amichne.kast.protocol.contract.RelationFactDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationOccurrenceWireDocument
import io.github.amichne.kast.protocol.contract.RelationProvenanceDocument
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.SourceRangeWireDocument
import io.github.amichne.kast.protocol.contract.SymbolDocument
import io.github.amichne.kast.protocol.contract.SymbolKindWireDocument
import io.github.amichne.kast.protocol.contract.SymbolQualifiedIdentityDocument
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.protocol.contract.TraversalRecordDocument
import io.github.amichne.kast.protocol.contract.WireDocumentConversion
import io.github.amichne.kast.protocol.contract.combineConverted
import io.github.amichne.kast.protocol.contract.flatMapConverted
import io.github.amichne.kast.protocol.contract.flattenConverted
import io.github.amichne.kast.protocol.contract.mapConverted
import io.github.amichne.kast.protocol.contract.toContract
import io.github.amichne.kast.protocol.contract.toProtocolText
import io.github.amichne.kast.protocol.contract.toWireDocument
import io.github.amichne.kast.protocol.contract.toWireDocumentConversion
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class RelationFactWireDocument(
    val meaning: RelationKindWireDocument,
    val source: SymbolWireDocument,
    val target: SymbolWireDocument,
    val occurrence: RelationOccurrenceWireDocument,
    val provenance: RelationProvenanceWireDocument,
    val coverage: RelationFactCoverageWireDocument,
)

@Serializable
internal enum class RelationProvenanceWireDocument {
    @SerialName("k2-authored-source") K2_AUTHORED_SOURCE,
    @SerialName("k2-generated-source") K2_GENERATED_SOURCE,
    @SerialName("k2-project-library") K2_PROJECT_LIBRARY,
}

@Serializable
internal enum class RelationFactCoverageWireDocument {
    @SerialName("exact-compiler-confirmed") EXACT_COMPILER_CONFIRMED
}

@Serializable
internal data class TraversalRecordWireDocument(
    val depth: Int,
    val relation: RelationFactWireDocument,
)

@Serializable
internal data class SymbolWireDocument(
    val selector: String,
    val kind: SymbolKindWireDocument,
    val name: String,
    val qualifiedIdentity: String?,
    val file: String,
    val range: SourceRangeWireDocument,
    val compilerEvidence: CompilerSymbolEvidenceWireDocument,
)

@Serializable
internal enum class SymbolCategoryWireDocument {
    @SerialName("file") FILE,
    @SerialName("class") CLASS,
    @SerialName("symbol") SYMBOL,
}

@Serializable
internal enum class SymbolDiscoveryMatchWireDocument {
    @SerialName("fuzzy") FUZZY,
    @SerialName("exact-name") EXACT_NAME,
}

internal fun RelationFactDocument.toWireDocument(): RelationFactWireDocument =
    RelationFactWireDocument(
        meaning = meaning.toRelationWireDocument(),
        source = source.toWireDocument(),
        target = target.toWireDocument(),
        occurrence =
            RelationOccurrenceWireDocument(
                occurrence.candidateSelector.value,
                occurrence.file.value,
                occurrence.range.toWireDocument(),
            ),
        provenance = provenance.toWireDocument(),
        coverage = coverage.toWireDocument(),
    )

internal fun RelationFactWireDocument.toContract(): WireDocumentConversion<RelationFactDocument> =
    combineConverted(
        source.toContract(),
        target.toContract(),
        occurrence.toContract(),
    ) { source, target, occurrence ->
        RelationFactDocument(
            meaning = meaning.toRelationContract(),
            source = source,
            target = target,
            occurrence = occurrence,
            provenance = provenance.toContract(),
            coverage = coverage.toContract(),
        )
    }

internal fun TraversalRecordDocument.toWireDocument(): TraversalRecordWireDocument =
    TraversalRecordWireDocument(depth.value, relation.toWireDocument())

internal fun TraversalRecordWireDocument.toContract(): WireDocumentConversion<TraversalRecordDocument> =
    combineConverted(
        TraversalDepthDocument.parse(depth).toWireDocumentConversion(),
        relation.toContract(),
        ::TraversalRecordDocument,
    )

internal fun SymbolDocument.toWireDocument() =
    SymbolWireDocument(
        selector.value,
        kind.toWireDocument(),
        name.value,
        when (val identity = qualifiedIdentity) {
            is SymbolQualifiedIdentityDocument.Available -> identity.value.value
            SymbolQualifiedIdentityDocument.Unavailable -> null
        },
        file.value,
        range.toWireDocument(),
        compilerEvidence.toWireDocument(),
    )

/**
 * Proof transition: `SymbolWireDocument -> WireDocumentConversion<SymbolDocument>`. Establishes an exact symbol with a
 * closed qualified-identity state and valid range; raw symbol primitives exist only at this wire boundary.
 */
internal fun SymbolWireDocument.toContract(): WireDocumentConversion<SymbolDocument> =
    combineConverted(
            selector.toProtocolText(),
            name.toProtocolText(),
            qualifiedIdentity.toQualifiedIdentity(),
            file.toProtocolText(),
            range.toContract(),
        ) { selector, name, qualifiedIdentity, file, range ->
            AdmittedSymbolFields(selector, name, qualifiedIdentity, file, range)
        }
        .flatMapConverted { fields ->
            compilerEvidence.toContract().mapConverted { evidence ->
                SymbolDocument.create(
                        fields.selector,
                        kind.toContract(),
                        fields.name,
                        fields.qualifiedIdentity,
                        fields.file,
                        fields.range,
                        evidence,
                    )
                    .toWireDocumentConversion()
            }
        }
        .flattenConverted()

private data class AdmittedSymbolFields(
    val selector: ProtocolText,
    val name: ProtocolText,
    val qualifiedIdentity: SymbolQualifiedIdentityDocument,
    val file: ProtocolText,
    val range: SourceRangeDocument,
)

/**
 * `String? -> WireDocumentConversion<SymbolQualifiedIdentityDocument>` establishes explicit absence or refined text;
 * [WireDocumentConversion.Rejected] closes invalid raw wire text.
 */
private fun String?.toQualifiedIdentity(): WireDocumentConversion<SymbolQualifiedIdentityDocument> =
    when (this) {
        null -> WireDocumentConversion.Converted(SymbolQualifiedIdentityDocument.Unavailable)
        else ->
            toProtocolText().mapConverted { value ->
                SymbolQualifiedIdentityDocument.Available(value)
            }
    }

internal fun RelationKindDocument.toRelationWireDocument(): RelationKindWireDocument =
    when (this) {
        RelationKindDocument.REFERENCES -> RelationKindWireDocument.REFERENCES
        RelationKindDocument.CALLERS -> RelationKindWireDocument.CALLERS
        RelationKindDocument.CALLEES -> RelationKindWireDocument.CALLEES
        RelationKindDocument.IMPLEMENTATIONS -> RelationKindWireDocument.IMPLEMENTATIONS
        RelationKindDocument.INHERITORS -> RelationKindWireDocument.INHERITORS
        RelationKindDocument.OVERRIDES -> RelationKindWireDocument.OVERRIDES
        RelationKindDocument.TYPE_USES -> RelationKindWireDocument.TYPE_USES
    }

internal fun RelationKindWireDocument.toRelationContract(): RelationKindDocument =
    when (this) {
        RelationKindWireDocument.REFERENCES -> RelationKindDocument.REFERENCES
        RelationKindWireDocument.CALLERS -> RelationKindDocument.CALLERS
        RelationKindWireDocument.CALLEES -> RelationKindDocument.CALLEES
        RelationKindWireDocument.IMPLEMENTATIONS -> RelationKindDocument.IMPLEMENTATIONS
        RelationKindWireDocument.INHERITORS -> RelationKindDocument.INHERITORS
        RelationKindWireDocument.OVERRIDES -> RelationKindDocument.OVERRIDES
        RelationKindWireDocument.TYPE_USES -> RelationKindDocument.TYPE_USES
    }

internal fun RelationProvenanceDocument.toWireDocument(): RelationProvenanceWireDocument =
    when (this) {
        RelationProvenanceDocument.K2_AUTHORED_SOURCE -> RelationProvenanceWireDocument.K2_AUTHORED_SOURCE
        RelationProvenanceDocument.K2_GENERATED_SOURCE -> RelationProvenanceWireDocument.K2_GENERATED_SOURCE
        RelationProvenanceDocument.K2_PROJECT_LIBRARY -> RelationProvenanceWireDocument.K2_PROJECT_LIBRARY
    }

internal fun RelationProvenanceWireDocument.toContract(): RelationProvenanceDocument =
    when (this) {
        RelationProvenanceWireDocument.K2_AUTHORED_SOURCE -> RelationProvenanceDocument.K2_AUTHORED_SOURCE
        RelationProvenanceWireDocument.K2_GENERATED_SOURCE -> RelationProvenanceDocument.K2_GENERATED_SOURCE
        RelationProvenanceWireDocument.K2_PROJECT_LIBRARY -> RelationProvenanceDocument.K2_PROJECT_LIBRARY
    }

internal fun RelationFactCoverageDocument.toWireDocument(): RelationFactCoverageWireDocument =
    when (this) {
        RelationFactCoverageDocument.EXACT_COMPILER_CONFIRMED ->
            RelationFactCoverageWireDocument.EXACT_COMPILER_CONFIRMED
    }

internal fun RelationFactCoverageWireDocument.toContract(): RelationFactCoverageDocument =
    when (this) {
        RelationFactCoverageWireDocument.EXACT_COMPILER_CONFIRMED ->
            RelationFactCoverageDocument.EXACT_COMPILER_CONFIRMED
    }

/** `Int -> WireDocumentConversion<ProtocolCount>`; closes invalid counts at the wire boundary. */
private fun Int.toProtocolCount(): WireDocumentConversion<ProtocolCount> =
    ProtocolCount.parse(this).toWireDocumentConversion()
