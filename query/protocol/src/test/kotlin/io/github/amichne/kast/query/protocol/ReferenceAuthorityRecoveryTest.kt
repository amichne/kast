package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.SourceEntitySelectionDocument
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument
import io.github.amichne.kast.protocol.contract.SourceTextRequestDocument
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import io.github.amichne.kast.protocol.wire.presentation.canonicalReadRejectedDocument
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.source.contract.SourceReadRejection as DomainSourceRejection
import io.github.amichne.kast.source.contract.SourceReadResult as DomainSourceResult
import io.github.amichne.kast.source.contract.SourceTextByteLimit
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.LiveReadAuthorityFixture
import io.github.amichne.kast.workspace.contract.MovingLiveReadAuthorityFixture
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReferenceAuthorityRecoveryTest {
    private val root = parsed(CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")))
    private val current = LiveReadAuthorityFixture.create(root)
    private val references = CanonicalQueryReferences()
    private val budget =
        SourceProtocolBudget(
            ResourceBudget(
                parsed(ResultLimit.parse(10)),
                parsed(WorkUnitLimit.parse(100)),
                parsed(ElapsedTimeLimitMillis.parse(1_000)),
            ),
            parsed(SourceTextByteLimit.parse(10_000)),
        )
    private val live =
        LiveSelectorAuthorityDocument(
            current.reference.host.value.toString(),
            current.reference.epoch.value,
            current.reference.contentView.name,
            1,
        )

    @Test
    fun `source stale epoch preserves exact pair and safe action before provider effects`() = runTest {
        val rejected = read(candidate(live.copy(epoch = 2)), expectedCalls = 0)
        assertEquals("stale-authority", rejected.getValue("reason").jsonObject.getValue("reason").jsonPrimitive.content)
        assertEquals("reacquire_authority", rejected.getValue("next_action").jsonPrimitive.content)
        val mismatch = rejected.getValue("reason").jsonObject.getValue("authority_mismatch").jsonObject
        assertEquals(setOf("type", "expected_epoch", "observed_epoch"), mismatch.keys)
        assertEquals("EPOCH_MISMATCH", mismatch.getValue("type").jsonPrimitive.content)
        assertEquals("2", mismatch.getValue("expected_epoch").jsonPrimitive.content)
        assertEquals("1", mismatch.getValue("observed_epoch").jsonPrimitive.content)
    }

    @Test
    fun `query source and concat preserve epoch evidence through canonical rejection`() {
        val moving = MovingLiveReadAuthorityFixture(root)
        val issued = RelationPagingFixture(moving.admit())
        val observed = moving.advance()
        val fresh = RelationPagingFixture(observed)
        fun source(token: ProtocolText) =
            QueryFromDocument.References(
                parsed(BoundedProtocolList.create(listOf(QueryReferenceDocument.ExactSymbol(token))))
            )
        val output =
            QueryOutputDocument.Symbols(parsed(BoundedProtocolList.create(listOf(QuerySymbolFieldDocument.NAME))))
        val requests =
            listOf(
                source(issued.exact) to emptyList(),
                source(fresh.exact) to listOf(QueryStepDocument.Concat(source(issued.exact))),
            )
        for ((from, steps) in requests) {
            val request =
                QueryRunRequest.Run(
                    from,
                    parsed(BoundedProtocolList.create(steps)),
                    output,
                    QueryExecutionDocument(
                        QueryExecutionKindDocument.EXHAUSTIVE,
                        QueryExecutionBudgetDocument.INTERACTIVE,
                    ),
                )
            val rejected =
                request.admitSyntax(observed, issued.references, emptyMap()).refinePlanSyntax() as Refinement.Rejected
            val projected =
                CanonicalQueryCliDocuments.project(OperationOutcome.Rejected(rejected.failure))
                    as ProjectedOperationOutcome.Rejected
            val document = Json.parseToJsonElement(projected.document.value).jsonObject
            val mismatch = document.getValue("rejection").jsonObject.getValue("authority_mismatch").jsonObject
            assertEquals("1", mismatch.getValue("expected_epoch").jsonPrimitive.content)
            assertEquals("2", mismatch.getValue("observed_epoch").jsonPrimitive.content)
            assertEquals("reacquire_authority", document.getValue("next_action").jsonPrimitive.content)
            val outcome = OperationOutcome.Rejected(rejected.failure)
            val encoded = CanonicalOperationWireBindings.queryRun.encodeOutcome(outcome) as WireEncoding.Encoded
            assertTrue(encoded.document.contains("\"expected_epoch\":1"))
            assertTrue(encoded.document.contains("\"observed_epoch\":2"))
            assertEquals(
                WireDecoding.Decoded(outcome),
                CanonicalOperationWireBindings.queryRun.decodeOutcome(encoded.document),
            )
        }
    }

    @Test
    fun `same epoch unsupported content view stays distinct without numeric authority evidence`() = runTest {
        val rejected = read(candidate(live.copy(contentView = "UNSAVED")), expectedCalls = 0)
        val mismatch = rejected.getValue("reason").jsonObject.getValue("authority_mismatch").jsonObject
        assertEquals(setOf("type"), mismatch.keys)
        assertEquals("CONTENT_VIEW_MISMATCH", mismatch.getValue("type").jsonPrimitive.content)
        assertEquals("reacquire_authority", rejected.getValue("next_action").jsonPrimitive.content)
    }

    @Test
    fun `foreign root host unsupported version and invalid epoch issue no epoch pair`() = runTest {
        val cases =
            listOf(
                candidate(live.copy(epoch = 2), "/foreign") to "foreign-workspace",
                candidate(
                    live.copy(host = UUID.fromString("00000000-0000-0000-0000-000000000009").toString(), epoch = 2)
                ) to "incompatible-authority",
                candidate(live.copy(version = 2, epoch = 2)) to "unsupported-version",
                candidate(live.copy(epoch = 0)) to "stale-authority",
                candidate(live.copy(epoch = -1)) to "stale-authority",
            )
        for ((token, expected) in cases) {
            val reason = read(token, expectedCalls = 0).getValue("reason").jsonObject
            assertEquals(expected, reason.getValue("reason").jsonPrimitive.content)
            assertFalse("authority_mismatch" in reason)
        }
    }

    @Test
    fun `matching candidate reaches source while malformed and wrong-family candidates reject`() = runTest {
        val admitted = read(candidate(live), expectedCalls = 1)
        assertEquals("source-unavailable", admitted.getValue("reason").jsonPrimitive.content)
        val malformed = parsed(ProtocolText.parse(candidate(live).value.replaceAfterLast(':', "0".repeat(64))))
        val wrongFamily = parsed(ProtocolText.parse(candidate(live).value.replaceFirst("candidate:", "exact:")))
        for ((token, failure) in listOf(malformed to "payload-digest-mismatch", wrongFamily to "malformed")) {
            val reason = read(token, expectedCalls = 0).getValue("reason").jsonObject
            assertEquals("candidate", reason.getValue("role").jsonPrimitive.content)
            assertEquals(failure, reason.getValue("reason").jsonPrimitive.content)
            assertFalse("authority_mismatch" in reason)
        }
    }

    private suspend fun read(token: ProtocolText, expectedCalls: Int): JsonObject {
        var calls = 0
        val request =
            SourceReadRequest(
                SourceReadAnchorDocument.Candidate(token),
                SourceRegionSelectionDocument.Anchor,
                SourceEntitySelectionDocument.None,
                SourceTextRequestDocument.Complete,
            )
        val outcome =
            CanonicalSourceReadProtocol(
                    SourceReadOperations {
                        calls++
                        DomainSourceResult.Rejected(DomainSourceRejection.SOURCE_UNAVAILABLE)
                    },
                    references,
                )
                .execute(request, current, budget)
        assertEquals(expectedCalls, calls)
        assertTrue(outcome is OperationOutcome.Rejected)
        val encoded = CanonicalOperationWireBindings.sourceRead.encodeOutcome(outcome) as WireEncoding.Encoded
        assertEquals(
            WireDecoding.Decoded(outcome),
            CanonicalOperationWireBindings.sourceRead.decodeOutcome(encoded.document),
        )
        return Json.parseToJsonElement(
                canonicalReadRejectedDocument((outcome as OperationOutcome.Rejected).reason).value
            )
            .jsonObject
    }

    private fun candidate(authority: LiveSelectorAuthorityDocument, requestedRoot: String = root.value): ProtocolText {
        val document =
            CandidateSelectorDocument.Range(
                requestedRoot,
                live = authority,
                file = "/workspace/Subject.kt",
                startInclusive = 0,
                endExclusive = 1,
            )
        val payload = Json.encodeToString(CandidateSelectorDocument.serializer(), document).toByteArray()
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        val digest =
            MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") {
                (it.toInt() and 255).toString(16).padStart(2, '0')
            }
        return parsed(ProtocolText.parse("candidate:v3:$encoded:$digest"))
    }

    private fun <T> parsed(refinement: Refinement<T, *>): T =
        when (refinement) {
            is Refinement.Refined -> refinement.value
            is Refinement.Rejected -> error("Invalid explicit fixture: ${refinement.failure}")
        }
}
