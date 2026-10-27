package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

private const val MAX_EXPRESSION_KIND_LENGTH = 128

enum class ValueFlowExpressionKindFailure {
    INVALID_KIND
}

/** A bounded compiler expression-class name, never source text or a semantic execution proof. */
@JvmInline
value class ValueFlowExpressionKind private constructor(val value: String) {
    companion object {
        fun fromCompiler(raw: String): Refinement<ValueFlowExpressionKind, ValueFlowExpressionKindFailure> =
            if (
                raw.length in 1..MAX_EXPRESSION_KIND_LENGTH &&
                    raw.first() in 'A'..'Z' &&
                    raw.all { it.isAsciiIdentifierPart() }
            )
                Refinement.Refined(ValueFlowExpressionKind(raw))
            else Refinement.Rejected(ValueFlowExpressionKindFailure.INVALID_KIND)

        private fun Char.isAsciiIdentifierPart(): Boolean =
            this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '_'
    }
}

sealed interface ValueFlowObligationEvidence {
    data object None : ValueFlowObligationEvidence

    data class StringConversion
    internal constructor(
        val selection: ValueStringConversionSelection,
        val entryRange: ExactDeclarationTextRange,
        val inputRange: ExactDeclarationTextRange,
    ) : ValueFlowObligationEvidence

    data class CallbackInput internal constructor(val failure: ValueCallbackInputFailure) : ValueFlowObligationEvidence

    data class Expression
    internal constructor(val kind: ValueFlowExpressionKind, val range: ExactDeclarationTextRange) :
        ValueFlowObligationEvidence

    data class ImplementationDispatch internal constructor(val dispatch: ValueImplementationDispatch) :
        ValueFlowObligationEvidence
}

enum class ValueFlowObligationFailure {
    BASIS_MISMATCH,
    SOURCE_MISMATCH,
    EXPRESSION_OUTSIDE_OWNER,
}

/** Unresolved target-set evidence cannot confer a positive transfer or a terminal model. */
@ConsistentCopyVisibility
data class ValueFlowObligation
private constructor(
    val site: ValueSite,
    val cause: ValueFlowUnsupportedCause,
    val evidence: ValueFlowObligationEvidence,
) {
    constructor(site: ValueSite, cause: ValueFlowUnsupportedCause) : this(site, cause, ValueFlowObligationEvidence.None)

    internal fun shareCallableEvidence(shareSite: (ValueSite) -> ValueSite): ValueFlowObligation {
        val shared = shareSite(site)
        return if (shared === site) this else ValueFlowObligation(shared, cause, evidence)
    }

    companion object {
        fun fromStringConversion(
            site: ValueSite,
            cause: ValueFlowUnsupportedCause,
            selection: ValueStringConversionSelection,
            entryRange: ExactDeclarationTextRange,
            inputRange: ExactDeclarationTextRange,
        ): Refinement<ValueFlowObligation, ValueFlowObligationFailure> =
            if (
                site.executionOwner.range.containsValueRange(entryRange) &&
                    entryRange.containsValueRange(inputRange) &&
                    inputRange.containsValueRange(site.range)
            )
                Refinement.Refined(
                    ValueFlowObligation(
                        site,
                        cause,
                        ValueFlowObligationEvidence.StringConversion(selection, entryRange, inputRange),
                    )
                )
            else Refinement.Rejected(ValueFlowObligationFailure.EXPRESSION_OUTSIDE_OWNER)

        fun fromCallbackInput(
            site: ValueSite,
            failure: ValueCallbackInputFailure,
            cause: ValueFlowUnsupportedCause = ValueFlowUnsupportedCause.NESTED_EXECUTION,
        ): Refinement<ValueFlowObligation, ValueFlowObligationFailure> =
            if (failure.matchesBasis(site.basis))
                Refinement.Refined(ValueFlowObligation(site, cause, ValueFlowObligationEvidence.CallbackInput(failure)))
            else Refinement.Rejected(ValueFlowObligationFailure.BASIS_MISMATCH)

        fun fromExpression(
            site: ValueSite,
            cause: ValueFlowUnsupportedCause,
            kind: ValueFlowExpressionKind,
            range: ExactDeclarationTextRange,
        ): Refinement<ValueFlowObligation, ValueFlowObligationFailure> =
            if (site.enclosing.range.containsValueRange(range))
                Refinement.Refined(
                    ValueFlowObligation(site, cause, ValueFlowObligationEvidence.Expression(kind, range))
                )
            else Refinement.Rejected(ValueFlowObligationFailure.EXPRESSION_OUTSIDE_OWNER)

        fun fromImplementation(
            site: ValueSite,
            cause: ValueFlowUnsupportedCause,
            dispatch: ValueImplementationDispatch,
        ): Refinement<ValueFlowObligation, ValueFlowObligationFailure> {
            val question = dispatch.batch.request
            if (site.basis != question.subject.lease.identity)
                return Refinement.Rejected(ValueFlowObligationFailure.BASIS_MISMATCH)
            val matches =
                when (val role = site.role) {
                    is ValueRole.Argument -> role.call.callable.valueIdentity == question.subject.valueIdentity
                    is ValueRole.Receiver -> role.call.callable.valueIdentity == question.subject.valueIdentity
                    // A return can preserve an unresolved caller investigation without asserting its relationship.
                    ValueRole.Return -> true
                    is ValueRole.StringConversionResult,
                    is ValueRole.Formal,
                    is ValueRole.LoopResult,
                    ValueRole.ExpressionResult,
                    ValueRole.LocalBinding,
                    ValueRole.LocalRead,
                    ValueRole.PropertyAssignment -> false
                }
            return if (matches)
                Refinement.Refined(
                    ValueFlowObligation(site, cause, ValueFlowObligationEvidence.ImplementationDispatch(dispatch))
                )
            else Refinement.Rejected(ValueFlowObligationFailure.SOURCE_MISMATCH)
        }
    }
}
