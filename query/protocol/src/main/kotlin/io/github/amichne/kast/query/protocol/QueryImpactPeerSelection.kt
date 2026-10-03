package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.AdmittedImpactModelSyntax
import io.github.amichne.kast.protocol.contract.ImpactBoundaryRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactModelDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.QueryImpactDeclarationDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureCode
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import java.util.Collections

/** Routing syntax only. A peer's completed read must independently prove this exact site and basis. */
class QueryImpactPeerSelection
private constructor(
    val expectedBasis: ImpactSemanticBasisDocument.Live,
    val site: ImpactValueSiteReferenceDocument,
    declarations: List<QueryImpactDeclarationDocument>,
    val modelPosition: ProtocolOffset,
    val rulePosition: ProtocolOffset,
) {
    val declarations: List<QueryImpactDeclarationDocument> = Collections.unmodifiableList(declarations.toList())

    companion object {
        internal fun select(
            source: QueryImpactSourceDocument,
            lease: SemanticReadAuthority,
        ): Refinement<List<QueryImpactPeerSelection>, QueryRunRejection> {
            when (val admitted = source.admitCountBounds()) {
                is Refinement.Rejected -> return admitted
                is Refinement.Refined -> Unit
            }
            when (
                val admitted =
                    source.admitSiteClaims(
                        lease,
                        source.requestedSites.values,
                        QueryImpactSourceFailureCode.BASIS_MISMATCH,
                    )
            ) {
                is Refinement.Rejected -> return admitted
                is Refinement.Refined -> Unit
            }
            val peers = mutableListOf<QueryImpactPeerSelection>()
            for ((modelIndex, model) in source.models.values.withIndex()) {
                when (val admitted = AdmittedImpactModelSyntax.admit(model)) {
                    is Refinement.Rejected -> return impactFailure(admitted.failure.impactFailure(), modelIndex)
                    is Refinement.Refined -> Unit
                }
                when (val selected = selectModel(source, lease, model, modelIndex)) {
                    is Refinement.Rejected -> return selected
                    is Refinement.Refined -> appendDistinct(peers, selected.value)
                }
            }
            when (val admitted = admitInventory(source, lease, peers)) {
                is Refinement.Rejected -> return admitted
                is Refinement.Refined -> Unit
            }
            return Refinement.Refined(Collections.unmodifiableList(peers.toList()))
        }

        private fun appendDistinct(
            peers: MutableList<QueryImpactPeerSelection>,
            selected: List<QueryImpactPeerSelection>,
        ) {
            for (peer in selected) if (peers.none { it.site == peer.site }) peers += peer
        }

        private fun selectModel(
            source: QueryImpactSourceDocument,
            lease: SemanticReadAuthority,
            model: ImpactModelDocument,
            position: Int,
        ): Refinement<List<QueryImpactPeerSelection>, QueryRunRejection> =
            when (model) {
                is ImpactModelDocument.Representation -> {
                    val claims = model.rules.values.flatMap { it.declarationClaims() }
                    if (claims.any { !it.basis.matchesBasis(lease.identity) })
                        impactFailure(QueryImpactSourceFailureCode.RULE_BASIS_MISMATCH, position)
                    else Refinement.Refined(emptyList())
                }
                is ImpactModelDocument.Boundary -> selectBoundaryTargets(source, lease, model, position)
            }

        private fun selectBoundaryTargets(
            source: QueryImpactSourceDocument,
            lease: SemanticReadAuthority,
            model: ImpactModelDocument.Boundary,
            position: Int,
        ): Refinement<List<QueryImpactPeerSelection>, QueryRunRejection> {
            val peers = mutableListOf<QueryImpactPeerSelection>()
            for ((ruleIndex, rule) in model.rules.values.withIndex()) {
                val sourceSite =
                    when (rule) {
                        is ImpactBoundaryRuleDocument.Terminal -> rule.source.site
                        is ImpactBoundaryRuleDocument.Continuation -> rule.source.site
                    }
                when (
                    val admitted =
                        source.admitSiteClaims(
                            lease,
                            listOf(sourceSite),
                            QueryImpactSourceFailureCode.BOUNDARY_BASIS_MISMATCH,
                        )
                ) {
                    is Refinement.Rejected -> return admitted
                    is Refinement.Refined -> Unit
                }
                when (rule) {
                    is ImpactBoundaryRuleDocument.Terminal -> Unit
                    is ImpactBoundaryRuleDocument.Continuation ->
                        when (val selected = selectContinuation(source, lease, rule, position, ruleIndex)) {
                            is Refinement.Rejected -> return selected
                            is Refinement.Refined -> peers += selected.value
                        }
                }
            }
            return Refinement.Refined(peers.toList())
        }

        private fun selectContinuation(
            source: QueryImpactSourceDocument,
            lease: SemanticReadAuthority,
            rule: ImpactBoundaryRuleDocument.Continuation,
            modelIndex: Int,
            ruleIndex: Int,
        ): Refinement<List<QueryImpactPeerSelection>, QueryRunRejection> {
            val target = rule.target.site
            if (target.enclosing.basis.matchesBasis(lease.identity)) {
                return when (
                    val admitted =
                        source.admitSiteClaims(
                            lease,
                            listOf(target),
                            QueryImpactSourceFailureCode.BOUNDARY_BASIS_MISMATCH,
                        )
                ) {
                    is Refinement.Rejected -> admitted
                    is Refinement.Refined -> Refinement.Refined(emptyList())
                }
            }
            return when (val selected = selectForeignTarget(source, lease, target, modelIndex, ruleIndex)) {
                is Refinement.Rejected -> selected
                is Refinement.Refined -> Refinement.Refined(listOf(selected.value))
            }
        }

        private fun selectForeignTarget(
            source: QueryImpactSourceDocument,
            lease: SemanticReadAuthority,
            target: ImpactValueSiteReferenceDocument,
            modelIndex: Int,
            ruleIndex: Int,
        ): Refinement<QueryImpactPeerSelection, QueryRunRejection> {
            val expected =
                target.enclosing.basis as? ImpactSemanticBasisDocument.Live
                    ?: return impactFailure(QueryImpactSourceFailureCode.BOUNDARY_BASIS_MISMATCH, modelIndex)
            if (
                expected.root.value == lease.workspaceRoot.value ||
                    target.declarationClaims().any { it.basis != expected }
            )
                return impactFailure(QueryImpactSourceFailureCode.BOUNDARY_BASIS_MISMATCH, modelIndex)
            val selected = mutableListOf<QueryImpactDeclarationDocument>()
            for (claim in target.declarationClaims().distinct()) {
                val candidates = source.declarations.values.filter { it.declaration == claim }
                if (candidates.size != 1)
                    return impactFailure(QueryImpactSourceFailureCode.MISSING_DECLARATION, modelIndex)
                selected += candidates.single()
            }
            return Refinement.Refined(
                QueryImpactPeerSelection(
                    expectedBasis = expected,
                    site = target,
                    declarations = selected,
                    modelPosition = queryPosition(modelIndex),
                    rulePosition = queryPosition(ruleIndex),
                )
            )
        }

        private fun admitInventory(
            source: QueryImpactSourceDocument,
            lease: SemanticReadAuthority,
            peers: List<QueryImpactPeerSelection>,
        ): Refinement<Unit, QueryRunRejection> {
            val peerDeclarations = peers.flatMap { it.declarations }.toSet()
            val peerTokens = peerDeclarations.map { it.reference }.toSet()
            if (source.seeds.values.any { it.enclosing in peerTokens || it.callable in peerTokens })
                return impactFailure(QueryImpactSourceFailureCode.BASIS_MISMATCH)
            for ((index, declaration) in source.declarations.values.withIndex()) {
                if (declaration.declaration.basis.matchesBasis(lease.identity)) {
                    if (declaration.reference in peerTokens)
                        return impactFailure(QueryImpactSourceFailureCode.BASIS_MISMATCH, index)
                } else if (declaration !in peerDeclarations)
                    return impactFailure(QueryImpactSourceFailureCode.BOUNDARY_BASIS_MISMATCH, index)
            }
            return Refinement.Refined(Unit)
        }
    }
}

/** Foreign routing is restricted to exact reviewed continuation targets; it grants no semantic authority. */
fun QueryImpactSourceDocument.selectPeerSites(
    lease: SemanticReadAuthority
): Refinement<List<QueryImpactPeerSelection>, QueryRunRejection> = QueryImpactPeerSelection.select(this, lease)

internal fun ImpactValueSiteReferenceDocument.declarationClaims(): List<ImpactDeclarationReferenceDocument> =
    listOf(enclosing) +
        when (val syntax = role) {
            is ImpactValueRoleDocument.Argument -> listOf(syntax.invocation.callable)
            ImpactValueRoleDocument.ExpressionResult,
            ImpactValueRoleDocument.LocalBinding,
            ImpactValueRoleDocument.LocalRead,
            ImpactValueRoleDocument.Return,
            ImpactValueRoleDocument.PropertyAssignment -> emptyList()
        }

private fun ImpactRepresentationRuleDocument.declarationClaims(): List<ImpactDeclarationReferenceDocument> =
    when (this) {
        is ImpactRepresentationRuleDocument.Origin -> listOf(output.declaration)
        is ImpactRepresentationRuleDocument.Transfer -> listOf(input.declaration, output.declaration)
        is ImpactRepresentationRuleDocument.Transformation -> listOf(input.declaration, output.declaration)
        is ImpactRepresentationRuleDocument.ConsumerExpectation -> listOf(input.declaration)
    }
