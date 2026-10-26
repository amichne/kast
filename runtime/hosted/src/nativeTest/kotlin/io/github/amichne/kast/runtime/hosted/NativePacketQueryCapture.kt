package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryImpactDeclarationDocument
import io.github.amichne.kast.protocol.contract.QueryImpactFlowDocument
import io.github.amichne.kast.protocol.contract.QueryImpactProducerDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultInterpretationDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRetentionModeDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryPresentationExecution
import io.github.amichne.kast.query.protocol.QueryInvocationPolicy
import io.github.amichne.kast.query.protocol.QueryPublishedPage

/** The production protocol alone explores paths and owns their retained presentation. */
internal class NativePacketQueryCapture(private val services: NativePacketQueryServices) {
    private val budget = NativePacketQueryServices.budget
    private val policy =
        QueryInvocationPolicy(
            previewRows = budget.outputResults,
            previewBytesLimit = budget.returnedBytes,
            retainedBytes = QueryByteLimit.parse(32_000_000L).packetQueryRefined(),
            previewBytes = CanonicalQueryCliDocuments::previewBytes,
        )

    suspend fun paths(
        producer: QueryImpactProducerDocument,
        targets: List<ImpactValueSiteReferenceDocument>,
        outgoing: Boolean,
        declarations: List<QueryImpactDeclarationDocument>,
        domain: QueryExpansionScopeDocument.Sources = packetQueryDomain(),
    ): List<ImpactPathDocument> =
        capture(
                producer = producer,
                targets = targets,
                outgoing = outgoing,
                declarations = declarations,
                domain = domain,
            )
            .paths

    suspend fun capture(
        producer: QueryImpactProducerDocument,
        targets: List<ImpactValueSiteReferenceDocument>,
        outgoing: Boolean,
        declarations: List<QueryImpactDeclarationDocument>,
        domain: QueryExpansionScopeDocument.Sources = packetQueryDomain(),
    ): NativePacketQueryResult {
        val outcome =
            execute(request(producer = producer, targets = targets, declarations = declarations, domain = domain))
        val retained = retained(outcome, outgoing)
        check(services.nativeFlowReads > 0) { "No real K2 value-flow read ran" }
        val reads = services.nativeFlowReads
        val read = execute(QueryRunRequest.ReadResult.valuePaths(retained.reference))
        check(services.nativeFlowReads == reads) { "Retained presentation repeated native semantic work" }
        val result =
            when (read) {
                is OperationOutcome.Complete -> {
                    check(!retained.unproven) { "Retained presentation erased unresolved obligations" }
                    read.evidence.payload
                }
                is OperationOutcome.Qualified -> {
                    check(retained.unproven) { "Retained presentation changed the completion result" }
                    check(read.evidence.payload.interpretation is QueryResultInterpretationDocument.EvidenceOnly)
                    read.evidence.payload
                }
                is OperationOutcome.Rejected -> error("Retained evidence read failed: ${read.reason}")
            }
        val paths =
            result.items.values.map { item ->
                check(item is QueryResultItemDocument.ValuePath) { "Unexpected retained item: $item" }
                item.path
            }
        check(paths.isNotEmpty()) { "No retained compiler witness exists for emitted-witness evaluation" }
        recordCapture(outgoing, reads, paths.size)
        return NativePacketQueryResult(outcome, paths)
    }

    private suspend fun execute(request: QueryRunRequest): QueryPublishedPage =
        QueryPresentationExecution.evaluateAndFit(
            evaluate = { owner ->
                services
                    .protocol(owner)
                    .execute(
                        request = request,
                        lease = services.lease,
                        budget = budget,
                        policy = policy,
                    )
            },
            fit = { outcome ->
                val response =
                    encodeHostedQueryResponse(
                        semantic = outcome,
                        maximumResults = budget.outputResults,
                        maximumBytes = ReturnedByteLimit.parse(budget.returnedBytes.value).packetQueryRefined(),
                    )
                check(response is HostedResponse.Canonical<*, *, *>) { "Hosted query response did not fit" }
                check(response.semantic == outcome) { "Hosted encoding changed the semantic outcome" }
                val bytes = response.document.toByteArray(Charsets.UTF_8).size
                check(bytes <= budget.returnedBytes.value) { "Hosted response exceeded the byte grant" }
                println("PACKET_QUERY_RESPONSE outcome=${response.outcome} bytes=$bytes")
                outcome
            },
        )

    private fun recordCapture(outgoing: Boolean, reads: Int, paths: Int) {
        println(
            "PACKET_QUERY_CAPTURE outgoing=$outgoing nativeFlowReads=$reads " +
                "retainedReadDelta=${services.nativeFlowReads - reads} paths=$paths"
        )
    }

    private fun retained(
        outcome: io.github.amichne.kast.query.protocol.QueryPublishedPage,
        outgoing: Boolean,
    ): PacketQueryRetention =
        when (outcome) {
            is OperationOutcome.Complete -> {
                check(!outgoing) { "Opaque outgoing Transport argument lost its unresolved obligation" }
                val retention = outcome.evidence.payload.retention
                check(retention is QueryResultRetention.Retained) {
                    "Completed incoming query lost its retained result"
                }
                PacketQueryRetention(retention.reference, false)
            }
            is OperationOutcome.Rejected -> {
                val reason = outcome.reason
                check(reason is QueryRunRejection.CompletionUnproven) {
                    "Query setup or admission failed: $reason ${services.executionSummary()}"
                }
                val evidence = reason.evidence
                check(evidence is QueryCompletionEvidenceDocument.Retained) {
                    "Unproven query lost its retained evidence"
                }
                PacketQueryRetention(evidence.result, true)
            }
            is OperationOutcome.Qualified -> error("Strict initial query unexpectedly returned qualified success")
        }

    private fun request(
        producer: QueryImpactProducerDocument,
        targets: List<ImpactValueSiteReferenceDocument>,
        declarations: List<QueryImpactDeclarationDocument>,
        domain: QueryExpansionScopeDocument.Sources,
    ) =
        QueryRunRequest.Run(
            from =
                QueryFromDocument.Impact(
                    QueryImpactSourceDocument(
                        seeds = packetQueryList(listOf(producer)),
                        declarations = packetQueryList(declarations),
                        domain = domain,
                        flow = QueryImpactFlowDocument.KOTLIN_FORWARD_V1,
                        models = packetQueryList(emptyList()),
                        requestedSites = packetQueryList(targets),
                    )
                ),
            steps = packetQueryList(emptyList()),
            output = QueryOutputDocument.ValuePaths,
            execution =
                QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
            retention = QueryRetentionModeDocument.RETAIN,
        )
}

internal fun <T> packetQueryList(values: List<T>) = BoundedProtocolList.create(values).packetQueryRefined()

internal data class NativePacketQueryResult(val outcome: QueryPublishedPage, val paths: List<ImpactPathDocument>)

private data class PacketQueryRetention(val reference: QueryResultReference, val unproven: Boolean)
