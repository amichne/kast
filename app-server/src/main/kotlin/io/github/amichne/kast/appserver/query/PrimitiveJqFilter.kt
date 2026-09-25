package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryPredicateDocument
import io.github.amichne.kast.protocol.contract.QueryPrimitiveFieldDocument
import io.github.amichne.kast.protocol.contract.QueryPrimitiveOperatorDocument
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** The public jq spelling is admitted into a finite scalar predicate before it reaches query execution. */
internal object PrimitiveJqFilter {
    private const val MAX_EXPRESSION_LENGTH = 512
    private const val JSON_STRING = "\"(?:\\\\.|[^\"\\\\])*\""
    private val comparison = Regex("^select\\(\\.(name|kind|file)\\s*(==|!=)\\s*($JSON_STRING)\\)$")
    private val textOperation =
        Regex("^select\\(\\.(name|kind|file)\\s*\\|\\s*(startswith|endswith)\\(($JSON_STRING)\\)\\)$")

    fun admit(expression: ProtocolText): Refinement<QueryPredicateDocument.Primitive, PublicToolInputFailure> {
        val raw = expression.value
        if (raw.length > MAX_EXPRESSION_LENGTH) return rejected()
        val comparisonMatch = comparison.matchEntire(raw)
        val operationMatch = textOperation.matchEntire(raw)
        val match = comparisonMatch ?: operationMatch ?: return rejected()
        val literal = match.groupValues[3]
        val value =
            try {
                Json.decodeFromString<String>(literal)
            } catch (_: SerializationException) {
                return rejected()
            }
        val admittedValue =
            when (val parsed = ProtocolText.parse(value)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return rejected()
            }
        val field =
            when (match.groupValues[1]) {
                "name" -> QueryPrimitiveFieldDocument.NAME
                "kind" -> QueryPrimitiveFieldDocument.KIND
                "file" -> QueryPrimitiveFieldDocument.FILE
                else -> return rejected()
            }
        val operator =
            when (match.groupValues[2]) {
                "==" -> QueryPrimitiveOperatorDocument.EQUALS
                "!=" -> QueryPrimitiveOperatorDocument.NOT_EQUALS
                "startswith" -> QueryPrimitiveOperatorDocument.STARTS_WITH
                "endswith" -> QueryPrimitiveOperatorDocument.ENDS_WITH
                else -> return rejected()
            }
        return Refinement.Refined(QueryPredicateDocument.Primitive(field, operator, admittedValue))
    }

    private fun rejected(): Refinement.Rejected<PublicToolInputFailure> =
        Refinement.Rejected(
            PublicToolInputFailure.Parameter(PublicToolParameter.JQ_EXPRESSION, PublicToolRule.SUPPORTED_JQ_FILTER)
        )
}
