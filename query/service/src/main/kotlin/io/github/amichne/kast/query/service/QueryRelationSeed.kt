package io.github.amichne.kast.query.service

import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.ExactQueryStage
import io.github.amichne.kast.query.contract.QueryExecutionRejection
import io.github.amichne.kast.query.contract.QueryExecutionResult

/**
 * Whether the plan's first relation-bearing stage has admitted an input symbol.
 *
 * An exhausted relation hop over no input examined no relation, so its empty output cannot prove absence. Later
 * relation stages are fed by an earlier hop whose own completion already proves their input, so only the first
 * relation-bearing stage in plan order owns this obligation. The state is monotonic and survives checkpoints; a
 * checkpoint that omits it starts from [initial], which can only strengthen the guard.
 */
internal sealed interface QueryRelationSeed {
    /** No relation-bearing stage exists, or the source owns its own seed accounting. */
    data object NotRequired : QueryRelationSeed

    /** The first relation-bearing stage has not yet received an input symbol. */
    data class Awaiting(val stage: ExactQueryStage) : QueryRelationSeed

    /** At least one input symbol entered the first relation-bearing stage. */
    data object Seeded : QueryRelationSeed

    /** Records one symbol entering [stage]; any other stage leaves the state unchanged. */
    fun entered(stage: ExactQueryStage): QueryRelationSeed =
        if (this is Awaiting && this.stage == stage) Seeded else this

    /**
     * Rejects exhausted execution whose first relation hop never received input. Qualified and rejected outcomes keep
     * their own, more specific evidence.
     */
    fun admit(completed: QueryExecutionResult): QueryExecutionResult =
        when (completed) {
            is QueryExecutionResult.Complete ->
                if (this is Awaiting) QueryExecutionResult.Rejected(QueryExecutionRejection.RELATION_SEED_EMPTY)
                else completed
            is QueryExecutionResult.Qualified,
            is QueryExecutionResult.Rejection -> completed
        }

    companion object {
        fun initial(plan: AdmittedQueryPlan): QueryRelationSeed {
            val root =
                when (plan) {
                    // Impact sources reject empty seeds through their own accounting.
                    is AdmittedQueryPlan.Impact -> return NotRequired
                    is AdmittedQueryPlan.Symbols -> plan.stage
                    is AdmittedQueryPlan.Text -> plan.stage
                    is AdmittedQueryPlan.Location -> plan.stage
                    is AdmittedQueryPlan.ExactReferences -> plan.stage
                    is AdmittedQueryPlan.Retained -> plan.stage
                }
            return root.firstRelationStage()?.let(::Awaiting) ?: NotRequired
        }
    }
}

private tailrec fun ExactQueryStage.firstRelationStage(): ExactQueryStage? =
    when (this) {
        is ExactQueryStage.Related,
        is ExactQueryStage.Walk,
        is ExactQueryStage.Trace -> this
        is ExactQueryStage.ProjectBinding -> next.firstRelationStage()
        is ExactQueryStage.Where -> next.firstRelationStage()
        is ExactQueryStage.Distinct -> next.firstRelationStage()
        is ExactQueryStage.Concat -> next.firstRelationStage()
        is ExactQueryStage.Set -> next.firstRelationStage()
        is ExactQueryStage.Join -> next.firstRelationStage()
        is ExactQueryStage.Emit -> null
    }
