package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryContainmentDocument
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryDirectoryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourcePolicyDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.protocol.QueryInvocationPolicy

internal object NativeSemanticOwnersRequests {
    val directory =
        QueryDirectoryScopeDocument(
            text(NativeScopedTraceFixture.SOURCE_DIRECTORY),
            QueryContainmentDocument.DESCENDANTS,
        )
    private val domain =
        QueryExpansionScopeDocument.Sources(
            sourceSets = bounded(listOf(text("main"))),
            directory = directory,
            sourcePolicy = QueryDiscoverySourcePolicyDocument.PRODUCTION_ONLY,
            generatedSources = QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
        )
    val budget =
        QueryBudget(
            NativeScopedTraceFixture.resources,
            QueryByteLimit.parse(NativeScopedTraceFixture.RETURNED_BYTES).traceRefined(),
        )
    val policy =
        QueryInvocationPolicy(
            previewRows = NativeScopedTraceFixture.resources.resultLimit,
            previewBytesLimit = budget.returnedBytes,
            retainedBytes = QueryByteLimit.parse(32_000_000L).traceRefined(),
            previewBytes = CanonicalQueryCliDocuments::previewBytes,
        )

    fun request(shape: NativeSemanticOwnersTargetShape, relation: RelationKindDocument = RelationKindDocument.CALLERS) =
        QueryRunRequest.Run(
            from =
                QueryFromDocument.Symbols(
                    QueryMatchDocument.Name(
                        text(if (shape == NativeSemanticOwnersTargetShape.FUNCTION) "target" else "Target"),
                        SymbolDiscoveryMatchDocument.EXACT_NAME,
                    ),
                    QueryScopeDocument(bounded(listOf(text("main"))), directory, null),
                    bounded(
                        listOf(
                            if (shape == NativeSemanticOwnersTargetShape.FUNCTION) QueryDeclarationKindDocument.FUNCTION
                            else QueryDeclarationKindDocument.CLASS
                        )
                    ),
                ),
            steps = bounded(listOf(QueryStepDocument.Related(relation, domain))),
            output =
                QueryOutputDocument.Symbols(
                    bounded(
                        listOf(
                            QuerySymbolFieldDocument.NAME,
                            QuerySymbolFieldDocument.LOCATION,
                            QuerySymbolFieldDocument.SIGNATURE,
                        )
                    )
                ),
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
