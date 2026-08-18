package io.github.amichne.kast.change.verify.intellij

import io.github.amichne.kast.change.contract.AddDeclarationClasspathFingerprint
import io.github.amichne.kast.change.contract.AddDeclarationOutboundReferenceCount
import io.github.amichne.kast.change.contract.AddDeclarationProjectModelFingerprint
import io.github.amichne.kast.change.contract.AddDeclarationSourceOwner
import io.github.amichne.kast.change.contract.ExactFileContentProof
import io.github.amichne.kast.change.verify.spi.AddDeclarationVerificationCommand
import io.github.amichne.kast.change.verify.spi.AddDeclarationVerificationLimitation
import io.github.amichne.kast.change.verify.spi.AddDeclarationOutboundBindingsObservation
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGenerationState
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

fun interface IntellijPublishedWorkspaceGenerationAuthority {
    /**
     * Proof transition: live workspace publication state to [PublishedWorkspaceGenerationState].
     *
     * The returned state is the strongest detached publication proof available at this instant.
     * There is no expected failure beyond the closed unpublished state. Raw runtime state may be
     * extracted only inside the indexer integration boundary.
     */
    fun current(): PublishedWorkspaceGenerationState
}

@ConsistentCopyVisibility
data class IntellijAddDeclarationCompilerEnvironment private constructor(
    val projectModelFingerprint: AddDeclarationProjectModelFingerprint,
    val classpathFingerprint: AddDeclarationClasspathFingerprint,
    val owner: AddDeclarationSourceOwner,
) {
    companion object {
        /**
         * Proof transition: current typed compiler authorities to
         * [IntellijAddDeclarationCompilerEnvironment].
         *
         * The output preserves the exact current project model, classpath, and source owner for
         * one scoped read. There is no expected failure because every input is already refined.
         * Raw IDE model data may be extracted only by the injected environment authority.
         */
        fun observed(
            projectModelFingerprint: AddDeclarationProjectModelFingerprint,
            classpathFingerprint: AddDeclarationClasspathFingerprint,
            owner: AddDeclarationSourceOwner,
        ): IntellijAddDeclarationCompilerEnvironment = IntellijAddDeclarationCompilerEnvironment(
            projectModelFingerprint,
            classpathFingerprint,
            owner,
        )
    }
}

sealed interface IntellijAddDeclarationCompilerEnvironmentResult {
    data class Observed(
        val environment: IntellijAddDeclarationCompilerEnvironment,
    ) : IntellijAddDeclarationCompilerEnvironmentResult

    data class Rejected(
        val limitation: AddDeclarationVerificationLimitation,
    ) : IntellijAddDeclarationCompilerEnvironmentResult
}

fun interface IntellijAddDeclarationCompilerEnvironmentAuthority {
    /**
     * Proof transition: [AddDeclarationVerificationCommand] to
     * [IntellijAddDeclarationCompilerEnvironmentResult].
     *
     * Observed preserves current model, classpath, and owner evidence captured inside the caller's
     * scoped read. Expected failure is one finite verification limitation. Live model values must
     * not escape the authority except through the detached result.
     */
    fun observe(
        command: AddDeclarationVerificationCommand,
    ): IntellijAddDeclarationCompilerEnvironmentResult
}

enum class ExactVerifiedAddDeclarationPostimageFailure {
    PHYSICAL_POSTIMAGE_MISMATCH,
    DOCUMENT_POSTIMAGE_MISMATCH,
    INVALID_UTF8,
    NOT_EXACT_APPEND,
}

internal enum class VerifiedDeclarationRangeFailure {
    INVALID,
}

@ConsistentCopyVisibility
data class VerifiedDeclarationRange private constructor(
    val startOffset: Int,
    val endOffset: Int,
) {
    companion object {
        /**
         * Proof transition: raw UTF-16 offsets to
         * `Refinement<VerifiedDeclarationRange, VerifiedDeclarationRangeFailure>`.
         *
         * Refined proves a non-negative, non-empty source range. Invalid is the closed expected
         * failure. Raw offsets may enter only from the exact approved append-boundary calculation.
         */
        private fun admit(
            startOffset: Int,
            endOffset: Int,
        ): Refinement<VerifiedDeclarationRange, VerifiedDeclarationRangeFailure> =
            if (startOffset >= 0 && endOffset > startOffset) {
                Refinement.Refined(VerifiedDeclarationRange(startOffset, endOffset))
            } else {
                Refinement.Rejected(VerifiedDeclarationRangeFailure.INVALID)
            }

        internal fun fromExactAppend(
            startOffset: Int,
            endOffset: Int,
        ): Refinement<VerifiedDeclarationRange, VerifiedDeclarationRangeFailure> =
            admit(startOffset, endOffset)
    }
}

@ConsistentCopyVisibility
data class ExactVerifiedAddDeclarationPostimage private constructor(
    val declarationRange: VerifiedDeclarationRange,
) {
    companion object {
        /**
         * Proof transition: approved images plus physical bytes and normalized document to
         * `Refinement<ExactVerifiedAddDeclarationPostimage,
         * ExactVerifiedAddDeclarationPostimageFailure>`.
         *
         * Establishes byte-exact postimage equality, normalized document equality, and the exact
         * appended declaration range derived from the approved preimage/postimage boundary. The
         * failure set is closed. Raw bytes and text remain inside the IntelliJ adapter.
         */
        fun admit(
            expectedPreimage: ExactFileContentProof,
            expectedPostimage: ExactFileContentProof,
            currentPhysicalBytes: ByteArray,
            normalizedDocumentText: String,
            proposedDeclaration: String,
        ): Refinement<
            ExactVerifiedAddDeclarationPostimage,
            ExactVerifiedAddDeclarationPostimageFailure,
            > {
            val postimageBytes = expectedPostimage.copyBytes()
            if (!currentPhysicalBytes.contentEquals(postimageBytes)) {
                return Refinement.Rejected(
                    ExactVerifiedAddDeclarationPostimageFailure.PHYSICAL_POSTIMAGE_MISMATCH,
                )
            }
            val preimage = when (val normalized = NormalizedSourceText.parse(
                expectedPreimage.copyBytes(),
            )) {
                is Refinement.Refined -> normalized.value
                is Refinement.Rejected -> return Refinement.Rejected(normalized.failure)
            }
            val postimage = when (val normalized = NormalizedSourceText.parse(postimageBytes)) {
                is Refinement.Refined -> normalized.value
                is Refinement.Rejected -> return Refinement.Rejected(normalized.failure)
            }
            if (normalizedDocumentText != postimage.value) {
                return Refinement.Rejected(
                    ExactVerifiedAddDeclarationPostimageFailure.DOCUMENT_POSTIMAGE_MISMATCH,
                )
            }
            if (!postimage.value.startsWith(preimage.value) || !postimage.value.endsWith('\n')) {
                return Refinement.Rejected(
                    ExactVerifiedAddDeclarationPostimageFailure.NOT_EXACT_APPEND,
                )
            }
            val appended = postimage.value.substring(preimage.value.length, postimage.value.length - 1)
            if (!appended.endsWith(proposedDeclaration)) {
                return Refinement.Rejected(
                    ExactVerifiedAddDeclarationPostimageFailure.NOT_EXACT_APPEND,
                )
            }
            val prefix = appended.substring(0, appended.length - proposedDeclaration.length)
            val expectedPrefix = when {
                preimage.value.isEmpty() || preimage.value.endsWith("\n\n") -> ""
                preimage.value.endsWith('\n') -> "\n"
                else -> "\n\n"
            }
            if (prefix != expectedPrefix || proposedDeclaration.isEmpty()) {
                return Refinement.Rejected(
                    ExactVerifiedAddDeclarationPostimageFailure.NOT_EXACT_APPEND,
                )
            }
            val start = preimage.value.length + prefix.length
            return when (val range = VerifiedDeclarationRange.fromExactAppend(
                start,
                start + proposedDeclaration.length,
            )) {
                is Refinement.Refined -> Refinement.Refined(
                    ExactVerifiedAddDeclarationPostimage(range.value),
                )
                is Refinement.Rejected -> Refinement.Rejected(
                    ExactVerifiedAddDeclarationPostimageFailure.NOT_EXACT_APPEND,
                )
            }
        }
    }
}

@JvmInline
private value class NormalizedSourceText private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: raw physical bytes to
         * `Refinement<NormalizedSourceText, ExactVerifiedAddDeclarationPostimageFailure>`.
         *
         * Refined proves strict UTF-8 decoding, optional BOM removal, and IntelliJ line-separator
         * normalization. Invalid UTF-8 is the closed expected failure. Text may be extracted only
         * for exact comparison against the current IntelliJ document and approved append boundary.
         */
        fun parse(
            bytes: ByteArray,
        ): Refinement<NormalizedSourceText, ExactVerifiedAddDeclarationPostimageFailure> {
            val hasBom = bytes.size >= UTF8_BOM.size && UTF8_BOM.indices.all { index ->
                bytes[index] == UTF8_BOM[index]
            }
            val content = if (hasBom) bytes.copyOfRange(UTF8_BOM.size, bytes.size) else bytes
            return try {
                val decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                val chars = CharArray(decoded.remaining())
                decoded.get(chars)
                Refinement.Refined(
                    NormalizedSourceText(
                        String(chars)
                            .replace("\r\n", "\n")
                            .replace('\r', '\n'),
                    ),
                )
            } catch (_: java.nio.charset.CharacterCodingException) {
                Refinement.Rejected(ExactVerifiedAddDeclarationPostimageFailure.INVALID_UTF8)
            }
        }
    }
}

private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

/**
 * Proof transition: planned and observed outbound cardinalities to
 * `Refinement<AddDeclarationOutboundBindingsObservation,
 * IntellijAddDeclarationSemanticProofFailure>`.
 *
 * Refined proves preservation only for the vacuous zero-reference case. Any nonzero cardinality is
 * rejected as incomplete because this SPI does not carry typed per-occurrence target identities.
 * Raw counts may be extracted only at this exact comparison boundary.
 */
internal fun admitVacuousOutboundBindingProof(
    expected: AddDeclarationOutboundReferenceCount,
    observed: AddDeclarationOutboundReferenceCount,
): Refinement<
    AddDeclarationOutboundBindingsObservation,
    IntellijAddDeclarationSemanticProofFailure,
    > = if (expected.value == 0 && observed.value == 0) {
    Refinement.Refined(AddDeclarationOutboundBindingsObservation.PRESERVED_COMPLETE)
} else {
    Refinement.Rejected(IntellijAddDeclarationSemanticProofFailure.OUTBOUND_SCOPE_INCOMPLETE)
}
