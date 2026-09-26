package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.symbol.contract.CanonicalSymbolId
import java.util.Collections

enum class QueryBindingNameFailure {
    INVALID
}

/** A bounded name for a sealed stream or a joined column retained with its result. */
@JvmInline
value class QueryBindingName private constructor(val value: String) {
    companion object {
        private val ADMITTED = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")

        fun parse(raw: String): Refinement<QueryBindingName, QueryBindingNameFailure> =
            if (ADMITTED.matches(raw)) {
                Refinement.Refined(QueryBindingName(raw))
            } else {
                Refinement.Rejected(QueryBindingNameFailure.INVALID)
            }
    }
}

/** Each column retains the exact input symbol; an occurrence is present only when proven by arrival evidence. */
sealed interface QueryBindingValue {
    val symbol: QuerySymbol

    data class Symbol(override val symbol: QuerySymbol) : QueryBindingValue

    class Occurrence
    private constructor(
        override val symbol: QuerySymbol,
        val fact: RelationFact,
    ) : QueryBindingValue {
        companion object {
            internal fun fromExactArrival(symbol: QuerySymbol): Occurrence {
                val arrival = symbol.arrival as QueryArrivalEvidence.Proven
                return Occurrence(symbol, arrival.facts.single())
            }
        }

        override fun equals(other: Any?): Boolean = other is Occurrence && symbol == other.symbol && fact == other.fact

        override fun hashCode(): Int = 31 * symbol.hashCode() + fact.hashCode()
    }
}

data class QueryBinding(val name: QueryBindingName, val value: QueryBindingValue) {
    val canonicalIdentity: CanonicalSymbolId
        get() = CanonicalSymbolId.from(value.symbol.selector)
}

enum class QueryBindingRowFailure {
    DIFFERENT_SYMBOL_IDENTITIES
}

/** One equality match, with both original inputs and their independent evidence. */
class QueryBindingRow
private constructor(
    val left: QueryBinding,
    val right: QueryBinding,
) {
    companion object {
        fun join(
            mode: QueryJoinMode.Inner,
            left: QuerySymbol,
            right: QuerySymbol,
        ): Refinement<QueryBindingRow, QueryBindingRowFailure> =
            if (CanonicalSymbolId.from(left.selector) != CanonicalSymbolId.from(right.selector)) {
                Refinement.Rejected(QueryBindingRowFailure.DIFFERENT_SYMBOL_IDENTITIES)
            } else {
                Refinement.Refined(
                    QueryBindingRow(
                        QueryBinding(mode.leftName, left.detached().bindingValue()),
                        QueryBinding(mode.rightName, right.detached().bindingValue()),
                    )
                )
            }
    }

    override fun equals(other: Any?): Boolean = other is QueryBindingRow && left == other.left && right == other.right

    override fun hashCode(): Int = 31 * left.hashCode() + right.hashCode()
}

private fun QuerySymbol.bindingValue(): QueryBindingValue =
    when (val evidence = arrival) {
        is QueryArrivalEvidence.Proven ->
            if (evidence.facts.size == 1) {
                QueryBindingValue.Occurrence.fromExactArrival(this)
            } else {
                QueryBindingValue.Symbol(this)
            }
        QueryArrivalEvidence.None -> QueryBindingValue.Symbol(this)
    }

internal fun QuerySymbol.detached(): QuerySymbol =
    copy(connections = Collections.unmodifiableList(connections.toList()))

internal fun QueryBindingRow.detached(): QueryBindingRow {
    val mode =
        when (val admitted = QueryJoinMode.Inner.create(left.name, right.name)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> error("A joined row lost distinct binding names")
        }
    return when (val copied = QueryBindingRow.join(mode, left.value.symbol, right.value.symbol)) {
        is Refinement.Refined -> copied.value
        is Refinement.Rejected -> error("A joined row lost canonical identity equality")
    }
}

/** The result kind is closed so joined rows cannot be read as an empty symbol stream. */
sealed interface QueryRows {
    class Symbols private constructor(private val snapshot: List<QuerySymbol>) : QueryRows {
        val values: List<QuerySymbol>
            get() = snapshot

        companion object {
            fun of(values: List<QuerySymbol>): Symbols =
                Symbols(Collections.unmodifiableList(values.map(QuerySymbol::detached)))
        }

        override fun equals(other: Any?): Boolean = other is Symbols && values == other.values

        override fun hashCode(): Int = values.hashCode()
    }

    class Bindings private constructor(private val snapshot: List<QueryBindingRow>, val mode: QueryJoinMode.Inner) : QueryRows {
        val values: List<QueryBindingRow>
            get() = snapshot

        companion object {
            fun of(values: List<QueryBindingRow>, mode: QueryJoinMode.Inner): Bindings =
                Bindings(Collections.unmodifiableList(values.map(QueryBindingRow::detached)), mode)
        }

        override fun equals(other: Any?): Boolean = other is Bindings && values == other.values && mode == other.mode

        override fun hashCode(): Int = 31 * values.hashCode() + mode.hashCode()
    }
}
