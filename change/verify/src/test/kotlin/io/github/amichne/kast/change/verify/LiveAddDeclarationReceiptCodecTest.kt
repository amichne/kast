package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.contract.LiveAddDeclarationPlanCodec
import io.github.amichne.kast.change.contract.LiveChangeBasis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.IdeReadEpochRevision
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class LiveAddDeclarationReceiptCodecTest {
    @Test
    fun `historical receipt round trip retains exact plan scope approval recovery and observed evidence`() {
        val receipt = historicalReceiptFixture()
        val encoded = LiveAddDeclarationReceiptCodec.encode(receipt)
        val restored = LiveAddDeclarationReceiptCodec.decode(encoded).receiptRefined()
        assertEquals(encoded, LiveAddDeclarationReceiptCodec.encode(restored))
        assertEquals(receipt.identity, restored.identity)
        assertEquals(receipt.before.reference, restored.before.reference)
        assertEquals(receipt.after.reference, restored.after.reference)
        assertEquals(receipt.after.model.sourceRoots, restored.after.model.sourceRoots)
        assertEquals(receipt.postimage, restored.postimage)
        assertEquals(receipt.anchor, restored.anchor)
        assertEquals(receipt.evidence, restored.evidence)
        assertEquals(receipt.approval.call, restored.approval.call)
        assertEquals(receipt.recovery, restored.recovery)
        assertEquals(receipt.semanticObligations, restored.semanticObligations)
        assertEquals(receipt.liveObligations, restored.liveObligations)
        assertFalse(encoded.contains("generation"))
    }

    @Test
    fun `receipt identity binds postimage approval and observed recovery chain`() {
        val encoded = LiveAddDeclarationReceiptCodec.encode(historicalReceiptFixture())
        assertEquals(
            LiveReceiptFailure.IDENTITY_MISMATCH,
            rejected(rewriteBody(encoded) { it["postimage"] = JsonPrimitive("4".repeat(64)) }),
        )
        assertEquals(LiveReceiptFailure.IDENTITY_MISMATCH, rejected(encoded.replace("fixture-call", "different-call")))
        assertEquals(
            LiveReceiptFailure.RECOVERY_MISMATCH,
            rejected(encoded.replace("\"applied\":\"${"b".repeat(64)}\"", "\"applied\":\"${"a".repeat(64)}\"")),
        )
    }

    @Test
    fun `missing obligations or incomplete semantic evidence cannot be restored`() {
        val encoded = LiveAddDeclarationReceiptCodec.encode(historicalReceiptFixture())
        assertEquals(
            LiveReceiptFailure.OBLIGATIONS_INCOMPLETE,
            rejected(rewriteBody(encoded) { it["semanticObligations"] = JsonArray(emptyList()) }),
        )
        assertEquals(
            LiveReceiptFailure.OBLIGATIONS_INCOMPLETE,
            rejected(rewriteBody(encoded) { it["liveObligations"] = JsonArray(emptyList()) }),
        )
        assertEquals(
            LiveReceiptFailure.VERIFICATION_EVIDENCE_INCOMPLETE,
            rejected(
                rewriteBody(encoded) { body ->
                    body["evidence"] =
                        JsonObject(
                            body.getValue("evidence").jsonObject.toMutableMap().apply {
                                put("traversals", JsonArray(emptyList()))
                            }
                        )
                }
            ),
        )
    }

    @Test
    fun `stale owner epoch and changed after model are rejected`() {
        val encoded = LiveAddDeclarationReceiptCodec.encode(historicalReceiptFixture())
        listOf(
                rewriteAfter(encoded, "owner", JsonPrimitive(UUID(0, 99).toString())),
                rewriteAfter(encoded, "epoch", JsonPrimitive(6)),
                rewriteAfter(encoded, "model", JsonArray(emptyList())),
            )
            .forEach { assertEquals(LiveReceiptFailure.RESULTING_BASIS_MISMATCH, rejected(it)) }
    }

    @Test
    fun `unknown version and malformed shape fail closed`() {
        val encoded = LiveAddDeclarationReceiptCodec.encode(historicalReceiptFixture())
        assertEquals(
            LiveReceiptFailure.VERSION_UNSUPPORTED,
            rejected(encoded.replaceFirst("\"version\":1", "\"version\":2")),
        )
        assertEquals(LiveReceiptFailure.MALFORMED, rejected(encoded.dropLast(1)))
        assertEquals(LiveReceiptFailure.MALFORMED, rejected(encoded.dropLast(1) + ",\"unknown\":true}"))
    }

    private fun rewriteAfter(encoded: String, key: String, value: JsonElement) =
        rewriteBody(encoded) { body ->
            body["after"] = JsonObject(body.getValue("after").jsonObject.toMutableMap().apply { put(key, value) })
        }

    private fun rewriteBody(encoded: String, change: (MutableMap<String, JsonElement>) -> Unit): String {
        val root = Json.parseToJsonElement(encoded).jsonObject.toMutableMap()
        val content = root.getValue("content").jsonObject.toMutableMap()
        val body = content.getValue("body").jsonObject.toMutableMap().apply(change)
        content["body"] = JsonObject(body)
        root["content"] = JsonObject(content)
        return JsonObject(root).toString()
    }

    private fun rejected(encoded: String) =
        assertInstanceOf<Refinement.Rejected<LiveReceiptFailure>>(LiveAddDeclarationReceiptCodec.decode(encoded))
            .failure
}

/**
 * Synthetic historical data for codec/storage checks. It is never admitted as live authority or verified application.
 */
internal fun historicalReceiptFixture(): HistoricalLiveAddDeclarationReceipt {
    val plan =
        LiveAddDeclarationPlanCodec.decode(
                checkNotNull(
                        LiveAddDeclarationReceiptCodecTest::class.java.getResource("/live-add-declaration-plan-v1.json")
                    )
                    .readText()
            )
            .receiptRefined()
    val before = plan.basis.observation
    val after =
        LiveChangeBasis.observe(
                before.reference.copy(epoch = IdeReadEpochRevision.parse(8).receiptRefined()),
                before.model,
            )
            .receiptRefined()
    val postimage = receiptPostimageDigest()
    return HistoricalLiveAddDeclarationReceipt.restore(
            plan = plan,
            result =
                HistoricalLiveSemanticResult(
                    after = after,
                    postimage = WorkspaceSourceContentHash.parse(postimage).receiptRefined(),
                    anchor = plan.target.evidence,
                    observedDelta = plan.expectedSemanticDelta,
                    evidence = plan.evidence,
                ),
            approval = receiptApprovalFixture(),
            recovery =
                HistoricalLiveRecovery(
                    plan.planId,
                    HistoricalRecoveryRecordDigest.parse("a".repeat(64)).receiptRefined(),
                    HistoricalRecoveryRecordDigest.parse("b".repeat(64)).receiptRefined(),
                ),
            obligations =
                HistoricalLiveReceiptObligations(
                    plan.requiredVerification.semanticObligations,
                    plan.requiredVerification.liveObligations,
                ),
        )
        .receiptRefined()
}

private fun receiptApprovalFixture(): HistoricalLiveApproval =
    HistoricalLiveApproval.restore(
            thread = "fixture-thread",
            turn = "fixture-turn",
            call = "fixture-call",
            challenge =
                io.github.amichne.kast.change.apply.LiveApprovalChallenge.parse("c".repeat(64)).receiptRefined(),
        )
        .receiptRefined()

private fun receiptPostimageDigest(): String =
    MessageDigest.getInstance("SHA-256")
        .digest("package sample\nfun service() = 1\n\nfun added() = 1\n".toByteArray())
        .joinToString("") { "%02x".format(Locale.ROOT, it) }

private fun <T, F> Refinement<T, F>.receiptRefined(): T = assertInstanceOf<Refinement.Refined<T>>(this).value
