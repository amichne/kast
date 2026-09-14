package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import io.github.amichne.kast.cli.projection.CanonicalReadCliDocuments
import io.github.amichne.kast.kernel.AdmittedExecutionBudget
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ExecutionBudgetCapacity
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.RelationContinuationDocument
import io.github.amichne.kast.protocol.contract.RelationReadPositionDocument
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RelationHostedSchemaTest {
    @Test
    fun `hosted output cursor satisfies installed input and output contracts`() = runTest {
        with(LiveReadOutputSchemaTest()) {
            val fixture = RelationPagingFixture.live()
            val original = fixture.page() as OperationOutcome.Qualified
            val cursor =
                RelationContinuationDocument.parse("relation-output:v1:00000000-0000-0000-0000-000000000001").refined()
            val qualification =
                RelationReadQualification.admitResumable(
                        knownMinimum = original.qualification.knownMinimum,
                        limitations = original.qualification.limitations,
                        checkpoint =
                            io.github.amichne.kast.protocol.contract.RelationCheckpointDocument.RetainedOutput(
                                cursor,
                                io.github.amichne.kast.protocol.contract.RelationPreparedCoverageDocument.RESUMABLE,
                            ),
                        nextAction = io.github.amichne.kast.protocol.contract.ReadResumeActionDocument.RESUME,
                    )
                    .refined()
            assertAdmits(
                CanonicalOperation.RELATION_READ,
                CanonicalReadCliDocuments.projectRelation(OperationOutcome.Qualified(original.evidence, qualification))
                    .document(),
            )
            assertTrue(
                relationInputSchema()
                    .validate(
                        Json.encodeToString(fixture.request(RelationReadPositionDocument.Resume(cursor))),
                        InputFormat.JSON,
                    )
                    .isEmpty()
            )
            for (raw in
                listOf(cursor.value + "extra", cursor.value.replace(":v1:", ":v2:"), "relation-output:v1:invalid")) {
                assertTrue(RelationContinuationDocument.parse(raw) is Refinement.Rejected)
            }
        }
    }

    @Test
    fun `relation budget controls and admitted evidence satisfy the installed schemas`() = runTest {
        with(LiveReadOutputSchemaTest()) {
            val fixture = RelationPagingFixture.live()
            val request = ExecutionBudgetDocument(maxResults = ResultLimit.parse(999).refined())
            assertTrue(
                relationInputSchema()
                    .validate(
                        Json.encodeToString(
                            fixture.request(RelationReadPositionDocument.Start).copy(executionBudget = request)
                        ),
                        InputFormat.JSON,
                    )
                    .isEmpty()
            )
            val grant = hostedSchemaBudgetGrant(request)
            val original = fixture.page() as OperationOutcome.Qualified
            val result = original.evidence.payload.copy(executionBudget = ExecutionBudgetReport.from(grant))
            val document =
                CanonicalReadCliDocuments.projectRelation(
                        OperationOutcome.Qualified(original.evidence.copy(payload = result), original.qualification)
                    )
                    .document()
            assertAdmits(CanonicalOperation.RELATION_READ, document)
            val report = document.getValue("execution_budget").jsonObject
            val results = report.getValue("max_results").jsonObject
            assertEquals(JsonPrimitive(128), results["effective"])
            assertEquals(JsonPrimitive("caller"), results["selection"])
            assertRejects(
                CanonicalOperation.RELATION_READ,
                document.with(
                    "execution_budget",
                    report.with("max_results", results.with("effective", JsonPrimitive(0))),
                ),
            )
        }
    }
}

internal fun hostedSchemaBudgetGrant(request: ExecutionBudgetDocument) =
    with(LiveReadOutputSchemaTest()) {
        val resources =
            ResourceBudget(
                ResultLimit.parse(128).refined(),
                WorkUnitLimit.parse(100).refined(),
                ElapsedTimeLimitMillis.parse(200).refined(),
            )
        val bytes = ReturnedByteLimit.parse(10_000).refined()
        AdmittedExecutionBudget.admit(
            request.requested(),
            resources,
            bytes,
            resources,
            bytes,
            ExecutionBudgetCapacity(resources.elapsedTimeLimit, resources.resultLimit, bytes),
        )
    }
