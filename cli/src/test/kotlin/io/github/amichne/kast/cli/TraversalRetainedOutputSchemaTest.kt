package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.projection.CanonicalReadCliDocuments
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import io.github.amichne.kast.protocol.contract.TraversalCheckpointDocument
import io.github.amichne.kast.protocol.contract.TraversalContinuationDocument
import io.github.amichne.kast.protocol.contract.TraversalLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalPreparedCoverageDocument
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TraversalRetainedOutputSchemaTest {
    private val fixture = LiveReadOutputSchemaTest()
    private val token =
        TraversalContinuationDocument.parse("traversal-output:v1:00000000-0000-0000-0000-000000000001").proven()

    @Test
    fun `retained traversal coverage and resume action survive CLI and installed schemas`() = runTest {
        val cases =
            listOf(
                TraversalPreparedCoverageDocument.COMPLETE to "complete",
                TraversalPreparedCoverageDocument.RESUMABLE to "resumable",
                TraversalPreparedCoverageDocument.TERMINAL_INCOMPLETE to "terminal_incomplete",
            )
        for (owner in listOf(RelationPagingFixture.published(), RelationPagingFixture.live())) {
            val page = owner.page() as OperationOutcome.Qualified
            for ((coverage, expected) in cases) {
                val outcome =
                    OperationOutcome.Qualified(
                        EvidenceEnvelope(
                            CanonicalOperation.TRAVERSAL_RUN.id,
                            page.evidence.basis,
                            fixture.traversalResult(),
                        ),
                        qualification(coverage),
                    )
                with(fixture) {
                    val document = CanonicalReadCliDocuments.projectTraversal(outcome).document()
                    assertAdmits(CanonicalOperation.TRAVERSAL_RUN, document)
                    val qualification = document.getValue("qualification").jsonObject
                    val checkpoint = qualification.getValue("checkpoint").jsonObject
                    assertEquals(JsonPrimitive("retained_output"), checkpoint["type"])
                    assertEquals(JsonPrimitive(expected), checkpoint["upstream"])
                    assertEquals(JsonPrimitive(token.value), checkpoint["token"])
                    assertEquals(checkpoint["token"], qualification["continuation"])
                    assertEquals(JsonPrimitive("resume"), qualification["next_action"])
                    assertEquals(
                        JsonPrimitive("resume"),
                        qualification
                            .getValue("recovery")
                            .let { (it as kotlinx.serialization.json.JsonArray).first() }
                            .jsonObject
                            .getValue("action"),
                    )
                    assertInvalidCheckpoints(document, qualification, checkpoint)
                }
            }
        }
    }

    private fun qualification(coverage: TraversalPreparedCoverageDocument) =
        TraversalRunQualification.admitResumable(
                limitations =
                    when (coverage) {
                        TraversalPreparedCoverageDocument.COMPLETE,
                        TraversalPreparedCoverageDocument.RESUMABLE ->
                            listOf(TraversalLimitationDocument.BYTE_LIMIT_REACHED)
                        TraversalPreparedCoverageDocument.TERMINAL_INCOMPLETE ->
                            listOf(
                                TraversalLimitationDocument.BYTE_LIMIT_REACHED,
                                TraversalLimitationDocument.DEPTH_LIMIT_REACHED,
                            )
                    },
                relationLimitations = emptyList(),
                checkpoint = TraversalCheckpointDocument.RetainedOutput(token, coverage),
                nextAction = ReadResumeActionDocument.RESUME,
            )
            .proven()

    private fun assertInvalidCheckpoints(document: JsonObject, qualification: JsonObject, checkpoint: JsonObject) =
        with(fixture) {
            val operation = CanonicalOperation.TRAVERSAL_RUN
            for (invalid in listOf(JsonNull, JsonPrimitive("unknown"))) {
                assertRejects(
                    operation,
                    document.with(
                        "qualification",
                        qualification.with("checkpoint", checkpoint.with("upstream", invalid)),
                    ),
                )
                assertRejects(operation, document.with("qualification", qualification.with("next_action", invalid)))
            }
        }

    private fun <Value> Refinement<Value, *>.proven(): Value = (this as Refinement.Refined).value
}
