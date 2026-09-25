package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryPrimitiveFieldDocument
import io.github.amichne.kast.protocol.contract.QueryPrimitiveOperatorDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class PrimitiveJqFilterTest {
    @Test
    fun `bounded jq select forms refine to exact primitive predicates`() {
        val cases =
            listOf(
                Triple(
                    "select(.name == \"PaymentService\")",
                    QueryPrimitiveFieldDocument.NAME,
                    QueryPrimitiveOperatorDocument.EQUALS,
                ),
                Triple(
                    "select(.kind != \"function\")",
                    QueryPrimitiveFieldDocument.KIND,
                    QueryPrimitiveOperatorDocument.NOT_EQUALS,
                ),
                Triple(
                    "select(.file | startswith(\"/workspace\"))",
                    QueryPrimitiveFieldDocument.FILE,
                    QueryPrimitiveOperatorDocument.STARTS_WITH,
                ),
                Triple(
                    "select(.file | endswith(\".kt\"))",
                    QueryPrimitiveFieldDocument.FILE,
                    QueryPrimitiveOperatorDocument.ENDS_WITH,
                ),
            )
        cases.forEach { (expression, field, operator) ->
            val predicate =
                (PrimitiveJqFilter.admit((ProtocolText.parse(expression) as Refinement.Refined).value)
                        as Refinement.Refined)
                    .value
            assertEquals(field, predicate.field)
            assertEquals(operator, predicate.operator)
        }
    }

    @Test
    fun `jq functions outside finite filter grammar reject before execution`() {
        listOf(".", "select(.name | test(\".*\"))", "select(.unknown == \"x\")", "select(.name == \"\")").forEach {
            val expression = (ProtocolText.parse(it) as Refinement.Refined).value
            assertInstanceOf(Refinement.Rejected::class.java, PrimitiveJqFilter.admit(expression))
        }
    }
}
