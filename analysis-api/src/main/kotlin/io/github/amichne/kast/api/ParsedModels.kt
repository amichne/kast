package io.github.amichne.kast.api

/**
 * Validated internal representation of a [FilePosition].
 * Constructed at system boundaries to consolidate validation and parsing.
 */
data class ParsedFilePosition(
    val filePath: NormalizedPath,
    val offset: ByteOffset,
)

/**
 * Validated internal representation of a [Location].
 * Constructed at system boundaries to consolidate validation and parsing.
 */
data class ParsedLocation(
    val filePath: NormalizedPath,
    val startOffset: ByteOffset,
    val endOffset: ByteOffset,
    val startLine: LineNumber,
    val startColumn: ColumnNumber,
    val preview: String,
)

/**
 * Validated internal representation of a [TextEdit].
 * Constructed at system boundaries to consolidate validation and parsing.
 */
data class ParsedTextEdit(
    val filePath: NormalizedPath,
    val startOffset: ByteOffset,
    val endOffset: ByteOffset,
    val newText: String,
)

/**
 * Parse a wire-format [FilePosition] into a validated [ParsedFilePosition].
 * Throws [ValidationException] if the path is not absolute or the offset is negative.
 */
fun FilePosition.parsed(): ParsedFilePosition = ParsedFilePosition(
    filePath = NormalizedPath.parse(filePath),
    offset = ByteOffset(offset),
)

/**
 * Parse a wire-format [Location] into a validated [ParsedLocation].
 */
fun Location.parsed(): ParsedLocation = ParsedLocation(
    filePath = NormalizedPath.parse(filePath),
    startOffset = ByteOffset(startOffset),
    endOffset = ByteOffset(endOffset),
    startLine = LineNumber(startLine),
    startColumn = ColumnNumber(startColumn),
    preview = preview,
)

/**
 * Parse a wire-format [TextEdit] into a validated [ParsedTextEdit].
 */
fun TextEdit.parsed(): ParsedTextEdit = ParsedTextEdit(
    filePath = NormalizedPath.parse(filePath),
    startOffset = ByteOffset(startOffset),
    endOffset = ByteOffset(endOffset),
    newText = newText,
)
