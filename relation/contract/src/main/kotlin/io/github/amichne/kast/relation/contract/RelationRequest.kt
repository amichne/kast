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

enum class RelationWorkOffsetFailure {
    NEGATIVE,
}

@JvmInline
value class RelationWorkOffset private constructor(
    val value: Long,
) {
    companion object {
        val Zero: RelationWorkOffset = RelationWorkOffset(0L)

        /**
         * Proof transition: `Long -> Refinement<RelationWorkOffset,
         * RelationWorkOffsetFailure>`.
         *
         * Establishes a non-negative native enumeration position.
         * [RelationWorkOffsetFailure] is the closed expected failure. Raw offsets may be
         * extracted only by the relation compiler collector and continuation codec.
         */
        fun parse(raw: Long): Refinement<RelationWorkOffset, RelationWorkOffsetFailure> =
            if (raw >= 0L) {
                Refinement.Refined(RelationWorkOffset(raw))
            } else {
                Refinement.Rejected(RelationWorkOffsetFailure.NEGATIVE)
            }
    }
}

@JvmInline
value class RelationContinuationFingerprint internal constructor(
    val value: String,
) {
    init {
        require(
            value.length == RELATION_CONTINUATION_FINGERPRINT_LENGTH &&
                value.all { character -> character in '0'..'9' || character in 'a'..'f' },
        )
    }
}

/** Opaque resume authority bound to one exact subject, meaning, generation, and work position. */
class RelationContinuation private constructor(
    val subject: RelationEndpointFingerprint,
    val meaning: RelationMeaning,
    val generation: EvidenceGeneration,
    val nextWorkOffset: RelationWorkOffset,
    val fingerprint: RelationContinuationFingerprint,
) {
    companion object {
        /**
         * Proof transition: `(RelationRequest, RelationWorkOffset) -> RelationContinuation`.
         *
         * Establishes an opaque continuation bound to the request's exact subject, closed
         * meaning, and generation at the next native work position. Raw offset extraction is
         * permitted only inside a bounded relation compiler or continuation transport codec.
         */
        fun issue(
            request: RelationRequest,
            nextWorkOffset: RelationWorkOffset,
        ): RelationContinuation {
            val canonical = buildString {
                appendContinuationField(request.subject.fingerprint.value)
                appendContinuationField(request.meaning.canonicalName())
                appendContinuationField(request.subject.lease.generation.value.toString())
                appendContinuationField(nextWorkOffset.value.toString())
            }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            return RelationContinuation(
                subject = request.subject.fingerprint,
                meaning = request.meaning,
                generation = request.subject.lease.generation,
                nextWorkOffset = nextWorkOffset,
                fingerprint = RelationContinuationFingerprint(
                    digest.joinToString(separator = "") { byte ->
                        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                    },
                ),
            )
        }
    }
}

sealed interface RelationReadPosition {
    val workOffset: RelationWorkOffset

    data object Start : RelationReadPosition {
        override val workOffset: RelationWorkOffset = RelationWorkOffset.Zero
    }

    class Resume internal constructor(
        val continuation: RelationContinuation,
    ) : RelationReadPosition {
        override val workOffset: RelationWorkOffset = continuation.nextWorkOffset
    }
}

enum class RelationResumeFailure {
    SUBJECT_MISMATCH,
    MEANING_MISMATCH,
    GENERATION_MISMATCH,
}

/** Exact one-hop request; construction admits either the first page or a bound continuation. */
class RelationRequest private constructor(
    val subject: RelationEndpoint,
    val meaning: RelationMeaning,
    val budget: RelationBudget,
    val position: RelationReadPosition,
) {
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
            continuation.subject != subject.fingerprint ->
                Refinement.Rejected(RelationResumeFailure.SUBJECT_MISMATCH)
            continuation.meaning != meaning ->
                Refinement.Rejected(RelationResumeFailure.MEANING_MISMATCH)
            continuation.generation != subject.lease.generation ->
                Refinement.Rejected(RelationResumeFailure.GENERATION_MISMATCH)
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
