package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactClosure
import io.github.amichne.kast.query.contract.QueryImpactExclusionCause
import io.github.amichne.kast.query.contract.QueryImpactExecutionStop
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactReadRejection
import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryImpactRequiredObligation
import io.github.amichne.kast.query.contract.QueryImpactRetainedGraph
import io.github.amichne.kast.query.contract.QueryImpactStep
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QuerySourceSyntax
import io.github.amichne.kast.query.contract.QueryValuePathAccounting
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowCompilerPort
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowStepFailure
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolSelector
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryImpactBoundsTest {
    @Test
    fun `public workspace directory domain excludes admitted seed before flow enumeration`() = runTest {
        val f = QueryImpactExecutionFixture()
        val directory =
            SymbolDiscoveryDirectoryConstraint(
                SymbolDiscoveryDirectory.parse("src").value(),
                SymbolDiscoveryContainment.DESCENDANTS,
            )
        val domain = RelationSearchBoundary.Explicit(f.scope, directory)
        val plan = f.plan(domain = domain)
        val native = f.script(emptyList())
        val complete =
            assertInstanceOf(
                QueryExecutionResult.Complete::class.java,
                f.service(native.port).run(f.request(plan = plan)),
            )
        val rows = complete.result.rows as QueryRows.ValuePaths
        val exclusion = (rows.values.single().terminal as QueryImpactTerminal.ExplicitScopeExclusion).exclusion
        assertEquals(QueryImpactExclusionCause.OUTSIDE_DIRECTORY, exclusion.cause)
        assertSame(domain, exclusion.domain)
        assertEquals(emptyList<ValueSite>(), native.examined)
        native.assertConsumed()
    }

    @Test
    fun `failed native receipt exceeding granted work remains a contract failure with actual count`() = runTest {
        val f = QueryImpactExecutionFixture()
        var calls = 0
        val port = ValueFlowCompilerPort {
            calls++
            ValueFlowRead.Rejected(ValueFlowRejection.OWNER_UNAVAILABLE, RelationWorkCount.parse(101).value())
        }
        val result =
            assertInstanceOf(QueryExecutionResult.Qualified::class.java, f.service(port).run(f.request(work = 100)))
        val rejection =
            (((result.result.rows as QueryRows.ValuePaths).values.single().terminal
                    as QueryImpactTerminal.Unresolved.ReadRejected)
                .rejection as QueryImpactReadRejection.Contract)
        assertEquals(ValueFlowStepFailure.WORK_LIMIT_EXCEEDED, rejection.cause)
        assertEquals(101L, rejection.examinedWorkUnits.value)
        assertTrue(QueryLimitation.WORK_LIMIT_REACHED in result.coverage.limitations)
        assertEquals(1, calls)
    }

    @Test
    fun `cached repeated prefix obeys checkpoint byte admission before expansion`() = runTest {
        val f = QueryImpactExecutionFixture()
        val cached = cachedImpactCase(f)
        val checkpoint = cached.checkpoint
        val native = f.script(emptyList())
        val result =
            assertInstanceOf(
                QueryExecutionResult.Qualified::class.java,
                f.service(native.port)
                    .run(f.request(checkpoint = checkpoint, checkpointBytes = checkpoint.retainedBytes + 1)),
            )
        val rows = result.result.rows as QueryRows.ValuePaths
        val ledger = (rows.accounting as QueryValuePathAccounting.Investigated).ledger
        assertEquals(4, ledger.observations.size)
        assertEquals(listOf(cached.first.steps, cached.pending.steps), ledger.paths.map { it.steps })
        val stop =
            (ledger.paths.last().terminal as QueryImpactTerminal.Unresolved.ExecutionStop).stop
                as QueryImpactExecutionStop.CheckpointCapacity
        assertEquals(cached.pending.site, stop.source)
        assertTrue(stop.required.value > stop.available.value)
        assertEquals(
            setOf(QueryImpactRequiredObligation.EXECUTION_BOUNDARY),
            (ledger.closure as QueryImpactClosure.Unresolved).required,
        )
        assertEquals(emptyList<ValueSite>(), native.examined)
        native.assertConsumed()
    }

    @Test
    fun `retained path seed preserves graph sharing on every accounting read`() {
        val f = QueryImpactExecutionFixture()
        val path = cachedImpactCase(f).first
        val result =
            QueryExecutionResult.Qualified(
                QueryResult(
                    QueryRows.ValuePaths.of(listOf(path)),
                    emptyList(),
                ),
                QueryCoverage.Qualified.create(
                        1.queryCount(),
                        setOf(QueryLimitation.IMPACT_COVERAGE_UNPROVEN),
                    )
                    .value(),
            )
        val retained = QueryRetainedResult.capture(f.lease, result).value()
        val plan =
            QueryServiceTest()
                .admittedPlan(
                    QuerySourceSyntax.Retained(retained),
                    output = QueryOutputSyntax.ValuePaths,
                )
        val seed = PipelineSeed.Accounted.create(plan)
        val fresh = QueryImpactRetainedGraph()
        val precharged = QueryImpactRetainedGraph()
        precharged.path(path)
        val freshBytes = seed.retainedRootBytes(fresh)
        val sharedBytes = seed.retainedRootBytes(precharged)
        assertTrue(freshBytes > sharedBytes, "Shared path proof must remain graph-aware")
        assertEquals(840L, sharedBytes) // 256 seed + 8 root slot + 512 task + 64 shared path reference.
        assertEquals(sharedBytes, seed.retainedRootBytes(fresh))
        assertEquals(
            freshBytes,
            seed.retainedRootBytes(QueryImpactRetainedGraph()),
        )
    }

    private data class CachedImpactCase(
        val checkpoint: PipelineCheckpoint,
        val first: QueryImpactPath,
        val pending: QueryImpactRoute,
    )

    private fun cachedImpactCase(f: QueryImpactExecutionFixture): CachedImpactCase {
        val a = f.producer.site
        val b = f.site(30, ValueRole.LocalBinding)
        val c = f.site(40, ValueRole.LocalBinding)
        val d = f.site(50, ValueRole.LocalRead)
        val ab = f.edge(a, b, ValueTransferKind.LOCAL_BINDING)
        val ac = f.edge(a, c, ValueTransferKind.LOCAL_BINDING)
        val bd = f.edge(b, d, ValueTransferKind.LOCAL_READ)
        val cd = f.edge(c, d, ValueTransferKind.LOCAL_READ)
        val observations =
            listOf(
                observe(f, a, listOf(ab, ac)),
                observe(f, b, listOf(bd)),
                observe(f, c, listOf(cd)),
                observe(f, d, emptyList()),
            )
        val first =
            QueryImpactPath.fromEvidence(
                    a,
                    listOf(QueryImpactStep.Compiler(ab), QueryImpactStep.Compiler(bd)),
                    QueryImpactRepresentation.NotModeled,
                    QueryImpactTerminal.SupportedDomainEnd.admit(observations.last()).value(),
                )
                .value()
        val pending = QueryImpactRoute(a, listOf(QueryImpactStep.Compiler(ac)), QueryImpactRepresentation.NotModeled)
        val checkpoint = cachedCheckpoint(f, pending, observations, first)
        return CachedImpactCase(checkpoint, first, pending)
    }

    private fun cachedCheckpoint(
        f: QueryImpactExecutionFixture,
        pending: QueryImpactRoute,
        observations: List<ValueFlowStep>,
        first: QueryImpactPath,
    ): PipelineCheckpoint =
        PipelineCheckpoint(
            PipelineSeed.Accounted.create(f.plan),
            f.lease,
            listOf(PipelineTask.ImpactExplore(pending), PipelineTask.ImpactFinalize),
            emptyMap(),
            QueryJoinSnapshot(emptyMap()),
            emptySet(),
            QueryCount.parse(0).value(),
            QueryImpactSnapshot(observations.associate { it.source to ValueFlowRead.Observed(it) }, listOf(first)),
        )

    private fun observe(f: QueryImpactExecutionFixture, site: ValueSite, edges: List<ValueTransfer>): ValueFlowStep =
        ValueFlowStep.fromCompiler(
                site,
                edges,
                emptyList(),
                ValueFlowTerminal.SupportedDomainExhausted,
                RelationRequest.start(
                    SymbolSelector.issue(f.lease, f.scope, (f.owner as RelationEndpoint.Resolved).evidence),
                    RelationMeaning.References,
                    RelationBudget(f.request().budget.resources, RelationByteLimit.parse(1000000).value()),
                    RelationSearchBoundary.WORKSPACE_EXPANSION,
                ),
                RelationWorkCount.parse(1).value(),
            )
            .value()
}

private fun <V> Refinement<V, *>.value(): V = (this as Refinement.Refined).value
