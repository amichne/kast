package io.github.amichne.kast.testing

import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.contract.result.ImportOptimizeResult
import io.github.amichne.kast.api.contract.result.RenameResult
import io.github.amichne.kast.api.protocol.*
import io.github.amichne.kast.api.validation.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal suspend fun FakeAnalysisBackend.renameResult(query: ParsedRenameQuery): RenameResult {
    requireAnchor(query.position)
    val edits = symbolAnchors
        .map { anchor ->
            TextEdit(
                filePath = anchor.filePath,
                startOffset = anchor.startOffset,
                endOffset = anchor.endOffset,
                newText = query.newName.value,
            )
        }
        .distinctBy { edit -> Triple(edit.filePath, edit.startOffset, edit.endOffset) }
        .sortedWith(compareBy({ it.filePath }, { it.startOffset }))
    val affectedFiles = edits.map(TextEdit::filePath).distinct()

    return RenameResult.of(
        edits = edits,
        fileHashes = affectedFiles.map { filePath ->
            FileHash(
                filePath = filePath,
                hash = FileHashing.sha256(Files.readString(Path.of(filePath))),
            )
        },
    )
}

internal suspend fun FakeAnalysisBackend.optimizeImportsResult(query: ParsedImportOptimizeQuery): ImportOptimizeResult {
    query.filePaths.value.map { it.value }.forEach(::requireKnownFile)
    return ImportOptimizeResult(
        edits = emptyList(),
        fileHashes = emptyList(),
        affectedFiles = emptyList(),
    )
}

internal suspend fun FakeAnalysisBackend.applyEditsResult(query: ParsedApplyEditsQuery): ApplyEditsResult =
    applyEditsToFixtureFiles(query.toWire())

private fun FakeAnalysisBackend.applyEditsToFixtureFiles(query: ApplyEditsQuery): ApplyEditsResult {
    if (query.edits.isEmpty() && query.fileOperations.isEmpty()) {
        throw ValidationException("At least one text edit or file operation is required")
    }

    val affectedFiles = mutableListOf<String>()
    val createdFiles = mutableListOf<String>()
    val deletedFiles = mutableListOf<String>()

    EditPlanValidator.validateFileOperations(query.fileOperations).forEach { operation ->
        try {
            when (operation) {
                is ValidatedFileOperation.CreateFile -> {
                    val path = Path.of(operation.filePath)
                    if (Files.exists(path)) {
                        throw ConflictException(
                            message = "File already exists",
                            details = mapOf("filePath" to operation.filePath),
                        )
                    }
                    path.parent?.let(Files::createDirectories)
                    Files.writeString(path, operation.content)
                    createdFiles += operation.filePath
                    availableFiles.add(operation.filePath)
                }

                is ValidatedFileOperation.DeleteFile -> {
                    ensureFixtureFileHash(operation.filePath, operation.expectedHash)
                    Files.delete(Path.of(operation.filePath))
                    deletedFiles += operation.filePath
                    availableFiles.remove(operation.filePath)
                }
            }
            affectedFiles += operation.filePath
        } catch (exception: Exception) {
            throw PartialApplyException(
                details = mutationFailureDetails(
                    failedFile = operation.filePath,
                    affectedFiles = affectedFiles,
                    createdFiles = createdFiles,
                    deletedFiles = deletedFiles,
                    exception = exception,
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
        readFixtureFile(plan.filePath)
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
        val updatedContent = EditPlanValidator.applyEditsToContent(
            currentContents.getValue(plan),
            plan.edits,
        )
        try {
            writeFixtureFileAtomically(plan.filePath, updatedContent)
            affectedFiles += plan.filePath
            appliedEdits += plan.edits.sortedBy { it.startOffset }
        } catch (exception: Exception) {
            throw PartialApplyException(
                details = mutationFailureDetails(
                    failedFile = plan.filePath,
                    affectedFiles = affectedFiles,
                    createdFiles = createdFiles,
                    deletedFiles = deletedFiles,
                    exception = exception,
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

private fun FakeAnalysisBackend.readFixtureFile(filePath: String): String {
    val path = Path.of(filePath)
    if (!Files.exists(path)) {
        throw NotFoundException(
            message = "File does not exist",
            details = mapOf("filePath" to filePath),
        )
    }
    return Files.readString(path)
}

private fun FakeAnalysisBackend.ensureFixtureFileHash(filePath: String, expectedHash: String) {
    val content = readFixtureFile(filePath)
    val currentHash = FileHashing.sha256(content)
    if (currentHash != expectedHash) {
        throw ConflictException(
            message = "The file changed after the delete plan was created",
            details = mapOf(
                "filePath" to filePath,
                "expectedHash" to expectedHash,
                "actualHash" to currentHash,
            ),
        )
    }
}

private fun FakeAnalysisBackend.writeFixtureFileAtomically(filePath: String, content: String) {
    val target = Path.of(filePath)
    val parent = target.parent
    parent?.let(Files::createDirectories)
    val tempFile = Files.createTempFile(parent, ".kast-", ".tmp")
    try {
        Files.writeString(tempFile, content)
        Files.move(tempFile, target, ATOMIC_MOVE, REPLACE_EXISTING)
    } catch (exception: Exception) {
        Files.deleteIfExists(tempFile)
        throw exception
    }
}

private fun FakeAnalysisBackend.mutationFailureDetails(
    failedFile: String,
    affectedFiles: List<String>,
    createdFiles: List<String>,
    deletedFiles: List<String>,
    exception: Exception,
): Map<String, String> = mapOf(
    "failedFile" to failedFile,
    "appliedFiles" to affectedFiles.joinToString(","),
    "createdFiles" to createdFiles.joinToString(","),
    "deletedFiles" to deletedFiles.joinToString(","),
    "reason" to (exception.message ?: exception::class.java.simpleName),
)
