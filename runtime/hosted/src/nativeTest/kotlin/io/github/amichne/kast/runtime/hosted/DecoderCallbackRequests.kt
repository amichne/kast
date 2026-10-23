package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryContainmentDocument
import io.github.amichne.kast.protocol.contract.QueryDirectoryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourcePolicyDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument

internal object DecoderCallbackRequests {
    val useScope =
        QueryExpansionScopeDocument.Sources(
            sourceSets = bounded(listOf(text("main"))),
            directory = QueryDirectoryScopeDocument(text("use"), QueryContainmentDocument.DESCENDANTS),
            sourcePolicy = QueryDiscoverySourcePolicyDocument.PRODUCTION_ONLY,
            generatedSources = QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
        )
    val output =
        QueryOutputDocument.Symbols(
            bounded(
                listOf(
                    QuerySymbolFieldDocument.NAME,
                    QuerySymbolFieldDocument.LOCATION,
                    QuerySymbolFieldDocument.SIGNATURE,
                )
            )
        )

    fun discover() =
        request(
            QueryFromDocument.Location(
                text(DecoderFixtureModule.CONTRACT.relativePath),
                ProtocolOffset.parse(DecoderFixtureModule.CONTRACT.source().indexOf("decode(")).traceRefined(),
            ),
            emptyList(),
            output,
        )

    fun query(reference: QueryReferenceDocument.ExactSymbol, step: QueryStepDocument, output: QueryOutputDocument) =
        request(QueryFromDocument.References(bounded(listOf(reference))), listOf(step), output)

    private fun request(from: QueryFromDocument, steps: List<QueryStepDocument>, output: QueryOutputDocument) =
        QueryRunRequest.Run(
            from = from,
            steps = bounded(steps),
            output = output,
            execution =
                QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
            executionBudget =
                ExecutionBudgetDocument(
                    maxElapsedMillis = NativeScopedTraceFixture.resources.elapsedTimeLimit,
                    maxWorkUnits = NativeScopedTraceFixture.resources.workUnitLimit,
                    maxResults = NativeScopedTraceFixture.resources.resultLimit,
                    maxReturnedBytes = ReturnedByteLimit.parse(NativeScopedTraceFixture.RETURNED_BYTES).traceRefined(),
                ),
        )

    private fun text(value: String) = ProtocolText.parse(value).traceRefined()

    private fun <Value> bounded(values: List<Value>) = BoundedProtocolList.create(values).traceRefined()
}
