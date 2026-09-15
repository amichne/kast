package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.AdmittedExecutionBudget
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.ExecutionBudgetCapacity
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.AdmittedDiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.DiagnosticDocument
import io.github.amichne.kast.protocol.contract.DiagnosticInventoryDocument
import io.github.amichne.kast.protocol.contract.DiagnosticKnownCountDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLocationDocument
import io.github.amichne.kast.protocol.contract.DiagnosticProgressDocument
import io.github.amichne.kast.protocol.contract.DiagnosticProgressStage
import io.github.amichne.kast.protocol.contract.DiagnosticProgressStop
import io.github.amichne.kast.protocol.contract.DiagnosticRangeDocument
import io.github.amichne.kast.protocol.contract.DiagnosticSeverityDocument
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedDiagnosticResponseTest {
    @Test
    fun `byte suffix replay preserves original analysis continuation and all occurrences`() = runTest {
        val owner = RelationPagingFixture.live()
        val basis = (owner.page() as OperationOutcome.Qualified).evidence.basis
        val file = ProtocolText.parse("/workspace/Heavy.kt").refined()
        val upstream = ProtocolText.parse("diagnostic:v1:analysis").refined()
        val facts = facts(file)
        val progress = progress(file).copy(executionBudget = report())
        val semantic: HostedDiagnosticOutcome =
            OperationOutcome.Qualified(
                EvidenceEnvelope(
                    CanonicalOperation.DIAGNOSTIC_CHECK.id,
                    basis,
                    DiagnosticCheckResult(BoundedProtocolList.create(facts).refined(), progress),
                ),
                analysisQualification(file, upstream),
            )
        val original = HostedResponse.Canonical.encode(CanonicalOperationWireBindings.diagnosticCheck, semantic)
        val limit = ReturnedByteLimit.parse((original.document.toByteArray().size - 1).toLong()).refined()
        val outputs = hostedDiagnosticOutputPages(ReadLimits.Default)
        val request = DiagnosticCheckRequest(ProtocolText.parse(".").refined(), ProtocolCount.parse(4).refined())
        val response =
            encodeHostedDiagnosticResponse(
                semantic.withDiagnosticBudget(report(limit.value)),
                ReadLimits.Default,
                limit,
            ) { suffix ->
                outputs.issue(request, owner.authority, suffix)
            }
                as HostedResponse.Canonical<*, *, *>
        assertTrue(response.document.toByteArray().size <= limit.value)
        val decoded =
            CanonicalOperationWireBindings.diagnosticCheck.decodeOutcome(response.document) as WireDecoding.Decoded
        assertEquals(
            report(limit.value),
            (decoded.value as OperationOutcome.Qualified).evidence.payload.progress?.executionBudget,
        )
        val prefix = response.semantic as OperationOutcome.Qualified
        val qualification = prefix.qualification as DiagnosticCheckQualification
        val token = requireNotNull(qualification.continuation)
        val suffix =
            outputs.restore(token, request.copy(continuation = token), owner.authority) as OperationOutcome.Qualified
        assertEquals(suffix, outputs.restore(token, request.copy(continuation = token), owner.authority))
        assertEquals(upstream, suffix.qualification.continuation)
        assertEquals(
            facts,
            (prefix.evidence.payload as DiagnosticCheckResult).diagnostics.values +
                suffix.evidence.payload.diagnostics.values,
        )
        assertEquals(progress.copy(executionBudget = null), suffix.evidence.payload.progress)
    }

    @Test
    fun `no single diagnostic fits and rejection preserves actual report without issuing cursor`() = runTest {
        val semantic = heavyOutcome()
        val rejected =
            OperationOutcome.Rejected(
                AdmittedDiagnosticCheckRejection(DiagnosticCheckRejection.OUTPUT_GRANT_TOO_SMALL, report())
            )
        val reference = HostedResponse.Canonical.encode(CanonicalOperationWireBindings.diagnosticCheck, rejected)
        val limit = ReturnedByteLimit.parse(reference.document.toByteArray().size.toLong() + 100).refined()
        var retained = 0
        val response =
            encodeHostedDiagnosticResponse(
                semantic.withDiagnosticBudget(report(limit.value)),
                ReadLimits.Default,
                limit,
            ) {
                retained++
                HostedOutputRetention.CapacityExceeded
            }
        assertEquals(0, retained)
        assertTrue(response.document.toByteArray().size <= limit.value)
        assertEquals(
            WireDecoding.Decoded(
                OperationOutcome.Rejected(
                    AdmittedDiagnosticCheckRejection(
                        DiagnosticCheckRejection.OUTPUT_GRANT_TOO_SMALL,
                        report(limit.value),
                    )
                )
            ),
            CanonicalOperationWireBindings.diagnosticCheck.decodeOutcome(response.document),
        )
    }

    @Test
    fun `retention refusal publishes finite reported rejection instead of prefix`() = runTest {
        val semantic = heavyOutcome()
        val reference = HostedResponse.Canonical.encode(CanonicalOperationWireBindings.diagnosticCheck, semantic)
        val limit = ReturnedByteLimit.parse(reference.document.toByteArray().size.toLong() - 1).refined()
        var retained = 0
        val response =
            encodeHostedDiagnosticResponse(
                semantic.withDiagnosticBudget(report(limit.value)),
                ReadLimits.Default,
                limit,
            ) {
                retained++
                HostedOutputRetention.CapacityExceeded
            }
        assertEquals(1, retained)
        assertTrue(response.document.toByteArray().size <= limit.value)
        assertEquals(
            WireDecoding.Decoded(
                OperationOutcome.Rejected(
                    AdmittedDiagnosticCheckRejection(
                        DiagnosticCheckRejection.CONTINUATION_CAPACITY_EXCEEDED,
                        report(limit.value),
                    )
                )
            ),
            CanonicalOperationWireBindings.diagnosticCheck.decodeOutcome(response.document),
        )
    }

    private suspend fun heavyOutcome(): HostedDiagnosticOutcome {
        val owner = RelationPagingFixture.live()
        val basis = (owner.page() as OperationOutcome.Qualified).evidence.basis
        val file = ProtocolText.parse("/workspace/Heavy.kt").refined()
        return OperationOutcome.Complete(
            EvidenceEnvelope(
                CanonicalOperation.DIAGNOSTIC_CHECK.id,
                basis,
                DiagnosticCheckResult(
                    BoundedProtocolList.create(facts(file)).refined(),
                    progress(file).copy(executionBudget = report()),
                ),
            )
        )
    }

    private fun report(returnedBytes: Long = 49152): ExecutionBudgetReport {
        val resources =
            ResourceBudget(
                ResultLimit.parse(128).refined(),
                WorkUnitLimit.parse(100000).refined(),
                ElapsedTimeLimitMillis.parse(2000).refined(),
            )
        val bytes = ReturnedByteLimit.parse(49152).refined()
        return ExecutionBudgetReport.from(
            AdmittedExecutionBudget.admit(
                ExecutionBudgetDocument(maxReturnedBytes = ReturnedByteLimit.parse(returnedBytes).refined())
                    .requested(),
                resources,
                bytes,
                resources,
                bytes,
                ExecutionBudgetCapacity(resources.elapsedTimeLimit, resources.resultLimit, bytes),
            )
        )
    }

    private fun analysisQualification(file: ProtocolText, upstream: ProtocolText) =
        DiagnosticCheckQualification.create(
                DiagnosticKnownCountDocument.parse(4).refined(),
                false,
                listOf(file),
                emptyList(),
                upstream,
            )
            .refined()

    private fun progress(file: ProtocolText) =
        DiagnosticProgressDocument(
            DiagnosticProgressStage.ANALYSIS,
            DiagnosticInventoryDocument.Exhausted(ProtocolCount.parse(2).refined()),
            listOf(file),
            stop = DiagnosticProgressStop.ANALYSIS_PENDING,
            knownDiagnosticCount = DiagnosticKnownCountDocument.parse(4).refined(),
        )

    private fun facts(file: ProtocolText) =
        (0..3).map { offset ->
            DiagnosticDocument(
                DiagnosticSeverityDocument.ERROR,
                ProtocolText.parse("REPEATED").refined(),
                ProtocolText.parse("Repeated diagnostic message ".repeat(100)).refined(),
                DiagnosticLocationDocument(
                    ProtocolText.parse("candidate:fixture:$offset").refined(),
                    file,
                    DiagnosticRangeDocument.create(
                            ProtocolOffset.parse(offset).refined(),
                            ProtocolOffset.parse(offset + 1).refined(),
                        )
                        .refined(),
                ),
            )
        }

    private fun <T> Refinement<T, *>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Invalid fixture: $failure")
        }
}
