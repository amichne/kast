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
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.protocol.QueryInvocationPolicy

internal object CrossModuleCallbackRequests {
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

    fun discover(unit: CrossModuleCallbackUnit, name: String, supply: CrossModuleCallbackSupply): QueryRunRequest.Run {
        val offset = unit.source(supply).indexOf("fun $name(")
        require(offset >= 0) { "The independent fixture root must name an authored declaration" }
        return QueryRunRequest.Run(
            from =
                QueryFromDocument.Location(
                    text("${unit.directory}/${unit.fileName}"),
                    ProtocolOffset.parse(offset + "fun ".length).traceRefined(),
                ),
            steps = bounded(emptyList<QueryStepDocument>()),
            output = output,
            execution = execution,
            executionBudget = executionBudget,
        )
    }

    fun related(
        reference: QueryReferenceDocument.ExactSymbol,
        relation: RelationKindDocument,
        destination: QueryExpansionScopeDocument,
    ) =
        QueryRunRequest.Run(
            from = QueryFromDocument.References(bounded(listOf(reference))),
            steps = bounded(listOf(QueryStepDocument.Related(relation, destination))),
            output = output,
            execution = execution,
            executionBudget = executionBudget,
        )

    fun domain(unit: CrossModuleCallbackUnit) =
        QueryExpansionScopeDocument.Sources(
            sourceSets = bounded(listOf(text("main"))),
            directory = QueryDirectoryScopeDocument(text(unit.directory), QueryContainmentDocument.DESCENDANTS),
            sourcePolicy = QueryDiscoverySourcePolicyDocument.PRODUCTION_ONLY,
            generatedSources = QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
        )

    private val output =
        QueryOutputDocument.Symbols(
            bounded(
                listOf(
                    QuerySymbolFieldDocument.NAME,
                    QuerySymbolFieldDocument.LOCATION,
                    QuerySymbolFieldDocument.SIGNATURE,
                )
            )
        )
    private val execution =
        QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE)
    private val executionBudget =
        ExecutionBudgetDocument(
            maxElapsedMillis = NativeScopedTraceFixture.resources.elapsedTimeLimit,
            maxWorkUnits = NativeScopedTraceFixture.resources.workUnitLimit,
            maxResults = NativeScopedTraceFixture.resources.resultLimit,
            maxReturnedBytes = ReturnedByteLimit.parse(NativeScopedTraceFixture.RETURNED_BYTES).traceRefined(),
        )

    private fun text(value: String) = ProtocolText.parse(value).traceRefined()

    private fun <Value> bounded(values: List<Value>) = BoundedProtocolList.create(values).traceRefined()
}
