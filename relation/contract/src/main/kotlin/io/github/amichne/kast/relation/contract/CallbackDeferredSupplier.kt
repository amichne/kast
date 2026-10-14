package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerCallableSignature
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity

enum class CallbackAccessorKind {
    GETTER,
    SETTER,
}

/** Execution context remains distinct from its enclosing named declaration. */
sealed interface CallbackDeferredExecution {
    data object Declaration : CallbackDeferredExecution

    data class Accessor internal constructor(val kind: CallbackAccessorKind, val occurrence: RelationOccurrence) :
        CallbackDeferredExecution

    data class Initializer internal constructor(val occurrence: RelationOccurrence) : CallbackDeferredExecution

    data class Invoked internal constructor(val route: CallbackDirectExecutionRoute) : CallbackDeferredExecution
}

/** A nonempty compiler-confirmed route starts in a named callable and preserves each direct anonymous invocation. */
class CallbackDirectExecutionRoute
private constructor(
    val owner: RelationCallableBody.Named,
    val steps: List<Step>,
) {
    class Step
    internal constructor(val binding: CallbackDirectInvocationBinding, val body: RelationCallableBody.Anonymous)

    val basis = steps.first().binding.basis
    val terminal = steps.last().body

    fun append(
        binding: CallbackDirectInvocationBinding,
        body: RelationCallableBody.Anonymous,
    ): Refinement<CallbackDirectExecutionRoute, CallbackInvocationFlowCause> {
        if (binding.basis != basis || binding.owner != terminal || !binding.occurrence.containsBody(body))
            return Refinement.Rejected(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION)
        if (steps.any { it.body == body })
            return Refinement.Rejected(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION)
        return Refinement.Refined(
            CallbackDirectExecutionRoute(owner, java.util.Collections.unmodifiableList(steps + Step(binding, body)))
        )
    }

    companion object {
        fun start(
            binding: CallbackDirectInvocationBinding,
            body: RelationCallableBody.Anonymous,
        ): Refinement<CallbackDirectExecutionRoute, CallbackInvocationFlowCause> {
            val owner =
                binding.owner as? RelationCallableBody.Named
                    ?: return Refinement.Rejected(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION)
            if (!binding.occurrence.containsBody(body))
                return Refinement.Rejected(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION)
            return Refinement.Refined(CallbackDirectExecutionRoute(owner, listOf(Step(binding, body))))
        }
    }
}

/** Admission preserves the exact supplier, execution context, source identity, and semantic read basis. */
class CallbackDeferredSupplier
private constructor(
    val basis: SemanticReadIdentity,
    val occurrence: RelationOccurrence,
    val evidence: CompilerGroundedSymbolEvidence,
    val execution: CallbackDeferredExecution,
) {
    val file = evidence.file
    val range = evidence.range

    companion object {
        fun fromCompiler(
            basis: SemanticReadIdentity,
            occurrence: RelationOccurrence,
            evidence: CompilerGroundedSymbolEvidence,
        ): Refinement<CallbackDeferredSupplier, CallbackInvocationFlowCause> {
            if (
                evidence.signature !is CanonicalCompilerCallableSignature &&
                    evidence.signature !is CanonicalCompilerSignature.Property
            )
                return rejected()
            return admit(basis, occurrence, evidence, CallbackDeferredExecution.Declaration)
        }

        fun accessor(
            basis: SemanticReadIdentity,
            occurrence: RelationOccurrence,
            evidence: CompilerGroundedSymbolEvidence,
            kind: CallbackAccessorKind,
            accessor: RelationOccurrence,
        ): Refinement<CallbackDeferredSupplier, CallbackInvocationFlowCause> {
            if (
                evidence.signature !is CanonicalCompilerSignature.Property ||
                    !accessor.contains(occurrence) ||
                    !evidence.contains(accessor)
            )
                return rejected()
            return admit(basis, occurrence, evidence, CallbackDeferredExecution.Accessor(kind, accessor))
        }

        fun initializer(
            basis: SemanticReadIdentity,
            occurrence: RelationOccurrence,
            evidence: CompilerGroundedSymbolEvidence,
            initializer: RelationOccurrence,
        ): Refinement<CallbackDeferredSupplier, CallbackInvocationFlowCause> {
            if (
                evidence.signature !is CanonicalCompilerSignature.ClassLike ||
                    !initializer.contains(occurrence) ||
                    !evidence.contains(initializer)
            )
                return rejected()
            return admit(basis, occurrence, evidence, CallbackDeferredExecution.Initializer(initializer))
        }

        fun invoked(
            occurrence: RelationOccurrence,
            route: CallbackDirectExecutionRoute,
        ): Refinement<CallbackDeferredSupplier, CallbackInvocationFlowCause> {
            if (occurrence.file != route.terminal.file || !route.terminal.range.containsValueRange(occurrence.range))
                return rejected()
            return admit(route.basis, occurrence, route.owner.evidence, CallbackDeferredExecution.Invoked(route))
        }

        private fun admit(
            basis: SemanticReadIdentity,
            occurrence: RelationOccurrence,
            evidence: CompilerGroundedSymbolEvidence,
            execution: CallbackDeferredExecution,
        ): Refinement<CallbackDeferredSupplier, CallbackInvocationFlowCause> =
            if (evidence.contains(occurrence))
                Refinement.Refined(CallbackDeferredSupplier(basis, occurrence, evidence, execution))
            else rejected()

        private fun rejected(): Refinement.Rejected<CallbackInvocationFlowCause> =
            Refinement.Rejected(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
    }
}

private fun CompilerGroundedSymbolEvidence.contains(occurrence: RelationOccurrence) =
    file == occurrence.file && range.containsValueRange(occurrence.range)

private fun RelationOccurrence.contains(other: RelationOccurrence) =
    file == other.file && range.containsValueRange(other.range)

private fun RelationOccurrence.containsBody(body: RelationCallableBody.Anonymous) =
    file == body.file && range.containsValueRange(body.range)
