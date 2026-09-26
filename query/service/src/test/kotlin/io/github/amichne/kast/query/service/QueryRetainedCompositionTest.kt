package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryPredicate
import io.github.amichne.kast.query.contract.QueryPrimitiveField
import io.github.amichne.kast.query.contract.QueryPrimitiveOperator
import io.github.amichne.kast.query.contract.QueryPrimitiveValue
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRetainedResultFailure
import io.github.amichne.kast.query.contract.QuerySourceSyntax
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.query.contract.QuerySymbolField
import io.github.amichne.kast.query.contract.QuerySymbolFields
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryRetainedCompositionTest {
    @Test
    fun `split evaluation preserves semantic rows without rediscovering the prefix`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            var descriptions = 0
            val service =
                service(
                    exact =
                        exactOperations(
                            describe = {
                                descriptions++
                                SymbolDescriptionResult.Described(SymbolDescription.from(it))
                            },
                            resolve = { error("No discovery expected") },
                        )
                )
            val suffix = listOf(nameEquals("PaymentService"))
            val unsplit = service.run(request(exactReferencePlan(listOf(selected), suffix), 8L))
            assertEquals(1, descriptions)
            val prefixRequest = request(exactReferencePlan(listOf(selected)), 8L)
            val prefix = service.run(prefixRequest)
            assertEquals(2, descriptions)
            val retained = QueryRetainedResult.capture(prefixRequest.lease, prefix).refined()
            assertNull(retained.producerProgress)
            val split = service.run(request(retainedPlan(retained, suffix), 8L))
            assertEquals(2, descriptions)
            assertEquals(unsplit.rows(), split.rows())
            assertEquals(unsplit::class, split::class)
        }
    }

    @Test
    fun `empty retained input remains a valid complete source`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            var descriptions = 0
            val service =
                service(
                    exact =
                        exactOperations(
                            describe = {
                                descriptions++
                                SymbolDescriptionResult.Described(SymbolDescription.from(it))
                            },
                            resolve = { error("No discovery expected") },
                        )
                )
            val prefixRequest = request(exactReferencePlan(listOf(selected), listOf(nameEquals("Missing"))), 8L)
            val retained = QueryRetainedResult.capture(prefixRequest.lease, service.run(prefixRequest)).refined()
            val result = service.run(request(retainedPlan(retained, listOf(nameEquals("PaymentService"))), 8L))
            assertInstanceOf(QueryExecutionResult.Complete::class.java, result)
            assertTrue(result.rows().isEmpty())
            assertEquals(1, descriptions)
        }
    }

    @Test
    fun `qualified retained input keeps omissions while using known positive rows`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            var descriptions = 0
            val service =
                service(
                    exact =
                        exactOperations(
                            describe = {
                                descriptions++
                                SymbolDescriptionResult.Described(SymbolDescription.from(it))
                            },
                            resolve = { error("No discovery expected") },
                        )
                )
            val prefixRequest = request(exactReferencePlan(listOf(selected, selected)), 8L, resultLimit = 1)
            val prefix = assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(prefixRequest))
            val retained = QueryRetainedResult.capture(prefixRequest.lease, prefix).refined()
            val suffix = service.run(request(retainedPlan(retained, listOf(nameEquals("PaymentService"))), 8L))
            val qualified = assertInstanceOf(QueryExecutionResult.Qualified::class.java, suffix)
            assertEquals(1, qualified.rows().size)
            assertTrue(QueryLimitation.RESULT_LIMIT_REACHED in qualified.coverage.limitations)
            assertEquals(1, descriptions)
            assertTrue(retained.coverage is QueryCoverage.Qualified)
            assertEquals(prefix.continuation, retained.producerProgress)
            val checkpoint = assertInstanceOf(QueryContinuationState.Resumable::class.java, prefix.continuation)
            assertTrue(retained.retainedBytes > checkpoint.checkpoint.retainedBytes)
        }
    }

    @Test
    fun `retained rows and failures are detached from producer and reader lists`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            val service =
                service(
                    exact =
                        exactOperations(
                            describe = { SymbolDescriptionResult.Described(SymbolDescription.from(it)) },
                            resolve = { error("No discovery expected") },
                        )
                )
            val prefixRequest = request(exactReferencePlan(listOf(selected)), 8L)
            val prefix = assertInstanceOf(QueryExecutionResult.Complete::class.java, service.run(prefixRequest))
            val producerRows = prefix.result.items.toMutableList()
            val producerFailures = mutableListOf<QueryItemFailure>(QueryItemFailure.PredicateUnproven(selected))
            val retained =
                QueryRetainedResult.capture(
                        prefixRequest.lease,
                        prefix.copy(
                            result =
                                prefix.result.copy(
                                    items = producerRows,
                                    failures = producerFailures,
                                )
                        ),
                    )
                    .refined()
            producerRows.clear()
            producerFailures.clear()
            (retained.symbols as MutableList).clear()
            assertEquals(1, retained.symbols.size)
            assertEquals(1, retained.failures.size)
        }
    }

    @Test
    fun `retained capture rejects symbols from another read basis`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            val service =
                service(
                    exact =
                        exactOperations(
                            describe = { SymbolDescriptionResult.Described(SymbolDescription.from(it)) },
                            resolve = { error("No discovery expected") },
                        )
                )
            val prefixRequest = request(exactReferencePlan(listOf(selected)), 8L)
            val prefix = service.run(prefixRequest)
            val foreignLease =
                SemanticReadLease(prefixRequest.lease.workspaceRoot, EvidenceGeneration.parse(8L).refined())
            val rejected =
                assertInstanceOf(
                    Refinement.Rejected::class.java,
                    QueryRetainedResult.capture(foreignLease, prefix),
                )
            assertEquals(QueryRetainedResultFailure.BASIS_MISMATCH, rejected.failure)
        }
    }

    @Test
    fun `retained capture rejects producer progress from another read basis`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            val service =
                service(
                    exact =
                        exactOperations(
                            describe = { SymbolDescriptionResult.Described(SymbolDescription.from(it)) },
                            resolve = { error("No discovery expected") },
                        )
                )
            val prefixRequest = request(exactReferencePlan(listOf(selected, selected)), 8L, resultLimit = 1)
            val prefix = assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(prefixRequest))
            val foreignLease =
                SemanticReadLease(prefixRequest.lease.workspaceRoot, EvidenceGeneration.parse(8L).refined())
            val foreignProgress =
                QueryContinuationState.Resumable(
                    object : QueryCheckpoint {
                        override val plan = prefixRequest.plan
                        override val lease = foreignLease
                        override val retainedBytes = 1L
                    }
                )
            val rejected =
                assertInstanceOf(
                    Refinement.Rejected::class.java,
                    QueryRetainedResult.capture(prefixRequest.lease, prefix.copy(continuation = foreignProgress)),
                )
            assertEquals(QueryRetainedResultFailure.BASIS_MISMATCH, rejected.failure)
        }
    }

    private fun QueryServiceTest.retainedPlan(
        retained: QueryRetainedResult,
        steps: List<QueryStepSyntax>,
    ) =
        admittedPlan(
            QuerySourceSyntax.Retained(retained),
            steps,
            QueryOutputSyntax(QuerySymbolFields.from(setOf(QuerySymbolField.NAME)).refined()),
        )

    private fun nameEquals(name: String) =
        QueryStepSyntax.Where(
            QueryPredicate.Primitive(
                QueryPrimitiveField.NAME,
                QueryPrimitiveOperator.EQUALS,
                QueryPrimitiveValue.parse(name).refined(),
            )
        )

    private fun QueryExecutionResult.rows() =
        when (this) {
            is QueryExecutionResult.Complete -> result.items
            is QueryExecutionResult.Qualified -> result.items
            is QueryExecutionResult.Rejected -> error("Unexpected rejection: $reason")
        }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = (this as Refinement.Refined).value
}
