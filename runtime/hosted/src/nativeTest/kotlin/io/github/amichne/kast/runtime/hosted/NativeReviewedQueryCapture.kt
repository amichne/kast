package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryContainmentDocument
import io.github.amichne.kast.protocol.contract.QueryDirectoryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourcePolicyDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionModeDocument
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryImpactFlowDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultInterpretationDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRetentionModeDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryPresentationExecution
import io.github.amichne.kast.query.protocol.QueryInvocationPolicy
import io.github.amichne.kast.query.protocol.QueryPublishedPage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** The original question completes or rejects before a separate read presents its retained paths. */
internal class NativeReviewedQueryCapture(private val fixture: NativeReviewedQueryCase) {
    private val budget =
        QueryBudget(
            resources = ResourceBudget.RunnerControlled,
            returnedBytes = QueryByteLimit.parse(ORIGINAL_MAX_RETURNED_BYTES).packetQueryRefined(),
            checkpointBytes = QueryByteLimit.DefaultCheckpoint,
            pageResults = ResultLimit.parse(ORIGINAL_MAX_RESULTS).packetQueryRefined(),
        )
    private val policy =
        QueryInvocationPolicy(
            previewRows = budget.outputResults,
            previewBytesLimit = budget.returnedBytes,
            retainedBytes = QueryByteLimit.parse(RETAINED_EVIDENCE_BYTES).packetQueryRefined(),
            previewBytes = CanonicalQueryCliDocuments::previewBytes,
        )

    suspend fun capture(): NativeReviewedQueryResult {
        val services = fixture.services
        val outcome = execute(request())
        val reference = retainedReference(outcome)
        check(services.nativeFlowReads > 0) { "No native K2 value-flow read ran" }
        val reads = services.nativeFlowReads
        val retained = execute(QueryRunRequest.ReadResult.valuePaths(reference))
        check(services.nativeFlowReads == reads) { "Retained presentation repeated semantic work" }
        val page = retainedPage(retained, outcome)
        val paths =
            page.items.values.map { item ->
                check(item is QueryResultItemDocument.ValuePath) { "Unexpected retained item" }
                item.path
            }
        println(
            "R1_RETAINED_VALUE_PATHS ${fixture.variant} " +
                Json.encodeToString(ListSerializer(ImpactPathDocument.serializer()), paths)
        )
        check(paths.isNotEmpty()) { "The producer lost its upstream evidence" }
        println(
            "R1_QUERY_CAPTURE variant=${fixture.variant} complete=${outcome is OperationOutcome.Complete} " +
                "nativeFlowReads=$reads retainedReadDelta=${services.nativeFlowReads - reads} paths=${paths.size}"
        )
        return NativeReviewedQueryResult(outcome, paths)
    }

    private suspend fun execute(request: QueryRunRequest): QueryPublishedPage =
        QueryPresentationExecution.evaluateAndFit(
            evaluate = { owner ->
                fixture.services
                    .protocol(owner)
                    .execute(
                        request = request,
                        lease = fixture.services.lease,
                        budget = budget,
                        policy = policy,
                    )
            },
            fit = ::fit,
        )

    private fun fit(outcome: QueryPublishedPage): QueryPublishedPage {
        val response =
            encodeHostedQueryResponse(
                semantic = outcome,
                maximumResults = budget.outputResults,
                maximumBytes = ReturnedByteLimit.parse(ORIGINAL_MAX_RETURNED_BYTES).packetQueryRefined(),
            )
        check(response is HostedResponse.Canonical<*, *, *>) {
            "R1 hosted response did not fit the original grants: ${response.javaClass.simpleName}"
        }
        check(response.semantic == outcome) { "R1 hosted response changed the original semantic outcome" }
        val bytes = response.document.toByteArray(Charsets.UTF_8).size
        check(bytes <= ORIGINAL_MAX_RETURNED_BYTES) { "R1 encoded response exceeded the original byte grant" }
        println("R1_HOSTED_RESPONSE variant=${fixture.variant} outcome=${response.outcome} bytes=$bytes")
        return outcome
    }

    private fun retainedReference(outcome: QueryPublishedPage): QueryResultReference =
        when (outcome) {
            is OperationOutcome.Complete -> {
                val retention = outcome.evidence.payload.retention
                check(retention is QueryResultRetention.Retained) { "Completed query lost retained evidence" }
                retention.reference
            }
            is OperationOutcome.Rejected -> {
                val reason = outcome.reason
                check(reason is QueryRunRejection.CompletionUnproven) {
                    "Public query failed before semantic completion: $reason ${fixture.services.executionSummary()}"
                }
                println("R1_COMPLETION_REJECTION ${fixture.variant} ${reason.cause} ${reason.originalCoverage}")
                val evidence = reason.evidence
                check(evidence is QueryCompletionEvidenceDocument.Retained) { "Rejected query lost its evidence" }
                evidence.result
            }
            is OperationOutcome.Qualified -> error("COMPLETE_ONLY returned qualified success")
        }

    private fun retainedPage(retained: QueryPublishedPage, outcome: QueryPublishedPage): QueryRunResult =
        when (retained) {
            is OperationOutcome.Complete -> {
                check(outcome is OperationOutcome.Complete) { "Presentation erased the public rejection" }
                retained.evidence.payload
            }
            is OperationOutcome.Qualified -> {
                check(outcome is OperationOutcome.Rejected) { "Presentation changed the public completion" }
                check(retained.evidence.payload.interpretation is QueryResultInterpretationDocument.EvidenceOnly)
                retained.evidence.payload
            }
            is OperationOutcome.Rejected -> error("Retained evidence failed: ${retained.reason}")
        }

    private suspend fun request() =
        QueryRunRequest.Run(
            from =
                QueryFromDocument.Impact(
                    QueryImpactSourceDocument(
                        seeds = packetQueryList(listOf(fixture.source.producer())),
                        declarations = packetQueryList(emptyList()),
                        domain =
                            QueryExpansionScopeDocument.Sources(
                                sourceSets = packetQueryList(listOf(packetQueryText("test"))),
                                directory =
                                    QueryDirectoryScopeDocument(
                                        packetQueryText("src/test/kotlin"),
                                        QueryContainmentDocument.DESCENDANTS,
                                    ),
                                sourcePolicy = QueryDiscoverySourcePolicyDocument.TEST_ONLY,
                                generatedSources = QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
                            ),
                        flow = QueryImpactFlowDocument.KOTLIN_FORWARD_V1,
                        models = packetQueryList(emptyList()),
                    )
                ),
            steps = packetQueryList(emptyList()),
            output = QueryOutputDocument.ImpactWitness(ImpactWitnessSectionDocument.FINDINGS),
            execution =
                QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
            retention = QueryRetentionModeDocument.RETAIN,
            executionBudget =
                ExecutionBudgetDocument(
                    maxResults = ResultLimit.parse(ORIGINAL_MAX_RESULTS).packetQueryRefined(),
                    maxReturnedBytes = ReturnedByteLimit.parse(ORIGINAL_MAX_RETURNED_BYTES).packetQueryRefined(),
                ),
            executionMode = QueryExecutionModeDocument.RUNNER_CONTROLLED,
        )
}

internal data class NativeReviewedQueryResult(val outcome: QueryPublishedPage, val paths: List<ImpactPathDocument>)

private const val ORIGINAL_MAX_RESULTS = 100
private const val ORIGINAL_MAX_RETURNED_BYTES = 1_048_576L
private const val RETAINED_EVIDENCE_BYTES = 32_000_000L
