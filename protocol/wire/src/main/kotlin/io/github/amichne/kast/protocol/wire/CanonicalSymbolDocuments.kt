package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CompilerReceiverDocument
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.CompilerTypeParameterCountDocument
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationFactCoverageDocument
import io.github.amichne.kast.protocol.contract.RelationFactDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationOccurrenceDocument
import io.github.amichne.kast.protocol.contract.RelationProvenanceDocument
import io.github.amichne.kast.protocol.contract.RelationReadResult
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.SymbolDescribeResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverTargetDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryKindDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.protocol.contract.SymbolDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.contract.SymbolNameKindDocument
import io.github.amichne.kast.protocol.contract.SymbolQualifiedIdentityDocument
import io.github.amichne.kast.protocol.contract.SymbolTextScopeDocument
import io.github.amichne.kast.protocol.contract.TraversalRunResult
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.protocol.contract.TraversalRecordDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class SymbolDiscoverRequestWireDocument(
    val target: SymbolDiscoverTargetWireDocument,
    val limit: Int,
)
@Serializable
internal sealed interface SymbolDiscoverTargetWireDocument {
    @Serializable
    @SerialName("name")
    data class Name(
        val query: String,
        val kind: SymbolCategoryWireDocument,
        val match: SymbolDiscoveryMatchWireDocument,
    ) : SymbolDiscoverTargetWireDocument
    @Serializable
    @SerialName("location")
    data class Location(val file: String, val offset: Int) : SymbolDiscoverTargetWireDocument
    @Serializable
    @SerialName("structure")
    data class Structure(val file: String) : SymbolDiscoverTargetWireDocument
    @Serializable
    @SerialName("text")
    data class Text(
        val query: String,
        val scope: SymbolTextScopeWireDocument,
    ) : SymbolDiscoverTargetWireDocument
}
@Serializable
internal sealed interface SymbolTextScopeWireDocument {
    @Serializable
    @SerialName("workspace")
    data object Workspace : SymbolTextScopeWireDocument
    @Serializable
    @SerialName("file")
    data class File(val file: String) : SymbolTextScopeWireDocument
}
@Serializable
internal data class SymbolDiscoverResultWireDocument(val items: List<SymbolDiscoveryWireDocument>)
@Serializable
internal sealed interface SymbolDiscoveryWireDocument {
    @Serializable
    @SerialName("file")
    data class File(val name: String, val file: String) : SymbolDiscoveryWireDocument
    @Serializable
    @SerialName("declaration")
    data class Declaration(
        val candidateSelector: String,
        val kind: SymbolCategoryWireDocument,
        val name: String,
        val file: String,
        val offset: Int,
    ) : SymbolDiscoveryWireDocument
    @Serializable
    @SerialName("text-match")
    data class TextMatch(
        val query: String,
        val file: String,
        val range: SourceRangeWireDocument,
    ) : SymbolDiscoveryWireDocument
}
@Serializable
internal data class SymbolDescribeResultWireDocument(val symbol: SymbolWireDocument)
@Serializable
internal data class RelationReadResultWireDocument(val relations: List<RelationFactWireDocument>)
@Serializable
internal data class TraversalRunResultWireDocument(
    val snapshotRoot: String,
    val records: List<TraversalRecordWireDocument>,
)

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
internal data class RelationOccurrenceWireDocument(
    val file: String,
    val range: SourceRangeWireDocument,
)

@Serializable
internal enum class RelationProvenanceWireDocument {
    @SerialName("k2-authored-source") K2_AUTHORED_SOURCE,
    @SerialName("k2-generated-source") K2_GENERATED_SOURCE,
    @SerialName("k2-project-library") K2_PROJECT_LIBRARY,
}

@Serializable
internal enum class RelationFactCoverageWireDocument {
    @SerialName("exact-compiler-confirmed") EXACT_COMPILER_CONFIRMED,
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
internal data class CompilerSymbolEvidenceWireDocument(
    val identity: String,
    val signature: CompilerSignatureWireDocument,
)

@Serializable
internal sealed interface CompilerSignatureWireDocument {
    @Serializable
    @SerialName("function")
    data class Function(
        val qualifiedIdentity: String,
        val receiver: CompilerReceiverWireDocument,
        val contextReceivers: List<String>,
        val valueParameters: List<String>,
        val typeParameterCount: Int,
    ) : CompilerSignatureWireDocument

    @Serializable
    @SerialName("property")
    data class Property(
        val qualifiedIdentity: String,
        val receiver: CompilerReceiverWireDocument,
        val contextReceivers: List<String>,
        val returnType: String,
    ) : CompilerSignatureWireDocument

    @Serializable
    @SerialName("type-alias")
    data class TypeAlias(
        val qualifiedIdentity: String,
    ) : CompilerSignatureWireDocument

    @Serializable
    @SerialName("class-like")
    data class ClassLike(
        val qualifiedIdentity: String,
    ) : CompilerSignatureWireDocument
}

@Serializable
internal sealed interface CompilerReceiverWireDocument {
    @Serializable
    @SerialName("absent")
    data object Absent : CompilerReceiverWireDocument

    @Serializable
    @SerialName("present")
    data class Present(
        val compilerType: String,
    ) : CompilerReceiverWireDocument
}

@Serializable
internal data class SourceRangeWireDocument(val startInclusive: Int, val endExclusive: Int)
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
@Serializable
internal enum class SymbolKindWireDocument {
    @SerialName("classlike") CLASSLIKE,
    @SerialName("constructor") CONSTRUCTOR,
    @SerialName("function") FUNCTION,
    @SerialName("property") PROPERTY,
    @SerialName("type-alias") TYPE_ALIAS,
}
internal fun SymbolDiscoverRequest.toSymbolWireDocument() = SymbolDiscoverRequestWireDocument(
    target.toWireDocument(),
    limit.value,
)
/**
 * Proof transition: `SymbolDiscoverRequestWireDocument ->
 * WireDocumentConversion<SymbolDiscoverRequest>`. Establishes a closed target and bounded limit;
 * raw primitives are extracted only at this wire boundary.
 */
internal fun SymbolDiscoverRequestWireDocument.toContract():
    WireDocumentConversion<SymbolDiscoverRequest> = combineConverted(
    target.toContract(),
    limit.toProtocolCount(),
    ::SymbolDiscoverRequest,
)
private fun SymbolDiscoverTargetDocument.toWireDocument(): SymbolDiscoverTargetWireDocument =
    when (this) {
        is SymbolDiscoverTargetDocument.Name -> SymbolDiscoverTargetWireDocument.Name(
            query.value,
            kind.toWireDocument(),
            match.toWireDocument(),
        )
        is SymbolDiscoverTargetDocument.Location ->
            SymbolDiscoverTargetWireDocument.Location(file.value, offset.value)
        is SymbolDiscoverTargetDocument.Structure ->
            SymbolDiscoverTargetWireDocument.Structure(file.value)
        is SymbolDiscoverTargetDocument.Text ->
            SymbolDiscoverTargetWireDocument.Text(query.value, scope.toWireDocument())
    }
/**
 * Proof transition: `SymbolDiscoverTargetWireDocument ->
 * WireDocumentConversion<SymbolDiscoverTargetDocument>`. Establishes the selected closed variant
 * and its refined fields; raw target primitives exist only at this wire boundary.
 */
private fun SymbolDiscoverTargetWireDocument.toContract():
    WireDocumentConversion<SymbolDiscoverTargetDocument> = when (this) {
    is SymbolDiscoverTargetWireDocument.Name -> query.toProtocolText().mapConverted { query ->
        SymbolDiscoverTargetDocument.Name(query, kind.toNameKind(), match.toContract())
    }
    is SymbolDiscoverTargetWireDocument.Location -> combineConverted(
        file.toProtocolText(),
        offset.toProtocolOffset(),
    ) { file, offset ->
        SymbolDiscoverTargetDocument.Location(file, offset)
    }
    is SymbolDiscoverTargetWireDocument.Structure -> file.toProtocolText().mapConverted { file ->
        SymbolDiscoverTargetDocument.Structure(file)
    }
    is SymbolDiscoverTargetWireDocument.Text -> combineConverted(
        query.toProtocolText(),
        scope.toContract(),
    ) { query, scope ->
        SymbolDiscoverTargetDocument.Text(query, scope)
    }
}
private fun SymbolTextScopeDocument.toWireDocument(): SymbolTextScopeWireDocument = when (this) {
    SymbolTextScopeDocument.Workspace -> SymbolTextScopeWireDocument.Workspace
    is SymbolTextScopeDocument.File -> SymbolTextScopeWireDocument.File(file.value)
}
/**
 * Proof transition: `SymbolTextScopeWireDocument ->
 * WireDocumentConversion<SymbolTextScopeDocument>`. Establishes a closed scope and refined file
 * when present; raw scope primitives exist only at this wire boundary.
 */
private fun SymbolTextScopeWireDocument.toContract(): WireDocumentConversion<SymbolTextScopeDocument> =
    when (this) {
    SymbolTextScopeWireDocument.Workspace ->
        WireDocumentConversion.Converted(SymbolTextScopeDocument.Workspace)
    is SymbolTextScopeWireDocument.File ->
        file.toProtocolText().mapConverted { value -> SymbolTextScopeDocument.File(value) }
}
internal fun SymbolDiscoverResult.toSymbolWireDocument() =
    SymbolDiscoverResultWireDocument(items.values.map { it.toWireDocument() })

/**
 * Proof transition: `SymbolDiscoverResultWireDocument ->
 * WireDocumentConversion<SymbolDiscoverResult>`. Establishes refined, bounded discovery evidence;
 * raw item fields are extracted only at this wire boundary.
 */
internal fun SymbolDiscoverResultWireDocument.toContract():
    WireDocumentConversion<SymbolDiscoverResult> = items.convertEach { it.toContract() }
    .flatMapConverted { values -> values.toBoundedList() }
    .mapConverted(::SymbolDiscoverResult)

private fun SymbolDiscoveryDocument.toWireDocument(): SymbolDiscoveryWireDocument = when (this) {
    is SymbolDiscoveryDocument.File -> SymbolDiscoveryWireDocument.File(name.value, file.value)
    is SymbolDiscoveryDocument.Declaration -> SymbolDiscoveryWireDocument.Declaration(
        candidateSelector.value,
        kind.toWireDocument(),
        name.value,
        file.value,
        offset.value,
    )
    is SymbolDiscoveryDocument.TextMatch -> SymbolDiscoveryWireDocument.TextMatch(
        query.value,
        file.value,
        range.toWireDocument(),
    )
}

/**
 * Proof transition: `SymbolDiscoveryWireDocument ->
 * WireDocumentConversion<SymbolDiscoveryDocument>`. Establishes one closed evidence variant with
 * refined fields; raw evidence primitives exist only at this wire boundary.
 */
private fun SymbolDiscoveryWireDocument.toContract(): WireDocumentConversion<SymbolDiscoveryDocument> =
    when (this) {
    is SymbolDiscoveryWireDocument.File -> combineConverted(
        name.toProtocolText(),
        file.toProtocolText(),
    ) { name, file -> SymbolDiscoveryDocument.File(name, file) }
    is SymbolDiscoveryWireDocument.Declaration -> combineConverted(
        candidateSelector.toProtocolText(),
        name.toProtocolText(),
        file.toProtocolText(),
        offset.toProtocolOffset(),
    ) { selector, name, file, offset ->
        SymbolDiscoveryDocument.Declaration(selector, kind.toDiscoveryKind(), name, file, offset)
    }
    is SymbolDiscoveryWireDocument.TextMatch -> combineConverted(
        query.toProtocolText(),
        file.toProtocolText(),
        range.toContract(),
    ) { query, file, range -> SymbolDiscoveryDocument.TextMatch(query, file, range) }
}

internal fun SymbolDescribeResult.toSymbolWireDocument() =
    SymbolDescribeResultWireDocument(symbol.toWireDocument())
/**
 * `SymbolDescribeResultWireDocument -> SymbolDescribeResult` establishes one exact symbol;
 * invalid raw fields become `WireFailure.InvalidPayload` at this wire boundary.
 */
internal fun SymbolDescribeResultWireDocument.toContract():
    WireDocumentConversion<SymbolDescribeResult> = symbol.toContract().mapConverted(
    ::SymbolDescribeResult,
)

internal fun RelationReadResult.toSymbolWireDocument() =
    RelationReadResultWireDocument(relations.values.map { it.toWireDocument() })
/**
 * `RelationReadResultWireDocument -> RelationReadResult` establishes a bounded exact-symbol list;
 * invalid raw fields become `WireFailure.InvalidPayload` at this wire boundary.
 */
internal fun RelationReadResultWireDocument.toContract(): WireDocumentConversion<RelationReadResult> =
    relations.convertEach { it.toContract() }
        .flatMapConverted { values -> values.toBoundedList() }
        .mapConverted(::RelationReadResult)

internal fun TraversalRunResult.toSymbolWireDocument() =
    TraversalRunResultWireDocument(
        snapshotRoot = snapshotRoot.value,
        records = records.values.map { it.toWireDocument() },
    )
/**
 * `TraversalRunResultWireDocument -> TraversalRunResult` establishes a bounded exact-symbol list;
 * invalid raw fields become `WireFailure.InvalidPayload` at this wire boundary.
 */
internal fun TraversalRunResultWireDocument.toContract(): WireDocumentConversion<TraversalRunResult> =
    combineConverted(
        snapshotRoot.toProtocolText(),
        records.convertEach { it.toContract() }
            .flatMapConverted { values -> values.toBoundedList() },
        ::TraversalRunResult,
    )

private fun RelationFactDocument.toWireDocument(): RelationFactWireDocument =
    RelationFactWireDocument(
        meaning = meaning.toRelationWireDocument(),
        source = source.toWireDocument(),
        target = target.toWireDocument(),
        occurrence = RelationOccurrenceWireDocument(
            occurrence.file.value,
            occurrence.range.toWireDocument(),
        ),
        provenance = provenance.toWireDocument(),
        coverage = coverage.toWireDocument(),
    )

private fun RelationFactWireDocument.toContract(): WireDocumentConversion<RelationFactDocument> =
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

private fun RelationOccurrenceWireDocument.toContract():
    WireDocumentConversion<RelationOccurrenceDocument> = combineConverted(
    file.toProtocolText(),
    range.toContract(),
    ::RelationOccurrenceDocument,
)

private fun TraversalRecordDocument.toWireDocument(): TraversalRecordWireDocument =
    TraversalRecordWireDocument(depth.value, relation.toWireDocument())

private fun TraversalRecordWireDocument.toContract(): WireDocumentConversion<TraversalRecordDocument> =
    combineConverted(
        TraversalDepthDocument.parse(depth).toWireDocumentConversion(),
        relation.toContract(),
        ::TraversalRecordDocument,
    )

private fun SymbolDocument.toWireDocument() = SymbolWireDocument(
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
 * Proof transition: `SymbolWireDocument -> WireDocumentConversion<SymbolDocument>`. Establishes an
 * exact symbol with a closed qualified-identity state and valid range; raw symbol primitives exist
 * only at this wire boundary.
 */
private fun SymbolWireDocument.toContract(): WireDocumentConversion<SymbolDocument> =
    combineConverted(
        selector.toProtocolText(),
        name.toProtocolText(),
        qualifiedIdentity.toQualifiedIdentity(),
        file.toProtocolText(),
        range.toContract(),
    ) { selector, name, qualifiedIdentity, file, range ->
        AdmittedSymbolFields(selector, name, qualifiedIdentity, file, range)
    }.flatMapConverted { fields ->
        compilerEvidence.toContract().mapConverted { evidence ->
            SymbolDocument.create(
                fields.selector,
                kind.toContract(),
                fields.name,
                fields.qualifiedIdentity,
                fields.file,
                fields.range,
                evidence,
            ).toWireDocumentConversion()
        }
    }.flattenConverted()

private data class AdmittedSymbolFields(
    val selector: ProtocolText,
    val name: ProtocolText,
    val qualifiedIdentity: SymbolQualifiedIdentityDocument,
    val file: ProtocolText,
    val range: SourceRangeDocument,
)

internal fun CompilerSymbolEvidenceDocument.toWireDocument(): CompilerSymbolEvidenceWireDocument =
    CompilerSymbolEvidenceWireDocument(identity.value, signature.toWireDocument())

internal fun CompilerSymbolEvidenceWireDocument.toContract():
    WireDocumentConversion<CompilerSymbolEvidenceDocument> = combineConverted(
    identity.toProtocolText(),
    signature.toContract(),
) { identity, signature -> identity to signature }
    .flatMapConverted { (identity, signature) ->
        CompilerSymbolEvidenceDocument.restore(identity, signature).toWireDocumentConversion()
    }

private fun CompilerSignatureDocument.toWireDocument(): CompilerSignatureWireDocument = when (this) {
    is CompilerSignatureDocument.Function -> CompilerSignatureWireDocument.Function(
        qualifiedIdentity.value,
        receiver.toWireDocument(),
        contextReceivers.values.map(ProtocolText::value),
        valueParameters.values.map(ProtocolText::value),
        typeParameterCount.value,
    )
    is CompilerSignatureDocument.Property -> CompilerSignatureWireDocument.Property(
        qualifiedIdentity.value,
        receiver.toWireDocument(),
        contextReceivers.values.map(ProtocolText::value),
        returnType.value,
    )
    is CompilerSignatureDocument.TypeAlias ->
        CompilerSignatureWireDocument.TypeAlias(qualifiedIdentity.value)
    is CompilerSignatureDocument.ClassLike ->
        CompilerSignatureWireDocument.ClassLike(qualifiedIdentity.value)
}

private fun CompilerSignatureWireDocument.toContract():
    WireDocumentConversion<CompilerSignatureDocument> = when (this) {
    is CompilerSignatureWireDocument.Function -> combineConverted(
        qualifiedIdentity.toProtocolText(),
        receiver.toContract(),
        contextReceivers.toProtocolTextList(),
        valueParameters.toProtocolTextList(),
        CompilerTypeParameterCountDocument.parse(typeParameterCount).toWireDocumentConversion(),
    ) { qualified, receiver, contexts, parameters, typeParameters ->
        CompilerSignatureDocument.Function(
            qualified,
            receiver,
            contexts,
            parameters,
            typeParameters,
        )
    }
    is CompilerSignatureWireDocument.Property -> combineConverted(
        qualifiedIdentity.toProtocolText(),
        receiver.toContract(),
        contextReceivers.toProtocolTextList(),
        returnType.toProtocolText(),
    ) { qualified, receiver, contextReceivers, returnType ->
        CompilerSignatureDocument.Property(
            qualified,
            receiver,
            contextReceivers,
            returnType,
        )
    }
    is CompilerSignatureWireDocument.TypeAlias -> qualifiedIdentity.toProtocolText().mapConverted {
        CompilerSignatureDocument.TypeAlias(it)
    }
    is CompilerSignatureWireDocument.ClassLike -> qualifiedIdentity.toProtocolText().mapConverted {
        CompilerSignatureDocument.ClassLike(it)
    }
}

private fun CompilerReceiverDocument.toWireDocument(): CompilerReceiverWireDocument = when (this) {
    CompilerReceiverDocument.Absent -> CompilerReceiverWireDocument.Absent
    is CompilerReceiverDocument.Present ->
        CompilerReceiverWireDocument.Present(compilerType.value)
}

private fun CompilerReceiverWireDocument.toContract():
    WireDocumentConversion<CompilerReceiverDocument> = when (this) {
    CompilerReceiverWireDocument.Absent ->
        WireDocumentConversion.Converted(CompilerReceiverDocument.Absent)
    is CompilerReceiverWireDocument.Present -> compilerType.toProtocolText().mapConverted {
        CompilerReceiverDocument.Present(it)
    }
}

private fun List<String>.toProtocolTextList():
    WireDocumentConversion<BoundedProtocolList<ProtocolText>> = convertEach(String::toProtocolText)
    .flatMapConverted { values -> values.toBoundedList() }

/**
 * `String? -> WireDocumentConversion<SymbolQualifiedIdentityDocument>` establishes explicit
 * absence or refined text; [WireDocumentConversion.Rejected] closes invalid raw wire text.
 */
private fun String?.toQualifiedIdentity(): WireDocumentConversion<SymbolQualifiedIdentityDocument> =
    when (this) {
        null -> WireDocumentConversion.Converted(SymbolQualifiedIdentityDocument.Unavailable)
        else -> toProtocolText().mapConverted { value ->
            SymbolQualifiedIdentityDocument.Available(value)
        }
    }

private fun SourceRangeDocument.toWireDocument() =
    SourceRangeWireDocument(startInclusive.value, endExclusive.value)
/**
 * Proof transition: `SourceRangeWireDocument -> WireDocumentConversion<SourceRangeDocument>`.
 * Establishes non-negative, ordered offsets; [WireDocumentConversion.Rejected] is the closed
 * expected failure and raw offsets exist only at this wire boundary.
 */
private fun SourceRangeWireDocument.toContract(): WireDocumentConversion<SourceRangeDocument> =
    combineConverted(startInclusive.toProtocolOffset(), endExclusive.toProtocolOffset()) { start, end ->
        start to end
    }.flatMapConverted { (start, end) ->
        SourceRangeDocument.create(start, end).toWireDocumentConversion()
    }

private fun RelationKindDocument.toRelationWireDocument(): RelationKindWireDocument = when (this) {
    RelationKindDocument.REFERENCES -> RelationKindWireDocument.REFERENCES
    RelationKindDocument.CALLERS -> RelationKindWireDocument.CALLERS
    RelationKindDocument.CALLEES -> RelationKindWireDocument.CALLEES
    RelationKindDocument.IMPLEMENTATIONS -> RelationKindWireDocument.IMPLEMENTATIONS
    RelationKindDocument.INHERITORS -> RelationKindWireDocument.INHERITORS
    RelationKindDocument.OVERRIDES -> RelationKindWireDocument.OVERRIDES
    RelationKindDocument.TYPE_USES -> RelationKindWireDocument.TYPE_USES
}

private fun RelationKindWireDocument.toRelationContract(): RelationKindDocument = when (this) {
    RelationKindWireDocument.REFERENCES -> RelationKindDocument.REFERENCES
    RelationKindWireDocument.CALLERS -> RelationKindDocument.CALLERS
    RelationKindWireDocument.CALLEES -> RelationKindDocument.CALLEES
    RelationKindWireDocument.IMPLEMENTATIONS -> RelationKindDocument.IMPLEMENTATIONS
    RelationKindWireDocument.INHERITORS -> RelationKindDocument.INHERITORS
    RelationKindWireDocument.OVERRIDES -> RelationKindDocument.OVERRIDES
    RelationKindWireDocument.TYPE_USES -> RelationKindDocument.TYPE_USES
}

private fun RelationProvenanceDocument.toWireDocument(): RelationProvenanceWireDocument =
    when (this) {
        RelationProvenanceDocument.K2_AUTHORED_SOURCE ->
            RelationProvenanceWireDocument.K2_AUTHORED_SOURCE
        RelationProvenanceDocument.K2_GENERATED_SOURCE ->
            RelationProvenanceWireDocument.K2_GENERATED_SOURCE
        RelationProvenanceDocument.K2_PROJECT_LIBRARY ->
            RelationProvenanceWireDocument.K2_PROJECT_LIBRARY
    }

private fun RelationProvenanceWireDocument.toContract(): RelationProvenanceDocument = when (this) {
    RelationProvenanceWireDocument.K2_AUTHORED_SOURCE ->
        RelationProvenanceDocument.K2_AUTHORED_SOURCE
    RelationProvenanceWireDocument.K2_GENERATED_SOURCE ->
        RelationProvenanceDocument.K2_GENERATED_SOURCE
    RelationProvenanceWireDocument.K2_PROJECT_LIBRARY ->
        RelationProvenanceDocument.K2_PROJECT_LIBRARY
}

private fun RelationFactCoverageDocument.toWireDocument(): RelationFactCoverageWireDocument =
    when (this) {
        RelationFactCoverageDocument.EXACT_COMPILER_CONFIRMED ->
            RelationFactCoverageWireDocument.EXACT_COMPILER_CONFIRMED
    }

private fun RelationFactCoverageWireDocument.toContract(): RelationFactCoverageDocument =
    when (this) {
        RelationFactCoverageWireDocument.EXACT_COMPILER_CONFIRMED ->
            RelationFactCoverageDocument.EXACT_COMPILER_CONFIRMED
    }

private fun SymbolNameKindDocument.toWireDocument() = when (this) {
    SymbolNameKindDocument.FILE -> SymbolCategoryWireDocument.FILE
    SymbolNameKindDocument.CLASS -> SymbolCategoryWireDocument.CLASS
    SymbolNameKindDocument.SYMBOL -> SymbolCategoryWireDocument.SYMBOL
}

private fun SymbolCategoryWireDocument.toNameKind() = when (this) {
    SymbolCategoryWireDocument.FILE -> SymbolNameKindDocument.FILE
    SymbolCategoryWireDocument.CLASS -> SymbolNameKindDocument.CLASS
    SymbolCategoryWireDocument.SYMBOL -> SymbolNameKindDocument.SYMBOL
}

private fun SymbolDiscoveryKindDocument.toWireDocument() = when (this) {
    SymbolDiscoveryKindDocument.FILE -> SymbolCategoryWireDocument.FILE
    SymbolDiscoveryKindDocument.CLASS -> SymbolCategoryWireDocument.CLASS
    SymbolDiscoveryKindDocument.SYMBOL -> SymbolCategoryWireDocument.SYMBOL
}

private fun SymbolCategoryWireDocument.toDiscoveryKind() = when (this) {
    SymbolCategoryWireDocument.FILE -> SymbolDiscoveryKindDocument.FILE
    SymbolCategoryWireDocument.CLASS -> SymbolDiscoveryKindDocument.CLASS
    SymbolCategoryWireDocument.SYMBOL -> SymbolDiscoveryKindDocument.SYMBOL
}

private fun SymbolDiscoveryMatchDocument.toWireDocument() = when (this) {
    SymbolDiscoveryMatchDocument.FUZZY -> SymbolDiscoveryMatchWireDocument.FUZZY
    SymbolDiscoveryMatchDocument.EXACT_NAME -> SymbolDiscoveryMatchWireDocument.EXACT_NAME
}

private fun SymbolDiscoveryMatchWireDocument.toContract() = when (this) {
    SymbolDiscoveryMatchWireDocument.FUZZY -> SymbolDiscoveryMatchDocument.FUZZY
    SymbolDiscoveryMatchWireDocument.EXACT_NAME -> SymbolDiscoveryMatchDocument.EXACT_NAME
}

private fun SymbolKindDocument.toWireDocument() = when (this) {
    SymbolKindDocument.CLASSLIKE -> SymbolKindWireDocument.CLASSLIKE
    SymbolKindDocument.CONSTRUCTOR -> SymbolKindWireDocument.CONSTRUCTOR
    SymbolKindDocument.FUNCTION -> SymbolKindWireDocument.FUNCTION
    SymbolKindDocument.PROPERTY -> SymbolKindWireDocument.PROPERTY
    SymbolKindDocument.TYPE_ALIAS -> SymbolKindWireDocument.TYPE_ALIAS
}

private fun SymbolKindWireDocument.toContract() = when (this) {
    SymbolKindWireDocument.CLASSLIKE -> SymbolKindDocument.CLASSLIKE
    SymbolKindWireDocument.CONSTRUCTOR -> SymbolKindDocument.CONSTRUCTOR
    SymbolKindWireDocument.FUNCTION -> SymbolKindDocument.FUNCTION
    SymbolKindWireDocument.PROPERTY -> SymbolKindDocument.PROPERTY
    SymbolKindWireDocument.TYPE_ALIAS -> SymbolKindDocument.TYPE_ALIAS
}

/** `String -> WireDocumentConversion<ProtocolText>`; closes invalid text at the wire boundary. */
private fun String.toProtocolText(): WireDocumentConversion<ProtocolText> = ProtocolText.parse(this)
    .toWireDocumentConversion()

/** `Int -> WireDocumentConversion<ProtocolCount>`; closes invalid counts at the wire boundary. */
private fun Int.toProtocolCount(): WireDocumentConversion<ProtocolCount> = ProtocolCount.parse(this)
    .toWireDocumentConversion()

/** `Int -> WireDocumentConversion<ProtocolOffset>`; closes invalid offsets at the wire boundary. */
private fun Int.toProtocolOffset(): WireDocumentConversion<ProtocolOffset> = ProtocolOffset.parse(this)
    .toWireDocumentConversion()

/** `List<Value> -> WireDocumentConversion<BoundedProtocolList<Value>>`; proves the wire bound. */
private fun <Value> List<Value>.toBoundedList(): WireDocumentConversion<BoundedProtocolList<Value>> =
    BoundedProtocolList.create(this).toWireDocumentConversion()
