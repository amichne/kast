package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.QueryDeclarationKinds
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryPlanAdmission
import io.github.amichne.kast.query.contract.QueryPlanCompiler
import io.github.amichne.kast.query.contract.QueryPlanSyntax
import io.github.amichne.kast.query.contract.QueryScope
import io.github.amichne.kast.query.contract.QuerySourceSyntax
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.query.contract.QuerySymbolFields
import io.github.amichne.kast.query.contract.QueryTextDiscoverySyntax
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBlockCause
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteCount
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryProgress
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualifications
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWord
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import io.github.amichne.kast.symbol.contract.SymbolTextMatch
import io.github.amichne.kast.workspace.contract.WorkspaceSourceSetName
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue

/** Stateless adapters receive every mutable observation from its individual test case. */
internal class QueryTextDiscoveryFixture {
    fun plan(steps: List<QueryStepSyntax> = emptyList()): AdmittedQueryPlan =
        admit(
            QuerySourceSyntax.Text(
                QueryTextDiscoverySyntax(
                    SymbolDiscoveryWord.parse("launchd").refined(),
                    QueryScope.Restricted(
                        sourceSets(),
                        SymbolDiscoveryDirectoryConstraint(
                            SymbolDiscoveryDirectory.parse("services").refined(),
                            SymbolDiscoveryContainment.DESCENDANTS,
                        ),
                        null,
                    ),
                    QueryDeclarationKinds.from(setOf(CompilerSymbolKind.CLASSLIKE)).refined(),
                )
            ),
            steps,
        )

    fun sourceSets(): SymbolDiscoverySourceSets =
        SymbolDiscoverySourceSets.Exact.from(setOf(WorkspaceSourceSetName.parse("main").refined())).refined()

    fun admit(source: QuerySourceSyntax, steps: List<QueryStepSyntax> = emptyList()): AdmittedQueryPlan =
        (QueryPlanCompiler.admit(
                QueryPlanSyntax(source, steps, QueryOutputSyntax.Symbols(QuerySymbolFields.from(emptySet()).refined()))
            ) as QueryPlanAdmission.Admitted)
            .plan

    fun match(fixture: QueryServiceTest): SymbolTextMatch {
        val lease = fixture.request(fixture.symbolPlan(), 8).lease
        val file =
            CanonicalWorkspaceFilePath.fromCanonicalPath(
                    lease.workspaceRoot,
                    Path.of("/workspace/services/payments/PaymentService.kt"),
                )
                .refined()
        return SymbolTextMatch.fromBoundary(
                SymbolDiscoveryWord.parse("launchd").refined(),
                lease,
                file,
                12,
                19,
                7,
                27,
                "launchd",
                12,
                1,
            )
            .refined()
    }

    fun discovery(
        match: SymbolTextMatch,
        stopped: Boolean = false,
        observe: (SymbolDiscoveryRequest) -> Unit = {},
    ): SymbolDiscoveryOperations = SymbolDiscoveryOperations { request ->
        observe(request)
        val candidate =
            SymbolDiscoveryCandidate.fromBoundary(
                    SymbolDiscoveryKind.CLASS,
                    "PaymentService",
                    request.scope.lease,
                    Path.of("/workspace/services/payments/PaymentService.kt"),
                    "file:///workspace/services/payments/PaymentService.kt",
                    7,
                    textMatch = match,
                )
                .refined()
        val batch =
            SymbolDiscoveryBatch.create(
                    request,
                    listOf(candidate),
                    SymbolDiscoveryByteCount.parse(candidate.projectedUtf8Size().value).refined(),
                    SymbolDiscoveryWorkCount.parse(1).refined(),
                    SymbolDiscoveryTimings(
                        SymbolDiscoveryElapsedNanoseconds.parse(0).refined(),
                        SymbolDiscoveryElapsedNanoseconds.parse(0).refined(),
                    ),
                )
                .refined()
        SymbolDiscoveryResult.Discovered(
            if (stopped)
                SymbolDiscoveryOutcome.Qualified(
                    batch,
                    SymbolDiscoveryQualifications.from(setOf(SymbolDiscoveryQualification.WORK_LIMIT_REACHED))
                        .refined(),
                    SymbolDiscoveryProgress.Blocked(SymbolDiscoveryBlockCause.QUALIFIED_PROVIDER),
                )
            else SymbolDiscoveryOutcome.Complete(batch)
        )
    }

    fun discoveryPair(
        fixture: QueryServiceTest,
        observe: (SymbolDiscoveryRequest) -> Unit = {},
    ): SymbolDiscoveryOperations = SymbolDiscoveryOperations { request ->
        observe(request)
        assertTrue(
            request.budget.resources.resultLimit.value > 1,
            "Candidate capacity must permit owners beyond the presentation result limit",
        )
        val first = match(fixture)
        val second =
            SymbolTextMatch.fromBoundary(first.word, first.lease, first.file, 35, 42, 30, 50, "launchd", 35, 1)
                .refined()
        val candidates =
            listOf(
                SymbolDiscoveryCandidate.fromBoundary(
                        SymbolDiscoveryKind.CLASS,
                        "Excluded",
                        request.scope.lease,
                        Path.of("/workspace/services/payments/PaymentService.kt"),
                        "file:///workspace/services/payments/PaymentService.kt",
                        7,
                        textMatch = first,
                    )
                    .refined(),
                SymbolDiscoveryCandidate.fromBoundary(
                        SymbolDiscoveryKind.CLASS,
                        "PaymentService",
                        request.scope.lease,
                        Path.of("/workspace/services/payments/PaymentService.kt"),
                        "file:///workspace/services/payments/PaymentService.kt",
                        30,
                        textMatch = second,
                    )
                    .refined(),
            )
        val batch =
            SymbolDiscoveryBatch.create(
                    request,
                    candidates,
                    SymbolDiscoveryByteCount.parse(candidates.sumOf { it.projectedUtf8Size().value }).refined(),
                    SymbolDiscoveryWorkCount.parse(2).refined(),
                    SymbolDiscoveryTimings(
                        SymbolDiscoveryElapsedNanoseconds.parse(0).refined(),
                        SymbolDiscoveryElapsedNanoseconds.parse(0).refined(),
                    ),
                )
                .refined()
        SymbolDiscoveryResult.Discovered(SymbolDiscoveryOutcome.Complete(batch))
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = (this as Refinement.Refined).value
}
