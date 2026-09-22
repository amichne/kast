package io.github.amichne.kast.cli

import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import io.github.amichne.kast.protocol.contract.SourceCheckpointDocument
import io.github.amichne.kast.protocol.contract.SourceEntityCountDocument
import io.github.amichne.kast.protocol.contract.SourcePreparedCoverageDocument
import io.github.amichne.kast.protocol.contract.SourceQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.SourceReadLimitationDocument
import io.github.amichne.kast.protocol.contract.SourceReadQualification
import io.github.amichne.kast.protocol.contract.SourceTerminalReasonDocument
import io.github.amichne.kast.protocol.contract.SourceTextProjectionDocument
import io.github.amichne.kast.protocol.contract.SourceTextWithheldReasonDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalSourceReadCliDocuments
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SourceProgressSchemaTest {
    @Test
    fun `every source progress variant validates and derives cursor availability`() =
        with(LiveReadOutputSchemaTest()) {
            val basis = EvidenceBasis.Published(EvidenceGeneration.parse(1).proven())
            val result =
                sourceResult(basis)
                    .copy(
                        text =
                            SourceTextProjectionDocument.Withheld(SourceTextWithheldReasonDocument.BYTE_LIMIT_REACHED),
                        executionBudget =
                            ExecutionBudgetReport.from(hostedSchemaBudgetGrant(ExecutionBudgetDocument())),
                    )
            for (progress in progressStates()) {
                val qualification =
                    SourceReadQualification.create(
                            SourceEntityCountDocument.parse(0).proven(),
                            listOf(
                                SourceReadLimitationDocument.TEXT_BYTE_LIMIT_REACHED,
                                SourceReadLimitationDocument.WORK_LIMIT_REACHED,
                            ),
                            progress,
                        )
                        .proven()
                val document =
                    CanonicalSourceReadCliDocuments.project(
                            OperationOutcome.Qualified(
                                EvidenceEnvelope(CanonicalOperation.SOURCE_READ.id, basis, result),
                                qualification,
                            )
                        )
                        .document()
                assertAdmits(CanonicalOperation.SOURCE_READ, document)
                val qualified = document.getValue("qualification").jsonObject
                assertAvailability(progress, qualified)
                assertRejects(
                    CanonicalOperation.SOURCE_READ,
                    document.with(
                        "qualification",
                        qualified.with(
                            "progress",
                            qualified.getValue("progress").jsonObject.with("type", JsonPrimitive("invented")),
                        ),
                    ),
                )
            }
        }

    private fun assertAvailability(progress: SourceQualifiedProgressDocument, qualified: JsonObject) {
        val cursor = qualified.getValue("continuation").jsonObject
        when (progress) {
            is SourceQualifiedProgressDocument.Resumable -> {
                assertEquals(JsonPrimitive("available"), cursor["type"])
                assertEquals(JsonPrimitive(progress.checkpoint.token.value), cursor["continuation"])
            }
            is SourceQualifiedProgressDocument.TerminalIncomplete ->
                assertEquals(JsonPrimitive("unavailable"), cursor["type"])
        }
    }

    private fun progressStates(): List<SourceQualifiedProgressDocument> {
        val upstream =
            SourceQualifiedProgressDocument.Resumable(
                SourceCheckpointDocument.Upstream(
                    ProtocolText.parse("source-read-continuation-v1|" + "a".repeat(64)).proven()
                ),
                ReadResumeActionDocument.INCREASE_EXECUTION_BUDGET,
            )
        val terminals = SourceTerminalReasonDocument.entries.map(SourceQualifiedProgressDocument::TerminalIncomplete)
        val coverage =
            listOf(SourcePreparedCoverageDocument.Complete, SourcePreparedCoverageDocument.Resumable) +
                SourceTerminalReasonDocument.entries.map(SourcePreparedCoverageDocument::TerminalIncomplete)
        return listOf(upstream) +
            terminals +
            coverage.map {
                SourceQualifiedProgressDocument.Resumable(
                    SourceCheckpointDocument.RetainedOutput(
                        ProtocolText.parse("source-output:v1:00000000-0000-0000-0000-000000000001").proven(),
                        it,
                    ),
                    ReadResumeActionDocument.RESUME,
                )
            }
    }

    private fun <Value> Refinement<Value, *>.proven(): Value = (this as Refinement.Refined).value
}
