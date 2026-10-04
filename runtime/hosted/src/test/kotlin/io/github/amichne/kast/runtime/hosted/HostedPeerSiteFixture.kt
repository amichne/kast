package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactBoundaryContractDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryKindDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryPositionDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactContentViewDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactModelDocument
import io.github.amichne.kast.protocol.contract.ImpactModelFormatDocument
import io.github.amichne.kast.protocol.contract.ImpactModelIdentifierDocument
import io.github.amichne.kast.protocol.contract.ImpactModelIdentityDocument
import io.github.amichne.kast.protocol.contract.ImpactModelVersionDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument
import io.github.amichne.kast.protocol.contract.QueryImpactDeclarationDocument
import io.github.amichne.kast.protocol.contract.QueryImpactFlowDocument
import io.github.amichne.kast.protocol.contract.QueryImpactProducerDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceDocument
import io.github.amichne.kast.query.contract.QueryImpactRequestedSite
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import io.github.amichne.kast.query.protocol.selectPeerSites
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.RevalidatedValueSite
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path

/** Explicit domain/native-proof starting facts; these fixtures establish no installed native claim. */
internal class HostedPeerSiteFixture {
    val source =
        RelationPagingFixture(
            SemanticReadLease(
                CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/source")).peerValue(),
                EvidenceGeneration.parse(1).peerValue(),
            )
        )
    val peer = RelationPagingFixture.live()
    val authority = peer.authority as LiveSemanticReadAuthority
    val basis = declaration(peer.selector).basis as ImpactSemanticBasisDocument.Live
    val document =
        QueryImpactSourceDocument(
            bounded(listOf(QueryImpactProducerDocument(source.exact, source.exact, range(0, 1)))),
            bounded(
                listOf(
                    QueryImpactDeclarationDocument(source.exact, declaration(source.selector)),
                    QueryImpactDeclarationDocument(peer.exact, declaration(peer.selector)),
                )
            ),
            QueryExpansionScopeDocument.Workspace,
            QueryImpactFlowDocument.KOTLIN_FORWARD_V1,
            bounded(
                listOf(
                    ImpactModelDocument.Boundary(
                        ImpactModelFormatDocument.Current,
                        ImpactModelIdentityDocument(id("wire"), version(), id("review:914")),
                        bounded(
                            listOf(
                                ImpactBoundaryRuleDocument.Continuation(
                                    id("link"),
                                    position(source.selector, ImpactValueRoleDocument.ExpressionResult),
                                    position(peer.selector, ImpactValueRoleDocument.PropertyAssignment),
                                    bounded(emptyList()),
                                )
                            )
                        ),
                    )
                )
            ),
        )
    val selected
        get() = document.selectPeerSites(source.authority).peerValue().single()

    fun twoSelections(): List<io.github.amichne.kast.query.protocol.QueryImpactPeerSelection> {
        val model = document.models.values.single() as ImpactModelDocument.Boundary
        val original = model.rules.values.single() as ImpactBoundaryRuleDocument.Continuation
        val second =
            original.copy(
                id = id("link-two"),
                target = original.target.copy(site = original.target.site.copy(range = range(2, 3))),
            )
        return document
            .copy(models = bounded(listOf(model.copy(rules = bounded(listOf(original, second))))))
            .selectPeerSites(source.authority)
            .peerValue()
    }

    fun nativeSelection(grant: RelationBudget, start: Int = 0): QueryImpactRequestedSite {
        val anchor = ExactDeclarationTextRange.parse(start, start + 1).peerValue()
        val request =
            ValueSiteRevalidationRequest.create(
                    peer.selector,
                    anchor,
                    ValueSiteRoleClaim.PropertyAssignment,
                    grant,
                )
                .peerValue()
        val site =
            ValueSite.fromCompiler(RelationEndpoint.subject(peer.selector), anchor, ValueRole.PropertyAssignment)
                .peerValue()
        return QueryImpactRequestedSite.admit(
                request,
                RevalidatedValueSite.fromCompiler(request, site).peerValue(),
                RelationWorkCount.parse(1).peerValue(),
            )
            .peerValue()
    }

    private fun position(selector: SymbolSelector, role: ImpactValueRoleDocument) =
        ImpactBoundaryPositionDocument(
            ImpactValueSiteReferenceDocument(declaration(selector), range(0, 1), role),
            ImpactBoundaryKindDocument.SERIALIZATION,
            ImpactBoundaryContractDocument(id("payload"), version()),
            id("payload"),
        )

    private fun declaration(selector: SymbolSelector): ImpactDeclarationReferenceDocument {
        val basis =
            when (val identity = selector.lease.identity) {
                is SemanticReadIdentity.Published ->
                    ImpactSemanticBasisDocument.Published(
                        text(identity.workspaceRoot.value),
                        ImpactEvidenceRevisionDocument.parse(identity.lease.generation.value).peerValue(),
                    )
                is SemanticReadIdentity.Live ->
                    ImpactSemanticBasisDocument.Live(
                        text(identity.workspaceRoot.value),
                        text(identity.reference.host.value.toString()),
                        ImpactEvidenceRevisionDocument.parse(identity.reference.epoch.value).peerValue(),
                        ImpactContentViewDocument.SAVED_PSI_COMMITTED,
                        ImpactModelFormatDocument.Current,
                    )
            }
        return ImpactDeclarationReferenceDocument(
            basis,
            text(selector.file.stableValue),
            range(selector.range.startInclusive, selector.range.endExclusive),
            text(selector.compilerIdentity.value),
        )
    }

    private fun version() = ImpactModelVersionDocument.parse(1).peerValue()

    private fun id(value: String) = ImpactModelIdentifierDocument.parse(value).peerValue()

    private fun range(start: Int, end: Int) =
        ImpactSourceRangeDocument(
            ProtocolOffset.parse(start).peerValue(),
            ProtocolOffset.parse(end).peerValue(),
        )

    private fun text(value: String) = ProtocolText.parse(value).peerValue()

    private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).peerValue()
}

internal fun <V, F> Refinement<V, F>.peerValue(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Fixture rejected: $failure")
    }
