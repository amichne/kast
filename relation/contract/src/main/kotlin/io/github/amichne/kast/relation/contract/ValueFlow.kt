package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

/** The deliberately finite first compiler-supported transfer domain. */
enum class ValueTransferKind {
    LOCAL_BINDING,
    LOCAL_READ,
    ARGUMENT,
    RETURN,
    PROPERTY_ASSIGNMENT,
    BRANCH_ALTERNATIVE,
    WRAPPER_RETURN,
    RECEIVER,
    SOURCE_CALL_ENTRY,
    /** Source return to a call result, including a conditional compiler-declared binary callback result. */
    SOURCE_CALL_RETURN,
    FIELD_READ,
    CONSTRUCTOR_FIELD,
    CAST_RESULT,
    STRING_CONTENT,
}

enum class ValueTransferFailure {
    EVIDENCE_MISMATCH,
    BASIS_MISMATCH,
    ROLE_MISMATCH,
    SELF_EDGE,
}

/** A native-confirmed transfer. Model continuations are a separate evidence type. */
class ValueTransfer
private constructor(
    val source: ValueSite,
    val target: ValueSite,
    val kind: ValueTransferKind,
    val evidence: ValueTransferEvidence,
) {
    internal fun shareCallableEvidence(proofs: ValueFlowSiteSharing): ValueTransfer {
        val sharedSource = proofs.site(source)
        val sharedTarget = proofs.site(target)
        return if (sharedSource === source && sharedTarget === target) this
        else ValueTransfer(sharedSource, sharedTarget, kind, evidence)
    }

    companion object {
        fun declaredCallbackResult(
            source: ValueSite,
            binding: DeclaredCallbackValueBinding,
        ): Refinement<ValueTransfer, ValueFlowUnsupportedCause> = binding.resultTransfer(source)

        /** The native adapter owns semantic confirmation; this constructor preserves its detached invariants. */
        fun fromCompiler(
            source: ValueSite,
            target: ValueSite,
            kind: ValueTransferKind,
            evidence: ValueTransferEvidence = ValueTransferEvidence.Direct,
        ): Refinement<ValueTransfer, ValueTransferFailure> {
            if (source.basis != target.basis) return Refinement.Rejected(ValueTransferFailure.BASIS_MISMATCH)
            if (source.identity == target.identity) return Refinement.Rejected(ValueTransferFailure.SELF_EDGE)
            if (!source.conditions.admits(source, target, kind))
                return Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH)
            if (!evidence.admits(source, target, kind))
                return Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH)
            val rolesMatch = kind.admitsRoles(source, target)
            return if (rolesMatch) Refinement.Refined(ValueTransfer(source, target, kind, evidence))
            else Refinement.Rejected(ValueTransferFailure.ROLE_MISMATCH)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ValueTransfer &&
            source == other.source &&
            target == other.target &&
            kind == other.kind &&
            evidence == other.evidence

    override fun hashCode(): Int =
        31 * (31 * (31 * source.hashCode() + target.hashCode()) + kind.hashCode()) + evidence.hashCode()
}

enum class ValueFlowUnsupportedCause {
    FINALLY_UNSUPPORTED,
    ABRUPT_COMPLETION,
    EXTERNAL_CALL,
    UNMODELED_CALL,
    MUTABLE_CONTROL_FLOW,
    UNSUPPORTED_EXPRESSION,
    UNRESOLVED_REFERENCE,
    UNSUPPORTED_PROPERTY,
    UNSUPPORTED_RETURN,
    NESTED_EXECUTION,
    OUTSIDE_DOMAIN,
    RESULT_LIMIT_REACHED,
    WORK_LIMIT_REACHED,
    TIME_LIMIT_REACHED,
    BYTE_LIMIT_REACHED,
    ACCESS_PATH_DEPTH_EXCEEDED,
    CALL_CONTEXT_DEPTH_EXCEEDED,
    RECURSIVE_SOURCE_CALL,
}

enum class ValueFlowTerminal {
    SupportedDomainExhausted,
    Unresolved,
    ResourceSuspended,
}

enum class ValueFlowStepFailure {
    INVALID_PROGRESS,
    WORK_LIMIT_EXCEEDED,
    RESULT_LIMIT_EXCEEDED,
    DETACHED_CAPACITY_EXCEEDED,
    DOMAIN_MISMATCH,
    SOURCE_MISMATCH,
    BASIS_MISMATCH,
    UNRESOLVED_OBLIGATIONS,
    MISSING_OBLIGATION,
}

enum class ValueFlowRejection {
    STALE_SITE,
    OUTSIDE_DOMAIN,
    OWNER_UNAVAILABLE,
    AUTHORITY_MOVED,
    UNSUPPORTED_SEED,
    UNRESOLVED_SEED,
    NATIVE_UNAVAILABLE,
    GRANT_TOO_SMALL,
    NESTED_EXECUTION,
}

sealed interface ValueFlowRead {
    data class Observed(val step: ValueFlowStep) : ValueFlowRead

    data class Suspended(
        val step: ValueFlowStep,
        val remainder: LocalBindingReadRemainder,
        val cause: ValueFlowSuspensionCause,
    ) : ValueFlowRead

    data class Rejected(val cause: ValueFlowRejection, val examinedWorkUnits: RelationWorkCount) : ValueFlowRead

    /** A failed detached invariant retains its exact finite cause instead of becoming native unavailability. */
    data class ContractRejected(val cause: ValueFlowStepFailure, val examinedWorkUnits: RelationWorkCount) :
        ValueFlowRead
}

/** One-hop compiler work; existing query tasks own scheduling, grants, retention and continuations. */
fun interface ValueFlowCompilerPort {
    suspend fun read(request: ValueFlowRequest): ValueFlowRead
}

/** Carries the native grant and semantic expansion domain together with the exact input value. */
data class ValueFlowRequest(
    val source: ValueSite,
    val budget: RelationBudget,
    val boundary: RelationSearchBoundary,
    val remainder: LocalBindingReadRemainder? = null,
)

private fun ValueTransferKind.admitsRoles(source: ValueSite, target: ValueSite): Boolean =
    when (this) {
        ValueTransferKind.LOCAL_BINDING -> target.role == ValueRole.LocalBinding
        ValueTransferKind.LOCAL_READ ->
            (source.role == ValueRole.LocalBinding || source.role is ValueRole.Formal) &&
                target.role == ValueRole.LocalRead
        ValueTransferKind.ARGUMENT -> target.role is ValueRole.Argument
        ValueTransferKind.RECEIVER -> target.role is ValueRole.Receiver
        ValueTransferKind.RETURN -> target.role == ValueRole.Return
        ValueTransferKind.PROPERTY_ASSIGNMENT -> target.role == ValueRole.PropertyAssignment
        ValueTransferKind.BRANCH_ALTERNATIVE -> target.role == ValueRole.ExpressionResult
        ValueTransferKind.STRING_CONTENT -> target.role == ValueRole.ExpressionResult
        ValueTransferKind.CAST_RESULT ->
            (source.role == ValueRole.ExpressionResult || source.role == ValueRole.LocalRead) &&
                target.role == ValueRole.ExpressionResult
        ValueTransferKind.WRAPPER_RETURN ->
            source.role is ValueRole.Argument &&
                target.enclosing.valueIdentity == source.role.call.enclosing.valueIdentity &&
                target.range == source.role.call.range &&
                target.role == ValueRole.ExpressionResult &&
                target.executionOwner == source.role.call.executionOwner
        ValueTransferKind.SOURCE_CALL_ENTRY -> target.role is ValueRole.Formal
        ValueTransferKind.SOURCE_CALL_RETURN ->
            target.role == ValueRole.ExpressionResult ||
                target.role is ValueRole.LoopResult ||
                target.role is ValueRole.StringConversionResult
        ValueTransferKind.FIELD_READ,
        ValueTransferKind.CONSTRUCTOR_FIELD -> target.role == ValueRole.ExpressionResult
    }
