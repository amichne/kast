package io.github.amichne.kast.traversal.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private const val TRAVERSAL_IDENTITY_FINGERPRINT_LENGTH = 64

enum class TraversalPositiveLimitFailure {
    NOT_POSITIVE,
}

@JvmInline
value class TraversalByteLimit private constructor(val value: Long) {
    companion object {
        /**
         * Proof transition: `Long -> Refinement<TraversalByteLimit,
         * TraversalPositiveLimitFailure>`.
         *
         * Establishes a finite positive bound for returned detached relation-fact bytes.
         * [TraversalPositiveLimitFailure] is the closed expected failure. Raw byte limits may be
         * extracted only at request admission, traversal accounting, or transport.
         */
        fun parse(raw: Long): Refinement<TraversalByteLimit, TraversalPositiveLimitFailure> =
            if (raw > 0L) Refinement.Refined(TraversalByteLimit(raw))
            else Refinement.Rejected(TraversalPositiveLimitFailure.NOT_POSITIVE)
    }
}

@JvmInline
value class TraversalDepthLimit private constructor(val value: Int) {
    companion object {
        /**
         * Proof transition: `Int -> Refinement<TraversalDepthLimit,
         * TraversalPositiveLimitFailure>`.
         *
         * Establishes a finite positive maximum semantic hop depth. [TraversalPositiveLimitFailure]
         * is the closed expected failure. Raw depth limits may be extracted only at request
         * admission or transport.
         */
        fun parse(raw: Int): Refinement<TraversalDepthLimit, TraversalPositiveLimitFailure> =
            if (raw > 0) Refinement.Refined(TraversalDepthLimit(raw))
            else Refinement.Rejected(TraversalPositiveLimitFailure.NOT_POSITIVE)
    }
}

@JvmInline
value class TraversalFrontierLimit private constructor(val value: Int) {
    companion object {
        /**
         * Proof transition: `Int -> Refinement<TraversalFrontierLimit,
         * TraversalPositiveLimitFailure>`.
         *
         * Establishes a finite positive bound on one-hop frontier expansions per run.
         * [TraversalPositiveLimitFailure] is the closed expected failure. Raw frontier limits may
         * be extracted only at request admission or transport.
         */
        fun parse(raw: Int): Refinement<TraversalFrontierLimit, TraversalPositiveLimitFailure> =
            if (raw > 0) Refinement.Refined(TraversalFrontierLimit(raw))
            else Refinement.Rejected(TraversalPositiveLimitFailure.NOT_POSITIVE)
    }
}

data class TraversalBudget(
    val records: ResultLimit,
    val returnedBytes: TraversalByteLimit,
    val workUnits: WorkUnitLimit,
    val elapsedTime: ElapsedTimeLimitMillis,
    val depth: TraversalDepthLimit,
    val frontier: TraversalFrontierLimit,
    val oneHop: RelationBudget,
)

enum class TraversalPlanFailure {
    ONE_HOP_RECORD_LIMIT_EXCEEDS_TRAVERSAL,
    ONE_HOP_BYTE_LIMIT_EXCEEDS_TRAVERSAL,
    ONE_HOP_WORK_LIMIT_EXCEEDS_TRAVERSAL,
    ONE_HOP_TIME_LIMIT_EXCEEDS_TRAVERSAL,
}

enum class TraversalResumeFailure {
    IDENTITY_MISMATCH,
}

@JvmInline
value class TraversalIdentityFingerprint internal constructor(val value: String) {
    init {
        require(
            value.length == TRAVERSAL_IDENTITY_FINGERPRINT_LENGTH &&
            value.all { character -> character in '0'..'9' || character in 'a'..'f' },
        )
    }
}

sealed interface TraversalPosition {
    data object Start : TraversalPosition

    @ConsistentCopyVisibility
    data class Resume internal constructor(
        val continuation: TraversalContinuation,
    ) : TraversalPosition
}

/** Exact semantic traversal plan with explicit aggregate and one-hop resource authority. */
class TraversalPlan private constructor(
    val start: SymbolSelector,
    val meaning: RelationMeaning,
    val budget: TraversalBudget,
    val position: TraversalPosition,
    val identity: TraversalIdentityFingerprint,
) {
    val scope: SymbolSearchScope = start.scope

    companion object {
        /**
         * Proof transition: `(SymbolSelector, RelationMeaning, TraversalBudget) ->
         * Refinement<TraversalPlan, TraversalPlanFailure>`.
         *
         * Establishes one exact root/generation/scope, one closed meaning, and a one-hop budget no
         * stronger than every aggregate traversal bound. [TraversalPlanFailure] is the closed
         * expected failure. Raw selector and numeric extraction may occur only before this public
         * traversal boundary.
         */
        fun start(
            selector: SymbolSelector,
            meaning: RelationMeaning,
            budget: TraversalBudget,
        ): Refinement<TraversalPlan, TraversalPlanFailure> = admit(
            selector,
            meaning,
            budget,
            TraversalPosition.Start,
        )

        /**
         * Proof transition: `(SymbolSelector, RelationMeaning, TraversalBudget,
         * TraversalContinuation) -> Refinement<TraversalPlan, TraversalPlanResumeFailure>`.
         *
         * Establishes that explicit new bounds resume only the continuation's exact selector,
         * meaning, root, generation, and scope. [TraversalPlanResumeFailure] is the closed expected
         * failure. Raw continuation decoding may occur only before this public traversal boundary.
         */
        fun resume(
            selector: SymbolSelector,
            meaning: RelationMeaning,
            budget: TraversalBudget,
            continuation: TraversalContinuation,
        ): Refinement<TraversalPlan, TraversalPlanResumeFailure> {
            val identity = traversalIdentity(selector, meaning)
            if (identity != continuation.identity) {
                return Refinement.Rejected(
                    TraversalPlanResumeFailure.Resume(TraversalResumeFailure.IDENTITY_MISMATCH),
                )
            }
            return when (val admitted = admit(
                selector,
                meaning,
                budget,
                TraversalPosition.Resume(continuation),
            )) {
                is Refinement.Refined -> Refinement.Refined(admitted.value)
                is Refinement.Rejected -> Refinement.Rejected(
                    TraversalPlanResumeFailure.Plan(admitted.failure),
                )
            }
        }

        /**
         * Proof transition: `(SymbolSelector, RelationMeaning, TraversalBudget,
         * TraversalPosition) -> Refinement<TraversalPlan, TraversalPlanFailure>`.
         *
         * Establishes that no one-hop limit exceeds its aggregate traversal authority.
         * [TraversalPlanFailure] is the closed expected failure. Raw values remain at the public
         * start/resume boundary.
         */
        private fun admit(
            selector: SymbolSelector,
            meaning: RelationMeaning,
            budget: TraversalBudget,
            position: TraversalPosition,
        ): Refinement<TraversalPlan, TraversalPlanFailure> = when {
            budget.oneHop.resources.resultLimit.value > budget.records.value ->
                Refinement.Rejected(
                    TraversalPlanFailure.ONE_HOP_RECORD_LIMIT_EXCEEDS_TRAVERSAL,
                )
            budget.oneHop.returnedBytes.value > budget.returnedBytes.value ->
                Refinement.Rejected(
                    TraversalPlanFailure.ONE_HOP_BYTE_LIMIT_EXCEEDS_TRAVERSAL,
                )
            budget.oneHop.resources.workUnitLimit.value > budget.workUnits.value ->
                Refinement.Rejected(
                    TraversalPlanFailure.ONE_HOP_WORK_LIMIT_EXCEEDS_TRAVERSAL,
                )
            budget.oneHop.resources.elapsedTimeLimit.value > budget.elapsedTime.value ->
                Refinement.Rejected(
                    TraversalPlanFailure.ONE_HOP_TIME_LIMIT_EXCEEDS_TRAVERSAL,
                )
            else -> Refinement.Refined(
                TraversalPlan(
                    selector,
                    meaning,
                    budget,
                    position,
                    traversalIdentity(selector, meaning),
                ),
            )
        }
    }
}

sealed interface TraversalPlanResumeFailure {
    data class Resume(val failure: TraversalResumeFailure) : TraversalPlanResumeFailure
    data class Plan(val failure: TraversalPlanFailure) : TraversalPlanResumeFailure
}

private fun traversalIdentity(
    selector: SymbolSelector,
    meaning: RelationMeaning,
): TraversalIdentityFingerprint {
    val canonical = buildString {
        appendTraversalField(selector.fingerprint.value)
        appendTraversalField(meaning.canonicalName())
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(StandardCharsets.UTF_8))
    return TraversalIdentityFingerprint(
        digest.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        },
    )
}

internal fun RelationMeaning.canonicalName(): String = when (this) {
    RelationMeaning.References -> "references"
    RelationMeaning.Callers -> "callers"
    RelationMeaning.Callees -> "callees"
    RelationMeaning.Implementations -> "implementations"
    RelationMeaning.Inheritors -> "inheritors"
    RelationMeaning.Overrides -> "overrides"
    RelationMeaning.TypeUses -> "type-uses"
}

internal fun StringBuilder.appendTraversalField(value: String) {
    append(value.toByteArray(StandardCharsets.UTF_8).size)
    append(':')
    append(value)
}
