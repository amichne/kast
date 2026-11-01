package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import java.util.Collections

/** Complete static owner routes, independently of a particular target occurrence or named-call projection. */
class CompleteCallbackFlowGraph
private constructor(
    val root: CompilerGroundedSymbolEvidence,
    val edges: List<StaticCallbackEdge>,
    val basis: io.github.amichne.kast.workspace.contract.SemanticReadIdentity,
) {
    companion object {
        /** Each alternative already proves its own source route on the same root and basis. */
        fun combine(
            alternatives: List<CompleteCallbackFlowGraph>
        ): Refinement<CompleteCallbackFlowGraph, StaticCallbackGraphFailure> {
            val first =
                alternatives.firstOrNull() ?: return Refinement.Rejected(StaticCallbackGraphFailure.MissingNamedOwner)
            if (
                alternatives.any {
                    it.basis != first.basis || it.root != first.root
                }
            )
                return Refinement.Rejected(StaticCallbackGraphFailure.SupplierIdentityMismatch)
            return Refinement.Refined(
                CompleteCallbackFlowGraph(
                    first.root,
                    Collections.unmodifiableList(alternatives.flatMap { it.edges }.distinct()),
                    first.basis,
                )
            )
        }

        fun admit(flow: CallbackInvocationFlow): Refinement<CompleteCallbackFlowGraph, StaticCallbackGraphFailure> {
            val composition = CallbackFlowComposition()
            return when (val root = composition.append(flow)) {
                is Refinement.Rejected -> root
                is Refinement.Refined ->
                    Refinement.Refined(
                        CompleteCallbackFlowGraph(
                            root.value,
                            Collections.unmodifiableList(composition.edges.distinct()),
                            flow.basis,
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
                            flow.basis,
                        )
                    )
            }
        }

        internal fun admitImmutableOwners(
            flow: ImmutableCallbackInvocationFlow
        ): Refinement<Unit, StaticCallbackGraphFailure> = CallbackFlowComposition().appendImmutable(flow)
    }
}

internal fun RelationEndpoint.matchesCallbackRoot(evidence: CompilerGroundedSymbolEvidence): Boolean =
    file == evidence.file && range == evidence.range && compilerIdentity == evidence.compilerIdentity
