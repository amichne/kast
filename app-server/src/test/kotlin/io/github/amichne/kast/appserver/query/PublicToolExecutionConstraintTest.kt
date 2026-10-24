package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.ExecutionAllowance
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RequestedExecutionBudget
import io.github.amichne.kast.protocol.contract.QueryExecutionModeDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.requestedExecutionBudget
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PublicToolExecutionConstraintTest {
    @Test
    fun `execution mode and work budget combinations retain their public admission meaning`() {
        val outputAndTimeout = PublicToolExecutionBudget(maxElapsedMs = 3000, maxResults = 2, maxReturnedBytes = 2000)
        val runner = QueryExecutionModeDocument.RUNNER_CONTROLLED
        val bounded = QueryExecutionModeDocument.BOUNDED
        val workOfOne = PublicToolExecutionBudget(maxWorkUnits = 1)
        val maximumWork = outputAndTimeout.copy(maxWorkUnits = Long.MAX_VALUE)
        val cases =
            listOf(
                ExecutionConstraintCase(null, null, true),
                ExecutionConstraintCase(null, PublicToolExecutionBudget(), true),
                ExecutionConstraintCase(null, outputAndTimeout, true),
                ExecutionConstraintCase(null, workOfOne, false),
                ExecutionConstraintCase(runner, null, true),
                ExecutionConstraintCase(runner, outputAndTimeout, true),
                ExecutionConstraintCase(runner, workOfOne, false),
                ExecutionConstraintCase(runner, maximumWork, false),
                ExecutionConstraintCase(bounded, null, true),
                ExecutionConstraintCase(bounded, outputAndTimeout, true),
                ExecutionConstraintCase(bounded, workOfOne, true),
                ExecutionConstraintCase(bounded, maximumWork, true),
            )
        for (encodeDefaults in listOf(false, true)) {
            val json = Json { this.encodeDefaults = encodeDefaults }
            for (case in cases) {
                val input =
                    json.encodeToJsonElement(
                        PublicToolQuerySymbols(
                            PublicToolRunAction(
                                source = PublicToolAllSource(),
                                terminalStep = PublicToolEmit(),
                                executionMode = case.mode,
                                executionBudget = case.budget,
                            )
                        )
                    )
                val admission = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, input)
                if (case.accepted) {
                    val tool = assertInstanceOf(Refinement.Refined::class.java, admission).value as AdmittedPublicTool
                    val run = (tool.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
                    assertEquals(case.mode ?: QueryExecutionModeDocument.RUNNER_CONTROLLED, run.executionMode)
                    assertEquals(case.budget?.maxWorkUnits, run.executionBudget?.maxWorkUnits?.value)
                } else {
                    val rejection = assertInstanceOf(Refinement.Rejected::class.java, admission)
                    assertWorkConstraint(rejection.failure as PublicToolInputFailure)
                }
            }
        }
    }

    @Test
    fun `request serializer preserves the constraint and its correction`() {
        val input =
            Json.encodeToJsonElement(
                PublicToolQuerySymbols(
                    PublicToolRunAction(
                        source = PublicToolAllSource(),
                        terminalStep = PublicToolEmit(),
                        executionBudget = PublicToolExecutionBudget(maxWorkUnits = 1),
                    )
                )
            )
        val failure =
            assertThrows(PublicToolSerializationException::class.java) {
                Json.decodeFromJsonElement(PublicToolRequestSerializer(PublicToolIdentity.QUERY_SYMBOLS), input)
            }
        assertWorkConstraint(failure.failure)
        assertEquals(failure.failure.explanation(), failure.message)
    }

    @Test
    fun `bounded work of one admits an execution allowance rather than an input failure`() {
        val input =
            Json.encodeToJsonElement(
                PublicToolQuerySymbols(
                    PublicToolRunAction(
                        source = PublicToolAllSource(),
                        terminalStep = PublicToolEmit(),
                        executionMode = QueryExecutionModeDocument.BOUNDED,
                        executionBudget = PublicToolExecutionBudget(maxWorkUnits = 1),
                    )
                )
            )
        val admission = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, input) as Refinement.Refined
        val run = (admission.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
        val budget = assertInstanceOf(RequestedExecutionBudget.Bounded::class.java, run.requestedExecutionBudget())
        val work = assertInstanceOf(ExecutionAllowance.Requested::class.java, budget.work)
        assertEquals(run.executionBudget?.maxWorkUnits, work.value)
        assertEquals(1L, run.executionBudget?.maxWorkUnits?.value)
    }

    @Test
    fun `a constraint cannot lose its required parameter when no remaining parameters exist`() {
        val constraint =
            PublicToolInputFailure.Constraint(
                firstParameter = PublicToolParameter.EXECUTION_MODE,
                remainingParameters = emptySet(),
                rule = PublicToolRule.RUNNER_CONTROLLED_WORK_LIMIT,
            )
        assertEquals(setOf(PublicToolParameter.EXECUTION_MODE), constraint.parameters)
    }

    private fun assertWorkConstraint(failure: PublicToolInputFailure) {
        val constraint = assertInstanceOf(PublicToolInputFailure.Constraint::class.java, failure)
        assertEquals(
            setOf(PublicToolParameter.EXECUTION_MODE, PublicToolParameter.EXECUTION_BUDGET_MAX_WORK_UNITS),
            constraint.parameters,
        )
        assertEquals(PublicToolRule.RUNNER_CONTROLLED_WORK_LIMIT, constraint.rule)
        assertEquals(
            "executionMode, executionBudget.maxWorkUnits: " +
                "RUNNER_CONTROLLED does not accept maxWorkUnits; " +
                "remove executionBudget.maxWorkUnits or select BOUNDED.",
            failure.explanation(),
        )
    }
}

private data class ExecutionConstraintCase(
    val mode: QueryExecutionModeDocument?,
    val budget: PublicToolExecutionBudget?,
    val accepted: Boolean,
)
