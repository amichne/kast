package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryCompositionInput
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExactReferences
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryPredicate
import io.github.amichne.kast.query.contract.QueryPrimitiveField
import io.github.amichne.kast.query.contract.QueryPrimitiveOperator
import io.github.amichne.kast.query.contract.QueryPrimitiveValue
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class QueryCompositionAndPredicateTest {
    @Test
    fun `structured kind and file predicates filter compiler-grounded facts without source reads`() = runTest {
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
            val predicates =
                listOf(
                    QueryPredicate.Primitive(
                        QueryPrimitiveField.KIND,
                        QueryPrimitiveOperator.EQUALS,
                        QueryPrimitiveValue.parse("class").refined(),
                    ),
                    QueryPredicate.Primitive(
                        QueryPrimitiveField.FILE,
                        QueryPrimitiveOperator.ENDS_WITH,
                        QueryPrimitiveValue.parse(".kt").refined(),
                    ),
                )
            predicates.forEach { predicate ->
                assertEquals(
                    1,
                    service
                        .run(
                            request(exactReferencePlan(listOf(selected), listOf(QueryStepSyntax.Where(predicate))), 8L)
                        )
                        .symbolCount(),
                )
            }
            val mismatch =
                QueryPredicate.Primitive(
                    QueryPrimitiveField.KIND,
                    QueryPrimitiveOperator.EQUALS,
                    QueryPrimitiveValue.parse("function").refined(),
                )
            assertEquals(
                0,
                service
                    .run(request(exactReferencePlan(listOf(selected), listOf(QueryStepSyntax.Where(mismatch))), 8L))
                    .symbolCount(),
            )
        }
    }

    @Test
    fun `concatenated exact outputs flow through later structured filtering and distinct`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            var descriptions = 0
            val exact =
                exactOperations(
                    describe = {
                        descriptions++
                        SymbolDescriptionResult.Described(SymbolDescription.from(it))
                    },
                    resolve = { error("No discovery expected") },
                )
            val plan =
                exactReferencePlan(
                    listOf(selected),
                    listOf(
                        QueryStepSyntax.Concat(
                            QueryCompositionInput.ExactReferences(QueryExactReferences.from(listOf(selected)).refined())
                        ),
                        QueryStepSyntax.Where(
                            QueryPredicate.Primitive(
                                QueryPrimitiveField.NAME,
                                QueryPrimitiveOperator.EQUALS,
                                QueryPrimitiveValue.parse("PaymentService").refined(),
                            )
                        ),
                        QueryStepSyntax.Distinct,
                    ),
                )
            val result = service(exact = exact).run(request(plan, workLimit = 8L))
            assertEquals(1, result.symbolCount())
            assertEquals(2, descriptions)
        }
    }

    @Test
    fun `concat survives a result page and does not rerun the first reference`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            var descriptions = 0
            val exact =
                exactOperations(
                    describe = {
                        descriptions++
                        SymbolDescriptionResult.Described(SymbolDescription.from(it))
                    },
                    resolve = { error("No discovery expected") },
                )
            val plan =
                exactReferencePlan(
                    listOf(selected),
                    listOf(
                        QueryStepSyntax.Concat(
                            QueryCompositionInput.ExactReferences(QueryExactReferences.from(listOf(selected)).refined())
                        )
                    ),
                )
            val service = service(exact = exact)
            val firstRequest = request(plan, workLimit = 8L, resultLimit = 1)
            val first = assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(firstRequest))
            assertEquals(1, first.symbolCount())
            val checkpoint = (first.continuation as QueryContinuationState.Resumable).checkpoint
            val second =
                service.run(
                    QueryExecutionRequest.create(plan, firstRequest.lease, firstRequest.budget, checkpoint).refined()
                )
            assertEquals(1, second.symbolCount())
            assertEquals(2, descriptions)
        }
    }
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = (this as Refinement.Refined).value
