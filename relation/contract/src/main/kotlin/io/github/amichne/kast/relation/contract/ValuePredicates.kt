package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity
import java.util.Collections

enum class ValuePredicateFailure {
    BASIS_MISMATCH,
    NOT_CLASS,
    CAPACITY_EXCEEDED,
    NESTED_GUARD,
}

/** A predicate names an exact value and call context, without recursively copying its conjunction. */
class ValuePredicateSubject private constructor(val occurrence: ValueSite) {
    companion object {
        fun fromCompiler(site: ValueSite): ValuePredicateSubject =
            ValuePredicateSubject(
                when (val value = site.withState(predicates = ValuePredicates.Empty)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> error("An empty conjunction cannot change the admitted basis")
                }
            )
    }

    override fun equals(other: Any?): Boolean = other is ValuePredicateSubject && occurrence == other.occurrence

    override fun hashCode(): Int = occurrence.hashCode()
}

sealed interface ValuePredicate {
    val subject: ValuePredicateSubject

    @ConsistentCopyVisibility
    data class Subtype
    private constructor(
        override val subject: ValuePredicateSubject,
        val resolvedType: RelationEndpoint,
    ) : ValuePredicate {
        companion object {
            fun fromCompiler(
                subject: ValuePredicateSubject,
                resolvedType: RelationEndpoint,
            ): Refinement<Subtype, ValuePredicateFailure> =
                when {
                    subject.occurrence.basis != resolvedType.lease.identity ->
                        Refinement.Rejected(ValuePredicateFailure.BASIS_MISMATCH)
                    resolvedType.signature !is CanonicalCompilerSignature.ClassLike ->
                        Refinement.Rejected(ValuePredicateFailure.NOT_CLASS)
                    else -> Refinement.Refined(Subtype(subject, resolvedType))
                }
        }
    }

    @ConsistentCopyVisibility
    data class NonNull private constructor(override val subject: ValuePredicateSubject) : ValuePredicate {
        companion object {
            fun fromCompiler(subject: ValuePredicateSubject): NonNull = NonNull(subject)
        }
    }
}

/** A bounded conjunction carries static path conditions, not runtime reachability. */
class ValuePredicates private constructor(val entries: Set<ValuePredicate>) {
    fun plus(added: Collection<ValuePredicate>): Refinement<ValuePredicates, ValuePredicateFailure> =
        fromCompiler(entries + added)

    fun matchesBasis(basis: SemanticReadIdentity): Boolean = entries.all { it.subject.occurrence.basis == basis }

    companion object {
        const val MAX_COUNT = 16
        val Empty = ValuePredicates(emptySet())

        fun fromCompiler(entries: Collection<ValuePredicate>): Refinement<ValuePredicates, ValuePredicateFailure> {
            val distinct = entries.toSet()
            if (distinct.size > MAX_COUNT) return Refinement.Rejected(ValuePredicateFailure.CAPACITY_EXCEEDED)
            if (distinct.map { it.subject.occurrence.basis }.distinct().size > 1)
                return Refinement.Rejected(ValuePredicateFailure.BASIS_MISMATCH)
            return Refinement.Refined(ValuePredicates(Collections.unmodifiableSet(distinct)))
        }
    }

    override fun equals(other: Any?): Boolean = other is ValuePredicates && entries == other.entries

    override fun hashCode(): Int = entries.hashCode()
}
