package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.apply.LiveApprovalChallenge
import io.github.amichne.kast.change.contract.LiveReplaceBodyPlanCodec
import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class LiveReplaceBodyReceiptCodecTest {
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
                    receipt.approval,
                    receipt.recovery,
                )
                .refined()
                .identity,
        )
    }

    private fun fixture(): HistoricalLiveReplaceBodyReceipt {
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
                    LiveApprovalChallenge.parse("a".repeat(64)).refined(),
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
                approval,
                recovery,
            )
            .refined()
    }

    private fun <T, F> Refinement<T, F>.refined(): T = assertInstanceOf<Refinement.Refined<T>>(this).value
}
