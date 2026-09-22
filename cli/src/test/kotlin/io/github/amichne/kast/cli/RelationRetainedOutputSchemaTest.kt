package io.github.amichne.kast.cli

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import io.github.amichne.kast.protocol.contract.RelationCheckpointDocument
import io.github.amichne.kast.protocol.contract.RelationContinuationDocument
import io.github.amichne.kast.protocol.contract.RelationKnownMinimumDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationPreparedCoverageDocument
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.wire.presentation.CanonicalReadCliDocuments
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RelationRetainedOutputSchemaTest {
    private val fixture = LiveReadOutputSchemaTest()
    private val token =
        RelationContinuationDocument.parse("relation-output:v1:00000000-0000-0000-0000-000000000001").proven()

    @Test
    fun `relation CLI retains every upstream coverage state with explicit output resume action`() = runTest {
        val cases =
            listOf(
                RelationPreparedCoverageDocument.COMPLETE to "complete",
                RelationPreparedCoverageDocument.RESUMABLE to "resumable",
                RelationPreparedCoverageDocument.TERMINAL_INCOMPLETE to "terminal_incomplete",
            )
        for (owner in listOf(RelationPagingFixture.published(), RelationPagingFixture.live())) {
            val page = owner.page() as OperationOutcome.Qualified
            for ((coverage, expected) in cases) {
                val outcome = OperationOutcome.Qualified(page.evidence, qualification(coverage))
                with(fixture) {
                    val document = CanonicalReadCliDocuments.projectRelation(outcome).document()
                    assertAdmits(CanonicalOperation.RELATION_READ, document)
                    val qualification = document.getValue("qualification").jsonObject
                    val checkpoint = qualification.getValue("checkpoint").jsonObject
                    assertEquals(JsonPrimitive("retained_output"), checkpoint["type"])
                    assertEquals(JsonPrimitive(expected), checkpoint["upstream"])
                    assertEquals(JsonPrimitive(token.value), checkpoint["token"])
                    assertEquals(checkpoint["token"], qualification["continuation"])
                    assertEquals(JsonPrimitive("resume"), qualification["next_action"])
                    for (invalid in listOf(JsonNull, JsonPrimitive("unknown"))) {
                        assertRejects(
                            CanonicalOperation.RELATION_READ,
                            document.with(
                                "qualification",
                                qualification.with("checkpoint", checkpoint.with("upstream", invalid)),
                            ),
                        )
                        assertRejects(
                            CanonicalOperation.RELATION_READ,
                            document.with("qualification", qualification.with("next_action", invalid)),
                        )
                    }
                }
            }
        }
    }

    private fun qualification(coverage: RelationPreparedCoverageDocument) =
        RelationReadQualification.admitResumable(
                knownMinimum = RelationKnownMinimumDocument.parse(3).proven(),
                limitations =
                    when (coverage) {
                        RelationPreparedCoverageDocument.COMPLETE ->
                            listOf(RelationLimitationDocument.RESULT_LIMIT_REACHED)
                        RelationPreparedCoverageDocument.RESUMABLE,
                        RelationPreparedCoverageDocument.TERMINAL_INCOMPLETE ->
                            listOf(
                                RelationLimitationDocument.RESULT_LIMIT_REACHED,
                                RelationLimitationDocument.PROVIDER_FAILURE,
                            )
                    },
                checkpoint = RelationCheckpointDocument.RetainedOutput(token, coverage),
                nextAction = ReadResumeActionDocument.RESUME,
            )
            .proven()

    private fun <Value> Refinement<Value, *>.proven(): Value = (this as Refinement.Refined).value
}
