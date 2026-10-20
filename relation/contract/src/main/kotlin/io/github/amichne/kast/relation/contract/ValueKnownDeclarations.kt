package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import java.util.Collections

enum class ValueKnownDeclarationsFailure {
    TOO_MANY_DECLARATIONS,
    FOREIGN_BASIS,
}

/** Explicit fresh inputs only; this finite inventory establishes no catalog completeness or relationship. */
class ValueKnownDeclarations private constructor(val declarations: List<RevalidatedRelationEndpoint>) {
    fun belongsTo(lease: SemanticReadAuthority): Boolean = declarations.all { it.endpoint.lease == lease }

    fun hasSameProofAs(other: ValueKnownDeclarations): Boolean =
        declarations.size == other.declarations.size &&
            declarations.zip(other.declarations).all { (first, second) ->
                first.endpoint.hasSameKnownProofAs(second.endpoint)
            }

    companion object {
        const val MAX_SIZE = 128
        val Empty = ValueKnownDeclarations(emptyList())

        fun admit(
            declarations: List<RevalidatedRelationEndpoint>,
            lease: SemanticReadAuthority,
        ): Refinement<ValueKnownDeclarations, ValueKnownDeclarationsFailure> =
            when {
                declarations.size > MAX_SIZE -> Refinement.Rejected(ValueKnownDeclarationsFailure.TOO_MANY_DECLARATIONS)
                declarations.any { it.endpoint.lease != lease } ->
                    Refinement.Rejected(ValueKnownDeclarationsFailure.FOREIGN_BASIS)
                declarations.isEmpty() -> Refinement.Refined(Empty)
                else -> Refinement.Refined(ValueKnownDeclarations(Collections.unmodifiableList(declarations.toList())))
            }
    }
}

private fun RelationEndpoint.hasSameKnownProofAs(other: RelationEndpoint): Boolean =
    fingerprint == other.fingerprint &&
        hasSameProofAs(other) &&
        when (this) {
            is RelationEndpoint.Subject -> other is RelationEndpoint.Subject
            is RelationEndpoint.Resolved -> other is RelationEndpoint.Resolved
        }
