@file:OptIn(ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.SearchScope
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*
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

    init {
        require(storedAffectedFiles == storedEdits.map(TextEdit::filePath).distinct()) {
            "affectedFiles must match distinct edit file paths in edit order"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RenameResult) return false

        return storedEdits == other.storedEdits &&
            storedFileHashes == other.storedFileHashes &&
            storedAffectedFiles == other.storedAffectedFiles &&
            searchScope == other.searchScope &&
            schemaVersion == other.schemaVersion
    }

    override fun hashCode(): Int {
        var result = storedEdits.hashCode()
        result = 31 * result + storedFileHashes.hashCode()
        result = 31 * result + storedAffectedFiles.hashCode()
        result = 31 * result + (searchScope?.hashCode() ?: 0)
        result = 31 * result + schemaVersion
        return result
    }

    override fun toString(): String =
        "RenameResult(" +
            "edits=$storedEdits, " +
            "fileHashes=$storedFileHashes, " +
            "affectedFiles=$storedAffectedFiles, " +
            "searchScope=$searchScope, " +
            "schemaVersion=$schemaVersion" +
            ")"

    companion object {
        fun of(
            edits: List<TextEdit>,
            fileHashes: List<FileHash>,
            searchScope: SearchScope? = null,
        ): RenameResult {
            val editSnapshot = edits.toList()
            return RenameResult(
                storedEdits = editSnapshot,
                storedFileHashes = fileHashes.toList(),
                storedAffectedFiles = editSnapshot.map(TextEdit::filePath).distinct(),
                searchScope = searchScope,
            )
        }
    }
}
