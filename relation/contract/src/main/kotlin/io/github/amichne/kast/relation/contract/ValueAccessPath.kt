package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity
import java.util.Collections

enum class ValueAccessPathFailure {
    NOT_PROPERTY,
    DEPTH_EXCEEDED,
    BASIS_MISMATCH,
}

/** A resolved property declaration, rather than a field name or a rendered type. */
class ValueField private constructor(val declaration: RelationEndpoint) {
    val identity = declaration.valueIdentity
    val basis = declaration.lease.identity

    companion object {
        fun fromCompiler(declaration: RelationEndpoint): Refinement<ValueField, ValueAccessPathFailure> =
            if (declaration.signature is CanonicalCompilerSignature.Property)
                Refinement.Refined(ValueField(declaration))
            else Refinement.Rejected(ValueAccessPathFailure.NOT_PROPERTY)
    }

    override fun equals(other: Any?): Boolean =
        other is ValueField && identity == other.identity && basis == other.basis

    override fun hashCode(): Int = 31 * identity.hashCode() + basis.hashCode()
}

sealed interface ValuePathSegment {
    data class Field(val field: ValueField) : ValuePathSegment

    /** An element position preserves the rest of the path without an index or cardinality assertion. */
    data object Element : ValuePathSegment
}

sealed interface ValuePathSelection {
    data class Selected(val path: ValueAccessPath) : ValuePathSelection

    data object DifferentField : ValuePathSelection
}

/** Each resolved field contributes one unit to the bounded depth. */
class ValueAccessPath private constructor(val segments: List<ValuePathSegment>) {
    val depth: Int = segments.size

    fun prepend(segment: ValuePathSegment): Refinement<ValueAccessPath, ValueAccessPathFailure> =
        fromSegments(listOf(segment) + segments)

    fun select(segment: ValuePathSegment): ValuePathSelection =
        when {
            segments.isEmpty() -> ValuePathSelection.Selected(Whole)
            segments.first() == segment ->
                ValuePathSelection.Selected(ValueAccessPath(Collections.unmodifiableList(segments.drop(1))))
            else -> ValuePathSelection.DifferentField
        }

    fun matchesBasis(basis: SemanticReadIdentity): Boolean =
        segments.filterIsInstance<ValuePathSegment.Field>().all { it.field.basis == basis }

    companion object {
        const val MAX_DEPTH = 8
        val Whole = ValueAccessPath(emptyList())

        fun fromSegments(segments: List<ValuePathSegment>): Refinement<ValueAccessPath, ValueAccessPathFailure> {
            if (segments.size > MAX_DEPTH) return Refinement.Rejected(ValueAccessPathFailure.DEPTH_EXCEEDED)
            val bases = segments.filterIsInstance<ValuePathSegment.Field>().map { it.field.basis }.distinct()
            if (bases.size > 1) return Refinement.Rejected(ValueAccessPathFailure.BASIS_MISMATCH)
            return Refinement.Refined(ValueAccessPath(Collections.unmodifiableList(segments.toList())))
        }
    }

    override fun equals(other: Any?): Boolean = other is ValueAccessPath && segments == other.segments

    override fun hashCode(): Int = segments.hashCode()
}
