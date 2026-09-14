package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationContinuationDocument
import io.github.amichne.kast.protocol.contract.RelationKnownMinimumDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionMeasurementDocument
import io.github.amichne.kast.protocol.contract.RelationProviderDocument
import io.github.amichne.kast.protocol.contract.RelationReadPositionDocument
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.RelationReadResult
import io.github.amichne.kast.protocol.contract.RelationRemediationDocument
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedRelationCoverageTest {
    @Test
    fun `terminal relation output drains two pages without losing omissions or inventing upstream completion`() =
        runTest {
            val fixture = RelationPagingFixture.live()
            val request = fixture.request(RelationReadPositionDocument.Start)
            val semantic = terminal(fixture)
            val pages = HostedQueryContinuations.Active(fixture.authority, ReadLimits.Default).relationOutputs
            val first =
                encodeHostedRelationResponse(semantic, ReadLimits.Default, ResultLimit.parse(1).proven()) { suffix ->
                        pages.issue(request, fixture.authority, suffix)
                    }
                    .qualified()
            val progress = first.qualification as RelationReadQualification.Resumable
            assertTrue(progress.continuation.value.startsWith(RelationContinuationDocument.OUTPUT_PREFIX))
            val token = ProtocolText.parse(progress.continuation.value).proven()
            val restored = pages.restore(token, request, fixture.authority)
            val last =
                encodeHostedRelationResponse(restored, ReadLimits.Default) {
                        error("The remaining terminal page fits without another output continuation")
                    }
                    .qualified()
            assertEquals(1, first.evidence.payload.relations.values.size)
            assertEquals(2, last.evidence.payload.relations.values.size)
            assertEquals(
                semantic.evidence.payload.relations.values,
                first.evidence.payload.relations.values + last.evidence.payload.relations.values,
            )
            assertEquals(semantic.qualification, last.qualification)
            for (page in listOf(first, last)) {
                assertEquals(semantic.qualification.knownMinimum, page.qualification.knownMinimum)
                assertTrue(RelationLimitationDocument.PROVIDER_FAILURE in page.qualification.limitations)
                assertEquals(semantic.evidence.payload.omissions, page.evidence.payload.omissions)
                val omission = page.evidence.payload.omissions.values.single()
                assertEquals(RelationOmissionMeasurementDocument.UnmeasuredOnPage, omission.measurement)
                assertEquals(RelationRemediationDocument.RETRY_PROVIDER, omission.remediation)
            }
        }

    @Test
    fun `retained relation wire identifies upstream coverage and supported resume action`() = runTest {
        val fixture = RelationPagingFixture.live()
        val request = fixture.request(RelationReadPositionDocument.Start)
        val pages = HostedQueryContinuations.Active(fixture.authority, ReadLimits.Default).relationOutputs
        val response =
            encodeHostedRelationResponse(terminal(fixture), ReadLimits.Default, ResultLimit.parse(1).proven()) { suffix
                ->
                pages.issue(request, fixture.authority, suffix)
            }
        val body = Json.parseToJsonElement(response.document).jsonObject.getValue("body").jsonObject
        val qualification = body.getValue("qualification").jsonObject
        assertEquals(JsonPrimitive("resume"), qualification["next_action"])
        val checkpoint = qualification.getValue("checkpoint").jsonObject
        assertEquals(JsonPrimitive("retained_output"), checkpoint["type"])
        assertEquals(JsonPrimitive("terminal_incomplete"), checkpoint["upstream"])
        assertEquals(qualification["continuation"], checkpoint["token"])
    }

    private suspend fun terminal(
        fixture: RelationPagingFixture
    ): OperationOutcome.Qualified<RelationReadResult, RelationReadQualification> {
        val page = fixture.page() as OperationOutcome.Qualified
        val omission =
            RelationOmissionDocument.create(
                    provider = RelationProviderDocument.INTELLIJ_REFERENCES_V2,
                    reason = RelationLimitationDocument.PROVIDER_FAILURE,
                    measurement = RelationOmissionMeasurementDocument.UnmeasuredOnPage,
                    samples =
                        BoundedProtocolList.create(
                                emptyList<io.github.amichne.kast.protocol.contract.RelationOmissionLocationDocument>()
                            )
                            .proven(),
                )
                .proven()
        return OperationOutcome.Qualified(
            page.evidence.copy(
                payload = page.evidence.payload.copy(omissions = BoundedProtocolList.create(listOf(omission)).proven())
            ),
            RelationReadQualification.terminalIncomplete(
                    RelationKnownMinimumDocument.parse(page.evidence.payload.relations.values.size).proven(),
                    listOf(RelationLimitationDocument.PROVIDER_FAILURE),
                )
                .proven(),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun HostedResponse.qualified() =
        (this as HostedResponse.Canonical<*, *, *>).semantic
            as OperationOutcome.Qualified<RelationReadResult, RelationReadQualification>

    private fun <Value> Refinement<Value, *>.proven(): Value = (this as Refinement.Refined).value
}
