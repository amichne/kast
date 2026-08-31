package io.github.amichne.kast.runtime.ide.read.composition

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.CompilerReceiverDocument
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.CompilerTypeParameterCountDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.SymbolDescribeRejection
import io.github.amichne.kast.protocol.contract.SymbolDescribeResult
import io.github.amichne.kast.protocol.contract.SymbolDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.contract.SymbolQualifiedIdentityDocument
import io.github.amichne.kast.protocol.contract.SymbolResolveRejection
import io.github.amichne.kast.protocol.contract.SymbolResolveResult
import io.github.amichne.kast.symbol.contract.CanonicalCompilerReceiver
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalCompilerType
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionCompilation
import io.github.amichne.kast.symbol.contract.SymbolExactCompilerRejection
import io.github.amichne.kast.symbol.contract.SymbolResolutionCompilation

/**
 * Proof transition: `(SymbolResolutionCompilation, HostedSelectorAuthority) -> closed resolve
 * outcome`.
 *
 * Issues public exact-selector authority only from compiler-grounded resolution. Every native
 * rejection remains a finite protocol rejection; raw selector text leaves only at token issuance.
 */
internal fun SymbolResolutionCompilation.hostedOutcome(
    selectors: HostedSelectorAuthority,
): OperationOutcome<SymbolResolveResult, Nothing, SymbolResolveRejection> = when (this) {
    is SymbolResolutionCompilation.Resolved -> when (val issued = selectors.issueExact(selector)) {
        is HostedExactIssuance.Issued -> OperationOutcome.Complete(
            EvidenceEnvelope(
                CanonicalOperation.SYMBOL_RESOLVE.id,
                selector.lease.generation,
                SymbolResolveResult(issued.token),
            ),
        )
        HostedExactIssuance.Rejected -> rejectedResolve(SymbolResolveRejection.AMBIGUOUS)
    }
    is SymbolResolutionCompilation.Rejected -> rejectedResolve(reason.hostedResolveRejection())
}

/**
 * Proof transition: `(SymbolDescriptionCompilation, ProtocolText) -> closed describe outcome`.
 *
 * Preserves compiler-grounded exact identity and the same endpoint-issued selector in one detached
 * protocol document. Projection failure and native rejection remain finite protocol rejections.
 */
internal fun SymbolDescriptionCompilation.hostedOutcome(
    token: ProtocolText,
): OperationOutcome<SymbolDescribeResult, Nothing, SymbolDescribeRejection> = when (this) {
    is SymbolDescriptionCompilation.Described -> {
        val document = description.hostedDocument(token)
            ?: return rejectedDescribe(SymbolDescribeRejection.NOT_FOUND)
        OperationOutcome.Complete(
            EvidenceEnvelope(
                CanonicalOperation.SYMBOL_DESCRIBE.id,
                description.selector.lease.generation,
                SymbolDescribeResult(document),
            ),
        )
    }
    is SymbolDescriptionCompilation.Rejected ->
        rejectedDescribe(reason.hostedDescribeRejection())
}

private fun SymbolDescription.hostedDocument(token: ProtocolText): SymbolDocument? {
    val admittedName = refined(ProtocolText.parse(name.value)) ?: return null
    val admittedFile = refined(ProtocolText.parse(file.stableValue)) ?: return null
    val start = refined(ProtocolOffset.parse(range.startInclusive)) ?: return null
    val end = refined(ProtocolOffset.parse(range.endExclusive)) ?: return null
    val admittedRange = refined(SourceRangeDocument.create(start, end)) ?: return null
    val identity = when (val qualified = qualifiedIdentity) {
        is ExactDeclarationQualifiedIdentity.Available ->
            SymbolQualifiedIdentityDocument.Available(
                refined(ProtocolText.parse(qualified.value)) ?: return null,
            )
        ExactDeclarationQualifiedIdentity.Unavailable ->
            SymbolQualifiedIdentityDocument.Unavailable
    }
    val signatureDocument = signature.hostedDocument() ?: return null
    val compilerEvidence = refined(
        CompilerSymbolEvidenceDocument.restore(
            identity = refined(ProtocolText.parse(compilerIdentity.value)) ?: return null,
            signature = signatureDocument,
        ),
    ) ?: return null
    return refined(
        SymbolDocument.create(
            selector = token,
            kind = kind.hostedKind(),
            name = admittedName,
            qualifiedIdentity = identity,
            file = admittedFile,
            range = admittedRange,
            compilerEvidence = compilerEvidence,
        ),
    )
}

private fun CanonicalCompilerSignature.hostedDocument(): CompilerSignatureDocument? = when (this) {
    is CanonicalCompilerSignature.Function -> CompilerSignatureDocument.Function(
        qualifiedIdentity = refined(ProtocolText.parse(qualifiedIdentity.value)) ?: return null,
        receiver = when (val canonical = receiver) {
            CanonicalCompilerReceiver.Absent -> CompilerReceiverDocument.Absent
            is CanonicalCompilerReceiver.Present -> CompilerReceiverDocument.Present(
                refined(ProtocolText.parse(canonical.type.value)) ?: return null,
            )
        },
        contextReceivers = contextReceivers.hostedTypes() ?: return null,
        valueParameters = valueParameters.hostedTypes() ?: return null,
        typeParameterCount = refined(
            CompilerTypeParameterCountDocument.parse(typeParameterCount.value),
        ) ?: return null,
    )
    is CanonicalCompilerSignature.Property -> CompilerSignatureDocument.Property(
        qualifiedIdentity = refined(ProtocolText.parse(qualifiedIdentity.value)) ?: return null,
        receiver = when (val canonical = receiver) {
            CanonicalCompilerReceiver.Absent -> CompilerReceiverDocument.Absent
            is CanonicalCompilerReceiver.Present -> CompilerReceiverDocument.Present(
                refined(ProtocolText.parse(canonical.type.value)) ?: return null,
            )
        },
        contextReceivers = contextReceivers.hostedTypes() ?: return null,
        returnType = refined(ProtocolText.parse(returnType.value)) ?: return null,
    )
    is CanonicalCompilerSignature.TypeAlias -> CompilerSignatureDocument.TypeAlias(
        refined(ProtocolText.parse(qualifiedIdentity.value)) ?: return null,
    )
    is CanonicalCompilerSignature.ClassLike -> CompilerSignatureDocument.ClassLike(
        refined(ProtocolText.parse(qualifiedIdentity.value)) ?: return null,
    )
}

private fun List<CanonicalCompilerType>.hostedTypes(): BoundedProtocolList<ProtocolText>? =
    refined(
        BoundedProtocolList.create(
            map { type -> refined(ProtocolText.parse(type.value)) ?: return null },
        ),
    )

private fun CompilerSymbolKind.hostedKind(): SymbolKindDocument = when (this) {
    CompilerSymbolKind.CLASSLIKE -> SymbolKindDocument.CLASSLIKE
    CompilerSymbolKind.CONSTRUCTOR -> SymbolKindDocument.CONSTRUCTOR
    CompilerSymbolKind.FUNCTION -> SymbolKindDocument.FUNCTION
    CompilerSymbolKind.PROPERTY -> SymbolKindDocument.PROPERTY
    CompilerSymbolKind.TYPE_ALIAS -> SymbolKindDocument.TYPE_ALIAS
}

private fun SymbolExactCompilerRejection.hostedResolveRejection(): SymbolResolveRejection =
    when (this) {
        SymbolExactCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE,
        SymbolExactCompilerRejection.GENERATION_MOVED,
            -> SymbolResolveRejection.WORKSPACE_NOT_READY
        SymbolExactCompilerRejection.WORKSPACE_ROOT_MISMATCH,
        SymbolExactCompilerRejection.STALE_LOCATION,
        SymbolExactCompilerRejection.DECLARATION_MOVED_OR_CHANGED,
            -> SymbolResolveRejection.CANDIDATE_STALE
        SymbolExactCompilerRejection.AMBIGUOUS_DECLARATION -> SymbolResolveRejection.AMBIGUOUS
        SymbolExactCompilerRejection.SCOPE_REJECTED,
        SymbolExactCompilerRejection.OUTSIDE_SCOPE,
        SymbolExactCompilerRejection.UNSUPPORTED_DECLARATION,
        SymbolExactCompilerRejection.COMPILER_IDENTITY_UNAVAILABLE,
        SymbolExactCompilerRejection.INTERNAL_INVARIANT,
            -> SymbolResolveRejection.NOT_FOUND
    }

private fun SymbolExactCompilerRejection.hostedDescribeRejection(): SymbolDescribeRejection =
    when (this) {
        SymbolExactCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE,
        SymbolExactCompilerRejection.GENERATION_MOVED,
            -> SymbolDescribeRejection.WORKSPACE_NOT_READY
        SymbolExactCompilerRejection.WORKSPACE_ROOT_MISMATCH,
        SymbolExactCompilerRejection.STALE_LOCATION,
        SymbolExactCompilerRejection.DECLARATION_MOVED_OR_CHANGED,
            -> SymbolDescribeRejection.SELECTOR_STALE
        SymbolExactCompilerRejection.SCOPE_REJECTED,
        SymbolExactCompilerRejection.OUTSIDE_SCOPE,
        SymbolExactCompilerRejection.AMBIGUOUS_DECLARATION,
        SymbolExactCompilerRejection.UNSUPPORTED_DECLARATION,
        SymbolExactCompilerRejection.COMPILER_IDENTITY_UNAVAILABLE,
        SymbolExactCompilerRejection.INTERNAL_INVARIANT,
            -> SymbolDescribeRejection.NOT_FOUND
    }

internal fun rejectedResolve(
    reason: SymbolResolveRejection,
): OperationOutcome<SymbolResolveResult, Nothing, SymbolResolveRejection> =
    OperationOutcome.Rejected(reason)

internal fun rejectedDescribe(
    reason: SymbolDescribeRejection,
): OperationOutcome<SymbolDescribeResult, Nothing, SymbolDescribeRejection> =
    OperationOutcome.Rejected(reason)

private fun <Value, Failure> refined(value: Refinement<Value, Failure>): Value? = when (value) {
    is Refinement.Refined -> value.value
    is Refinement.Rejected -> null
}
