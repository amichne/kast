package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.Refinement
import java.util.Base64
import kotlinx.serialization.Serializable

enum class ExactFileContentProofFailure {
    SHA256_INVALID,
    BASE64_INVALID,
    CONTENT_HASH_MISMATCH,
}

@Serializable
@ConsistentCopyVisibility
data class ExactFileContentProof private constructor(
    val sha256: AddDeclarationSha256,
    val contentBase64: String,
) {
    fun copyBytes(): ByteArray = Base64.getDecoder().decode(contentBase64)

    companion object {
        /**
         * Proof transition:
         * String SHA-256 and String Base64 to Refinement of ExactFileContentProof or
         * ExactFileContentProofFailure.
         *
         * Establishes canonical Base64 whose exact decoded bytes hash to the declared SHA-256.
         * ExactFileContentProofFailure is the closed expected failure. Raw bytes may be extracted
         * only by an apply or recovery adapter after later plan admission.
         */
        fun admit(
            sha256: String,
            contentBase64: String,
        ): Refinement<ExactFileContentProof, ExactFileContentProofFailure> {
            if (!Regex("[0-9a-f]{64}").matches(sha256)) {
                return Refinement.Rejected(ExactFileContentProofFailure.SHA256_INVALID)
            }
            val bytes = runCatching { Base64.getDecoder().decode(contentBase64) }.getOrNull()
                        ?: return Refinement.Rejected(ExactFileContentProofFailure.BASE64_INVALID)
            if (Base64.getEncoder().encodeToString(bytes) != contentBase64) {
                return Refinement.Rejected(ExactFileContentProofFailure.BASE64_INVALID)
            }
            if (sha256Hex(bytes) != sha256) {
                return Refinement.Rejected(ExactFileContentProofFailure.CONTENT_HASH_MISMATCH)
            }
            return Refinement.Refined(
                ExactFileContentProof(AddDeclarationSha256.fromProvenRaw(sha256), contentBase64),
            )
        }
    }
}

enum class ExpectedFileProofFailure {
    PREIMAGE_DOES_NOT_MATCH_INTENT,
    PREIMAGE_EQUALS_POSTIMAGE,
}

@Serializable
@ConsistentCopyVisibility
data class ExpectedFileProof private constructor(
    val targetPath: AddDeclarationTargetPath,
    val preimage: ExactFileContentProof,
    val postimage: ExactFileContentProof,
) {
    companion object {
        /**
         * Proof transition:
         * AddDeclarationTargetCapability and exact images to Refinement of ExpectedFileProof or
         * ExpectedFileProofFailure.
         *
         * Establishes the exact existing target before-image and distinct authorized after-image.
         * ExpectedFileProofFailure is the closed expected failure. Image bytes may be extracted
         * only by later recovery and apply boundaries.
         */
        fun admit(
            target: AddDeclarationTargetCapability,
            preimage: ExactFileContentProof,
            postimage: ExactFileContentProof,
        ): Refinement<ExpectedFileProof, ExpectedFileProofFailure> {
            if (preimage.sha256 != target.expectedCurrentSha256) {
                return Refinement.Rejected(ExpectedFileProofFailure.PREIMAGE_DOES_NOT_MATCH_INTENT)
            }
            if (preimage.sha256 == postimage.sha256) {
                return Refinement.Rejected(ExpectedFileProofFailure.PREIMAGE_EQUALS_POSTIMAGE)
            }
            return Refinement.Refined(ExpectedFileProof(target.targetPath, preimage, postimage))
        }
    }
}

enum class DeclaredWriteSetFailure {
    EMPTY,
    DUPLICATE_PATH,
}

@Serializable
@ConsistentCopyVisibility
data class DeclaredWriteSet private constructor(val paths: List<AddDeclarationTargetPath>) {
    companion object {
        /**
         * Proof transition:
         * List of AddDeclarationTargetPath to Refinement of DeclaredWriteSet or
         * DeclaredWriteSetFailure.
         *
         * Establishes a non-empty, unique, canonical-order declared write set.
         * DeclaredWriteSetFailure is the closed expected failure. Raw paths may be extracted only
         * by the characterized write-set observer.
         */
        fun admit(
            paths: List<AddDeclarationTargetPath>,
        ): Refinement<DeclaredWriteSet, DeclaredWriteSetFailure> {
            if (paths.isEmpty()) return Refinement.Rejected(DeclaredWriteSetFailure.EMPTY)
            if (paths.distinct().size != paths.size) {
                return Refinement.Rejected(DeclaredWriteSetFailure.DUPLICATE_PATH)
            }
            return Refinement.Refined(DeclaredWriteSet(paths.sorted()))
        }
    }
}
