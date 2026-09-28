package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.contract.ExistingBodySourceText
import io.github.amichne.kast.change.contract.ReplaceBodyPreservation
import io.github.amichne.kast.change.contract.ReplaceBodySourceText
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class LiveReplaceBodyVerificationTest {
    private val original = "package sample\nfun service() { return 1 }\n"
    private val target = ExactDeclarationTextRange.parse(15, 41).refined()
    private val body = ExactDeclarationTextRange.parse(29, 41).refined()
    private val preservation =
        ReplaceBodyPreservation.capture(
                sourceText = original,
                targetRange = target,
                bodyRange = body,
                originalBody = ExistingBodySourceText.fromCompiler("{ return 1 }").refined(),
                replacement = ReplaceBodySourceText.parse("{ return 2 }").refined(),
                content = hash(original),
            )
            .refined()

    @Test
    fun `exact body postimage proves preserved signature and surroundings`() {
        val admitted =
            VerifiedReplaceBodyPostimage.admit(
                    preservation,
                    "package sample\nfun service() { return 2 }\n",
                    preservation.expectedPostimage,
                )
                .refined()
        assertEquals(preservation.expectedPostimage, admitted.content)
    }

    @Test
    fun `changed surrounding source is rejected despite a matching supplied content identity`() {
        assertEquals(
            Refinement.Rejected(LiveVerificationFailure.SOURCE_POSTIMAGE_MISMATCH),
            VerifiedReplaceBodyPostimage.admit(
                preservation,
                "package changed\nfun service() { return 2 }\n",
                preservation.expectedPostimage,
            ),
        )
    }

    @Test
    fun `changed body and wrong postimage identity are rejected`() {
        assertEquals(
            Refinement.Rejected(LiveVerificationFailure.SOURCE_POSTIMAGE_MISMATCH),
            VerifiedReplaceBodyPostimage.admit(
                preservation,
                "package sample\nfun service() { return 3 }\n",
                preservation.expectedPostimage,
            ),
        )
        assertEquals(
            Refinement.Rejected(LiveVerificationFailure.SOURCE_POSTIMAGE_MISMATCH),
            VerifiedReplaceBodyPostimage.admit(preservation, preservation.postimage, hash(original)),
        )
    }

    private fun hash(text: String): WorkspaceSourceContentHash {
        val digest =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8)).joinToString("") {
                "%02x".format(it)
            }
        return WorkspaceSourceContentHash.parse(digest).refined()
    }

    private fun <T, F> Refinement<T, F>.refined(): T = assertInstanceOf<Refinement.Refined<T>>(this).value
}
