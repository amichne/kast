package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueProducerSeedRejection
import io.github.amichne.kast.symbol.contract.SymbolSelector

internal data class NativeProducerInvocation(
    val callable: RelationEndpoint,
    val expression: PsiElement,
    val owner: ValueExecutionOwner,
    val captureWork: Long,
)

internal fun revalidateProducerDeclaration(
    native: IntellijValueFlowCompilerAdapter.Prepared.Ready,
    declaration: PsiNamedElement,
    selector: SymbolSelector,
): Refinement<RevalidatedRelationEndpoint, ValueProducerSeedRejection> =
    when (val projected = native.projection.project(declaration)) {
        is IntellijRelationDeclarationProjection.Projected ->
            when (
                val proof = RevalidatedRelationEndpoint.validate(RelationEndpoint.subject(selector), projected.evidence)
            ) {
                is Refinement.Refined -> proof
                is Refinement.Rejected -> Refinement.Rejected(ValueProducerSeedRejection.CALLABLE_MISMATCH)
            }
        IntellijRelationDeclarationProjection.Unsupported ->
            Refinement.Rejected(ValueProducerSeedRejection.UNRESOLVED_INVOCATION)
    }
