package io.github.amichne.kast.api.validation

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.protocol.ValidationException

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class ValidatedFileEdits(
    val filePath: String,
    val expectedHash: String,
    val edits: List<TextEdit>,
)

sealed interface ValidatedFileOperation {
    val filePath: String

    data class CreateFile(
        override val filePath: String,
        val content: String,
    ) : ValidatedFileOperation

    data class DeleteFile(
        override val filePath: String,
        val expectedHash: String,
    ) : ValidatedFileOperation
}

object EditPlanValidator {
    fun validate(
        edits: List<TextEdit>,
        fileHashes: List<FileHash>,
    ): List<ValidatedFileEdits> {
        if (edits.isEmpty()) {
            throw ValidationException("At least one text edit is required")
        }

        val normalizedHashes = fileHashes.associate { hash ->
            val normalizedPath = canonicalPath(hash.filePath)
            normalizedPath to hash.hash
        }

        if (normalizedHashes.size != fileHashes.size) {
            throw ValidationException("Duplicate file hash entries were provided")
        }

        val grouped = edits.groupBy { edit ->
            canonicalPath(edit.filePath)
        }

        return grouped.entries.sortedBy { it.key }.map { (filePath, fileEdits) ->
            val expectedHash = normalizedHashes[filePath]
                ?: throw ValidationException(
                    message = "Missing expected hash for edited file",
                    details = mapOf("filePath" to filePath),
                )

            val editsAscending = fileEdits.map {
                it.copy(filePath = filePath)
            }.sortedBy { it.startOffset }

            ensureRangesDoNotOverlap(editsAscending)

            ValidatedFileEdits(
                filePath = filePath,
                expectedHash = expectedHash,
                edits = editsAscending.sortedByDescending { it.startOffset },
            )
        }
    }

    fun validateFileOperations(
        fileOperations: List<FileOperation>,
    ): List<ValidatedFileOperation> {
        val normalizedPaths = linkedSetOf<String>()
        return fileOperations.map { operation ->
            val filePath = canonicalPath(operation.filePath)
            if (!normalizedPaths.add(filePath)) {
                throw ValidationException(
                    message = "Duplicate file operation entries were provided",
                    details = mapOf("filePath" to filePath),
                )
            }

            when (operation) {
                is FileOperation.CreateFile -> ValidatedFileOperation.CreateFile(
                    filePath = filePath,
                    content = operation.content,
                )

                is FileOperation.DeleteFile -> ValidatedFileOperation.DeleteFile(
                    filePath = filePath,
                    expectedHash = operation.expectedHash,
                )
            }
        }
    }

    fun applyEditsToContent(
        originalContent: String,
        edits: List<TextEdit>,
    ): String {
        val builder = StringBuilder(originalContent)
        edits.sortedByDescending { it.startOffset }.forEach { edit ->
            builder.replace(edit.startOffset, edit.endOffset, edit.newText)
        }
        return builder.toString()
    }

    private fun ensureRangesDoNotOverlap(edits: List<TextEdit>) {
        var lastEnd = -1
        edits.forEach { edit ->
            if (edit.startOffset < 0 || edit.endOffset < edit.startOffset) {
                throw ValidationException(
                    message = "Invalid edit range",
                    details = mapOf(
                        "filePath" to edit.filePath,
                        "startOffset" to edit.startOffset.toString(),
                        "endOffset" to edit.endOffset.toString(),
                    ),
                )
            }

            if (lastEnd > edit.startOffset) {
                throw ValidationException(
                    message = "Overlapping text edits are not allowed",
                    details = mapOf("filePath" to edit.filePath),
                )
            }

            lastEnd = edit.endOffset
        }
    }
}

object FileHashing {
    fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(content.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

private fun canonicalPath(filePath: String): String = NormalizedPath.parse(filePath).value
