package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactProducer
import io.github.amichne.kast.query.contract.QueryImpactSource
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryPlanAdmission
import io.github.amichne.kast.query.contract.QueryPlanCompiler
import io.github.amichne.kast.query.contract.QueryPlanSyntax
import io.github.amichne.kast.query.contract.QuerySourceSyntax
import io.github.amichne.kast.query.service.QueryService
import io.github.amichne.kast.relation.contract.LocalBindingReferenceScan
import io.github.amichne.kast.relation.contract.LocalReferenceKey
import io.github.amichne.kast.relation.contract.LocalReferenceKind
import io.github.amichne.kast.relation.contract.LocalReferenceResolution
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowCompilerPort
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolSelector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue

/** Real scan and scheduler; only reference enumeration and compiler observations are case-owned scripts. */
internal class NativeImpactProgressCase(
    private val owner: SymbolSelector,
    private val producer: QueryImpactProducer,
    private val allowance: QueryBudget,
    createService: (ValueFlowCompilerPort) -> QueryService,
) {
    private val binding = site(1, ValueRole.LocalBinding)
    private val locals = (2..4).map { site(it, ValueRole.LocalRead) }
    private val confirmations = mutableListOf<Int>()
    private val consumed = mutableListOf<Int>()
    private val terminals = mutableListOf<ValueSite>()
    private var producerReads = 0
    private val service = createService(ValueFlowCompilerPort(::read))
    private val source =
        QueryImpactSource.admit(listOf(producer), emptyList(), emptyList(), RelationSearchBoundary.RETAINED_SUBJECT)
            .refined()
    private val plan =
        (QueryPlanCompiler.admit(
                QueryPlanSyntax(QuerySourceSyntax.Impact(source), emptyList(), QueryOutputSyntax.ValuePaths)
            ) as QueryPlanAdmission.Admitted)
            .plan

    suspend fun pages(): List<QueryExecutionResult.Qualified> {
        val first = assertInstanceOf(QueryExecutionResult.Qualified::class.java, run(null, 1))
        val second = assertInstanceOf(QueryExecutionResult.Qualified::class.java, run(first.checkpoint(), 1))
        val final = run(second.checkpoint(), 20)
        assertInstanceOf(QueryExecutionResult.Complete::class.java, final, final.toString())
        assertEquals(listOf(1, 2), consumed)
        assertEquals(listOf(0, 1, 2), confirmations)
        assertEquals(locals, terminals)
        assertEquals(1, producerReads)
        return listOf(first, second)
    }

    private suspend fun run(checkpoint: QueryCheckpoint?, results: Int) =
        service.run(
            QueryExecutionRequest.create(
                    plan,
                    owner.lease,
                    allowance.copy(
                        resources = ResourceBudget.RunnerControlled,
                        pageResults = ResultLimit.parse(results).refined(),
                    ),
                    checkpoint,
                )
                .refined()
        )

    private fun read(input: ValueFlowRequest): ValueFlowRead {
        val domain = RelationRequest.start(owner, RelationMeaning.References, input.budget, input.boundary)
        return if (input.source == binding) readBinding(input, domain) else readOther(input, domain)
    }

    private fun readBinding(input: ValueFlowRequest, domain: RelationRequest): ValueFlowRead =
        LocalBindingReferenceScan(
                input,
                domain,
                { visit -> for (index in locals.indices) if (!visit(index)) break },
                { index: Int ->
                    LocalReferenceKey(
                        locals[index].range,
                        locals[index].range,
                        LocalReferenceKind.fromBoundary("fixture.Reference").refined(),
                    )
                },
                { index ->
                    assertTrue(index !in confirmations, "A consumed reference was confirmed twice")
                    confirmations += index
                    LocalReferenceResolution.Transfer(locals[index])
                },
                {},
                { 0L },
            )
            .read()
            .also { if (it is ValueFlowRead.Suspended) consumed += it.remainder.consumed.size }

    private fun readOther(input: ValueFlowRequest, domain: RelationRequest): ValueFlowRead {
        val transfers =
            if (input.source == producer.site) {
                assertEquals(0, producerReads++)
                listOf(ValueTransfer.fromCompiler(input.source, binding, ValueTransferKind.LOCAL_BINDING).refined())
            } else {
                assertTrue(input.source in locals && input.source !in terminals)
                terminals += input.source
                emptyList()
            }
        return ValueFlowRead.Observed(
            ValueFlowStep.fromCompiler(
                    input.source,
                    transfers,
                    emptyList(),
                    ValueFlowTerminal.SupportedDomainExhausted,
                    domain,
                    RelationWorkCount.parse(1).refined(),
                )
                .refined()
        )
    }

    private fun site(offset: Int, role: ValueRole) =
        ValueSite.fromCompiler(
                RelationEndpoint.subject(owner),
                ExactDeclarationTextRange.parse(offset, offset + 1).refined(),
                role,
            )
            .refined()

    private fun QueryExecutionResult.Qualified.checkpoint(): QueryCheckpoint =
        assertInstanceOf(QueryContinuationState.Resumable::class.java, continuation).checkpoint

    private fun <T, F> Refinement<T, F>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Native progress fixture rejected: $failure")
        }
}
