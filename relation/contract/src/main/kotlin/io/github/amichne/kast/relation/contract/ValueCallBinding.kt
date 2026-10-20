package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerCallableSignature
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity
import java.util.Collections

enum class ValueCallBindingFailure {
    BASIS_MISMATCH,
    CALLABLE_MISMATCH,
    INVALID_PARAMETER,
    PARAMETER_OUTSIDE_CALLABLE,
    DEPTH_EXCEEDED,
    RECURSIVE_CALL,
    RETURN_MISMATCH,
    IMPLEMENTATION_PROOF_MISMATCH,
    SOURCE_OWNERSHIP_MISMATCH,
}

/** K2 supplies the parameter identity under its actual execution body. */
class ValueFormalParameter
private constructor(
    val callable: RelationEndpoint,
    val position: ValueArgumentPosition,
    val name: SymbolDiscoveryCandidateName,
    val executionOwner: ValueExecutionOwner,
    val range: ExactDeclarationTextRange,
) {

    companion object {
        fun fromCompiler(
            callable: RelationEndpoint,
            position: ValueArgumentPosition,
            name: SymbolDiscoveryCandidateName,
            range: ExactDeclarationTextRange,
            executionOwner: ValueExecutionOwner = ValueExecutionOwner.declaration(callable),
        ): Refinement<ValueFormalParameter, ValueCallBindingFailure> {
            if (!executionOwner.belongsTo(callable))
                return Refinement.Rejected(ValueCallBindingFailure.CALLABLE_MISMATCH)
            val signature =
                when (val body = executionOwner.body) {
                    ValueExecutionBody.Declaration -> callable.signature as? CanonicalCompilerCallableSignature
                    is ValueExecutionBody.Anonymous -> body.callable.signature
                } ?: return Refinement.Rejected(ValueCallBindingFailure.INVALID_PARAMETER)
            if (position.value !in signature.valueParameters.indices)
                return Refinement.Rejected(ValueCallBindingFailure.INVALID_PARAMETER)
            if (!executionOwner.range.containsValueRange(range))
                return Refinement.Rejected(ValueCallBindingFailure.PARAMETER_OUTSIDE_CALLABLE)
            return Refinement.Refined(ValueFormalParameter(callable, position, name, executionOwner, range))
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ValueFormalParameter &&
            executionOwner == other.executionOwner &&
            position == other.position &&
            name == other.name &&
            range == other.range

    override fun hashCode(): Int = listOf(executionOwner, position, name, range).hashCode()
}

enum class ValueReceiverKind {
    DISPATCH,
    EXTENSION,
}

sealed interface ValueFormalPosition {
    data class Parameter(val parameter: ValueFormalParameter) : ValueFormalPosition

    data class Receiver(val kind: ValueReceiverKind) : ValueFormalPosition
}

/** One exact source invocation and its compiler-confirmed execution target. */
class ValueCallFrame
private constructor(
    val invocation: ValueInvocation,
    val callee: ValueExecutionOwner,
    val target: ValueCallTarget,
) {
    companion object {
        fun direct(
            invocation: ValueInvocation,
            callee: ValueExecutionOwner,
        ): Refinement<ValueCallFrame, ValueCallBindingFailure> =
            when {
                invocation.basis != callee.basis -> Refinement.Rejected(ValueCallBindingFailure.BASIS_MISMATCH)
                callee.body != ValueExecutionBody.Declaration ||
                    invocation.callable.valueIdentity != callee.enclosing.valueIdentity ->
                    Refinement.Rejected(ValueCallBindingFailure.CALLABLE_MISMATCH)
                else -> Refinement.Refined(ValueCallFrame(invocation, callee, ValueCallTarget.Direct))
            }

        fun implementation(
            invocation: ValueInvocation,
            dispatch: ValueImplementationDispatch,
            relation: RelationFact,
            ownership: ValueSourceOwnership,
        ): Refinement<ValueCallFrame, ValueCallBindingFailure> =
            when {
                invocation.basis != relation.authority -> Refinement.Rejected(ValueCallBindingFailure.BASIS_MISMATCH)
                relation !in dispatch.batch.facts ->
                    Refinement.Rejected(ValueCallBindingFailure.IMPLEMENTATION_PROOF_MISMATCH)
                invocation.callable.valueIdentity != relation.target.valueIdentity ->
                    Refinement.Rejected(ValueCallBindingFailure.CALLABLE_MISMATCH)
                !ownership.matches(relation.source) || !ownership.admittedBy(dispatch.batch.request) ->
                    Refinement.Rejected(ValueCallBindingFailure.SOURCE_OWNERSHIP_MISMATCH)
                else ->
                    Refinement.Refined(
                        ValueCallFrame(
                            invocation,
                            ValueExecutionOwner.declaration(relation.source),
                            ValueCallTarget.Implementation(dispatch, relation, ownership),
                        )
                    )
            }
    }

    fun hasSameDirectProofAs(other: ValueCallFrame): Boolean {
        if (target != ValueCallTarget.Direct || other.target != ValueCallTarget.Direct) return false
        val first = invocation as? ValueInvocation ?: return false
        val second = other.invocation as? ValueInvocation ?: return false
        return first.range == second.range &&
            first.enclosing.hasSameProofAs(second.enclosing) &&
            first.callable.hasSameProofAs(second.callable) &&
            first.executionOwner.hasSameProofAs(second.executionOwner) &&
            callee.hasSameProofAs(other.callee)
    }

    override fun equals(other: Any?): Boolean =
        other is ValueCallFrame && invocation == other.invocation && callee == other.callee && target == other.target

    override fun hashCode(): Int = 31 * (31 * invocation.hashCode() + callee.hashCode()) + target.hashCode()
}

/** The query's existing exact-site cache includes this matched call stack. */
class ValueCallStack private constructor(val frames: List<ValueCallFrame>) {
    fun enter(frame: ValueCallFrame): Refinement<ValueCallStack, ValueCallBindingFailure> =
        when {
            frames.size >= MAX_DEPTH -> Refinement.Rejected(ValueCallBindingFailure.DEPTH_EXCEEDED)
            frame in frames -> Refinement.Rejected(ValueCallBindingFailure.RECURSIVE_CALL)
            frames.any { it.invocation.basis != frame.invocation.basis } ->
                Refinement.Rejected(ValueCallBindingFailure.BASIS_MISMATCH)
            else -> Refinement.Refined(ValueCallStack(Collections.unmodifiableList(frames + frame)))
        }

    fun exit(frame: ValueCallFrame): Refinement<ValueCallStack, ValueCallBindingFailure> =
        if (frames.lastOrNull() == frame)
            Refinement.Refined(ValueCallStack(Collections.unmodifiableList(frames.dropLast(1))))
        else Refinement.Rejected(ValueCallBindingFailure.RETURN_MISMATCH)

    fun matchesBasis(basis: SemanticReadIdentity): Boolean = frames.all { it.invocation.basis == basis }

    fun hasSameDirectProofAs(other: ValueCallStack): Boolean =
        frames.size == other.frames.size && frames.indices.all { frames[it].hasSameDirectProofAs(other.frames[it]) }

    companion object {
        const val MAX_DEPTH = 32
        val Empty = ValueCallStack(emptyList())
    }

    override fun equals(other: Any?): Boolean = other is ValueCallStack && frames == other.frames

    override fun hashCode(): Int = frames.hashCode()
}
