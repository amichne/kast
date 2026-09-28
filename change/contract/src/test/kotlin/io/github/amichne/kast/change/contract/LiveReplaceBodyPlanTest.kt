package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class LiveReplaceBodyPlanTest {
    private val preimage = "package sample\nfun service() { }\n"
    private val bodyRange = ExactDeclarationTextRange.parse(29, 32).refined()
    private val oldBody = ExistingBodySourceText.fromCompiler(charArrayOf('{', ' ', '}').concatToString()).refined()
    private val replacement = ReplaceBodySourceText.parse("{ return 2 }").refined()
    private val content = WorkspaceSourceContentHash.parse(sha256Hex(preimage.toByteArray())).refined()

    @Test
    fun `single body replacement preserves signature and surrounding source`() {
        val plan = fixturePlan()
        assertEquals("package sample\nfun service() { return 2 }\n", plan.preservation.postimage)
        assertEquals("fun service() ", plan.preservation.signatureText)
        assertEquals("package sample\nfun service() ", plan.preservation.outsideBefore)
        assertEquals("\n", plan.preservation.outsideAfter)
        assertEquals(content, plan.content)
        assertEquals(plan.expectedPostimage, plan.preservation.expectedPostimage)
        assertEquals(1, plan.writes.entries.size)
        assertInstanceOf<SourceTextMutation.ReplaceBody>(plan.writes.entries.single().mutations.single())
    }

    @Test
    fun `selected overload body changes while sibling overload remains byte identical`() {
        val source = "package sample\nfun service(value: Int): Int { return value }\nfun service(): Int { return 1 }\n"
        val targetStart = source.indexOf("fun service(): Int")
        val bodyStart = source.indexOf("{ return 1 }", targetStart)
        val bodyEnd = bodyStart + "{ return 1 }".length
        val selected = ExactDeclarationTextRange.parse(targetStart, bodyEnd).refined()
        val block = ExactDeclarationTextRange.parse(bodyStart, bodyEnd).refined()
        val before = ExistingBodySourceText.fromCompiler("{ return 1 }").refined()
        val replaced =
            ReplaceBodyPreservation.capture(
                    source,
                    selected,
                    block,
                    before,
                    ReplaceBodySourceText.parse("{ return 2 }").refined(),
                    WorkspaceSourceContentHash.parse(sha256Hex(source.toByteArray())).refined(),
                )
                .refined()
        assertEquals(
            "package sample\nfun service(value: Int): Int { return value }\nfun service(): Int { return 2 }\n",
            replaced.postimage,
        )
    }

    @Test
    fun `stale preimage hash is rejected before a write plan exists`() {
        val wrong = WorkspaceSourceContentHash.parse("0".repeat(64)).refined()
        assertEquals(
            Refinement.Rejected(ReplaceBodyPreservationFailure.SOURCE_HASH_MISMATCH),
            ReplaceBodyPreservation.capture(
                preimage,
                detachedLivePlan().target.range,
                bodyRange,
                oldBody,
                replacement,
                wrong,
            ),
        )
    }

    @Test
    fun `body range cannot include the signature or surrounding source`() {
        val expanded = ExactDeclarationTextRange.parse(28, 32).refined()
        assertEquals(
            Refinement.Rejected(ReplaceBodyPreservationFailure.PREIMAGE_MISMATCH),
            ReplaceBodyPreservation.capture(
                preimage,
                detachedLivePlan().target.range,
                expanded,
                oldBody,
                replacement,
                content,
            ),
        )
    }

    @Test
    fun `canonical codec restores the same plan and rejects tampered postimage`() {
        val plan = fixturePlan()
        val encoded = LiveReplaceBodyPlanCodec.encode(plan)
        val restored = LiveReplaceBodyPlanCodec.decode(encoded).refined()
        assertEquals(plan.planId, restored.planId)
        assertEquals(plan.preservation.postimage, restored.preservation.postimage)
        val tampered = encoded.replace(plan.expectedPostimage.value, "0".repeat(64))
        assertTrue(LiveReplaceBodyPlanCodec.decode(tampered) is Refinement.Rejected)
    }

    private fun fixturePlan(): LiveReplaceBodyChangePlan {
        val add = detachedLivePlan()
        val preservation =
            ReplaceBodyPreservation.capture(
                    preimage,
                    add.target.range,
                    bodyRange,
                    oldBody,
                    replacement,
                    content,
                )
                .refined()
        val input =
            AdmittedLiveReplaceBodyPlanInput.restore(
                    add.basis.observation,
                    add.target,
                    content,
                    preservation,
                )
                .refined()
        return LiveReplaceBodyChangePlan.issue(input)
    }

    private fun <T, F> Refinement<T, F>.refined(): T = assertInstanceOf<Refinement.Refined<T>>(this).value
}
