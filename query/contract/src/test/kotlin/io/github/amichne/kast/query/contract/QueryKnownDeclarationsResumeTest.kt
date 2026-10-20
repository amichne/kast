package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint
import io.github.amichne.kast.relation.contract.ValueKnownDeclarations
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class QueryKnownDeclarationsResumeTest {
    @Test
    fun `a checkpoint accepts its original question and rejects replacement of the known input set`() {
        val f = QueryImpactLedgerFixture()
        val proof = RevalidatedRelationEndpoint.validate(f.owner, f.evidence).resumeKnown()
        val known = ValueKnownDeclarations.admit(listOf(proof), f.lease).resumeKnown()
        val original = plan(f, known)
        val changed = plan(f, ValueKnownDeclarations.Empty)
        val checkpoint =
            object : QueryCheckpoint {
                override val plan = original
                override val lease = f.lease
                override val retainedBytes = 0L
            }
        val budget = QueryBudget(f.domain.budget.resources, QueryByteLimit.parse(100000).resumeKnown())
        assertInstanceOf(
            Refinement.Refined::class.java,
            QueryExecutionRequest.create(original, f.lease, budget, checkpoint),
        )
        assertEquals(
            Refinement.Rejected(QueryExecutionRequestFailure.CHECKPOINT_MISMATCH),
            QueryExecutionRequest.create(changed, f.lease, budget, checkpoint),
        )
        assertEquals(
            known.declarations,
            (checkpoint.plan as AdmittedQueryPlan.Impact).source.knownDeclarations.declarations,
        )
    }

    private fun plan(f: QueryImpactLedgerFixture, known: ValueKnownDeclarations): AdmittedQueryPlan {
        val source =
            QueryImpactSource.admit(
                    listOf(f.producerWitness),
                    emptyList(),
                    emptyList(),
                    f.domain.boundary,
                    knownDeclarations = known,
                )
                .resumeKnown()
        return AdmittedQueryPlan.Impact(source, ExactQueryStage.Emit(QueryOutputSyntax.ValuePaths))
    }
}

private fun <T, F> Refinement<T, F>.resumeKnown(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Known input resume fixture rejected: $failure")
    }
