package io.github.amichne.kast.api.validation

import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.FileOperation
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.query.ApplyEditsQuery

fun ParsedTextEdit.toWire(): TextEdit = TextEdit(
    filePath = filePath.value,
    startOffset = startOffset.value,
    endOffset = endOffset.value,
    newText = newText,
)

fun ParsedFileHash.toWire(): FileHash = FileHash(
    filePath = filePath.value,
    hash = hash,
)

fun ParsedFileOperation.toWire(): FileOperation = when (this) {
    is ParsedFileOperation.CreateFile -> FileOperation.CreateFile(
        filePath = filePath.value,
        content = content,
    )

    is ParsedFileOperation.DeleteFile -> FileOperation.DeleteFile(
        filePath = filePath.value,
        expectedHash = expectedHash,
    )
}

fun ParsedApplyEditsQuery.toWire(): ApplyEditsQuery = ApplyEditsQuery(
    edits = edits.map(ParsedTextEdit::toWire),
    fileHashes = fileHashes.map(ParsedFileHash::toWire),
    fileOperations = fileOperations.map(ParsedFileOperation::toWire),
)
