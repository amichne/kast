package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QueryPresentationExecutionTest {
    @Test
    fun `evaluation retires its capability before fitting`() = runTest {
        val order = mutableListOf<String>()
        val result =
            QueryPresentationExecution.evaluateAndFit(
                evaluate = { owner ->
                    assertEquals(Refinement.Refined(Unit), owner.admitValuePath())
                    assertEquals(Refinement.Refined(Unit), owner.admitCallbackEvidence())
                    order += "evaluate"
                    owner
                },
                fit = { owner ->
                    assertEquals(
                        Refinement.Rejected(QueryPresentationAdmissionFailure.OWNER_EXPIRED),
                        owner.admitValuePath(),
                    )
                    assertEquals(
                        Refinement.Rejected(QueryPresentationAdmissionFailure.OWNER_EXPIRED),
                        owner.admitCallbackEvidence(),
                    )
                    order += "fit"
                    QueryExecutionResult.Rejected(QueryExecutionRejection.BUDGET_REJECTED)
                },
            )
        assertEquals(QueryExecutionResult.Rejected(QueryExecutionRejection.BUDGET_REJECTED), result)
        assertEquals(listOf("evaluate", "fit"), order)
    }

    @Test
    fun `evaluation exception retires leaked capability without entering fitter`() {
        lateinit var leaked: QueryPresentationExecution
        var fits = 0
        assertThrows(IllegalStateException::class.java) {
            runTest {
                QueryPresentationExecution.evaluateAndFit(
                    evaluate = { owner ->
                        leaked = owner
                        throw IllegalStateException("scripted evaluation failure")
                    },
                    fit = { _: Unit -> fits++ },
                )
            }
        }
        assertEquals(0, fits)
        assertEquals(Refinement.Rejected(QueryPresentationAdmissionFailure.OWNER_EXPIRED), leaked.admitValuePath())
        assertEquals(
            Refinement.Rejected(QueryPresentationAdmissionFailure.OWNER_EXPIRED),
            leaked.admitCallbackEvidence(),
        )
    }

    @Test
    fun `fitter exception cannot reactivate its evaluation capability`() {
        lateinit var leaked: QueryPresentationExecution
        assertThrows(IllegalStateException::class.java) {
            runTest {
                QueryPresentationExecution.evaluateAndFit(
                    evaluate = { owner -> leaked = owner },
                    fit = { throw IllegalStateException("scripted fitting failure") },
                )
            }
        }
        assertEquals(Refinement.Rejected(QueryPresentationAdmissionFailure.OWNER_EXPIRED), leaked.admitValuePath())
        assertEquals(
            Refinement.Rejected(QueryPresentationAdmissionFailure.OWNER_EXPIRED),
            leaked.admitCallbackEvidence(),
        )
    }
}
