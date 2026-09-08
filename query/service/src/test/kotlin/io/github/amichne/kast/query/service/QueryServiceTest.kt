package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryDeclarationKinds
import io.github.amichne.kast.query.contract.QueryDiscoverySyntax
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryExactReferences
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryMatch
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryPlanAdmission
import io.github.amichne.kast.query.contract.QueryPlanCompiler
import io.github.amichne.kast.query.contract.QueryPlanSyntax
import io.github.amichne.kast.query.contract.QueryResultSet
import io.github.amichne.kast.query.contract.QueryScope
import io.github.amichne.kast.query.contract.QuerySourceSyntax
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.query.contract.QuerySymbolField
import io.github.amichne.kast.query.contract.QuerySymbolFields
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.workspace.contract.WorkspaceSourceSetName
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteCount
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualifications
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import io.github.amichne.kast.symbol.contract.SymbolExactOperations
import io.github.amichne.kast.symbol.contract.SymbolExactRejection
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolResolutionResult
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.nio.file.Path

class QueryServiceTest {
    @Test
    fun `source-set identity survives query discovery without source-kind inference`() = runTest {
        listOf("main", "test", "integrationTest").forEach { name ->
            val sets = SymbolDiscoverySourceSets.Exact.from(
                setOf(WorkspaceSourceSetName.parse(name).refined()),
            ).refined()
            val syntax = QueryDiscoverySyntax(
                QueryMatch.All,
                QueryScope.Restricted(sets, null, null),
                QueryDeclarationKinds.from(setOf(CompilerSymbolKind.CLASSLIKE)).refined(),
            )
            var observed = false
            val service = service(discovery = SymbolDiscoveryOperations { request ->
                observed = true
                assertEquals(sets, request.constraints.sourceSets)
                assertEquals(SymbolSourceKindPolicy.PRODUCTION_AND_TEST, request.scope.scope.sourceKinds)
                discoveryEmpty(qualified = false).discover(request)
            })
            val plan = QueryPlanCompiler.admit(QueryPlanSyntax(
                QuerySourceSyntax.Symbols(syntax),
                emptyList(),
                QueryOutputSyntax.Symbols(QuerySymbolFields.from(emptySet()).refined()),
            ))
            assertTrue(plan is QueryPlanAdmission.Admitted)
            service.run(request((plan as QueryPlanAdmission.Admitted).plan, workLimit = 8L))
            assertTrue(observed)
        }
    }

    @Test
    fun `symbols source owns candidate refinement and emits exact symbols`() = runTest {
        var resolutions = 0
        val service = service(
            discovery = discoveryWithCandidate(),
            exact = exactOperations { selection ->
                resolutions += 1
                SymbolResolutionResult.Resolved(
                    io.github.amichne.kast.symbol.contract.ResolvedSymbol(selector(selection)),
                )
            },
        )

        val result = service.run(request(symbolPlan(), workLimit = 8L))

        val complete = assertInstanceOf(QueryExecutionResult.Complete::class.java, result)
        val symbols = assertInstanceOf(QueryResultSet.Symbols::class.java, complete.result.items)
        assertEquals(1, resolutions)
        assertEquals(listOf("PaymentService"), symbols.values.map { it.description.name.value })
        assertEquals(1, complete.coverage.resultCount.value)
    }

    @Test
    fun `failed refinement remains item failure and qualifies an empty exact set`() = runTest {
        val service = service(
            discovery = discoveryWithCandidate(),
            exact = exactOperations {
                SymbolResolutionResult.Rejected(SymbolExactRejection.AMBIGUOUS_DECLARATION)
            },
        )

        val result = service.run(request(symbolPlan(), workLimit = 8L))

        val qualified = assertInstanceOf(QueryExecutionResult.Qualified::class.java, result)
        val symbols = assertInstanceOf(QueryResultSet.Symbols::class.java, qualified.result.items)
        assertEquals(emptyList<Any>(), symbols.values)
        assertEquals(1, qualified.result.failures.size)
        assertEquals(listOf(QueryLimitation.REFINEMENT_INCOMPLETE), qualified.coverage.limitations)
        assertEquals(0, qualified.coverage.knownMinimum.value)
    }

    @Test
    fun `complete empty and incomplete empty remain distinct coverage claims`() = runTest {
        val complete = service(discovery = discoveryEmpty(qualified = false)).run(
            request(candidatePlan(), workLimit = 8L),
        )
        val incomplete = service(discovery = discoveryEmpty(qualified = true)).run(
            request(candidatePlan(), workLimit = 8L),
        )

        assertInstanceOf(QueryExecutionResult.Complete::class.java, complete)
        val qualified = assertInstanceOf(QueryExecutionResult.Qualified::class.java, incomplete)
        assertEquals(0, qualified.coverage.knownMinimum.value)
        assertEquals(listOf(QueryLimitation.DISCOVERY_INCOMPLETE), qualified.coverage.limitations)
    }

    @Test
    fun `one plan budget is not reacquired for candidate refinement`() = runTest {
        var resolutions = 0
        val service = service(
            discovery = discoveryWithCandidate(),
            exact = exactOperations { selection ->
                resolutions += 1
                SymbolResolutionResult.Resolved(
                    io.github.amichne.kast.symbol.contract.ResolvedSymbol(selector(selection)),
                )
            },
        )

        val result = service.run(request(symbolPlan(), workLimit = 1L))

        val qualified = assertInstanceOf(QueryExecutionResult.Qualified::class.java, result)
        assertEquals(0, resolutions)
        assertEquals(listOf(QueryLimitation.WORK_LIMIT_REACHED), qualified.coverage.limitations)
    }

    @Test
    fun `final exact projection is charged to the shared byte budget`() = runTest {
        val selector = selector(selection())
        val service = service(
            exact = exactOperations(
                resolve = { error("Candidate refinement was not expected") },
                describe = { SymbolDescriptionResult.Described(SymbolDescription.from(it)) },
            ),
        )

        val result = service.run(
            request(exactReferencePlan(listOf(selector)), workLimit = 8L, returnedBytes = 1L),
        )

        val qualified = assertInstanceOf(QueryExecutionResult.Qualified::class.java, result)
        val symbols = assertInstanceOf(QueryResultSet.Symbols::class.java, qualified.result.items)
        assertEquals(emptyList<Any>(), symbols.values)
        assertEquals(listOf(QueryLimitation.BYTE_LIMIT_REACHED), qualified.coverage.limitations)
    }

    @Test
    fun `per item failures are result bounded without becoming complete`() = runTest {
        val selector = selector(selection())
        val service = service(
            exact = exactOperations(
                resolve = { error("Candidate refinement was not expected") },
                describe = {
                    SymbolDescriptionResult.Rejected(SymbolExactRejection.AMBIGUOUS_DECLARATION)
                },
            ),
        )

        val result = service.run(
            request(
                exactReferencePlan(List(3) { selector }),
                workLimit = 8L,
                resultLimit = 1,
            ),
        )

        val qualified = assertInstanceOf(QueryExecutionResult.Qualified::class.java, result)
        assertEquals(1, qualified.result.failures.size)
        assertInstanceOf(QueryItemFailure.ExactReference::class.java, qualified.result.failures.single())
        assertEquals(
            listOf(QueryLimitation.RESULT_LIMIT_REACHED, QueryLimitation.REFINEMENT_INCOMPLETE),
            qualified.coverage.limitations,
        )
    }

    @Test
    fun `later exact rejection cannot erase compiler contract violation`() = runTest {
        val selector = selector(selection())
        var descriptions = 0
        val service = service(
            exact = exactOperations(
                resolve = { error("Candidate refinement was not expected") },
                describe = {
                    descriptions += 1
                    SymbolDescriptionResult.Rejected(
                        if (descriptions == 1) {
                            SymbolExactRejection.COMPILER_CONTRACT_VIOLATION
                        } else {
                            SymbolExactRejection.AMBIGUOUS_DECLARATION
                        },
                    )
                },
            ),
        )

        val result = service.run(
            request(exactReferencePlan(List(2) { selector }), workLimit = 8L),
        )

        assertEquals(
            QueryExecutionResult.Rejected(
                io.github.amichne.kast.query.contract.QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION,
            ),
            result,
        )
    }

    @Test
    fun `exact reference duplicates change only through explicit distinct stage`() = runTest {
        val selector = selector(selection())
        val service = service(
            exact = exactOperations(
                resolve = { error("Candidate refinement was not expected") },
                describe = { SymbolDescriptionResult.Described(SymbolDescription.from(it)) },
            ),
        )

        val retained = service.run(
            request(exactReferencePlan(List(2) { selector }), workLimit = 8L),
        )
        val distinct = service.run(
            request(
                exactReferencePlan(List(2) { selector }, listOf(QueryStepSyntax.Distinct)),
                workLimit = 8L,
            ),
        )

        assertEquals(2, retained.symbolCount())
        assertEquals(1, distinct.symbolCount())
    }

    @Test
    fun `time spent inside an exact effect qualifies the result`() = runTest {
        val selector = selector(selection())
        val service = service(
            exact = exactOperations(
                resolve = { error("Candidate refinement was not expected") },
                describe = { SymbolDescriptionResult.Described(SymbolDescription.from(it)) },
            ),
            clock = StepClock(1_000_000L),
        )

        val result = service.run(
            request(
                exactReferencePlan(listOf(selector)),
                workLimit = 8L,
                elapsedMillis = 2L,
            ),
        )

        val qualified = assertInstanceOf(QueryExecutionResult.Qualified::class.java, result)
        assertEquals(listOf(QueryLimitation.TIME_LIMIT_REACHED), qualified.coverage.limitations)
    }

    private fun service(
        discovery: SymbolDiscoveryOperations = discoveryEmpty(qualified = false),
        exact: SymbolExactOperations = exactOperations {
            error("Exact refinement was not expected")
        },
        clock: QueryNanoClock = QueryNanoClock(System::nanoTime),
    ): QueryService = QueryService(
        discovery = discovery,
        exact = exact,
        source = SourceReadOperations { error("Source read was not expected") },
        relations = RelationOperations { error("Relation read was not expected") },
        clock = clock,
    )

    private fun discoveryWithCandidate(): SymbolDiscoveryOperations = SymbolDiscoveryOperations { request ->
        val candidate = SymbolDiscoveryCandidate.fromBoundary(
            SymbolDiscoveryKind.CLASS,
            "PaymentService",
            request.scope.lease,
            Path.of("/workspace/services/payments/PaymentService.kt"),
            "file:///workspace/services/payments/PaymentService.kt",
            7,
        ).refined()
        SymbolDiscoveryResult.Discovered(
            SymbolDiscoveryOutcome.Complete(batch(request, listOf(candidate), 1L)),
        )
    }

    private fun discoveryEmpty(qualified: Boolean): SymbolDiscoveryOperations =
        SymbolDiscoveryOperations { request ->
            val batch = batch(request, emptyList(), 0L)
            SymbolDiscoveryResult.Discovered(
                if (qualified) {
                    SymbolDiscoveryOutcome.Qualified(
                        batch,
                        SymbolDiscoveryQualifications.from(
                            setOf(SymbolDiscoveryQualification.RESULT_LIMIT_REACHED),
                        ).refined(),
                    )
                } else {
                    SymbolDiscoveryOutcome.Complete(batch)
                },
            )
        }

    private fun batch(
        request: SymbolDiscoveryRequest,
        candidates: List<SymbolDiscoveryCandidate>,
        work: Long,
    ): SymbolDiscoveryBatch = SymbolDiscoveryBatch.create(
        request,
        candidates,
        SymbolDiscoveryByteCount.parse(candidates.sumOf { it.projectedUtf8Size().value }).refined(),
        SymbolDiscoveryWorkCount.parse(work).refined(),
        SymbolDiscoveryTimings(
            SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
            SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
        ),
    ).refined()

    private fun exactOperations(
        describe: (SymbolSelector) -> SymbolDescriptionResult = {
            error("Description was not expected")
        },
        resolve: (SymbolDiscoverySelection) -> SymbolResolutionResult,
    ): SymbolExactOperations = object : SymbolExactOperations {
        override suspend fun resolve(
            request: io.github.amichne.kast.symbol.contract.SymbolResolutionRequest,
        ): SymbolResolutionResult = resolve(request.selection)

        override suspend fun describe(
            request: io.github.amichne.kast.symbol.contract.ExactSymbolRequest,
        ): SymbolDescriptionResult = describe(request.selector)
    }

    private fun selector(selection: SymbolDiscoverySelection): SymbolSelector {
        val location = selection.candidate.location as
            io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation.Declaration
        val signature = CanonicalCompilerSignature.classLike("sample.PaymentService").refined()
        val evidence = CompilerGroundedSymbolEvidence.fromBoundary(
            location.file,
            location.offset.value,
            location.offset.value + 20,
            selection.candidate.name.value,
            "sample.PaymentService",
            CompilerSymbolKind.CLASSLIKE,
            signature,
        ).refined()
        return SymbolSelector.issue(selection, evidence).refined()
    }

    private fun request(
        plan: AdmittedQueryPlan,
        workLimit: Long,
        resultLimit: Int = 8,
        returnedBytes: Long = 100_000L,
        elapsedMillis: Long = 10_000L,
    ): QueryExecutionRequest =
        QueryExecutionRequest.create(
            plan,
            lease(),
            QueryBudget(
                ResourceBudget(
                    ResultLimit.parse(resultLimit).refined(),
                    WorkUnitLimit.parse(workLimit).refined(),
                    ElapsedTimeLimitMillis.parse(elapsedMillis).refined(),
                ),
                QueryByteLimit.parse(returnedBytes).refined(),
            ),
        ).refined()

    private fun symbolPlan(): AdmittedQueryPlan = admittedPlan(
        source = QuerySourceSyntax.Symbols(discovery()),
        output = QueryOutputSyntax.Symbols(symbolFields()),
    )

    private fun candidatePlan(): AdmittedQueryPlan = admittedPlan(
        source = QuerySourceSyntax.Candidates(discovery()),
        output = QueryOutputSyntax.Candidates(
            io.github.amichne.kast.query.contract.QueryCandidateFields.from(
                setOf(io.github.amichne.kast.query.contract.QueryCandidateField.NAME),
            ).refined(),
        ),
    )

    private fun exactReferencePlan(
        selectors: List<SymbolSelector>,
        steps: List<QueryStepSyntax> = emptyList(),
    ): AdmittedQueryPlan = admittedPlan(
        source = QuerySourceSyntax.ExactReferences(QueryExactReferences.from(selectors).refined()),
        steps = steps,
        output = QueryOutputSyntax.Symbols(symbolFields()),
    )

    private fun admittedPlan(
        source: QuerySourceSyntax,
        steps: List<QueryStepSyntax> = emptyList(),
        output: QueryOutputSyntax,
    ): AdmittedQueryPlan = when (
        val admission = QueryPlanCompiler.admit(QueryPlanSyntax(source, steps, output))
    ) {
        is QueryPlanAdmission.Admitted -> admission.plan
        is QueryPlanAdmission.Rejected -> error("Expected admitted plan, got ${admission.failure}")
    }

    private fun QueryExecutionResult.symbolCount(): Int = when (this) {
        is QueryExecutionResult.Complete -> (result.items as QueryResultSet.Symbols).values.size
        is QueryExecutionResult.Qualified -> (result.items as QueryResultSet.Symbols).values.size
        is QueryExecutionResult.Rejected -> error("Expected symbol result, got $reason")
    }

    private fun selection(): SymbolDiscoverySelection {
        val request = SymbolDiscoveryRequest(
            scope = SymbolSearchScopeRequest(
                lease(),
                SymbolSearchScope.Workspace(
                    SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    SymbolGeneratedSourcePolicy.EXCLUDE,
                    SymbolLibraryPolicy.EXCLUDE,
                ),
            ),
            target = SymbolDiscoveryTarget.All(SymbolNameDiscoveryKind.CLASS),
            budget = SymbolDiscoveryBudget(
                ResourceBudget(
                    ResultLimit.parse(1).refined(),
                    WorkUnitLimit.parse(1L).refined(),
                    ElapsedTimeLimitMillis.parse(1_000L).refined(),
                ),
                SymbolDiscoveryByteLimit.parse(10_000L).refined(),
            ),
        )
        val candidate = SymbolDiscoveryCandidate.fromBoundary(
            SymbolDiscoveryKind.CLASS,
            "PaymentService",
            request.scope.lease,
            Path.of("/workspace/services/payments/PaymentService.kt"),
            "file:///workspace/services/payments/PaymentService.kt",
            7,
        ).refined()
        return SymbolDiscoverySelection.select(batch(request, listOf(candidate), 1L), 0).refined()
    }

    private fun discovery(): QueryDiscoverySyntax = QueryDiscoverySyntax(
        QueryMatch.Name(
            SymbolDiscoveryPattern.parse("PaymentService").refined(),
            SymbolDiscoveryMatch.EXACT_NAME,
        ),
        QueryScope.Unrestricted,
        QueryDeclarationKinds.from(setOf(CompilerSymbolKind.CLASSLIKE)).refined(),
    )

    private fun symbolFields(): QuerySymbolFields = QuerySymbolFields.from(
        setOf(QuerySymbolField.NAME, QuerySymbolField.LOCATION),
    ).refined()

    private fun lease(): SemanticReadLease = SemanticReadLease(
        CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
        EvidenceGeneration.parse(7L).refined(),
    )

    private class StepClock(private val step: Long) : QueryNanoClock {
        private var current = 0L

        override fun now(): Long = current.also { current += step }
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }
}
