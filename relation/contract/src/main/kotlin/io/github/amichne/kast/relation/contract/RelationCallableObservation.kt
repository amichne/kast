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
        is CanonicalCompilerSignature.TypeAlias,
        is CanonicalCompilerSignature.ClassLike -> false
    }

enum class SourceLessCallableDisposition {
    BUILTIN_BOUNDARY,
    LIBRARY_POLICY_EXCLUDED,
    LIBRARY_SOURCE_UNAVAILABLE,
}

sealed interface RelationCallableTarget {
    data class ParameterInvocation(val parameter: CallbackParameterIdentity) : RelationCallableTarget

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
    val occurrence: RelationOccurrence,
    val lexicalOwner: CompilerGroundedSymbolEvidence,
    val body: RelationCallableBody,
    val target: RelationCallableTarget,
) : Comparable<RelationCallableObservation> {
    val retainedBytes: Long
        get() = 4096L + canonicalProjection().length * 4L

    fun belongsTo(request: RelationRequest): Boolean =
        request.meaning == RelationMeaning.Callees &&
            subject == request.subject.fingerprint &&
            basis == request.subject.lease &&
            requestedDomain == request.boundary &&
            effectiveDomain == request.scopeFingerprint

    fun canonicalProjection(): String =
        listOf(
                subject.value,
                effectiveDomain.value,
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
                when (val value = target) {
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
                },
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
        ): Refinement<RelationCallableObservation, RelationCallableObservationFailure> =
            when {
                request.meaning != RelationMeaning.Callees ->
                    Refinement.Rejected(RelationCallableObservationFailure.MEANING_MISMATCH)
                request.subject.file != lexicalOwner.file ||
                    request.subject.range != lexicalOwner.range ||
                    request.subject.compilerIdentity != lexicalOwner.compilerIdentity ->
                    Refinement.Rejected(RelationCallableObservationFailure.SUBJECT_MISMATCH)
                occurrence.file != body.file || !body.range.containsValueRange(occurrence.range) ->
                    Refinement.Rejected(RelationCallableObservationFailure.OCCURRENCE_OUTSIDE_BODY)
                body.file != lexicalOwner.file || !lexicalOwner.range.containsValueRange(body.range) ->
                    Refinement.Rejected(RelationCallableObservationFailure.BODY_OUTSIDE_LEXICAL_OWNER)
                target is RelationCallableTarget.ParameterInvocation &&
                    target.parameter.callable.lease != request.subject.lease ->
                    Refinement.Rejected(RelationCallableObservationFailure.PARAMETER_AUTHORITY_MISMATCH)
                target is RelationCallableTarget.SourceLess && !target.validDisposition(request) ->
                    Refinement.Rejected(RelationCallableObservationFailure.BOUNDARY_POLICY_MISMATCH)
                else ->
                    Refinement.Refined(
                        RelationCallableObservation(
                            request.subject.fingerprint,
                            request.subject.lease,
                            request.boundary,
                            request.scopeFingerprint,
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
