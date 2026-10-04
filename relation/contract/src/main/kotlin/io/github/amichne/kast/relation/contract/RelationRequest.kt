package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.fingerprintFields
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity
import java.nio.charset.StandardCharsets
import java.util.Collections

internal const val RELATION_CONTINUATION_FINGERPRINT_LENGTH = 64

/** One closed semantic hop; no direction flag can be combined with an arbitrary kind. */
sealed interface RelationMeaning {
    data object References : RelationMeaning

    data object Callers : RelationMeaning

    data object Callees : RelationMeaning

    data object Implementations : RelationMeaning

    data object Inheritors : RelationMeaning

    data object Overrides : RelationMeaning

    data object TypeUses : RelationMeaning

    companion object {
        val all: List<RelationMeaning> =
            listOf(
                References,
                Callers,
                Callees,
                Implementations,
                Inheritors,
                Overrides,
                TypeUses,
            )
    }
}

enum class RelationByteLimitFailure {
    NOT_POSITIVE
}

@JvmInline
value class RelationByteLimit private constructor(val value: Long) {
    companion object {
        /**
         * Proof transition: `Long -> Refinement<RelationByteLimit, RelationByteLimitFailure>`.
         *
         * Establishes a finite positive bound for detached relation bytes. [RelationByteLimitFailure] is the closed
         * expected failure. Raw byte limits may be extracted only by request admission, a bounded compiler collector,
         * or transport.
         */
        fun parse(raw: Long): Refinement<RelationByteLimit, RelationByteLimitFailure> =
            if (raw > 0L) {
                Refinement.Refined(RelationByteLimit(raw))
            } else {
                Refinement.Rejected(RelationByteLimitFailure.NOT_POSITIVE)
            }
    }
}

data class RelationBudget(
    val resources: ResourceBudget,
    val returnedBytes: RelationByteLimit,
)

enum class RelationScopeFingerprintFailure {
    INVALID_SHA256
}

@JvmInline
value class RelationScopeFingerprint private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<RelationScopeFingerprint, RelationScopeFingerprintFailure> =
            if (raw.isCanonicalSha256()) {
                Refinement.Refined(RelationScopeFingerprint(raw))
            } else {
                Refinement.Rejected(RelationScopeFingerprintFailure.INVALID_SHA256)
            }

        fun from(
            subject: RelationEndpoint,
            boundary: RelationSearchBoundary = RelationSearchBoundary.RETAINED_SUBJECT,
        ): RelationScopeFingerprint =
            RelationScopeFingerprint(
                (subject.selectorScopeCanonical() + "\u0000" + boundary.canonical(subject))
                    .toByteArray(StandardCharsets.UTF_8)
                    .sha256()
            )
    }
}

enum class RelationContinuationFingerprintFailure {
    INVALID_SHA256
}

@JvmInline
value class RelationContinuationFingerprint private constructor(val value: String) {
    init {
        require(
            value.length == RELATION_CONTINUATION_FINGERPRINT_LENGTH &&
                value.all { character -> character in '0'..'9' || character in 'a'..'f' }
        )
    }

    companion object {
        fun parse(raw: String): Refinement<RelationContinuationFingerprint, RelationContinuationFingerprintFailure> =
            if (raw.isCanonicalSha256()) {
                Refinement.Refined(RelationContinuationFingerprint(raw))
            } else {
                Refinement.Rejected(RelationContinuationFingerprintFailure.INVALID_SHA256)
            }

        internal fun digest(canonical: String): RelationContinuationFingerprint =
            RelationContinuationFingerprint(canonical.toByteArray(StandardCharsets.UTF_8).sha256())
    }
}

enum class RelationContinuationRestorationFailure {
    INTEGRITY_MISMATCH
}

/** Resume authority bound to one exact subject, scope, meaning, authority, and retained provider inventory. */
class RelationContinuation
private constructor(
    val subject: RelationEndpointFingerprint,
    val meaning: RelationMeaning,
    val scope: RelationScopeFingerprint,
    val authority: SemanticReadIdentity,
    val nextProviderCursor: RelationProviderCursor,
    val fingerprint: RelationContinuationFingerprint,
    val retainedLimitations: Set<RelationLimitation>,
    val providerState: RelationProviderState,
) {
    companion object {
        /**
         * Proof transition: `(RelationRequest, RelationProviderCursor) -> RelationContinuation`.
         *
         * Establishes an opaque continuation bound to the request's exact subject, closed meaning, and authority at the
         * next native work position. Raw offset extraction is permitted only inside a bounded relation compiler or
         * continuation transport codec.
         */
        fun issue(
            request: RelationRequest,
            nextProviderCursor: RelationProviderCursor,
            limitations: Set<RelationLimitation> = emptySet(),
            providerState: RelationProviderState,
        ): RelationContinuation {
            val retained =
                Collections.unmodifiableSet(
                    LinkedHashSet((request.retainedLimitations + limitations).filterNot { it in relationPageLimits })
                )
            check(request.admitsProvider(nextProviderCursor.provider))
            check(providerState.provider == nextProviderCursor.provider)
            check(providerState.providerCursor == nextProviderCursor)
            check(providerState.confirmAuthority(request.subject.lease.identity) is Refinement.Refined)
            val scope = request.scopeFingerprint
            return RelationContinuation(
                subject = request.subject.fingerprint,
                meaning = request.meaning,
                scope = scope,
                authority = request.subject.lease.identity,
                nextProviderCursor = nextProviderCursor,
                retainedLimitations = retained,
                providerState = providerState,
                fingerprint =
                    relationContinuationFingerprint(
                        request.subject.fingerprint,
                        request.meaning,
                        scope,
                        request.subject.lease.identity,
                        nextProviderCursor,
                        retained,
                        providerState,
                    ),
            )
        }

        /** Restores detached continuation fields only when their domain fingerprint is exact. */
        fun restore(
            subject: RelationEndpointFingerprint,
            meaning: RelationMeaning,
            scope: RelationScopeFingerprint,
            authority: SemanticReadIdentity,
            nextProviderCursor: RelationProviderCursor,
            fingerprint: RelationContinuationFingerprint,
            retainedLimitations: Set<RelationLimitation> = emptySet(),
            providerState: RelationProviderState,
        ): Refinement<RelationContinuation, RelationContinuationRestorationFailure> {
            if (providerState.confirmAuthority(authority) is Refinement.Rejected) {
                return Refinement.Rejected(RelationContinuationRestorationFailure.INTEGRITY_MISMATCH)
            }
            return if (
                providerState.providerCursor == nextProviderCursor &&
                    nextProviderCursor.provider.supports(meaning, authority) &&
                    fingerprint ==
                        relationContinuationFingerprint(
                            subject,
                            meaning,
                            scope,
                            authority,
                            nextProviderCursor,
                            retainedLimitations,
                            providerState,
                        )
            ) {
                Refinement.Refined(
                    RelationContinuation(
                        subject,
                        meaning,
                        scope,
                        authority,
                        nextProviderCursor,
                        fingerprint,
                        Collections.unmodifiableSet(LinkedHashSet(retainedLimitations)),
                        providerState,
                    )
                )
            } else {
                Refinement.Rejected(RelationContinuationRestorationFailure.INTEGRITY_MISMATCH)
            }
        }
    }
}

sealed interface RelationReadPosition {
    data object Start : RelationReadPosition

    class Resume internal constructor(val continuation: RelationContinuation) : RelationReadPosition
}

enum class RelationResumeFailure {
    SUBJECT_MISMATCH,
    MEANING_MISMATCH,
    SCOPE_MISMATCH,
    GENERATION_MISMATCH,
    PROVIDER_MISMATCH,
}

/** Seed selection, expansion domain, and output predicates have separate owners. */
sealed interface RelationSearchBoundary {
    data object RETAINED_SUBJECT : RelationSearchBoundary

    data object WORKSPACE_EXPANSION : RelationSearchBoundary

    /** Only native source ownership restrictions may constrain expansion; no presentation predicate can enter. */
    data class Explicit(
        val scope: SymbolSearchScope,
        val directory: io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint? = null,
        val sourceSets: io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets =
            io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets.All,
    ) : RelationSearchBoundary

    fun effectiveScope(subject: RelationEndpoint): SymbolSearchScope =
        when (this) {
            RETAINED_SUBJECT -> subject.scope
            WORKSPACE_EXPANSION ->
                SymbolSearchScope.Workspace(
                    subject.scope.sourceKinds,
                    subject.scope.generatedSources,
                    (subject.scope as? SymbolSearchScope.Workspace)?.libraries ?: SymbolLibraryPolicy.EXCLUDE,
                )
            is Explicit -> scope
        }

    fun effectiveConstraints(subject: RelationEndpoint): SymbolDiscoveryConstraints =
        when (this) {
            RETAINED_SUBJECT -> subject.constraints
            WORKSPACE_EXPANSION -> SymbolDiscoveryConstraints.None
            is Explicit -> SymbolDiscoveryConstraints(directory, null, sourceSets = sourceSets)
        }

    fun canonical(subject: RelationEndpoint): String = buildString {
        appendContinuationField(
            when (this@RelationSearchBoundary) {
                RETAINED_SUBJECT -> "RETAINED_SUBJECT"
                WORKSPACE_EXPANSION -> "WORKSPACE_EXPANSION"
                is Explicit -> "EXPLICIT"
            }
        )
        appendContinuationField(scopeCanonical(effectiveScope(subject), effectiveConstraints(subject)))
    }
}

/** Exact one-hop request; construction admits either the first page or a bound continuation. */
class RelationRequest
private constructor(
    val subject: RelationEndpoint,
    val meaning: RelationMeaning,
    val budget: RelationBudget,
    val position: RelationReadPosition,
    val boundary: RelationSearchBoundary,
) {
    val scopeFingerprint: RelationScopeFingerprint = RelationScopeFingerprint.from(subject, boundary)
    val searchScope: SymbolSearchScope = boundary.effectiveScope(subject)
    val searchConstraints: SymbolDiscoveryConstraints = boundary.effectiveConstraints(subject)

    fun admitsEndpoint(endpoint: RelationEndpoint): Boolean =
        endpoint === subject ||
            (endpoint.lease == subject.lease &&
                endpoint.scope == searchScope &&
                endpoint.constraints == searchConstraints)

    val providerCursor: RelationProviderCursor =
        when (position) {
            RelationReadPosition.Start -> RelationProviderCursor.start(RelationProviderKind.forMeaning(meaning))
            is RelationReadPosition.Resume -> position.continuation.nextProviderCursor
        }

    /** The compiler establishes the provider on Start; every resumed read retains that exact provider. */
    fun admitsProvider(provider: RelationProviderKind): Boolean =
        provider.supports(meaning, subject.lease.identity) &&
            when (position) {
                RelationReadPosition.Start -> true
                is RelationReadPosition.Resume -> provider == position.continuation.nextProviderCursor.provider
            }

    companion object {
        /**
         * Proof transition: `(SymbolSelector, RelationMeaning, RelationBudget) -> RelationRequest`.
         *
         * Establishes the initial page of exactly one closed semantic relation from one exact compiler-grounded
         * selector, retained as the request's subject endpoint. Primitive symbol identity cannot enter this boundary.
         */
        fun start(
            selector: SymbolSelector,
            meaning: RelationMeaning,
            budget: RelationBudget,
            boundary: RelationSearchBoundary = RelationSearchBoundary.RETAINED_SUBJECT,
        ): RelationRequest =
            RelationRequest(
                RelationEndpoint.subject(selector),
                meaning,
                budget,
                RelationReadPosition.Start,
                boundary,
            )

        /**
         * Proof transition: `(RelationEndpoint, RelationMeaning, RelationBudget) -> RelationRequest`.
         *
         * Establishes the initial page of the next closed semantic hop from an already exact, compiler-grounded subject
         * or related endpoint. The endpoint's root, authority, scope, declaration, and compiler identity remain sealed;
         * primitive reconstruction is not permitted.
         */
        fun start(
            subject: RelationEndpoint,
            meaning: RelationMeaning,
            budget: RelationBudget,
            boundary: RelationSearchBoundary = RelationSearchBoundary.RETAINED_SUBJECT,
        ): RelationRequest =
            RelationRequest(
                subject,
                meaning,
                budget,
                RelationReadPosition.Start,
                boundary,
            )

        /**
         * Proof transition: `(SymbolSelector, RelationMeaning, RelationBudget, RelationContinuation) ->
         * Refinement<RelationRequest, RelationResumeFailure>`.
         *
         * Establishes that continuation authority belongs to the exact selector subject, meaning, and authority of this
         * one-hop read. [RelationResumeFailure] is the closed expected failure. Raw continuation decoding may occur
         * only before this admission boundary.
         */
        fun resume(
            selector: SymbolSelector,
            meaning: RelationMeaning,
            budget: RelationBudget,
            continuation: RelationContinuation,
            boundary: RelationSearchBoundary = RelationSearchBoundary.RETAINED_SUBJECT,
        ): Refinement<RelationRequest, RelationResumeFailure> =
            admitResume(
                RelationEndpoint.subject(selector),
                meaning,
                budget,
                continuation,
                boundary,
            )

        /**
         * Proof transition: `(RelationEndpoint.Resolved, RelationMeaning, RelationBudget, RelationContinuation) ->
         * Refinement<RelationRequest, RelationResumeFailure>`.
         *
         * Establishes that continuation authority belongs to the same exact resolved endpoint, meaning, and authority.
         * [RelationResumeFailure] is the closed expected failure. Raw continuation decoding may occur only before this
         * admission boundary.
         */
        fun resume(
            subject: RelationEndpoint.Resolved,
            meaning: RelationMeaning,
            budget: RelationBudget,
            continuation: RelationContinuation,
            boundary: RelationSearchBoundary = RelationSearchBoundary.RETAINED_SUBJECT,
        ): Refinement<RelationRequest, RelationResumeFailure> =
            admitResume(
                subject,
                meaning,
                budget,
                continuation,
                boundary,
            )

        /**
         * Proof transition: `(RelationEndpoint, RelationMeaning, RelationBudget, RelationContinuation) ->
         * Refinement<RelationRequest, RelationResumeFailure>`.
         *
         * Establishes exact subject, meaning, and authority ownership for resumed one-hop work. [RelationResumeFailure]
         * is the closed expected failure. Raw continuation extraction is permitted only at the outer public
         * start/resume or transport boundary.
         */
        private fun admitResume(
            subject: RelationEndpoint,
            meaning: RelationMeaning,
            budget: RelationBudget,
            continuation: RelationContinuation,
            boundary: RelationSearchBoundary = RelationSearchBoundary.RETAINED_SUBJECT,
        ): Refinement<RelationRequest, RelationResumeFailure> =
            when {
                continuation.scope != RelationScopeFingerprint.from(subject, boundary) ->
                    Refinement.Rejected(RelationResumeFailure.SCOPE_MISMATCH)
                continuation.meaning != meaning -> Refinement.Rejected(RelationResumeFailure.MEANING_MISMATCH)
                continuation.authority != subject.lease.identity ->
                    Refinement.Rejected(RelationResumeFailure.GENERATION_MISMATCH)
                continuation.subject != subject.fingerprint ->
                    Refinement.Rejected(RelationResumeFailure.SUBJECT_MISMATCH)
                !continuation.nextProviderCursor.provider.supports(meaning, subject.lease.identity) ->
                    Refinement.Rejected(RelationResumeFailure.PROVIDER_MISMATCH)
                else ->
                    Refinement.Refined(
                        RelationRequest(
                            subject,
                            meaning,
                            budget,
                            RelationReadPosition.Resume(continuation),
                            boundary,
                        )
                    )
            }
    }
}

private fun RelationMeaning.canonicalName(): String =
    when (this) {
        RelationMeaning.References -> "references"
        RelationMeaning.Callers -> "callers"
        RelationMeaning.Callees -> "callees"
        RelationMeaning.Implementations -> "implementations"
        RelationMeaning.Inheritors -> "inheritors"
        RelationMeaning.Overrides -> "overrides"
        RelationMeaning.TypeUses -> "type-uses"
    }

private fun relationContinuationFingerprint(
    subject: RelationEndpointFingerprint,
    meaning: RelationMeaning,
    scope: RelationScopeFingerprint,
    authority: SemanticReadIdentity,
    cursor: RelationProviderCursor,
    retainedLimitations: Set<RelationLimitation>,
    providerState: RelationProviderState,
): RelationContinuationFingerprint {
    val canonical = buildString {
        appendContinuationField(subject.value)
        appendContinuationField(meaning.canonicalName())
        appendContinuationField(scope.value)
        appendContinuationField(authority.revisionKey.value)
        appendContinuationField(cursor.provider.name)
        appendContinuationField(cursor.nextPosition.value.toString())
        appendContinuationField(cursor.consumedPrefixDigest.value)
        retainedLimitations.sortedBy { it.ordinal }.forEach { appendContinuationField(it.name) }
        appendContinuationField(providerState.canonicalProjection())
    }
    return RelationContinuationFingerprint.digest(canonical)
}

private fun RelationEndpoint.selectorScopeCanonical(): String = scopeCanonical(scope, constraints)

private fun scopeCanonical(scope: SymbolSearchScope, constraints: SymbolDiscoveryConstraints): String {
    val snapshot = io.github.amichne.kast.symbol.contract.SymbolSearchScope.snapshot(scope)
    return buildString {
        appendContinuationField(snapshot.kind.name)
        appendContinuationField(snapshot.primary ?: "")
        appendContinuationField(snapshot.secondary ?: "")
        appendContinuationField(snapshot.sourceKinds.name)
        appendContinuationField(snapshot.generatedSources.name)
        appendContinuationField(snapshot.libraries?.name ?: "")
        constraints.fingerprintFields().forEach(::appendContinuationField)
    }
}
