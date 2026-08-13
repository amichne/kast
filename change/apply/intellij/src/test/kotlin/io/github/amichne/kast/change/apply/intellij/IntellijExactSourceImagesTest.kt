package io.github.amichne.kast.change.apply.intellij

import io.github.amichne.kast.change.contract.ExactFileContentProof
import io.github.amichne.kast.kernel.Refinement
import java.security.MessageDigest
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class IntellijExactSourceImagesTest {
    @Test
    fun `BOM and CRLF bytes remain distinct from normalized IntelliJ text`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val before = bom + "package sample\r\n".toByteArray()
        val after = bom + "package sample\r\n\r\nfun added(): Unit = Unit\r\n".toByteArray()

        val admitted = assertInstanceOf<Refinement.Refined<ExactIntellijSourceImages>>(
            ExactIntellijSourceImages.admit(
                expectedPreimage = exact(before),
                expectedPostimage = exact(after),
                currentPhysicalBytes = before,
                normalizedDocumentText = "package sample\n",
            ),
        ).value

        assertEquals("package sample\n", admitted.normalizedPreimage.text)
        assertEquals("package sample\n\nfun added(): Unit = Unit\n", admitted.normalizedPostimage.text)
        assertArrayEquals(before, admitted.copyPreimageBytes())
        assertArrayEquals(after, admitted.copyPostimageBytes())
    }

    @Test
    fun `physical bytes different from approved preimage reject even when document text matches`() {
        val approved = "package sample\n".toByteArray()
        val moved = "package sample\r\n".toByteArray()

        val rejected = assertInstanceOf<Refinement.Rejected<ExactIntellijSourceImagesFailure>>(
            ExactIntellijSourceImages.admit(
                expectedPreimage = exact(approved),
                expectedPostimage = exact(approved),
                currentPhysicalBytes = moved,
                normalizedDocumentText = "package sample\n",
            ),
        )

        assertEquals(ExactIntellijSourceImagesFailure.PREIMAGE_BYTES_MISMATCH, rejected.failure)
    }

    @Test
    fun `command progress separates uncertain first effect from observed mutation`() {
        assertInstanceOf<IntellijCommandExecution.MutationOutcomeUnknown>(
            commandFailure(IntellijApplyAttemptProgress.MAY_HAVE_BEGUN),
        )
        assertInstanceOf<IntellijCommandExecution.RecoveryRequiredAfterMutation>(
            commandFailure(IntellijApplyAttemptProgress.BEGUN),
        )
    }

    @Test
    fun `runtime admission is exact to the pinned product and build`() {
        assertEquals(
            IntellijRuntimeAdmission.Supported,
            admitIntellijRuntime("IC", "261.25134.95", "IC", "261.25134.95"),
        )
        assertEquals(
            IntellijRuntimeAdmission.Unsupported,
            admitIntellijRuntime("IU", "261.25134.95", "IC", "261.25134.95"),
        )
    }

    private fun exact(bytes: ByteArray): ExactFileContentProof =
        assertInstanceOf<Refinement.Refined<ExactFileContentProof>>(
            ExactFileContentProof.admit(
                sha256 = MessageDigest.getInstance("SHA-256")
                    .digest(bytes)
                    .joinToString(separator = "") { byte -> "%02x".format(byte) },
                contentBase64 = Base64.getEncoder().encodeToString(bytes),
            ),
        ).value
}
