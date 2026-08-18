package io.github.amichne.kast.change.verify.intellij

import io.github.amichne.kast.change.contract.AddDeclarationOutboundReferenceCount
import io.github.amichne.kast.change.contract.ExactFileContentProof
import io.github.amichne.kast.change.verify.spi.AddDeclarationOutboundBindingsObservation
import io.github.amichne.kast.kernel.Refinement
import java.security.MessageDigest
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class IntellijAddDeclarationVerificationProtocolTest {
    @Test
    fun `exact physical postimage and normalized document prove appended declaration range`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val before = bom + "package sample\r\n".toByteArray()
        val declaration = "fun added(): Int = 1"
        val after = bom + "package sample\r\n\r\n$declaration\r\n".toByteArray()

        val observed = assertInstanceOf<Refinement.Refined<ExactVerifiedAddDeclarationPostimage>>(
            ExactVerifiedAddDeclarationPostimage.admit(
                expectedPreimage = exact(before),
                expectedPostimage = exact(after),
                currentPhysicalBytes = after,
                normalizedDocumentText = "package sample\n\n$declaration\n",
                proposedDeclaration = declaration,
            ),
        ).value

        assertEquals(16, observed.declarationRange.startOffset)
        assertEquals(36, observed.declarationRange.endOffset)
    }

    @Test
    fun `postimage admission separates physical and document mismatch`() {
        val before = "package sample\n".toByteArray()
        val declaration = "fun added(): Int = 1"
        val after = "package sample\n\n$declaration\n".toByteArray()

        val physical = assertInstanceOf<
            Refinement.Rejected<ExactVerifiedAddDeclarationPostimageFailure>,
            >(
            ExactVerifiedAddDeclarationPostimage.admit(
                expectedPreimage = exact(before),
                expectedPostimage = exact(after),
                currentPhysicalBytes = before,
                normalizedDocumentText = "package sample\n\n$declaration\n",
                proposedDeclaration = declaration,
            ),
        )
        val document = assertInstanceOf<
            Refinement.Rejected<ExactVerifiedAddDeclarationPostimageFailure>,
            >(
            ExactVerifiedAddDeclarationPostimage.admit(
                expectedPreimage = exact(before),
                expectedPostimage = exact(after),
                currentPhysicalBytes = after,
                normalizedDocumentText = "package sample\n",
                proposedDeclaration = declaration,
            ),
        )

        assertEquals(
            ExactVerifiedAddDeclarationPostimageFailure.PHYSICAL_POSTIMAGE_MISMATCH,
            physical.failure,
        )
        assertEquals(
            ExactVerifiedAddDeclarationPostimageFailure.DOCUMENT_POSTIMAGE_MISMATCH,
            document.failure,
        )
    }

    @Test
    fun `nonzero outbound references cannot manufacture preservation proof`() {
        val rejection = assertInstanceOf<
            Refinement.Rejected<IntellijAddDeclarationSemanticProofFailure>,
            >(
            admitVacuousOutboundBindingProof(
                expected = outboundCount(1),
                observed = outboundCount(1),
            ),
        )

        assertEquals(
            IntellijAddDeclarationSemanticProofFailure.OUTBOUND_SCOPE_INCOMPLETE,
            rejection.failure,
        )
    }

    @Test
    fun `zero outbound references carry vacuous preservation proof`() {
        val observed = assertInstanceOf<
            Refinement.Refined<AddDeclarationOutboundBindingsObservation>,
            >(
            admitVacuousOutboundBindingProof(
                expected = outboundCount(0),
                observed = outboundCount(0),
            ),
        )

        assertEquals(AddDeclarationOutboundBindingsObservation.PRESERVED_COMPLETE, observed.value)
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

    private fun outboundCount(value: Int): AddDeclarationOutboundReferenceCount =
        assertInstanceOf<Refinement.Refined<AddDeclarationOutboundReferenceCount>>(
            AddDeclarationOutboundReferenceCount.parse(value),
        ).value
}
