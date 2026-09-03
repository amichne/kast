package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.symbol.contract.SymbolSelector
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private const val RELATION_CONTINUATION_FINGERPRINT_LENGTH = 64

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
        val all: List<RelationMeaning> = listOf(
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
    NOT_POSITIVE,
}

@JvmInline
value class RelationByteLimit private constructor(
    val value: Long,
) {
    companion object {
        /**
         * Proof transition: `Long -> Refinement<RelationByteLimit,
         * RelationByteLimitFailure>`.
         *
         * Establishes a finite positive bound for detached relation bytes.
         * [RelationByteLimitFailure] is the closed expected failure. Raw byte limits may be
         * extracted only by request admission, a bounded compiler collector, or transport.
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

enum class RelationProviderPositionFailure {
    NEGATIVE,
}

@JvmInline
value class RelationProviderPosition private constructor(
    val value: Long,
) {
    companion object {
        val Zero: RelationProviderPosition = RelationProviderPosition(0L)

        /**
         * Proof transition: `Long -> Refinement<RelationProviderPosition,
         * RelationProviderPositionFailure>`.
         *
         * Establishes a non-negative native enumeration position.
         * [RelationProviderPositionFailure] is the closed expected failure. Raw positions may be
         * extracted only by the relation compiler collector and continuation codec.
         */
        fun parse(
            raw: Long,
        ): Refinement<RelationProviderPosition, RelationProviderPositionFailure> =
            if (raw >= 0L) {
                Refinement.Refined(RelationProviderPosition(raw))
            } else {
                Refinement.Rejected(RelationProviderPositionFailure.NEGATIVE)
            }
    }
}

/** Stable native enumeration family. An implementation-order change requires a new member. */
enum class RelationProviderKind {
    INTELLIJ_REFERENCES_V1,
    INTELLIJ_DEFINITIONS_V1,
    INTELLIJ_CALLEES_V1,
    ;

    companion object {
        fun forMeaning(meaning: RelationMeaning): RelationProviderKind = when (meaning) {
            RelationMeaning.References,
            RelationMeaning.Callers,
            RelationMeaning.TypeUses,
                -> INTELLIJ_REFERENCES_V1
            RelationMeaning.Implementations,
            RelationMeaning.Inheritors,
            RelationMeaning.Overrides,
                -> INTELLIJ_DEFINITIONS_V1
            RelationMeaning.Callees -> INTELLIJ_CALLEES_V1
        }
    }
}

enum class RelationProviderItemDescriptorFailure {
    BLANK,
}

/** Stable detached identity of one item in native provider order. */
@JvmInline
value class RelationProviderItemDescriptor private constructor(val value: String) {
    companion object {
        fun parse(
            raw: String,
        ): Refinement<RelationProviderItemDescriptor, RelationProviderItemDescriptorFailure> =
            if (raw.isBlank()) {
                Refinement.Rejected(RelationProviderItemDescriptorFailure.BLANK)
            } else {
                Refinement.Refined(RelationProviderItemDescriptor(raw))
            }
    }
}

enum class RelationProviderPrefixDigestFailure {
    INVALID_SHA256,
}

@JvmInline
value class RelationProviderPrefixDigest private constructor(val value: String) {
    companion object {
        fun parse(
            raw: String,
        ): Refinement<RelationProviderPrefixDigest, RelationProviderPrefixDigestFailure> =
            if (raw.isCanonicalSha256()) {
                Refinement.Refined(RelationProviderPrefixDigest(raw))
            } else {
                Refinement.Rejected(RelationProviderPrefixDigestFailure.INVALID_SHA256)
            }

        internal fun digest(bytes: ByteArray): RelationProviderPrefixDigest =
            RelationProviderPrefixDigest(bytes.sha256())
    }
}

/** Native resume position bound to the exact ordered prefix already consumed. */
data class RelationProviderCursor private constructor(
    val provider: RelationProviderKind,
    val nextPosition: RelationProviderPosition,
    val consumedPrefixDigest: RelationProviderPrefixDigest,
) {
    fun advance(item: RelationProviderItemDescriptor): RelationProviderCursor {
        check(nextPosition.value < Long.MAX_VALUE) { "Relation provider position overflow" }
        val canonical = buildString {
            appendContinuationField(consumedPrefixDigest.value)
            appendContinuationField(item.value)
        }
        return RelationProviderCursor(
            provider,
            RelationProviderPosition.parse(nextPosition.value + 1L).refinedInvariant(),
            RelationProviderPrefixDigest.digest(canonical.toByteArray(StandardCharsets.UTF_8)),
        )
    }

    companion object {
        fun start(provider: RelationProviderKind): RelationProviderCursor {
            val canonical = "kast-relation-provider-prefix-v1:${provider.name}"
            return RelationProviderCursor(
                provider,
                RelationProviderPosition.Zero,
                RelationProviderPrefixDigest.digest(canonical.toByteArray(StandardCharsets.UTF_8)),
            )
        }

        fun restore(
            provider: RelationProviderKind,
            nextPosition: RelationProviderPosition,
            consumedPrefixDigest: RelationProviderPrefixDigest,
        ): RelationProviderCursor = RelationProviderCursor(
            provider,
            nextPosition,
            consumedPrefixDigest,
        )
    }
}

enum class RelationScopeFingerprintFailure {
    INVALID_SHA256,
}

@JvmInline
value class RelationScopeFingerprint private constructor(val value: String) {
    companion object {
        fun parse(
            raw: String,
        ): Refinement<RelationScopeFingerprint, RelationScopeFingerprintFailure> =
            if (raw.isCanonicalSha256()) {
                Refinement.Refined(RelationScopeFingerprint(raw))
            } else {
                Refinement.Rejected(RelationScopeFingerprintFailure.INVALID_SHA256)
            }

        fun from(subject: RelationEndpoint): RelationScopeFingerprint =
            RelationScopeFingerprint(
                subject.selectorScopeCanonical().toByteArray(StandardCharsets.UTF_8).sha256(),
            )
    }
}

enum class RelationContinuationFingerprintFailure {
    INVALID_SHA256,
}

@JvmInline
value class RelationContinuationFingerprint private constructor(
    val value: String,
) {
    init {
        require(
            value.length == RELATION_CONTINUATION_FINGERPRINT_LENGTH &&
            value.all { character -> character in '0'..'9' || character in 'a'..'f' },
        )
    }

    companion object {
        fun parse(
            raw: String,
        ): Refinement<RelationContinuationFingerprint, RelationContinuationFingerprintFailure> =
            if (raw.isCanonicalSha256()) {
                Refinement.Refined(RelationContinuationFingerprint(raw))
            } else {
                Refinement.Rejected(RelationContinuationFingerprintFailure.INVALID_SHA256)
            }

        internal fun digest(canonical: String): RelationContinuationFingerprint =
            RelationContinuationFingerprint(
                canonical.toByteArray(StandardCharsets.UTF_8).sha256(),
            )
    }
}

enum class RelationContinuationRestorationFailure {
    INTEGRITY_MISMATCH,
}

/** Resume authority bound to one exact subject, scope, meaning, generation, and provider prefix. */
class RelationContinuation private constructor(
    val subject: RelationEndpointFingerprint,
    val meaning: RelationMeaning,
    val scope: RelationScopeFingerprint,
    val generation: EvidenceGeneration,
    val nextProviderCursor: RelationProviderCursor,
    val fingerprint: RelationContinuationFingerprint,
) {
    companion object {
        /**
         * Proof transition: `(RelationRequest, RelationProviderCursor) -> RelationContinuation`.
         *
         * Establishes an opaque continuation bound to the request's exact subject, closed
         * meaning, and generation at the next native work position. Raw offset extraction is
         * permitted only inside a bounded relation compiler or continuation transport codec.
         */
        fun issue(
            request: RelationRequest,
            nextProviderCursor: RelationProviderCursor,
        ): RelationContinuation {
            check(nextProviderCursor.provider == RelationProviderKind.forMeaning(request.meaning))
            val scope = RelationScopeFingerprint.from(request.subject)
            return RelationContinuation(
                subject = request.subject.fingerprint,
                meaning = request.meaning,
                scope = scope,
                generation = request.subject.lease.generation,
                nextProviderCursor = nextProviderCursor,
                fingerprint = relationContinuationFingerprint(
                    request.subject.fingerprint,
                    request.meaning,
                    scope,
                    request.subject.lease.generation,
                    nextProviderCursor,
                ),
            )
        }

        /** Restores detached continuation fields only when their domain fingerprint is exact. */
        fun restore(
            subject: RelationEndpointFingerprint,
            meaning: RelationMeaning,
            scope: RelationScopeFingerprint,
            generation: EvidenceGeneration,
            nextProviderCursor: RelationProviderCursor,
            fingerprint: RelationContinuationFingerprint,
        ): Refinement<RelationContinuation, RelationContinuationRestorationFailure> =
            if (
                fingerprint == relationContinuationFingerprint(
                    subject,
                    meaning,
                    scope,
                    generation,
                    nextProviderCursor,
                )
            ) {
                Refinement.Refined(
                    RelationContinuation(
                        subject,
                        meaning,
                        scope,
                        generation,
                        nextProviderCursor,
                        fingerprint,
                    ),
                )
            } else {
                Refinement.Rejected(RelationContinuationRestorationFailure.INTEGRITY_MISMATCH)
            }
    }
}

sealed interface RelationReadPosition {
    data object Start : RelationReadPosition

    class Resume internal constructor(
        val continuation: RelationContinuation,
    ) : RelationReadPosition
}

enum class RelationResumeFailure {
    SUBJECT_MISMATCH,
    MEANING_MISMATCH,
    SCOPE_MISMATCH,
    GENERATION_MISMATCH,
    PROVIDER_MISMATCH,
}

/** Exact one-hop request; construction admits either the first page or a bound continuation. */
class RelationRequest private constructor(
    val subject: RelationEndpoint,
    val meaning: RelationMeaning,
    val budget: RelationBudget,
    val position: RelationReadPosition,
) {
    val scopeFingerprint: RelationScopeFingerprint = RelationScopeFingerprint.from(subject)

    val providerCursor: RelationProviderCursor = when (position) {
        RelationReadPosition.Start -> RelationProviderCursor.start(
            RelationProviderKind.forMeaning(meaning),
        )
        is RelationReadPosition.Resume -> position.continuation.nextProviderCursor
    }

    companion object {
        /**
         * Proof transition: `(SymbolSelector, RelationMeaning, RelationBudget) -> RelationRequest`.
         *
         * Establishes the initial page of exactly one closed semantic relation from one exact
         * compiler-grounded selector, retained as the request's subject endpoint. Primitive symbol
         * identity cannot enter this boundary.
         */
        fun start(
            selector: SymbolSelector,
            meaning: RelationMeaning,
            budget: RelationBudget,
        ): RelationRequest = RelationRequest(
            RelationEndpoint.subject(selector),
            meaning,
            budget,
            RelationReadPosition.Start,
        )

        /**
         * Proof transition: `(RelationEndpoint.Resolved, RelationMeaning, RelationBudget) ->
         * RelationRequest`.
         *
         * Establishes the initial page of the next closed semantic hop from an already exact,
         * compiler-grounded related endpoint. The endpoint's root, generation, scope, declaration,
         * and compiler identity remain sealed; primitive reconstruction is not permitted.
         */
        fun start(
            subject: RelationEndpoint.Resolved,
            meaning: RelationMeaning,
            budget: RelationBudget,
        ): RelationRequest = RelationRequest(
            subject,
            meaning,
            budget,
            RelationReadPosition.Start,
        )

        /**
         * Proof transition: `(SymbolSelector, RelationMeaning, RelationBudget,
         * RelationContinuation) -> Refinement<RelationRequest, RelationResumeFailure>`.
         *
         * Establishes that continuation authority belongs to the exact selector subject, meaning,
         * and generation of this one-hop read. [RelationResumeFailure] is the closed expected
         * failure. Raw continuation decoding may occur only before this admission boundary.
         */
        fun resume(
            selector: SymbolSelector,
            meaning: RelationMeaning,
            budget: RelationBudget,
            continuation: RelationContinuation,
        ): Refinement<RelationRequest, RelationResumeFailure> = admitResume(
            RelationEndpoint.subject(selector),
            meaning,
            budget,
            continuation,
        )

        /**
         * Proof transition: `(RelationEndpoint.Resolved, RelationMeaning, RelationBudget,
         * RelationContinuation) -> Refinement<RelationRequest, RelationResumeFailure>`.
         *
         * Establishes that continuation authority belongs to the same exact resolved endpoint,
         * meaning, and generation. [RelationResumeFailure] is the closed expected failure. Raw
         * continuation decoding may occur only before this admission boundary.
         */
        fun resume(
            subject: RelationEndpoint.Resolved,
            meaning: RelationMeaning,
            budget: RelationBudget,
            continuation: RelationContinuation,
        ): Refinement<RelationRequest, RelationResumeFailure> = admitResume(
            subject,
            meaning,
            budget,
            continuation,
        )

        /**
         * Proof transition: `(RelationEndpoint, RelationMeaning, RelationBudget,
         * RelationContinuation) -> Refinement<RelationRequest, RelationResumeFailure>`.
         *
         * Establishes exact subject, meaning, and generation ownership for resumed one-hop work.
         * [RelationResumeFailure] is the closed expected failure. Raw continuation extraction is
         * permitted only at the outer public start/resume or transport boundary.
         */
        private fun admitResume(
            subject: RelationEndpoint,
            meaning: RelationMeaning,
            budget: RelationBudget,
            continuation: RelationContinuation,
        ): Refinement<RelationRequest, RelationResumeFailure> = when {
            continuation.scope != RelationScopeFingerprint.from(subject) ->
                Refinement.Rejected(RelationResumeFailure.SCOPE_MISMATCH)
            continuation.meaning != meaning ->
                Refinement.Rejected(RelationResumeFailure.MEANING_MISMATCH)
            continuation.generation != subject.lease.generation ->
                Refinement.Rejected(RelationResumeFailure.GENERATION_MISMATCH)
            continuation.subject != subject.fingerprint ->
                Refinement.Rejected(RelationResumeFailure.SUBJECT_MISMATCH)
            continuation.nextProviderCursor.provider != RelationProviderKind.forMeaning(meaning) ->
                Refinement.Rejected(RelationResumeFailure.PROVIDER_MISMATCH)
            else -> Refinement.Refined(
                RelationRequest(
                    subject,
                    meaning,
                    budget,
                    RelationReadPosition.Resume(continuation),
                ),
            )
        }
    }
}

private fun RelationMeaning.canonicalName(): String = when (this) {
    RelationMeaning.References -> "references"
    RelationMeaning.Callers -> "callers"
    RelationMeaning.Callees -> "callees"
    RelationMeaning.Implementations -> "implementations"
    RelationMeaning.Inheritors -> "inheritors"
    RelationMeaning.Overrides -> "overrides"
    RelationMeaning.TypeUses -> "type-uses"
}

private fun StringBuilder.appendContinuationField(value: String) {
    append(value.toByteArray(StandardCharsets.UTF_8).size)
    append(':')
    append(value)
}

private fun relationContinuationFingerprint(
    subject: RelationEndpointFingerprint,
    meaning: RelationMeaning,
    scope: RelationScopeFingerprint,
    generation: EvidenceGeneration,
    cursor: RelationProviderCursor,
): RelationContinuationFingerprint {
    val canonical = buildString {
        appendContinuationField(subject.value)
        appendContinuationField(meaning.canonicalName())
        appendContinuationField(scope.value)
        appendContinuationField(generation.value.toString())
        appendContinuationField(cursor.provider.name)
        appendContinuationField(cursor.nextPosition.value.toString())
        appendContinuationField(cursor.consumedPrefixDigest.value)
    }
    return RelationContinuationFingerprint.digest(canonical)
}

private fun RelationEndpoint.selectorScopeCanonical(): String {
    val snapshot = io.github.amichne.kast.symbol.contract.SymbolSearchScope.snapshot(scope)
    return buildString {
        appendContinuationField(snapshot.kind.name)
        appendContinuationField(snapshot.primary ?: "")
        appendContinuationField(snapshot.secondary ?: "")
        appendContinuationField(snapshot.sourceKinds.name)
        appendContinuationField(snapshot.generatedSources.name)
        appendContinuationField(snapshot.libraries?.name ?: "")
    }
}

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

private fun String.isCanonicalSha256(): Boolean =
    length == RELATION_CONTINUATION_FINGERPRINT_LENGTH &&
        all { character -> character in '0'..'9' || character in 'a'..'f' }

private fun <Value, Failure> Refinement<Value, Failure>.refinedInvariant(): Value = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> error("Internally derived relation value violated its invariant")
}
