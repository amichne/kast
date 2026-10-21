package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.workspace.contract.SemanticReadIdentity

data class ValueExecutionIdentity
internal constructor(
    val host: ValueDeclarationIdentity,
    val basis: SemanticReadIdentity,
)

/** Plain source execution belongs to one exact compiler-confirmed declaration. */
class ValueExecutionOwner private constructor(val enclosing: RelationEndpoint) {
    val basis = enclosing.lease.identity
    val range = enclosing.range
    val identity = ValueExecutionIdentity(enclosing.valueIdentity, basis)

    fun belongsTo(host: RelationEndpoint): Boolean = identity.host == host.valueIdentity && basis == host.lease.identity

    fun hasSameProofAs(other: ValueExecutionOwner): Boolean = enclosing.hasSameProofAs(other.enclosing)

    companion object {
        fun declaration(enclosing: RelationEndpoint): ValueExecutionOwner = ValueExecutionOwner(enclosing)
    }

    override fun equals(other: Any?): Boolean = other is ValueExecutionOwner && identity == other.identity

    override fun hashCode(): Int = identity.hashCode()
}
