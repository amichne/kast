package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.ExactFileImage
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.SCHEMA_VERSION
import io.github.amichne.kast.api.validation.ExactTextEditReplayValidator
import io.github.amichne.kast.api.validation.FileHashing
import java.util.Collections
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class ReplacementPlanResult private constructor(
    @DocField(description = "Single non-mutating edit that replaces the exact source declaration.")
    val edit: TextEdit,
    @DocField(description = "Required compiler-backed proof for the replacement plan.")
    val proof: ExactReplacementProof,
    @SerialName("fileImages")
    @DocField(description = "Exact immutable preimage and postimage bytes for the replacement file.")
    private val storedFileImages: List<ExactFileImage>,
    @DocField(description = "Protocol schema version for forward compatibility.", serverManaged = true)
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    val fileImages: List<ExactFileImage>
        get() = Collections.unmodifiableList(storedFileImages)

    init {
        require(edit.filePath == proof.sourceRange.filePath &&
            edit.startOffset == proof.sourceRange.startOffset &&
            edit.endOffset == proof.sourceRange.endOffset) {
            "Replacement edit must match the exact proven source range"
        }
        require(edit.newText.length == proof.proposedDeclarationLength) {
            "Replacement edit must match the proven declaration length"
        }
        require(FileHashing.sha256(edit.newText) == proof.proposedDeclarationHash.value) {
            "Replacement edit must match the proven declaration hash"
        }
        val declarationStart = proof.declarationSlice.startOffset.value
        val declarationEnd = proof.declarationSlice.endOffset.value
        val declarationText = edit.newText.substring(declarationStart, declarationEnd)
        require(edit.newText.substring(0, declarationStart).isBlank() &&
            edit.newText.substring(declarationEnd).isBlank()
        ) {
            "Replacement edit may contain only whitespace outside the proven declaration slice"
        }
        require(declarationText.isNotBlank() && declarationText == declarationText.trim()) {
            "Replacement declaration slice must contain the exact non-blank declaration"
        }
        require(proof.outboundReferences.all { reference ->
            edit.newText.substring(reference.relativeStartOffset, reference.relativeEndOffset) ==
                reference.sourceText
        }) {
            "Replacement outbound references must match the exact full proposed edit"
        }
        require(storedFileImages.size == 1 && storedFileImages.single().filePath.value == edit.filePath) {
            "Replacement result requires one exact file image for its edit path"
        }
        val image = storedFileImages.single()
        val fileHash = proof.fileHashes.single()
        require(fileHash.filePath == edit.filePath && fileHash.hash.matches(LOWERCASE_SHA256)) {
            "Replacement file hash must be lowercase SHA-256 for the exact edit path"
        }
        require(fileHash.hash == image.preimage.sha256.value) {
            "Replacement file hash must match the exact preimage"
        }
        require(image.preimage.sha256 != image.postimage.sha256) {
            "A replacement edit must have a changed exact postimage"
        }
        ExactTextEditReplayValidator.requireExactPostimages(listOf(edit), storedFileImages)
    }

    override fun equals(other: Any?): Boolean = other is ReplacementPlanResult &&
        edit == other.edit &&
        proof == other.proof &&
        storedFileImages == other.storedFileImages &&
        schemaVersion == other.schemaVersion

    override fun hashCode(): Int = listOf(edit, proof, storedFileImages, schemaVersion).hashCode()

    override fun toString(): String =
        "ReplacementPlanResult(edit=$edit, proof=$proof, fileImages=$storedFileImages, schemaVersion=$schemaVersion)"

    companion object {
        fun of(
            edit: TextEdit,
            proof: ExactReplacementProof,
            fileImages: List<ExactFileImage>,
        ): ReplacementPlanResult = ReplacementPlanResult(
            edit = edit,
            proof = proof,
            storedFileImages = fileImages.toList(),
        )
    }
}

private val LOWERCASE_SHA256 = Regex("[0-9a-f]{64}")
