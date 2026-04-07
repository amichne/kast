package io.github.amichne.kast.api

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.io.path.readText
import kotlin.io.path.writeText

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

object LocalDiskEditApplier {
    fun apply(query: ApplyEditsQuery): ApplyEditsResult {
        if (query.edits.isEmpty() && query.fileOperations.isEmpty()) {
            throw ValidationException("At least one text edit or file operation is required")
        }

        val validatedFileOperations = EditPlanValidator.validateFileOperations(query.fileOperations)
        validateFileOperationsAgainstDisk(validatedFileOperations)

        val affectedFiles = mutableListOf<String>()
        val createdFiles = mutableListOf<String>()
        val deletedFiles = mutableListOf<String>()

        validatedFileOperations.forEach { operation ->
            val path = Path.of(operation.filePath)
            try {
                when (operation) {
                    is ValidatedFileOperation.CreateFile -> {
                        writeAtomically(path, operation.content)
                        createdFiles += operation.filePath
                    }

                    is ValidatedFileOperation.DeleteFile -> {
                        Files.delete(path)
                        deletedFiles += operation.filePath
                    }
                }
                affectedFiles += operation.filePath
            } catch (exception: Exception) {
                throw PartialApplyException(
                    details = mapOf(
                        "failedFile" to operation.filePath,
                        "appliedFiles" to affectedFiles.joinToString(","),
                        "createdFiles" to createdFiles.joinToString(","),
                        "deletedFiles" to deletedFiles.joinToString(","),
                        "reason" to (exception.message ?: exception::class.java.simpleName),
                    ),
                )
            }
        }

        val validatedEdits = if (query.edits.isEmpty()) {
            emptyList()
        } else {
            EditPlanValidator.validate(query.edits, query.fileHashes)
        }
        val currentContents = validatedEdits.associateWith { plan ->
            val path = Path.of(plan.filePath)
            ensureExists(path)
            path.readText()
        }

        currentContents.forEach { (plan, content) ->
            val currentHash = FileHashing.sha256(content)
            if (currentHash != plan.expectedHash) {
                throw ConflictException(
                    message = "The file changed after the edit plan was created",
                    details = mapOf(
                        "filePath" to plan.filePath,
                        "expectedHash" to plan.expectedHash,
                        "actualHash" to currentHash,
                    ),
                )
            }
        }

        val appliedEdits = mutableListOf<TextEdit>()

        validatedEdits.forEach { plan ->
            val currentContent = currentContents.getValue(plan)
            val updatedContent = EditPlanValidator.applyEditsToContent(currentContent, plan.edits)
            val path = Path.of(plan.filePath)

            try {
                writeAtomically(path, updatedContent)
                affectedFiles += plan.filePath
                appliedEdits += plan.edits.sortedBy { it.startOffset }
            } catch (exception: Exception) {
                throw PartialApplyException(
                    details = mapOf(
                        "failedFile" to plan.filePath,
                        "appliedFiles" to affectedFiles.joinToString(","),
                        "createdFiles" to createdFiles.joinToString(","),
                        "deletedFiles" to deletedFiles.joinToString(","),
                        "reason" to (exception.message ?: exception::class.java.simpleName),
                    ),
                )
            }
        }

        return ApplyEditsResult(
            applied = appliedEdits,
            affectedFiles = affectedFiles.distinct().sorted(),
            createdFiles = createdFiles.sorted(),
            deletedFiles = deletedFiles.sorted(),
        )
    }

    fun currentHashes(filePaths: Collection<String>): List<FileHash> = filePaths
        .map(::canonicalPath)
        .distinct()
        .sorted()
        .map { filePath ->
            val content = Path.of(filePath).readText()
            FileHash(filePath = filePath, hash = FileHashing.sha256(content))
        }

    fun normalizePath(filePath: String): String = canonicalPath(filePath)

    private fun validateFileOperationsAgainstDisk(
        fileOperations: List<ValidatedFileOperation>,
    ) {
        fileOperations.forEach { operation ->
            when (operation) {
                is ValidatedFileOperation.CreateFile -> {
                    val path = Path.of(operation.filePath)
                    if (Files.exists(path)) {
                        throw ConflictException(
                            message = "The requested file already exists",
                            details = mapOf("filePath" to operation.filePath),
                        )
                    }
                }

                is ValidatedFileOperation.DeleteFile -> {
                    val path = Path.of(operation.filePath)
                    ensureExists(path)
                    val currentHash = FileHashing.sha256(path.readText())
                    if (currentHash != operation.expectedHash) {
                        throw ConflictException(
                            message = "The file changed after the delete plan was created",
                            details = mapOf(
                                "filePath" to operation.filePath,
                                "expectedHash" to operation.expectedHash,
                                "actualHash" to currentHash,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun ensureExists(path: Path) {
        if (!Files.exists(path)) {
            throw NotFoundException(
                message = "The requested file does not exist",
                details = mapOf("filePath" to path.toString()),
            )
        }
    }

    private fun writeAtomically(
        path: Path,
        content: String,
    ) {
        val directory = path.parent ?: throw ValidationException("A parent directory is required for edited files")
        Files.createDirectories(directory)
        val tempFile = Files.createTempFile(directory, ".kast-", ".tmp")
        try {
            tempFile.writeText(
                content,
                options = arrayOf(
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                ),
            )
            Files.move(
                tempFile,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }
}

private fun canonicalPath(filePath: String): String = NormalizedPath.parse(filePath).value
