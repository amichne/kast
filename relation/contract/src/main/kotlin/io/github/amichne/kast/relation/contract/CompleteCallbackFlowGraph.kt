package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import java.util.Collections

/** Complete static owner routes, independently of a particular target occurrence or named-call projection. */
internal class CompleteCallbackFlowGraph
private constructor(
    val root: CompilerGroundedSymbolEvidence,
    val edges: List<StaticCallbackEdge>,
) {
    companion object {
        fun admit(flow: CallbackInvocationFlow): Refinement<CompleteCallbackFlowGraph, StaticCallbackGraphFailure> {
            val composition = CallbackFlowComposition()
            return when (val root = composition.append(flow)) {
                is Refinement.Rejected -> root
                is Refinement.Refined ->
                    Refinement.Refined(
                        CompleteCallbackFlowGraph(
                            root.value,
                            Collections.unmodifiableList(composition.edges.distinct()),
                        )
                    )
            }
        }

        fun admit(
            flow: ImmutableCallbackInvocationFlow,
            owner: RelationCallableBody.Named,
        ): Refinement<CompleteCallbackFlowGraph, StaticCallbackGraphFailure> {
            if (!flow.source.enclosing.matchesCallbackRoot(owner.evidence))
                return Refinement.Rejected(StaticCallbackGraphFailure.SupplierIdentityMismatch)
            val composition = CallbackFlowComposition()
            return when (val admitted = composition.appendImmutable(flow)) {
                is Refinement.Rejected -> admitted
                is Refinement.Refined ->
                    Refinement.Refined(
                        CompleteCallbackFlowGraph(
                            owner.evidence,
                            Collections.unmodifiableList(composition.edges.distinct()),
                        )
                    )
            }
        }

        fun admitImmutableOwners(flow: ImmutableCallbackInvocationFlow): Refinement<Unit, StaticCallbackGraphFailure> =
            CallbackFlowComposition().appendImmutable(flow)
    }
}

internal fun RelationEndpoint.matchesCallbackRoot(evidence: CompilerGroundedSymbolEvidence): Boolean =
    file == evidence.file && range == evidence.range && compilerIdentity == evidence.compilerIdentity
