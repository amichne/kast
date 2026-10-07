package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.AdmittedExecutionBudget
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.ExecutionBudgetCapacity
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RequestedExecutionBudget
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCheckpointDocument
import io.github.amichne.kast.protocol.contract.QueryExactFailureDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryPreparedCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRelationOmissionDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import io.github.amichne.kast.protocol.contract.RelationFactDocument
import io.github.amichne.kast.protocol.contract.SymbolIdDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedEvaluationOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedQueryResponseTest {
    @Test
    fun `caller byte grant encodes a complete response above the historical transport cap`() {
        val original = OperationOutcome.Complete(envelope(List(40) { item() }, emptyList()))
        val response = encodeHostedQueryResponse(original, maximumBytes = ReturnedByteLimit.parse(524_288).refined())
        assertTrue(response.document.toByteArray(Charsets.UTF_8).size > 65_536)
        assertEquals(original, (response as HostedResponse.Canonical<*, *, *>).semantic)
    }

    @Test
    fun `caller byte allowance includes grant evidence and emits each retained failure once`() {
        val report = ExecutionBudgetReport.from(queryTestGrant())
        val all = List(4) { item() }
        val original = OperationOutcome.Complete(envelope(all, listOf(failure()))).withQueryBudget(report)
        val maximum = ReturnedByteLimit.parse(11000).refined()
        var suffix: HostedQueryOutcome? = null
        val response =
            encodeHostedQueryResponse(original, maximumBytes = maximum) { remaining ->
                suffix = remaining
                HostedOutputRetention.Retained(
                    ProtocolText.parse(HostedQueryContinuations.prefix + "00000000-0000-0000-0000-000000000000")
                        .refined()
                )
            }
                as HostedResponse.Canonical<*, *, *>
        assertTrue(response.document.toByteArray().size <= maximum.value)
        val decoded = CanonicalOperationWireBindings.queryRun.decodeOutcome(response.document) as WireDecoding.Decoded
        val page = (decoded.value as OperationOutcome.Qualified).evidence.payload
        assertEquals(report, page.executionBudget)
        assertTrue(page.failures.values.isEmpty())
        val remainder = (suffix as OperationOutcome.Complete).evidence.payload
        assertEquals(listOf(failure()), remainder.failures.values)
        assertEquals(all, page.items.values + remainder.items.values)
    }

    private fun retainedOutput() =
        HostedOutputRetention.Retained(
            ProtocolText.parse(HostedQueryContinuations.prefix + "00000000-0000-0000-0000-000000000000").refined()
        )

    private fun queryTestGrant(): AdmittedExecutionBudget {
        val resources =
            ResourceBudget(
                ResultLimit.parse(128).refined(),
                WorkUnitLimit.parse(100000).refined(),
                ElapsedTimeLimitMillis.parse(2000).refined(),
            )
        val bytes = ReturnedByteLimit.parse(49152).refined()
        return AdmittedExecutionBudget.admit(
            RequestedExecutionBudget(),
            resources,
            bytes,
            resources,
            bytes,
            ExecutionBudgetCapacity(
                resources.elapsedTimeLimit,
                resources.resultLimit,
                bytes,
            ),
        )
    }

    @Test
    fun `caller page result allowance fits retained output without changing its semantics`() {
        val all = List(3) { item() }
        var suffix: HostedQueryOutcome? = null
        val response =
            encodeHostedQueryResponse(
                OperationOutcome.Complete(envelope(all, emptyList())),
                maximumResults = ResultLimit.parse(1).refined(),
            ) { retained ->
                suffix = retained
                HostedOutputRetention.Retained(
                    ProtocolText.parse(HostedQueryContinuations.prefix + "00000000-0000-0000-0000-000000000000")
                        .refined()
                )
            }
                as HostedResponse.Canonical<*, *, *>
        val qualified =
            org.junit.jupiter.api.Assertions.assertInstanceOf(OperationOutcome.Qualified::class.java, response.semantic)
        val page = qualified.evidence.payload as QueryRunResult
        assertEquals(all.take(1), page.items.values)
        assertEquals(
            listOf(QueryLimitationDocument.RESULT_LIMIT_REACHED),
            (qualified.qualification as QueryRunQualification).limitations,
        )
        val remaining = suffix as OperationOutcome.Complete
        assertEquals(all.drop(1), remaining.evidence.payload.items.values)
    }

    @Test
    fun `opaque reference expansion retains a qualified prefix inside the actual wire byte limit`() {
        val items = List(40) { item() }
        val envelope =
            EvidenceEnvelope(
                CanonicalOperationWireBindings.queryRun.operation.id,
                EvidenceGeneration.parse(1).refined(),
                QueryRunResult(
                    fixtureQueryQuestion(),
                    BoundedProtocolList.create(items).refined(),
                    BoundedProtocolList.create(listOf(failure())).refined(),
                    BoundedProtocolList.create(emptyList<QueryRelationOmissionDocument>()).refined(),
                ),
            )
        val qualification =
            QueryRunQualification.create(
                    QueryKnownMinimum.parse(items.size).refined(),
                    listOf(QueryLimitationDocument.TIME_LIMIT_REACHED),
                    QueryQualifiedProgressDocument.TerminalIncomplete(QueryTerminalReasonDocument.UPSTREAM_INCOMPLETE),
                )
                .refined()
        val response = encodeWithRetention(OperationOutcome.Qualified(envelope, qualification))
        assertEquals(HostedEvaluationOutcome.QUALIFIED, response.outcome)
        assertTrue(
            response.document.toByteArray(Charsets.UTF_8).size <=
                ReadLimits.Default[ReadLimitParameter.HOST_RESPONSE_BYTES].value
        )
        val semantic = (response as HostedResponse.Canonical<*, *, *>).semantic as OperationOutcome.Qualified
        val result = semantic.evidence.payload as QueryRunResult
        val retained = semantic.qualification as QueryRunQualification
        assertTrue(result.items.values.isNotEmpty())
        assertTrue(result.items.values.size < items.size)
        assertEquals(items.take(result.items.values.size), result.items.values)
        assertEquals(envelope.basis, semantic.evidence.basis)
        assertTrue(result.failures.values.isEmpty())
        assertEquals(40, retained.knownMinimum.value)
        assertRetainedTerminalCoverage(retained)
        assertEquals(
            listOf(QueryLimitationDocument.BYTE_LIMIT_REACHED, QueryLimitationDocument.TIME_LIMIT_REACHED),
            retained.limitations,
        )
    }

    @Test
    fun `small complete response remains complete without projection`() {
        val envelope = envelope(listOf(item()), emptyList())
        val response = encodeWithRetention(OperationOutcome.Complete(envelope)) as HostedResponse.Canonical<*, *, *>
        assertEquals(OperationOutcome.Complete(envelope), response.semantic)
    }

    @Test
    fun `oversized complete response retains its proven lower bound as qualified`() {
        val response =
            encodeWithRetention(OperationOutcome.Complete(envelope(List(40) { item() }, emptyList())))
                as HostedResponse.Canonical<*, *, *>
        val outcome = response.semantic as OperationOutcome.Qualified
        val qualification = outcome.qualification as QueryRunQualification
        assertEquals(40, qualification.knownMinimum.value)
        assertEquals(listOf(QueryLimitationDocument.BYTE_LIMIT_REACHED), qualification.limitations)
    }

    @Test
    fun `one indivisible failure exceeding the cap stays rejected`() {
        val response =
            encodeHostedQueryResponse(
                OperationOutcome.Complete(envelope(emptyList(), listOf(failure()))),
                maximumBytes = ReturnedByteLimit.parse(2000).refined(),
            ) {
                retainedOutput()
            }
        assertTrue(response is HostedResponse.Oversized)
        assertEquals(HostedEvaluationOutcome.REJECTED, response.outcome)
    }

    @Test
    fun `actual byte pages retain every suffix and eventually finish`() {
        val original =
            List(54) { index ->
                (item() as QueryResultItemDocument.ExactSymbol).copy(name = ProtocolText.parse("row$index").refined())
            }
        var pending: HostedQueryOutcome = OperationOutcome.Complete(envelope(original, emptyList()))
        val observed = mutableListOf<QueryResultItemDocument>()
        var pages = 0
        while (true) {
            var remainder: HostedQueryOutcome? = null
            val response =
                encodeHostedQueryResponse(
                    pending,
                    maximumResults = ResultLimit.parse(5).refined(),
                ) { retained ->
                    remainder = retained
                    HostedOutputRetention.Retained(
                        ProtocolText.parse(HostedQueryContinuations.prefix + "00000000-0000-0000-0000-000000000000")
                            .refined()
                    )
                }
                    as HostedResponse.Canonical<*, *, *>
            pages++
            check(pages < 20)
            when (val semantic = response.semantic) {
                is OperationOutcome.Complete -> observed += (semantic.evidence.payload as QueryRunResult).items.values
                is OperationOutcome.Qualified -> {
                    val payload = semantic.evidence.payload as QueryRunResult
                    assertEquals(54, (semantic.qualification as QueryRunQualification).knownMinimum.value)
                    assertTrue(
                        (semantic.qualification as QueryRunQualification).progress
                            is QueryQualifiedProgressDocument.Resumable
                    )
                    observed += payload.items.values
                }
                is OperationOutcome.Rejected -> error("Unexpected rejection")
            }
            pending = remainder ?: break
        }
        assertEquals(original, observed)
        assertEquals(11, pages)
    }

    @Test
    fun `projection without retention refuses irreversible clipping`() {
        val response =
            encodeHostedQueryResponse(
                OperationOutcome.Complete(envelope(List(40) { item() }, emptyList())),
                maximumBytes = ReturnedByteLimit.parse(65_536).refined(),
            )
        assertTrue(response is HostedResponse.Oversized)
    }

    private fun assertRetainedTerminalCoverage(retained: QueryRunQualification) {
        val progress = retained.progress as QueryQualifiedProgressDocument.Resumable
        val checkpoint = progress.checkpoint as QueryCheckpointDocument.RetainedOutput
        assertEquals(
            QueryPreparedCoverageDocument.TerminalIncomplete(QueryTerminalReasonDocument.UPSTREAM_INCOMPLETE),
            checkpoint.upstream,
        )
        assertEquals(ReadResumeActionDocument.RESUME, progress.nextAction)
    }

    private fun encodeWithRetention(semantic: HostedQueryOutcome): HostedResponse =
        encodeHostedQueryResponse(semantic, maximumBytes = ReturnedByteLimit.parse(65_536).refined()) {
            HostedOutputRetention.Retained(
                ProtocolText.parse(HostedQueryContinuations.prefix + "00000000-0000-0000-0000-000000000000").refined()
            )
        }

    private fun failure(): QueryItemFailureDocument =
        QueryItemFailureDocument.ExactReference(
            QueryReferenceDocument.ExactSymbol(ProtocolText.parse("exact:v3:" + "b".repeat(3000)).refined()),
            QueryExactFailureDocument.AMBIGUOUS_DECLARATION,
        )

    private fun envelope(items: List<QueryResultItemDocument>, failures: List<QueryItemFailureDocument>) =
        EvidenceEnvelope(
            CanonicalOperationWireBindings.queryRun.operation.id,
            EvidenceGeneration.parse(1).refined(),
            QueryRunResult(
                fixtureQueryQuestion(),
                BoundedProtocolList.create(items).refined(),
                BoundedProtocolList.create(failures).refined(),
                BoundedProtocolList.create(emptyList<QueryRelationOmissionDocument>()).refined(),
            ),
        )

    private fun item(): QueryResultItemDocument =
        QueryResultItemDocument.ExactSymbol(
            QueryReferenceDocument.ExactSymbol(ProtocolText.parse("exact:v3:" + "a".repeat(3000)).refined()),
            SymbolKindDocument.CLASSLIKE,
            null,
            null,
            null,
            BoundedProtocolList.create(emptyList<RelationFactDocument>()).refined(),
            SymbolIdDocument.parse("sym:" + "A".repeat(43)).refined(),
        )

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}

private fun fixtureQueryQuestion(): QueryQuestionDocument {
    fun <Value, Failure> fixtureValue(value: Refinement<Value, Failure>): Value =
        when (value) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> error("Invalid question fixture: ${value.failure}")
        }
    return QueryQuestionDocument(
        QueryFromDocument.Location(
            fixtureValue(ProtocolText.parse("Fixture.kt")),
            fixtureValue(ProtocolOffset.parse(0)),
        ),
        fixtureValue(BoundedProtocolList.create(emptyList())),
        QueryOutputDocument.Symbols(fixtureValue(BoundedProtocolList.create(listOf(QuerySymbolFieldDocument.NAME)))),
    )
}
