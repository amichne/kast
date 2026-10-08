package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.fromCanonicalSignature
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

private const val MAX_SOURCELESS_MODULE_NAME_LENGTH = 512

enum class SourceLessCallableOrigin {
    SOURCE,
    SOURCE_MEMBER_GENERATED,
    LIBRARY,
    JAVA_SOURCE,
    JAVA_LIBRARY,
    SAM_CONSTRUCTOR,
    TYPEALIASED_CONSTRUCTOR,
    INTERSECTION_OVERRIDE,
    SUBSTITUTION_OVERRIDE,
    DELEGATED,
    JAVA_SYNTHETIC_PROPERTY,
    PROPERTY_BACKING_FIELD,
    PLUGIN,
    JS_DYNAMIC,
    NATIVE_FORWARD_DECLARATION,
}

enum class SourceLessCallableModuleKind {
    BUILTINS,
    LIBRARY,
}

enum class SourceLessCallableFailure {
    INVALID_MODULE_NAME,
    NOT_CALLABLE,
    UNSUPPORTED_ORIGIN,
}

@JvmInline
value class SourceLessCallableModuleName private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<SourceLessCallableModuleName, SourceLessCallableFailure> =
            if (raw.isBlank() || raw.length > MAX_SOURCELESS_MODULE_NAME_LENGTH || raw.any { it.isISOControl() })
                Refinement.Rejected(SourceLessCallableFailure.INVALID_MODULE_NAME)
            else Refinement.Refined(SourceLessCallableModuleName(raw))
    }
}

/** A resolved compiler symbol needs no fabricated source file or range to retain its identity. */
@ConsistentCopyVisibility
data class SourceLessCallable
private constructor(
    val signature: CanonicalCompilerSignature,
    val kind: CompilerSymbolKind,
    val compilerIdentity: CompilerSymbolIdentity,
    val origin: SourceLessCallableOrigin,
    val moduleKind: SourceLessCallableModuleKind,
    val moduleName: SourceLessCallableModuleName,
) {
    companion object {
        fun fromCompiler(
            signature: CanonicalCompilerSignature,
            kind: CompilerSymbolKind,
            origin: SourceLessCallableOrigin,
            moduleKind: SourceLessCallableModuleKind,
            moduleName: SourceLessCallableModuleName,
        ): Refinement<SourceLessCallable, SourceLessCallableFailure> =
            when {
                !signature.supportsSourceLessCallable(kind) ->
                    Refinement.Rejected(SourceLessCallableFailure.NOT_CALLABLE)
                moduleKind == SourceLessCallableModuleKind.LIBRARY &&
                    origin != SourceLessCallableOrigin.LIBRARY &&
                    origin != SourceLessCallableOrigin.JAVA_LIBRARY ->
                    Refinement.Rejected(SourceLessCallableFailure.UNSUPPORTED_ORIGIN)
                else ->
                    Refinement.Refined(
                        SourceLessCallable(
                            signature,
                            kind,
                            CompilerSymbolIdentity.fromCanonicalSignature(signature),
                            origin,
                            moduleKind,
                            moduleName,
                        )
                    )
            }
    }
}

private fun CanonicalCompilerSignature.supportsSourceLessCallable(kind: CompilerSymbolKind): Boolean =
    when (this) {
        is CanonicalCompilerSignature.Function ->
            kind == CompilerSymbolKind.FUNCTION || kind == CompilerSymbolKind.CONSTRUCTOR
        is CanonicalCompilerSignature.Property -> kind == CompilerSymbolKind.PROPERTY
        is CanonicalCompilerSignature.LocalFunction,
        is CanonicalCompilerSignature.LocalProperty -> false
        is CanonicalCompilerSignature.TypeAlias,
        is CanonicalCompilerSignature.ClassLike -> false
    }

enum class SourceLessCallableDisposition {
    BUILTIN_BOUNDARY,
    LIBRARY_POLICY_EXCLUDED,
    LIBRARY_SOURCE_UNAVAILABLE,
}

sealed interface RelationCallableTarget {
    data class DirectInvocations(val invocations: CompleteCallbackDirectInvocations) : RelationCallableTarget

    data class UnavailableSupply(val evidence: CallbackSupplyUnavailable) : RelationCallableTarget

    data class CallbackSupplies(val supplies: CompleteCallbackSupplies) : RelationCallableTarget

    data class UnavailableReference(val cause: CallbackInvocationFlowCause) : RelationCallableTarget

    data class NamedReference(val reference: NamedCallbackReference) : RelationCallableTarget

    data class ParameterInvocation(
        val parameter: CallbackParameterIdentity,
        val suppliers: CallbackSupplierInventoryEvidence,
        val invocation: CallbackParameterInvocation,
    ) : RelationCallableTarget

    data class SourceLess(val callable: SourceLessCallable, val disposition: SourceLessCallableDisposition) :
        RelationCallableTarget
}

enum class RelationCallableObservationFailure {
    MEANING_MISMATCH,
    SUBJECT_MISMATCH,
    OCCURRENCE_OUTSIDE_BODY,
    BODY_OUTSIDE_LEXICAL_OWNER,
    PARAMETER_AUTHORITY_MISMATCH,
    BOUNDARY_POLICY_MISMATCH,
}

/** Symbolic callee and known compiler boundary evidence do not manufacture a named graph edge. */
@ConsistentCopyVisibility
data class RelationCallableObservation
private constructor(
    val subject: RelationEndpointFingerprint,
    val basis: SemanticReadAuthority,
    val requestedDomain: RelationSearchBoundary,
    val effectiveDomain: RelationScopeFingerprint,
    val meaning: RelationMeaning,
    val occurrence: RelationOccurrence,
    val lexicalOwner: CompilerGroundedSymbolEvidence,
    val body: RelationCallableBody,
    val target: RelationCallableTarget,
) : Comparable<RelationCallableObservation> {
    val retainedBytes: Long
        get() =
            (4096L + canonicalProjection().length * 4L).addBytes(
                when (val value = target) {
                    is RelationCallableTarget.DirectInvocations -> value.invocations.retainedBytes
                    is RelationCallableTarget.CallbackSupplies -> value.supplies.retainedBytes
                    is RelationCallableTarget.NamedReference -> value.reference.retainedBytes
                    is RelationCallableTarget.ParameterInvocation ->
                        value.invocation.retainedBytes.addBytes(
                            (value.suppliers as? CallbackSupplierInventoryEvidence.Exhaustive)?.inventory?.retainedBytes
                                ?: 0L
                        )
                    is RelationCallableTarget.UnavailableSupply,
                    is RelationCallableTarget.UnavailableReference,
                    is RelationCallableTarget.SourceLess -> 0L
                }
            )

    fun belongsTo(request: RelationRequest): Boolean =
        request.meaning == meaning &&
            subject == request.subject.fingerprint &&
            basis == request.subject.lease &&
            requestedDomain == request.boundary &&
            effectiveDomain == request.scopeFingerprint

    fun canonicalProjection(): String =
        listOf(
                subject.value,
                effectiveDomain.value,
                meaning.toString(),
                occurrence.file.stableValue,
                occurrence.range.toString(),
                lexicalOwner.file.stableValue,
                lexicalOwner.range.toString(),
                lexicalOwner.name.value,
                lexicalOwner.kind.name,
                lexicalOwner.compilerIdentity.value,
                lexicalOwner.signature.canonicalEncoding().value,
                body.file.stableValue,
                body.compilerIdentity.value,
                body.range.toString(),
                when (val value = body) {
                    is RelationCallableBody.Named ->
                        "NAMED:${value.evidence.name.value}:${value.evidence.signature.canonicalEncoding().value}"
                    is RelationCallableBody.Anonymous -> "ANONYMOUS:${value.signature.canonicalEncoding().value}"
                },
                target.callableCanonicalProjection(),
            )
            .joinToString("\u0000")

    override fun compareTo(other: RelationCallableObservation): Int =
        canonicalProjection().compareTo(other.canonicalProjection())

    companion object {
        fun fromNativeBoundary(
            request: RelationRequest,
            occurrence: RelationOccurrence,
            lexicalOwner: CompilerGroundedSymbolEvidence,
            body: RelationCallableBody,
            target: RelationCallableTarget,
        ): Refinement<RelationCallableObservation, RelationCallableObservationFailure> {
            when (val admitted = admitCallableFrame(request, occurrence, lexicalOwner, body, target)) {
                is Refinement.Rejected -> return admitted
                is Refinement.Refined -> Unit
            }
            when (val admitted = target.admitCallableTarget(request, occurrence, body)) {
                is Refinement.Rejected -> return admitted
                is Refinement.Refined -> Unit
            }
            return Refinement.Refined(
                RelationCallableObservation(
                    request.subject.fingerprint,
                    request.subject.lease,
                    request.boundary,
                    request.scopeFingerprint,
                    request.meaning,
                    occurrence,
                    lexicalOwner,
                    body,
                    target,
                )
            )
        }
    }
}

private fun RelationCallableTarget.SourceLess.validDisposition(request: RelationRequest): Boolean {
    val libraries =
        (request.searchScope as? io.github.amichne.kast.symbol.contract.SymbolSearchScope.Workspace)?.libraries
            ?: io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy.EXCLUDE
    return when (disposition) {
        SourceLessCallableDisposition.BUILTIN_BOUNDARY -> callable.moduleKind == SourceLessCallableModuleKind.BUILTINS
        SourceLessCallableDisposition.LIBRARY_POLICY_EXCLUDED ->
            callable.moduleKind == SourceLessCallableModuleKind.LIBRARY &&
                libraries == io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy.EXCLUDE
        SourceLessCallableDisposition.LIBRARY_SOURCE_UNAVAILABLE ->
            callable.moduleKind == SourceLessCallableModuleKind.LIBRARY &&
                libraries == io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy.INCLUDE
    }
}

private fun CompleteCallbackSupply.admitsObservation(
    request: RelationRequest,
    occurrence: RelationOccurrence,
    body: RelationCallableBody,
): Boolean {
    val invocation = supplier.binding.invocation
    if (invocation.basis != request.subject.lease.identity || supplier.binding.invocationOwner != body) return false
    return occurrence.file == invocation.enclosing.file && occurrence.range == invocation.range
}

private fun CompleteCallbackDirectInvocations.admitsObservation(
    request: RelationRequest,
    occurrence: RelationOccurrence,
    body: RelationCallableBody,
): Boolean =
    binding.basis == request.subject.lease.identity && binding.occurrence == occurrence && binding.owner == body

private fun RelationCallableTarget.ParameterInvocation.admitsParameter(
    request: RelationRequest,
    occurrence: RelationOccurrence,
    body: RelationCallableBody,
): Boolean {
    if (
        parameter.callable.lease != request.subject.lease ||
            parameter.callable.file != occurrence.file ||
            !parameter.callable.range.containsValueRange(body.range)
    )
        return false
    if (!suppliers.admits(parameter, request.scopeFingerprint)) return false
    if (invocation.occurrence != occurrence || invocation.owner != body || invocation.forwardings.isNotEmpty())
        return false
    return invocation.callableTransfers.all { transfer ->
        listOf(transfer.source, transfer.target).all { site ->
            site.basis == request.subject.lease.identity &&
                site.enclosing.valueIdentity == parameter.callable.valueIdentity
        }
    }
}

private fun RelationCallableTarget.callableCanonicalProjection(): String =
    when (val value = this) {
        is RelationCallableTarget.DirectInvocations -> value.invocations.canonicalProjection()
        is RelationCallableTarget.CallbackSupplies -> value.supplies.canonicalProjection()
        is RelationCallableTarget.UnavailableSupply ->
            "UNAVAILABLE_SUPPLY:${value.evidence.causes.sortedBy { it.ordinal }.joinToString { it.name }}"
        is RelationCallableTarget.UnavailableReference -> "UNAVAILABLE_REFERENCE:${value.cause.name}"
        is RelationCallableTarget.NamedReference -> value.reference.canonicalProjection()
        is RelationCallableTarget.ParameterInvocation ->
            listOf(
                    "PARAMETER",
                    value.parameter.callable.compilerIdentity.value,
                    value.parameter.callable.file.stableValue,
                    value.parameter.callable.range.toString(),
                    value.parameter.callable.name.value,
                    value.parameter.callable.kind.name,
                    value.parameter.callable.signature.canonicalEncoding().value,
                    value.parameter.position.value.toString(),
                    value.parameter.parameter.file.stableValue,
                    value.parameter.parameter.range.toString(),
                    value.suppliers.canonicalProjection(),
                    value.invocation.canonicalProjection(),
                )
                .joinToString("\u0000")
        is RelationCallableTarget.SourceLess ->
            listOf(
                    "SOURCE_LESS",
                    value.callable.compilerIdentity.value,
                    value.callable.kind.name,
                    value.callable.signature.canonicalEncoding().value,
                    value.callable.origin.name,
                    value.callable.moduleKind.name,
                    value.callable.moduleName.value,
                    value.disposition.name,
                )
                .joinToString("\u0000")
    }

private fun admitCallableFrame(
    request: RelationRequest,
    occurrence: RelationOccurrence,
    lexicalOwner: CompilerGroundedSymbolEvidence,
    body: RelationCallableBody,
    target: RelationCallableTarget,
): Refinement<Unit, RelationCallableObservationFailure> =
    when {
        !request.meaning.admitsCallableTarget(target) ->
            Refinement.Rejected(RelationCallableObservationFailure.MEANING_MISMATCH)
        request.meaning == RelationMeaning.Callees && !request.namesLexicalOwner(lexicalOwner) ->
            Refinement.Rejected(RelationCallableObservationFailure.SUBJECT_MISMATCH)
        occurrence.file != body.file || !body.range.containsValueRange(occurrence.range) ->
            Refinement.Rejected(RelationCallableObservationFailure.OCCURRENCE_OUTSIDE_BODY)
        body.file != lexicalOwner.file || !lexicalOwner.range.containsValueRange(body.range) ->
            Refinement.Rejected(RelationCallableObservationFailure.BODY_OUTSIDE_LEXICAL_OWNER)
        else -> Refinement.Refined(Unit)
    }

private fun RelationMeaning.admitsCallableTarget(target: RelationCallableTarget): Boolean {
    if (this == RelationMeaning.Callees) return true
    return this == RelationMeaning.Callers &&
        (target is RelationCallableTarget.NamedReference || target is RelationCallableTarget.UnavailableReference)
}

private fun RelationRequest.namesLexicalOwner(owner: CompilerGroundedSymbolEvidence): Boolean =
    subject.file == owner.file && subject.range == owner.range && subject.compilerIdentity == owner.compilerIdentity

private fun RelationCallableTarget.admitCallableTarget(
    request: RelationRequest,
    occurrence: RelationOccurrence,
    body: RelationCallableBody,
): Refinement<Unit, RelationCallableObservationFailure> {
    val admitted =
        when (this) {
            is RelationCallableTarget.NamedReference -> admitsNamedTarget(request, occurrence)
            is RelationCallableTarget.DirectInvocations -> invocations.admitsObservation(request, occurrence, body)
            is RelationCallableTarget.CallbackSupplies ->
                supplies.values.all { it.admitsObservation(request, occurrence, body) }
            is RelationCallableTarget.ParameterInvocation ->
                return if (admitsParameter(request, occurrence, body)) Refinement.Refined(Unit)
                else Refinement.Rejected(RelationCallableObservationFailure.PARAMETER_AUTHORITY_MISMATCH)
            is RelationCallableTarget.SourceLess ->
                return if (validDisposition(request)) Refinement.Refined(Unit)
                else Refinement.Rejected(RelationCallableObservationFailure.BOUNDARY_POLICY_MISMATCH)
            is RelationCallableTarget.UnavailableSupply,
            is RelationCallableTarget.UnavailableReference -> true
        }
    return if (admitted) Refinement.Refined(Unit)
    else Refinement.Rejected(RelationCallableObservationFailure.SUBJECT_MISMATCH)
}

private fun RelationCallableTarget.NamedReference.admitsNamedTarget(
    request: RelationRequest,
    occurrence: RelationOccurrence,
): Boolean {
    if (reference.occurrence != occurrence || reference.target.lease != request.subject.lease) return false
    return request.meaning != RelationMeaning.Callers ||
        RevalidatedRelationEndpoint.validate(request.subject, reference.target.evidence) is Refinement.Refined
}
