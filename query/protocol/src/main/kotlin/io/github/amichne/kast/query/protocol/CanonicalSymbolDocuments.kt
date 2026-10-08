package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CompilerReceiverDocument
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.CompilerTypeParameterCountDocument
import io.github.amichne.kast.protocol.contract.LocalDeclarationAddressDocument
import io.github.amichne.kast.protocol.contract.LocalDeclarationFileDocument
import io.github.amichne.kast.protocol.contract.LocalDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.LocalPropertyMutabilityDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
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
import io.github.amichne.kast.symbol.contract.LocalDeclarationAddress
import io.github.amichne.kast.symbol.contract.LocalPropertyMutability
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

fun SymbolDescription.protocolDocument(exactSelector: ProtocolText): SymbolDocument? =
    symbolDocument(
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

fun RelationEndpoint.protocolDocument(exactSelector: ProtocolText): SymbolDocument? =
    symbolDocument(
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
    val qualifiedDocument =
        when (qualified) {
            is ExactDeclarationQualifiedIdentity.Available ->
                SymbolQualifiedIdentityDocument.Available(text(qualified.value) ?: return null)
            ExactDeclarationQualifiedIdentity.Unavailable -> SymbolQualifiedIdentityDocument.Unavailable
        }
    val signatureDocument = signature.protocolDocument() ?: return null
    val compilerEvidence =
        CompilerSymbolEvidenceDocument.restore(
                identity = text(compilerIdentity.value) ?: return null,
                signature = signatureDocument,
            )
            .refinedOrNull() ?: return null
    return SymbolDocument.create(
            selector = selector,
            kind = kind.protocolKind(),
            name = text(rawName) ?: return null,
            qualifiedIdentity = qualifiedDocument,
            file = text(rawFile) ?: return null,
            range = range(rawStart, rawEnd) ?: return null,
            compilerEvidence = compilerEvidence,
        )
        .refinedOrNull()
}

internal fun CanonicalCompilerSignature.protocolDocument(): CompilerSignatureDocument? {
    return when (this) {
        is CanonicalCompilerSignature.LocalFunction -> protocolLocalFunction()
        is CanonicalCompilerSignature.LocalProperty ->
            CompilerSignatureDocument.LocalProperty(
                address.protocolDocument() ?: return null,
                text(returnType.value) ?: return null,
                when (mutability) {
                    LocalPropertyMutability.VAL -> LocalPropertyMutabilityDocument.VAL
                    LocalPropertyMutability.VAR -> LocalPropertyMutabilityDocument.VAR
                },
            )
        is CanonicalCompilerSignature.Function -> protocolFunction()
        is CanonicalCompilerSignature.Property -> protocolProperty()
        is CanonicalCompilerSignature.TypeAlias ->
            CompilerSignatureDocument.TypeAlias(qualifiedIdentity = text(qualifiedIdentity.value) ?: return null)
        is CanonicalCompilerSignature.ClassLike ->
            CompilerSignatureDocument.ClassLike(qualifiedIdentity = text(qualifiedIdentity.value) ?: return null)
    }
}

private fun CanonicalCompilerSignature.LocalFunction.protocolLocalFunction(): CompilerSignatureDocument.LocalFunction? {
    return CompilerSignatureDocument.LocalFunction(
        address.protocolDocument() ?: return null,
        receiver.protocolDocument() ?: return null,
        contextReceivers.protocolTypes() ?: return null,
        valueParameters.protocolTypes() ?: return null,
        CompilerTypeParameterCountDocument.parse(typeParameterCount.value).refinedOrNull() ?: return null,
        text(returnType.value) ?: return null,
    )
}

private fun CanonicalCompilerSignature.Function.protocolFunction(): CompilerSignatureDocument.Function? {
    return CompilerSignatureDocument.Function(
        qualifiedIdentity = text(qualifiedIdentity.value) ?: return null,
        receiver =
            when (val compilerReceiver = receiver) {
                CanonicalCompilerReceiver.Absent -> CompilerReceiverDocument.Absent
                is CanonicalCompilerReceiver.Present ->
                    CompilerReceiverDocument.Present(text(compilerReceiver.type.value) ?: return null)
            },
        contextReceivers = contextReceivers.protocolTypes() ?: return null,
        valueParameters = valueParameters.protocolTypes() ?: return null,
        typeParameterCount =
            CompilerTypeParameterCountDocument.parse(typeParameterCount.value).refinedOrNull() ?: return null,
    )
}

private fun CanonicalCompilerSignature.Property.protocolProperty(): CompilerSignatureDocument.Property? {
    return CompilerSignatureDocument.Property(
        qualifiedIdentity = text(qualifiedIdentity.value) ?: return null,
        receiver =
            when (val compilerReceiver = receiver) {
                CanonicalCompilerReceiver.Absent -> CompilerReceiverDocument.Absent
                is CanonicalCompilerReceiver.Present ->
                    CompilerReceiverDocument.Present(text(compilerReceiver.type.value) ?: return null)
            },
        contextReceivers = contextReceivers.protocolTypes() ?: return null,
        returnType = text(returnType.value) ?: return null,
    )
}

private fun List<CanonicalCompilerType>.protocolTypes(): BoundedProtocolList<ProtocolText>? {
    val projected = map { compilerType -> text(compilerType.value) ?: return null }
    return BoundedProtocolList.create(projected).refinedOrNull()
}

internal fun CompilerSymbolKind.protocolKind(): SymbolKindDocument =
    when (this) {
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

private fun <Value, Failure> Refinement<Value, Failure>.refinedOrNull(): Value? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }

private fun LocalDeclarationAddress.protocolDocument(): LocalDeclarationAddressDocument? =
    LocalDeclarationAddressDocument.create(
            when (val source = file) {
                is SymbolDiscoveryFileIdentity.Workspace ->
                    LocalDeclarationFileDocument.Workspace(text(source.stableValue) ?: return null)
                is SymbolDiscoveryFileIdentity.External ->
                    LocalDeclarationFileDocument.External(text(source.stableValue) ?: return null)
            },
            when (kind) {
                io.github.amichne.kast.symbol.contract.LocalDeclarationKind.FUNCTION ->
                    LocalDeclarationKindDocument.FUNCTION
                io.github.amichne.kast.symbol.contract.LocalDeclarationKind.PROPERTY ->
                    LocalDeclarationKindDocument.PROPERTY
            },
            range(range.startInclusive, range.endExclusive) ?: return null,
            text(ownerIdentity.value) ?: return null,
            range(ownerRange.startInclusive, ownerRange.endExclusive) ?: return null,
            BoundedProtocolList.create(lexicalOwners.map { range(it.startInclusive, it.endExclusive) ?: return null })
                .refinedOrNull() ?: return null,
        )
        .refinedOrNull()

private fun CanonicalCompilerReceiver.protocolDocument(): CompilerReceiverDocument? =
    when (this) {
        CanonicalCompilerReceiver.Absent -> CompilerReceiverDocument.Absent
        is CanonicalCompilerReceiver.Present -> CompilerReceiverDocument.Present(text(type.value) ?: return null)
    }
