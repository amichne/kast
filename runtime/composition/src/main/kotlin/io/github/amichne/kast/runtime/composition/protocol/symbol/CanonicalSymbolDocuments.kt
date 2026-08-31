package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CompilerReceiverDocument
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.CompilerTypeParameterCountDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryKindDocument
import io.github.amichne.kast.protocol.contract.SymbolDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.contract.SymbolQualifiedIdentityDocument
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.symbol.contract.CanonicalCompilerReceiver
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalCompilerType
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind

internal fun SymbolDiscoveryCandidate.protocolDocument(
    candidateSelector: ProtocolText?,
): SymbolDiscoveryDocument? {
    val name = text(name.value) ?: return null
    val file = text(location.file.stableValue) ?: return null
    return when (val candidateLocation = location) {
        is SymbolDiscoveryCandidateLocation.File -> SymbolDiscoveryDocument.File(name, file)
        is SymbolDiscoveryCandidateLocation.Declaration -> SymbolDiscoveryDocument.Declaration(
            candidateSelector ?: return null,
            kind.protocolDiscoveryKind() ?: return null,
            name,
            file,
            offset(candidateLocation.offset.value) ?: return null,
        )
        is SymbolDiscoveryCandidateLocation.Text -> SymbolDiscoveryDocument.TextMatch(
            name,
            file,
            range(
                candidateLocation.range.startInclusive.value,
                candidateLocation.range.endExclusive.value,
            ) ?: return null,
        )
    }
}

internal fun SymbolDescription.protocolDocument(
    exactSelector: ProtocolText,
): SymbolDocument? = symbolDocument(
    exactSelector,
    kind,
    name.value,
    qualifiedIdentity,
    file.stableValue,
    range.startInclusive,
    range.endExclusive,
    signature,
    compilerIdentity,
)

internal fun RelationEndpoint.protocolDocument(
    exactSelector: ProtocolText,
): SymbolDocument? = symbolDocument(
    exactSelector,
    kind,
    name.value,
    qualifiedIdentity,
    file.stableValue,
    range.startInclusive,
    range.endExclusive,
    signature,
    compilerIdentity,
)

private fun symbolDocument(
    selector: ProtocolText,
    kind: CompilerSymbolKind,
    rawName: String,
    qualified: ExactDeclarationQualifiedIdentity,
    rawFile: String,
    rawStart: Int,
    rawEnd: Int,
    signature: CanonicalCompilerSignature,
    compilerIdentity: CompilerSymbolIdentity,
): SymbolDocument? {
    val qualifiedDocument = when (qualified) {
        is ExactDeclarationQualifiedIdentity.Available ->
            SymbolQualifiedIdentityDocument.Available(text(qualified.value) ?: return null)
        ExactDeclarationQualifiedIdentity.Unavailable ->
            SymbolQualifiedIdentityDocument.Unavailable
    }
    val signatureDocument = signature.protocolDocument() ?: return null
    val compilerEvidence = CompilerSymbolEvidenceDocument.restore(
        identity = text(compilerIdentity.value) ?: return null,
        signature = signatureDocument,
    ).refinedOrNull() ?: return null
    return SymbolDocument.create(
        selector = selector,
        kind = kind.protocolKind(),
        name = text(rawName) ?: return null,
        qualifiedIdentity = qualifiedDocument,
        file = text(rawFile) ?: return null,
        range = range(rawStart, rawEnd) ?: return null,
        compilerEvidence = compilerEvidence,
    ).refinedOrNull()
}

private fun CanonicalCompilerSignature.protocolDocument(): CompilerSignatureDocument? {
    return when (this) {
    is CanonicalCompilerSignature.Function -> CompilerSignatureDocument.Function(
        qualifiedIdentity = text(qualifiedIdentity.value) ?: return null,
        receiver = when (val compilerReceiver = receiver) {
            CanonicalCompilerReceiver.Absent -> CompilerReceiverDocument.Absent
            is CanonicalCompilerReceiver.Present -> CompilerReceiverDocument.Present(
                text(compilerReceiver.type.value) ?: return null,
            )
        },
        contextReceivers = contextReceivers.protocolTypes() ?: return null,
        valueParameters = valueParameters.protocolTypes() ?: return null,
        typeParameterCount = CompilerTypeParameterCountDocument.parse(typeParameterCount.value)
            .refinedOrNull() ?: return null,
    )
    is CanonicalCompilerSignature.Property -> CompilerSignatureDocument.Property(
        qualifiedIdentity = text(qualifiedIdentity.value) ?: return null,
        receiver = when (val compilerReceiver = receiver) {
            CanonicalCompilerReceiver.Absent -> CompilerReceiverDocument.Absent
            is CanonicalCompilerReceiver.Present -> CompilerReceiverDocument.Present(
                text(compilerReceiver.type.value) ?: return null,
            )
        },
        contextReceivers = contextReceivers.protocolTypes() ?: return null,
        returnType = text(returnType.value) ?: return null,
    )
    is CanonicalCompilerSignature.TypeAlias -> CompilerSignatureDocument.TypeAlias(
        qualifiedIdentity = text(qualifiedIdentity.value) ?: return null,
    )
    is CanonicalCompilerSignature.ClassLike -> CompilerSignatureDocument.ClassLike(
        qualifiedIdentity = text(qualifiedIdentity.value) ?: return null,
    )
    }
}

private fun List<CanonicalCompilerType>.protocolTypes(): BoundedProtocolList<ProtocolText>? {
    val projected = map { compilerType -> text(compilerType.value) ?: return null }
    return BoundedProtocolList.create(projected).refinedOrNull()
}

private fun SymbolDiscoveryKind.protocolDiscoveryKind(): SymbolDiscoveryKindDocument? = when (this) {
    SymbolDiscoveryKind.FILE -> SymbolDiscoveryKindDocument.FILE
    SymbolDiscoveryKind.CLASS -> SymbolDiscoveryKindDocument.CLASS
    SymbolDiscoveryKind.SYMBOL -> SymbolDiscoveryKindDocument.SYMBOL
    SymbolDiscoveryKind.TEXT -> null
}

private fun CompilerSymbolKind.protocolKind(): SymbolKindDocument = when (this) {
    CompilerSymbolKind.CLASSLIKE -> SymbolKindDocument.CLASSLIKE
    CompilerSymbolKind.CONSTRUCTOR -> SymbolKindDocument.CONSTRUCTOR
    CompilerSymbolKind.FUNCTION -> SymbolKindDocument.FUNCTION
    CompilerSymbolKind.PROPERTY -> SymbolKindDocument.PROPERTY
    CompilerSymbolKind.TYPE_ALIAS -> SymbolKindDocument.TYPE_ALIAS
}

private fun text(raw: String): ProtocolText? = ProtocolText.parse(raw).refinedOrNull()

private fun offset(raw: Int): ProtocolOffset? = ProtocolOffset.parse(raw).refinedOrNull()

private fun range(start: Int, end: Int): SourceRangeDocument? {
    val startOffset = offset(start) ?: return null
    val endOffset = offset(end) ?: return null
    return SourceRangeDocument.create(startOffset, endOffset).refinedOrNull()
}

private fun <Value, Failure> Refinement<Value, Failure>.refinedOrNull(): Value? = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> null
}
