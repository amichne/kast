package io.github.amichne.kast.query.service

import io.github.amichne.kast.query.contract.QueryImpactLedger
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryValuePathAccounting
import io.github.amichne.kast.relation.contract.LocalBindingReferenceScan
import io.github.amichne.kast.relation.contract.LocalReferenceKey
import io.github.amichne.kast.relation.contract.LocalReferenceKind
import io.github.amichne.kast.relation.contract.LocalReferenceResolution
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint
import io.github.amichne.kast.relation.contract.ValueFlowCompilerPort
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueKnownDeclarations
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/** Issued endpoint facts are assumed inputs. The real query coordinator owns carrier conservation. */
internal class KnownDeclarationQueryCase(empty: Boolean = false) {
    val f = QueryImpactExecutionFixture()
    val declaration = f.call("Known", 70, 80).callable as RelationEndpoint.Resolved
    val known = if (empty) ValueKnownDeclarations.Empty else inventory(declaration)
    val plan = f.plan(knownDeclarations = known)
    val source = (plan as io.github.amichne.kast.query.contract.AdmittedQueryPlan.Impact).source
    val binding = f.site(30, ValueRole.LocalBinding)
    val local = f.site(50, ValueRole.LocalRead)
    val confirmations = mutableListOf<Int>()

    fun alternate(): ValueKnownDeclarations =
        inventory(
            RelationEndpoint.resolve(
                    declaration.lease,
                    (declaration.scope as SymbolSearchScope.Workspace).copy(
                        generatedSources = SymbolGeneratedSourcePolicy.INCLUDE
                    ),
                    declaration.evidence,
                )
                .autoRefined() as RelationEndpoint.Resolved
        )

    fun observed(
        request: ValueFlowRequest,
        edges: List<ValueTransfer> = emptyList(),
        known: ValueKnownDeclarations = request.knownDeclarations,
    ): ValueFlowRead.Observed =
        ValueFlowRead.Observed(
            ValueFlowStep.fromCompiler(
                    source = request.source,
                    transfers = edges,
                    obligations = emptyList(),
                    terminal = ValueFlowTerminal.SupportedDomainExhausted,
                    domain = domain(request),
                    examinedWorkUnits = RelationWorkCount.parse(1).autoRefined(),
                    knownDeclarations = known,
                )
                .autoRefined()
        )

    fun scan(request: ValueFlowRequest): ValueFlowRead =
        LocalBindingReferenceScan<Int>(
                request = request,
                domain = domain(request),
                enumerate = { visit -> for (i in 0..2) if (!visit(i)) break },
                key = { i ->
                    val range = ExactDeclarationTextRange.parse(100 + i, 101 + i).autoRefined()
                    LocalReferenceKey(range, range, LocalReferenceKind.fromBoundary("fixture.Reference").autoRefined())
                },
                confirm = { i ->
                    confirmations += i
                    if (i == 1) LocalReferenceResolution.Transfer(local) else LocalReferenceResolution.OtherBinding
                },
                checkCanceled = {},
                elapsedMillis = { 0L },
            )
            .read()

    private fun domain(request: ValueFlowRequest) =
        RelationRequest.start(f.owner, RelationMeaning.References, request.budget, request.boundary)

    private fun inventory(endpoint: RelationEndpoint.Resolved) =
        ValueKnownDeclarations.admit(
                listOf(RevalidatedRelationEndpoint.validate(endpoint, endpoint.evidence).autoRefined()),
                f.lease,
            )
            .autoRefined()
}

internal class KnownDeclarationQueryScript(
    sites: List<ValueSite>,
    answer: (ValueFlowRequest) -> ValueFlowRead,
) {
    private val expected = ArrayDeque(sites)
    private val violations = mutableListOf<String>()
    val requests = mutableListOf<ValueFlowRequest>()
    val reads = mutableListOf<ValueFlowRead>()
    val port = ValueFlowCompilerPort { request ->
        requests += request
        if (expected.removeFirstOrNull() != request.source) {
            violations += "Unexpected native site"
            error(violations.last())
        }
        answer(request).also(reads::add)
    }

    fun assertConsumed() {
        assertTrue(violations.isEmpty(), violations.toString())
        assertEquals(0, expected.size, "Every expected native read must run")
    }
}

internal fun knownLedger(rows: QueryRows): QueryImpactLedger =
    ((rows as QueryRows.ValuePaths).accounting as QueryValuePathAccounting.Investigated).ledger
