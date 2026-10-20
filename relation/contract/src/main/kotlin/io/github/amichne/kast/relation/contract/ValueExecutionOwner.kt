package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity

enum class ValueExecutionKind {
    DECLARATION,
    ANONYMOUS,
}

data class ValueExecutionIdentity
internal constructor(
    val host: ValueDeclarationIdentity,
    val basis: SemanticReadIdentity,
    val body: ValueDeclarationIdentity,
    val kind: ValueExecutionKind,
)

enum class ValueExecutionOwnerFailure {
    BASIS_MISMATCH,
    FILE_MISMATCH,
    BODY_OUTSIDE_HOST,
}

sealed interface ValueExecutionBody {
    data object Declaration : ValueExecutionBody

    data class Anonymous(val callable: RelationCallableBody.Anonymous) : ValueExecutionBody
}

/** The lexical selection and its actual execution body keep distinct identities on one admitted basis. */
class ValueExecutionOwner
private constructor(
    val enclosing: RelationEndpoint,
    val body: ValueExecutionBody,
) {
    val basis = enclosing.lease.identity
    val range =
        when (body) {
            ValueExecutionBody.Declaration -> enclosing.range
            is ValueExecutionBody.Anonymous -> body.callable.range
        }
    val identity =
        ValueExecutionIdentity(
            enclosing.valueIdentity,
            basis,
            when (body) {
                ValueExecutionBody.Declaration -> enclosing.valueIdentity
                is ValueExecutionBody.Anonymous ->
                    body.callable.let { ValueDeclarationIdentity(it.compilerIdentity, it.file, it.range) }
            },
            when (body) {
                ValueExecutionBody.Declaration -> ValueExecutionKind.DECLARATION
                is ValueExecutionBody.Anonymous -> ValueExecutionKind.ANONYMOUS
            },
        )

    fun belongsTo(host: RelationEndpoint): Boolean = identity.host == host.valueIdentity && basis == host.lease.identity

    fun hasSameProofAs(other: ValueExecutionOwner): Boolean =
        enclosing.hasSameProofAs(other.enclosing) && body == other.body

    companion object {
        fun declaration(enclosing: RelationEndpoint): ValueExecutionOwner =
            ValueExecutionOwner(enclosing, ValueExecutionBody.Declaration)

        /** The native adapter reacquires the K2 function literal before it supplies this source-bound body proof. */
        fun fromCompiler(
            enclosing: RelationEndpoint,
            body: RelationCallableBody.Anonymous,
            basis: SemanticReadIdentity,
        ): Refinement<ValueExecutionOwner, ValueExecutionOwnerFailure> =
            when {
                basis != enclosing.lease.identity -> Refinement.Rejected(ValueExecutionOwnerFailure.BASIS_MISMATCH)
                body.file != enclosing.file -> Refinement.Rejected(ValueExecutionOwnerFailure.FILE_MISMATCH)
                !enclosing.range.containsValueRange(body.range) ->
                    Refinement.Rejected(ValueExecutionOwnerFailure.BODY_OUTSIDE_HOST)
                else -> Refinement.Refined(ValueExecutionOwner(enclosing, ValueExecutionBody.Anonymous(body)))
            }
    }

    override fun equals(other: Any?): Boolean = other is ValueExecutionOwner && identity == other.identity

    override fun hashCode(): Int = identity.hashCode()
}
