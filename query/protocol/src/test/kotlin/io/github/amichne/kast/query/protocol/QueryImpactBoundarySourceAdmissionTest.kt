package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactBoundaryCompatibilityDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryContractDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryKindDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryPositionDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryTerminalDocument
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
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureCode
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.relation.contract.BoundaryKind
import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.BoundaryTerminalMeaning
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint
import io.github.amichne.kast.relation.contract.RevalidatedValueSite
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueModelDeclarationRead
import io.github.amichne.kast.relation.contract.ValueModelSiteRead
import io.github.amichne.kast.relation.contract.ValueProducerSeed
import io.github.amichne.kast.relation.contract.ValueProducerSeedCompilerPort
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Detached native observations prove public admission and grants, not installed role semantics. */
class QueryImpactBoundarySourceAdmissionTest {
    @Test
    fun `fresh exact boundary positions attach supplied contracts and bind once on shared grants`() = runTest {
        val f = Fixture()
        val native = f.native()
        val admitted = f.document().admitImpact(f.owner.lease, f.references, native, f.budget).refined()
        assertEquals(listOf(10L, 7L, 6L, 4L), native.grants)
        assertEquals(8L, admitted.examinedWork)
        assertEquals(listOf(f.range(20, 35), f.range(60, 75)), native.positions)
        val rules = admitted.source.boundaryModels
        assertEquals(listOf("wire", "dispose"), rules.map { it.reference.rule.value })
        val continuation = rules[0] as BoundaryModel.Continuation
        assertEquals(f.owner.file, continuation.source.site.enclosing.file)
        assertEquals(f.range(60, 75), continuation.target.site.range)
        assertEquals(BoundaryKind.SERIALIZATION, continuation.source.kind)
        assertEquals("payload-format", continuation.source.contract.id.value)
        assertEquals("payload", continuation.source.slot.value)
        assertEquals("review:boundary", continuation.reference.model.provenance.value)
        assertEquals(BoundaryTerminalMeaning.REVIEWED_DISPOSAL, (rules[1] as BoundaryModel.Terminal).meaning)
        native.assertConsumed()
    }

    @Test
    fun `foreign boundary basis rejects before producer or model compiler work`() = runTest {
        val f = Fixture()
        val source = f.position(20, 35)
        val foreign =
            source.copy(
                site =
                    source.site.copy(
                        enclosing =
                            source.site.enclosing.copy(
                                basis =
                                    ImpactSemanticBasisDocument.Published(
                                        f.text("/client"),
                                        ImpactEvidenceRevisionDocument.parse(7).refined(),
                                    ),
                                file = f.text("/client/File.kt"),
                            )
                    )
            )
        val native = f.native()
        val result = f.document(foreign).admitImpact(f.owner.lease, f.references, native, f.budget).failure()
        assertEquals(QueryImpactSourceFailureCode.BOUNDARY_BASIS_MISMATCH, result.code())
        assertEquals(emptyList<Long>(), native.grants)
    }

    @Test
    fun `native unsupported boundary position retains finite failure and stops admission`() = runTest {
        val f = Fixture()
        val native = f.native(rejection = ValueFlowRejection.UNSUPPORTED_SEED)
        val result = f.document().admitImpact(f.owner.lease, f.references, native, f.budget).failure()
        assertEquals(QueryImpactSourceFailureCode.UNSUPPORTED_BOUNDARY_SITE, result.code())
        assertEquals(listOf(10L, 7L, 6L), native.grants)
        assertEquals(listOf(f.range(20, 35)), native.positions)
    }

    private class Fixture {
        val owner = endpoint("investigate", 0, 200)
        private val callable = endpoint("produce", 210, 250)
        val references = CanonicalQueryReferences()
        val budget =
            QueryBudget(
                ResourceBudget(
                    ResultLimit.parse(20).refined(),
                    WorkUnitLimit.parse(10).refined(),
                    ElapsedTimeLimitMillis.parse(1000).refined(),
                ),
                QueryByteLimit.parse(100000).refined(),
            )

        fun document(source: ImpactBoundaryPositionDocument = position(20, 35)): QueryImpactSourceDocument {
            val identity =
                ImpactModelIdentityDocument(
                    id("wire-model"),
                    ImpactModelVersionDocument.parse(1).refined(),
                    id("review:boundary"),
                )
            val model =
                ImpactModelDocument.Boundary(
                    ImpactModelFormatDocument.Current,
                    identity,
                    bounded(
                        listOf(
                            ImpactBoundaryRuleDocument.Continuation(
                                id("wire"),
                                source,
                                position(60, 75),
                                bounded(listOf(ImpactBoundaryCompatibilityDocument.REPRESENTATION_PRESERVED)),
                            ),
                            ImpactBoundaryRuleDocument.Terminal(
                                id("dispose"),
                                source,
                                ImpactBoundaryTerminalDocument.REVIEWED_DISPOSAL,
                            ),
                        )
                    ),
                )
            return QueryImpactSourceDocument(
                bounded(listOf(QueryImpactProducerDocument(token(owner), token(callable), anchor(20, 35)))),
                bounded(listOf(QueryImpactDeclarationDocument(token(owner), declaration(owner)))),
                QueryExpansionScopeDocument.Workspace,
                QueryImpactFlowDocument.KOTLIN_FORWARD_V1,
                bounded(listOf(model)),
            )
        }

        fun position(start: Int, end: Int) =
            ImpactBoundaryPositionDocument(
                ImpactValueSiteReferenceDocument(
                    declaration(owner),
                    anchor(start, end),
                    ImpactValueRoleDocument.ExpressionResult,
                ),
                ImpactBoundaryKindDocument.SERIALIZATION,
                ImpactBoundaryContractDocument(id("payload-format"), ImpactModelVersionDocument.parse(2).refined()),
                id("payload"),
            )

        fun native(rejection: ValueFlowRejection? = null) = Native(rejection)

        inner class Native(private val rejection: ValueFlowRejection?) : ValueProducerSeedCompilerPort {
            val grants = mutableListOf<Long>()
            val positions = mutableListOf<ExactDeclarationTextRange>()
            private var seeds = 0
            private var declarations = 0

            override suspend fun seed(request: ValueProducerSeedRequest): ValueProducerSeedRead {
                assertEquals(0, seeds++)
                assertEquals(range(20, 35), request.anchor)
                grants += request.budget.resources.workUnitLimit.value
                val invocation = ValueInvocation.fromCompiler(owner, range(20, 35), callable).refined()
                return ValueProducerSeedRead.Seeded(
                    ValueProducerSeed.fromCompiler(request, invocation.resultSite(), invocation).refined(),
                    work(3),
                )
            }

            override suspend fun revalidate(
                selector: SymbolSelector,
                budget: RelationBudget,
            ): ValueModelDeclarationRead {
                assertEquals(0, declarations++)
                assertEquals(owner.file, selector.file)
                grants += budget.resources.workUnitLimit.value
                return ValueModelDeclarationRead.Revalidated(
                    RevalidatedRelationEndpoint.validate(owner, owner.evidence).refined(),
                    work(1),
                )
            }

            override suspend fun revalidateSite(request: ValueSiteRevalidationRequest): ValueModelSiteRead {
                assertTrue(positions.size < 2, "unexpected native position")
                val expected = listOf(range(20, 35), range(60, 75))[positions.size]
                assertEquals(expected, request.anchor)
                assertEquals(ValueSiteRoleClaim.ExpressionResult, request.role)
                grants += request.budget.resources.workUnitLimit.value
                positions += expected
                if (rejection != null) return ValueModelSiteRead.Rejected(rejection)
                val site = ValueSite.fromCompiler(owner, expected, ValueRole.ExpressionResult).refined()
                return ValueModelSiteRead.Revalidated(
                    RevalidatedValueSite.fromCompiler(request, site).refined(),
                    work(2),
                )
            }

            fun assertConsumed() {
                assertEquals(1, seeds)
                assertEquals(1, declarations)
                assertEquals(2, positions.size)
            }
        }

        private fun token(endpoint: RelationEndpoint.Resolved) =
            (references.issueExact(SymbolSelector.issue(endpoint.lease, endpoint.scope, endpoint.evidence))
                    as ExactSelectorIssuance.Issued)
                .selector

        private fun declaration(endpoint: RelationEndpoint.Resolved) =
            ImpactDeclarationReferenceDocument(
                ImpactSemanticBasisDocument.Published(
                    text("/workspace"),
                    ImpactEvidenceRevisionDocument.parse(7).refined(),
                ),
                text(endpoint.file.stableValue),
                anchor(endpoint.range.startInclusive, endpoint.range.endExclusive),
                text(endpoint.compilerIdentity.value),
            )

        private fun endpoint(name: String, start: Int, end: Int): RelationEndpoint.Resolved {
            val lease =
                SemanticReadLease(
                    CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
                    EvidenceGeneration.parse(7).refined(),
                )
            val file =
                (SymbolDiscoveryCandidate.fromBoundary(
                            SymbolDiscoveryKind.SYMBOL,
                            name,
                            lease,
                            Path.of("/workspace/File.kt"),
                            "file:///workspace/File.kt",
                            start,
                        )
                        .refined()
                        .location as SymbolDiscoveryCandidateLocation.Declaration)
                    .file
            val evidence =
                CompilerGroundedSymbolEvidence.fromBoundary(
                        file,
                        start,
                        end,
                        name,
                        "fixture.$name",
                        CompilerSymbolKind.FUNCTION,
                        CanonicalCompilerSignature.function("fixture.$name", null, emptyList(), emptyList(), 0)
                            .refined(),
                    )
                    .refined()
            return RelationEndpoint.resolve(
                    lease,
                    SymbolSearchScope.Workspace(
                        SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                        SymbolGeneratedSourcePolicy.EXCLUDE,
                        SymbolLibraryPolicy.EXCLUDE,
                    ),
                    evidence,
                )
                .refined() as RelationEndpoint.Resolved
        }

        fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).refined()

        private fun anchor(start: Int, end: Int) =
            ImpactSourceRangeDocument(ProtocolOffset.parse(start).refined(), ProtocolOffset.parse(end).refined())

        private fun work(value: Long) = RelationWorkCount.parse(value).refined()

        private fun id(value: String) = ImpactModelIdentifierDocument.parse(value).refined()

        fun text(value: String) = ProtocolText.parse(value).refined()

        private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).refined()
    }

    private fun QueryRunRejection.code() =
        ((this as QueryRunRejection.ImpactSourceRejected).cause as QueryImpactSourceFailureDocument.Admission).cause

    companion object {
        private fun <V, F> Refinement<V, F>.refined(): V =
            when (this) {
                is Refinement.Refined -> value
                is Refinement.Rejected -> error(failure.toString())
            }

        private fun <V, F> Refinement<V, F>.failure(): F =
            when (this) {
                is Refinement.Refined -> error("expected rejection")
                is Refinement.Rejected -> failure
            }
    }
}
