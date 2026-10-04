package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import java.util.Collections

/** The deliberately finite first compiler-supported transfer domain. */
enum class ValueTransferKind {
    LOCAL_BINDING,
    LOCAL_READ,
    ARGUMENT,
    RETURN,
    PROPERTY_ASSIGNMENT,
    BRANCH_ALTERNATIVE,
    WRAPPER_RETURN,
}

enum class ValueTransferFailure {
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
) {
    companion object {
        /** The native adapter owns semantic confirmation; this constructor preserves its detached invariants. */
        fun fromCompiler(
            source: ValueSite,
            target: ValueSite,
            kind: ValueTransferKind,
        ): Refinement<ValueTransfer, ValueTransferFailure> {
            if (source.basis != target.basis) return Refinement.Rejected(ValueTransferFailure.BASIS_MISMATCH)
            if (source.identity == target.identity) return Refinement.Rejected(ValueTransferFailure.SELF_EDGE)
            val rolesMatch =
                when (kind) {
                    ValueTransferKind.LOCAL_BINDING -> target.role == ValueRole.LocalBinding
                    ValueTransferKind.LOCAL_READ ->
                        source.role == ValueRole.LocalBinding && target.role == ValueRole.LocalRead
                    ValueTransferKind.ARGUMENT -> target.role is ValueRole.Argument
                    ValueTransferKind.RETURN -> target.role == ValueRole.Return
                    ValueTransferKind.PROPERTY_ASSIGNMENT -> target.role == ValueRole.PropertyAssignment
                    ValueTransferKind.BRANCH_ALTERNATIVE -> target.role == ValueRole.ExpressionResult
                    ValueTransferKind.WRAPPER_RETURN ->
                        source.role is ValueRole.Argument && target == source.role.call.resultSite()
                }
            return if (rolesMatch) Refinement.Refined(ValueTransfer(source, target, kind))
            else Refinement.Rejected(ValueTransferFailure.ROLE_MISMATCH)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ValueTransfer && source == other.source && target == other.target && kind == other.kind

    override fun hashCode(): Int = 31 * (31 * source.hashCode() + target.hashCode()) + kind.hashCode()
}

enum class ValueFlowUnsupportedCause {
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
}

/** A boundary is positive arrival evidence, never proof of absence beyond it. */
data class ValueFlowObligation(val site: ValueSite, val cause: ValueFlowUnsupportedCause)

/** A single exact input must account for every discovered supported output and every unsupported arrival. */
class ValueFlowStep
private constructor(
    val source: ValueSite,
    val domain: RelationRequest,
    val transfers: List<ValueTransfer>,
    val obligations: List<ValueFlowObligation>,
    val terminal: ValueFlowTerminal,
    val examinedWorkUnits: RelationWorkCount,
) {
    val retainedBytes: Long = detachedByteCount(source, domain, transfers, obligations).value

    companion object {
        /** Conservative detached capacity; final wire bytes remain the query presentation owner's budget. */
        fun detachedByteCount(
            source: ValueSite,
            domain: RelationRequest,
            transfers: List<ValueTransfer>,
            obligations: List<ValueFlowObligation>,
        ): RelationByteCount = valueFlowStorageBytes(source, domain, transfers, obligations)

        fun fromCompiler(
            source: ValueSite,
            transfers: List<ValueTransfer>,
            obligations: List<ValueFlowObligation>,
            terminal: ValueFlowTerminal,
            domain: RelationRequest,
            examinedWorkUnits: RelationWorkCount,
        ): Refinement<ValueFlowStep, ValueFlowStepFailure> {
            if (transfers.size > domain.budget.resources.resultLimit.value)
                return Refinement.Rejected(ValueFlowStepFailure.RESULT_LIMIT_EXCEEDED)
            if (detachedByteCount(source, domain, transfers, obligations).value > domain.budget.returnedBytes.value)
                return Refinement.Rejected(ValueFlowStepFailure.DETACHED_CAPACITY_EXCEEDED)
            if (examinedWorkUnits.value > domain.budget.resources.workUnitLimit.value)
                return Refinement.Rejected(ValueFlowStepFailure.WORK_LIMIT_EXCEEDED)
            if (
                domain.subject.lease.identity != source.basis ||
                    ValueDeclarationIdentity(
                        domain.subject.compilerIdentity,
                        domain.subject.file,
                        domain.subject.range,
                    ) != source.identity.owner
            )
                return Refinement.Rejected(ValueFlowStepFailure.DOMAIN_MISMATCH)
            if (transfers.any { it.source != source }) return Refinement.Rejected(ValueFlowStepFailure.SOURCE_MISMATCH)
            if (obligations.any { it.site.basis != source.basis })
                return Refinement.Rejected(ValueFlowStepFailure.BASIS_MISMATCH)
            if (obligations.any { it.site != source }) return Refinement.Rejected(ValueFlowStepFailure.SOURCE_MISMATCH)
            when (val admitted = terminal.validateObligations(obligations)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return admitted
            }
            return Refinement.Refined(
                ValueFlowStep(
                    source,
                    domain,
                    Collections.unmodifiableList(transfers.toList()),
                    Collections.unmodifiableList(obligations.toList()),
                    terminal,
                    examinedWorkUnits,
                )
            )
        }
    }
}

enum class ValueFlowTerminal {
    SupportedDomainExhausted,
    Unresolved,
}

enum class ValueFlowStepFailure {
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
data class ValueFlowRequest(val source: ValueSite, val budget: RelationBudget, val boundary: RelationSearchBoundary)

private fun ValueFlowTerminal.validateObligations(
    obligations: List<ValueFlowObligation>
): Refinement<Unit, ValueFlowStepFailure> =
    when (this) {
        ValueFlowTerminal.SupportedDomainExhausted ->
            if (obligations.isEmpty()) Refinement.Refined(Unit)
            else Refinement.Rejected(ValueFlowStepFailure.UNRESOLVED_OBLIGATIONS)
        ValueFlowTerminal.Unresolved ->
            if (obligations.isNotEmpty()) Refinement.Refined(Unit)
            else Refinement.Rejected(ValueFlowStepFailure.MISSING_OBLIGATION)
    }
