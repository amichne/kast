package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.relation.contract.ContractModelIdentity
import io.github.amichne.kast.relation.contract.ExactModelCallablePosition
import io.github.amichne.kast.relation.contract.ModelCallableReference
import io.github.amichne.kast.relation.contract.ModelIdentifier
import io.github.amichne.kast.relation.contract.ModelRuleReference
import io.github.amichne.kast.relation.contract.ModelValuePosition
import io.github.amichne.kast.relation.contract.ModelVersion
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.RepresentationDomain
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryValuePathRetentionTest {
    @Test
    fun `connected terminal paths cannot construct complete investigation without an expansion ledger`() {
        val fixture = Fixture()
        val result = QueryResult(QueryRows.ValuePaths.of(listOf(fixture.path())), emptyList())
        assertEquals(
            QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION),
            QueryExecutionResult.Complete.create(result, QueryCoverage.Complete(count(1))),
        )
        assertEquals(
            QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION),
            QueryExecutionResult.Complete.create(
                result.copy(rows = QueryRows.ValuePaths.of(emptyList())),
                QueryCoverage.Complete(count(0)),
            ),
        )
    }

    @Test
    fun `qualified paths snapshot rows and selection preserves exact route and qualification`() {
        val fixture = Fixture()
        val original = fixture.path()
        val other = fixture.path(20)
        val callerRows = mutableListOf(original, other)
        val rows = QueryRows.ValuePaths.of(callerRows)
        callerRows.clear()
        val retained =
            assertInstanceOf(
                QueryRetainedResult.ValuePaths::class.java,
                QueryRetainedResult.capture(fixture.lease, qualified(rows)).refined(),
            )
        assertEquals(listOf(original, other), retained.valuePaths)
        assertThrows(UnsupportedOperationException::class.java) {
            (retained.valuePaths as MutableList<QueryImpactPath>).clear()
        }
        val selected = retained.selectRows(listOf(1)).refined()
        assertSame(other, selected.valuePaths.single())
        assertEquals(
            setOf(QueryLimitation.ROW_SELECTION_INCOMPLETE, QueryLimitation.RELATION_INCOMPLETE),
            (selected.coverage as QueryCoverage.Qualified).limitations.toSet(),
        )
        assertEquals(Refinement.Rejected(QueryRetainedResultFailure.UNKNOWN_ROW), retained.selectRows(listOf(2)))
        assertEquals(Refinement.Rejected(QueryRetainedResultFailure.DUPLICATE_ROW), retained.selectRows(listOf(0, 0)))
        assertEquals(Refinement.Rejected(QueryMembershipFailure.INCOMPLETE), retained.completeMembership())
    }

    @Test
    fun `foreign value path basis rejects capture and exact payload growth consumes retention bytes`() {
        val fixture = Fixture()
        val path = fixture.path()
        val foreign = SemanticReadLease(fixture.lease.workspaceRoot, EvidenceGeneration.parse(2).refined())
        assertEquals(
            Refinement.Rejected(QueryRetainedResultFailure.BASIS_MISMATCH),
            QueryRetainedResult.capture(foreign, qualified(QueryRows.ValuePaths.of(listOf(path)))),
        )
        val short =
            QueryRetainedResult.capture(fixture.lease, qualified(QueryRows.ValuePaths.of(listOf(path)))).refined()
        val longFixture = Fixture(qualifiedName = "owner." + "x".repeat(900))
        val long =
            QueryRetainedResult.capture(
                    longFixture.lease,
                    qualified(QueryRows.ValuePaths.of(listOf(longFixture.path()))),
                )
                .refined()
        assertTrue(long.retainedBytes > short.retainedBytes + 2000)
    }

    @Test
    fun `discharged full ledger admits complete while selection retains the ledger with weaker rows`() {
        val fixture = Fixture()
        val paths = listOf(fixture.path(), fixture.path(20))
        val ledger = fixture.ledger(paths)
        val rows = QueryRows.ValuePaths.fromInvestigation(ledger).refined()
        val complete =
            assertInstanceOf(
                QueryExecutionResult.Complete::class.java,
                QueryExecutionResult.Complete.create(QueryResult(rows, emptyList()), QueryCoverage.Complete(count(2))),
            )
        val retained = QueryRetainedResult.capture(fixture.lease, complete).refined() as QueryRetainedResult.ValuePaths
        val selected = retained.selectRows(listOf(1)).refined()
        assertSame(ledger, (selected.rows.accounting as QueryValuePathAccounting.Investigated).ledger)
        assertSame(paths[1], selected.valuePaths.single())
        assertEquals(
            listOf(QueryLimitation.ROW_SELECTION_INCOMPLETE),
            (selected.coverage as QueryCoverage.Qualified).limitations,
        )
        assertEquals(
            QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION),
            QueryExecutionResult.Complete.create(
                QueryResult(selected.rows, emptyList()),
                QueryCoverage.Complete(count(1)),
            ),
        )
        assertEquals(
            QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION),
            QueryExecutionResult.Complete.create(
                QueryResult(QueryRows.ValuePaths.of(paths), emptyList()),
                QueryCoverage.Complete(count(2)),
            ),
        )
    }

    @Test
    fun `unused ledger models retain their full byte charge and cannot smuggle a foreign basis`() {
        val fixture = Fixture()
        val paths = listOf(fixture.path())
        val baseline = fixture.ledger(paths)
        val extended = fixture.ledger(paths, listOf(Fixture(qualifiedName = "owner." + "x".repeat(900)).originRule()))
        assertTrue(extended.retainedStorageBytes() > baseline.retainedStorageBytes() + 2000)
        val foreign = fixture.ledger(paths, listOf(Fixture(generation = 2).originRule()))
        val rows = QueryRows.ValuePaths.fromInvestigation(foreign).refined()
        val complete =
            QueryExecutionResult.Complete.create(QueryResult(rows, emptyList()), QueryCoverage.Complete(count(1)))
        assertEquals(
            Refinement.Rejected(QueryRetainedResultFailure.BASIS_MISMATCH),
            QueryRetainedResult.capture(fixture.lease, complete),
        )
    }

    private fun qualified(rows: QueryRows.ValuePaths) =
        QueryExecutionResult.Qualified(
            QueryResult(rows, emptyList()),
            QueryCoverage.Qualified.create(count(rows.values.size), setOf(QueryLimitation.RELATION_INCOMPLETE))
                .refined(),
        )

    private fun count(value: Int) = QueryCount.parse(value).refined()

    private class Fixture(qualifiedName: String = "fixture.owner", generation: Long = 1) {
        val lease =
            SemanticReadLease(
                CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
                EvidenceGeneration.parse(generation).refined(),
            )
        val scope =
            SymbolSearchScope.Workspace(
                SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                SymbolGeneratedSourcePolicy.INCLUDE,
                SymbolLibraryPolicy.EXCLUDE,
            )
        val candidate =
            SymbolDiscoveryCandidate.fromBoundary(
                    SymbolDiscoveryKind.SYMBOL,
                    "owner",
                    lease,
                    Path.of("/workspace/File.kt"),
                    "file:///workspace/File.kt",
                    0,
                )
                .refined()
        val file = (candidate.location as SymbolDiscoveryCandidateLocation.Declaration).file
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    file,
                    0,
                    100,
                    "owner",
                    qualifiedName,
                    CompilerSymbolKind.FUNCTION,
                    CanonicalCompilerSignature.function(qualifiedName, null, emptyList(), emptyList(), 0).refined(),
                )
                .refined()
        val owner = RelationEndpoint.resolve(lease, scope, evidence).refined()
        val domain =
            RelationRequest.start(
                SymbolSelector.issue(lease, scope, evidence),
                RelationMeaning.References,
                RelationBudget(
                    ResourceBudget(
                        ResultLimit.parse(10).refined(),
                        WorkUnitLimit.parse(100).refined(),
                        ElapsedTimeLimitMillis.parse(1000).refined(),
                    ),
                    RelationByteLimit.parse(100000).refined(),
                ),
            )

        fun ledger(paths: List<QueryImpactPath>, models: List<RepresentationRule> = emptyList()) =
            QueryImpactLedger.fromEvidence(
                    paths.map { it.producer },
                    domain.boundary,
                    QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
                    models,
                    emptyList(),
                    paths.map { (it.terminal as QueryImpactTerminal.SupportedDomainEnd).observation },
                    paths,
                    originalProducers =
                        paths.map { path ->
                            QueryImpactProducer.admit(
                                    path.producer,
                                    ValueInvocation.fromCompiler(
                                            path.producer.enclosing,
                                            path.producer.range,
                                            path.producer.enclosing,
                                        )
                                        .refined(),
                                )
                                .refined()
                        },
                )
                .refined()

        fun originRule(): RepresentationRule.Origin {
            val identity =
                ContractModelIdentity(
                    ModelIdentifier.parse("model").refined(),
                    ModelVersion.parse(1).refined(),
                    ModelIdentifier.parse("review").refined(),
                )
            val vocabulary =
                RepresentationDomain.admit(identity, listOf(ModelIdentifier.parse("clear").refined())).refined()
            val position =
                ExactModelCallablePosition.admit(
                        ModelCallableReference(
                            lease.identity,
                            owner.compilerIdentity,
                            owner.file,
                            owner.range,
                            ModelValuePosition.Result,
                        ),
                        RevalidatedRelationEndpoint.validate(owner, evidence).refined(),
                    )
                    .refined()
            return RepresentationRule.Origin.admit(
                    ModelRuleReference(identity, ModelIdentifier.parse("origin").refined()),
                    position,
                    vocabulary.state(ModelIdentifier.parse("clear").refined()).refined(),
                )
                .refined()
        }

        fun path(offset: Int = 10): QueryImpactPath {
            val source =
                ValueSite.fromCompiler(
                        owner,
                        ExactDeclarationTextRange.parse(offset, offset + 1).refined(),
                        ValueRole.ExpressionResult,
                    )
                    .refined()
            val terminal =
                QueryImpactTerminal.SupportedDomainEnd.admit(
                        ValueFlowStep.fromCompiler(
                                source,
                                emptyList(),
                                emptyList(),
                                ValueFlowTerminal.SupportedDomainExhausted,
                                domain,
                                RelationWorkCount.parse(1).refined(),
                            )
                            .refined()
                    )
                    .refined()
            return QueryImpactPath.fromEvidence(source, emptyList(), QueryImpactRepresentation.NotModeled, terminal)
                .refined()
        }
    }
}

private fun <V, F> Refinement<V, F>.refined(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
