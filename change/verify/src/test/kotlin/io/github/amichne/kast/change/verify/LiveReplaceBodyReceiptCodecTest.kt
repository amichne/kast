package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.contract.LiveReplaceBodyPlanCodec
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class LiveReplaceBodyReceiptCodecTest {
    @Test
    fun `keyless body receipt records exact local operation without nonce or invocation`() {
        val receipt = fixture(local = true)
        val encoded = LiveReplaceBodyReceiptCodec.encode(receipt)
        val content =
            kotlinx.serialization.json.Json.parseToJsonElement(encoded).jsonObject.getValue("content").jsonObject
        assertEquals(kotlinx.serialization.json.JsonPrimitive(2), content.getValue("version"))
        assertTrue("approval" !in content)
        val execution = content.getValue("execution").jsonObject
        assertEquals(setOf("type", "operation", "root", "host", "planId"), execution.keys)
        assertEquals(kotlinx.serialization.json.JsonPrimitive("LOCAL_ENDPOINT_OPERATION"), execution.getValue("type"))
        assertEquals(kotlinx.serialization.json.JsonPrimitive(receipt.plan.planId.value), execution.getValue("planId"))
        assertEquals(encoded, LiveReplaceBodyReceiptCodec.encode(LiveReplaceBodyReceiptCodec.decode(encoded).refined()))
        assertTrue(
            LiveReplaceBodyReceiptCodec.decode(encoded.replace("LOCAL_ENDPOINT_OPERATION", "UNKNOWN"))
                is Refinement.Rejected
        )
    }

    @Test
    fun `historical body receipt restores exact plan postimage scope and fresh token`() {
        val receipt = fixture()
        val restored = LiveReplaceBodyReceiptCodec.decode(LiveReplaceBodyReceiptCodec.encode(receipt)).refined()
        assertEquals(receipt.identity, restored.identity)
        assertEquals(receipt.plan.planId, restored.plan.planId)
        assertEquals(receipt.postimage, restored.postimage)
        assertEquals(receipt.plan.verificationScope.file, restored.plan.verificationScope.file)
        assertEquals(receipt.freshReference, restored.freshReference)
        assertEquals(receipt.recovery, restored.recovery)
    }

    @Test
    fun `changed postimage or diagnostic scope cannot restore success`() {
        val receipt = fixture()
        val encoded = LiveReplaceBodyReceiptCodec.encode(receipt)
        assertTrue(
            LiveReplaceBodyReceiptCodec.decode(encoded.replace(receipt.postimage.value, "0".repeat(64)))
                is Refinement.Rejected
        )
        assertTrue(
            LiveReplaceBodyReceiptCodec.decode(
                encoded.replace(receipt.plan.verificationScope.file.path.value, "/workspace/other.kt")
            ) is Refinement.Rejected
        )
    }

    @Test
    fun `receipt admits a fresh equivalent workspace model capture`() {
        val receipt = fixture()
        val freshPlan = LiveReplaceBodyPlanCodec.decode(LiveReplaceBodyPlanCodec.encode(receipt.plan)).refined()
        assertTrue(receipt.plan.basis.observation.model !== freshPlan.basis.observation.model)
        assertEquals(
            receipt.identity,
            HistoricalLiveReplaceBodyReceipt.restore(
                    receipt.plan,
                    freshPlan.basis.observation,
                    receipt.postimage,
                    receipt.freshReference,
                    receipt.execution,
                    receipt.recovery,
                )
                .refined()
                .identity,
        )
    }

    private fun fixture(local: Boolean = false): HistoricalLiveReplaceBodyReceipt {
        val plan =
            LiveReplaceBodyPlanCodec.decode(
                    checkNotNull(javaClass.getResource("/live-replace-body-plan-v1.json")).readText().trim()
                )
                .refined()
        val approval =
            HistoricalLiveApproval.restore(
                    "thread",
                    "turn",
                    "call",
                    HistoricalApprovalChallenge.parse("a".repeat(64)).refined(),
                )
                .refined()
        val recovery =
            HistoricalLiveRecovery(
                plan.planId,
                HistoricalRecoveryRecordDigest.parse("b".repeat(64)).refined(),
                HistoricalRecoveryRecordDigest.parse("c".repeat(64)).refined(),
            )
        return HistoricalLiveReplaceBodyReceipt.restore(
                plan,
                plan.basis.observation,
                plan.expectedPostimage,
                BodyExactReference.parse("exact:v5:fixture").refined(),
                if (local)
                    HistoricalLiveExecution.LocalEndpointOperation.restore(
                            plan,
                            plan.planId,
                            plan.basis.observation.reference.workspaceRoot,
                            plan.basis.observation.reference.host,
                            io.github.amichne.kast.change.apply.LiveChangeEffect.CHANGE_APPLY,
                        )
                        .refined()
                else approval,
                recovery,
            )
            .refined()
    }

    private fun <T, F> Refinement<T, F>.refined(): T = assertInstanceOf<Refinement.Refined<T>>(this).value
}
