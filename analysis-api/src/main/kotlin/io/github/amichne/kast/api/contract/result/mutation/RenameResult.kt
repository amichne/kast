@file:OptIn(ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.ExactFileImage
import io.github.amichne.kast.api.contract.SearchScope
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*
import io.github.amichne.kast.api.validation.ExactTextEditReplayValidator
import java.util.Collections
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class RenameResult private constructor(
    @SerialName("edits")
    @DocField(description = "Text edits needed to perform the rename across the workspace.")
    private val storedEdits: List<TextEdit>,
    @SerialName("fileHashes")
    @DocField(description = "File hashes at edit-plan time for conflict detection.")
    private val storedFileHashes: List<FileHash>,
    @SerialName("affectedFiles")
    @DocField(description = "Absolute paths of all files that would be modified.")
    private val storedAffectedFiles: List<String>,
    @SerialName("fileImages")
    @DocField(description = "Exact immutable preimage and postimage bytes for every affected file.")
    private val storedFileImages: List<ExactFileImage>,
    @DocField(description = "Exact semantic identity, generation, coverage, and occurrence proof for this rename.")
    val proof: ExactRenameProof,
    @DocField(description = "Describes the scope and exhaustiveness of the rename search.")
    val searchScope: SearchScope? = null,
    @DocField(description = "Protocol schema version for forward compatibility.", serverManaged = true)
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    val edits: List<TextEdit>
        get() = Collections.unmodifiableList(storedEdits)

    val fileHashes: List<FileHash>
        get() = Collections.unmodifiableList(storedFileHashes)

    val affectedFiles: List<String>
        get() = Collections.unmodifiableList(storedAffectedFiles)

    val fileImages: List<ExactFileImage>
        get() = Collections.unmodifiableList(storedFileImages)

    init {
        require(storedAffectedFiles == storedEdits.map(TextEdit::filePath).distinct()) {
            "affectedFiles must match distinct edit file paths in edit order"
        }
        val affectedPathSet = storedAffectedFiles.toSet()
        val imagePaths = storedFileImages.map { image -> image.filePath.value }
        require(storedFileImages.isNotEmpty() && imagePaths.size == imagePaths.distinct().size) {
            "Rename file images must contain one unique image per affected file"
        }
        require(imagePaths.toSet() == affectedPathSet) {
            "Rename file image paths must match the affected edit paths"
        }
        val hashPaths = storedFileHashes.map(FileHash::filePath)
        require(hashPaths.size == hashPaths.distinct().size && hashPaths.toSet() == affectedPathSet) {
            "Rename file hash paths must match the affected edit paths"
        }
        val imagesByPath = storedFileImages.associateBy { image -> image.filePath.value }
        require(storedFileHashes.all { fileHash ->
            fileHash.hash.matches(LOWERCASE_SHA256) &&
                imagesByPath.getValue(fileHash.filePath).preimage.sha256.value == fileHash.hash
        }) {
            "Every rename file hash must be lowercase SHA-256 and match its exact preimage"
        }
        require(storedAffectedFiles.all { filePath ->
            val image = imagesByPath.getValue(filePath)
            image.preimage.sha256 != image.postimage.sha256
        }) {
            "Every edited rename file must have a changed exact postimage"
        }
        require(storedEdits.map(TextEdit::newText).distinct().size == 1) {
            "Every rename edit must use one replacement name"
        }
        val declarationEdits = storedEdits.filter { edit ->
            edit.filePath == proof.target.declarationFile.value &&
                edit.startOffset == proof.target.declarationStartOffset.value
        }
        require(declarationEdits.size == 1) {
            "Rename edits must contain exactly one target declaration edit"
        }
        val declarationEdit = declarationEdits.single()
        val referenceEditRanges = storedEdits
            .filterNot { edit -> edit === declarationEdit }
            .map(TextEdit::sourceRangeKey)
            .toSet()
        val provenReferenceRanges = proof.occurrences
            .map { occurrence -> occurrence.reference.location.sourceRangeKey() }
            .toSet()
        require(referenceEditRanges == provenReferenceRanges) {
            "Rename reference edits must match the exact proven occurrences"
        }
        ExactTextEditReplayValidator.requireExactPostimages(storedEdits, storedFileImages)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RenameResult) return false

        return storedEdits == other.storedEdits &&
            storedFileHashes == other.storedFileHashes &&
            storedAffectedFiles == other.storedAffectedFiles &&
            storedFileImages == other.storedFileImages &&
            proof == other.proof &&
            searchScope == other.searchScope &&
            schemaVersion == other.schemaVersion
    }

    override fun hashCode(): Int {
        var result = storedEdits.hashCode()
        result = 31 * result + storedFileHashes.hashCode()
        result = 31 * result + storedAffectedFiles.hashCode()
        result = 31 * result + storedFileImages.hashCode()
        result = 31 * result + proof.hashCode()
        result = 31 * result + (searchScope?.hashCode() ?: 0)
        result = 31 * result + schemaVersion
        return result
    }

    override fun toString(): String =
        "RenameResult(" +
            "edits=$storedEdits, " +
            "fileHashes=$storedFileHashes, " +
            "affectedFiles=$storedAffectedFiles, " +
            "fileImages=$storedFileImages, " +
            "proof=$proof, " +
            "searchScope=$searchScope, " +
            "schemaVersion=$schemaVersion" +
            ")"

    companion object {
        fun of(
            edits: List<TextEdit>,
            fileHashes: List<FileHash>,
            fileImages: List<ExactFileImage>,
            proof: ExactRenameProof,
            searchScope: SearchScope? = null,
        ): RenameResult {
            val editSnapshot = edits.toList()
            return RenameResult(
                storedEdits = editSnapshot,
                storedFileHashes = fileHashes.toList(),
                storedAffectedFiles = editSnapshot.map(TextEdit::filePath).distinct(),
                storedFileImages = fileImages.toList(),
                proof = proof,
                searchScope = searchScope,
            )
        }
    }
}

private val LOWERCASE_SHA256 = Regex("[0-9a-f]{64}")

private fun TextEdit.sourceRangeKey(): Triple<String, Int, Int> = Triple(filePath, startOffset, endOffset)

private fun io.github.amichne.kast.api.contract.Location.sourceRangeKey(): Triple<String, Int, Int> =
    Triple(filePath, startOffset, endOffset)
