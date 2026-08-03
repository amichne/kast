package io.github.amichne.kast.api.validation

import io.github.amichne.kast.api.contract.ExactFileImage
import io.github.amichne.kast.api.contract.TextEdit
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal enum class ExactTextEditReplayFailure {
    IMAGE_SET_MISMATCH,
    MALFORMED_PREIMAGE_UTF8,
    MALFORMED_POSTIMAGE_UTF8,
    INVALID_UTF16_RANGE,
    SURROGATE_SPLIT,
    EDITS_NOT_SORTED_OR_OVERLAP,
    REPLACEMENT_TEXT_NOT_NORMALIZED,
    MALFORMED_REPLACEMENT_TEXT,
    POSTIMAGE_MISMATCH,
}

internal class ExactTextEditReplayException(
    val failure: ExactTextEditReplayFailure,
    message: String,
) : IllegalArgumentException(message)

internal object ExactTextEditReplayValidator {
    fun requireExactPostimages(
        edits: List<TextEdit>,
        images: List<ExactFileImage>,
    ) {
        val editsByPath = edits.groupBy(TextEdit::filePath)
        val imagePaths = images.map { image -> image.filePath.value }
        if (editsByPath.isEmpty() ||
            imagePaths.size != imagePaths.distinct().size ||
            editsByPath.keys != imagePaths.toSet()
        ) {
            fail(
                ExactTextEditReplayFailure.IMAGE_SET_MISMATCH,
                "Exact mutation images must match the edited file paths",
            )
        }

        images.forEach { image ->
            val claimedPostimage = image.postimage.copyBytes()
            strictUtf8Text(
                claimedPostimage,
                ExactTextEditReplayFailure.MALFORMED_POSTIMAGE_UTF8,
                "The claimed exact postimage is not strict UTF-8",
            )
            val replayedPostimage = replay(
                rawPreimage = image.preimage.copyBytes(),
                edits = editsByPath.getValue(image.filePath.value).map { edit -> edit.toNormalizedEdit() },
            )
            if (!replayedPostimage.contentEquals(claimedPostimage)) {
                fail(
                    ExactTextEditReplayFailure.POSTIMAGE_MISMATCH,
                    "The claimed exact postimage does not follow from its normalized UTF-16 edits",
                )
            }
        }
    }

    private fun replay(
        rawPreimage: ByteArray,
        edits: List<NormalizedUtf16TextEdit>,
    ): ByteArray {
        val source = decodeSource(rawPreimage)
        validateEdits(edits, source)
        val separator = source.firstLineSeparator ?: RawLineSeparator.LF
        val output = ByteArrayOutputStream(rawPreimage.size)
        var rawCursor = 0
        edits.forEach { edit ->
            val rawStart = source.rawBoundaries[edit.start.value]
            val rawEnd = source.rawBoundaries[edit.end.value]
            output.write(rawPreimage, rawCursor, rawStart - rawCursor)
            output.write(strictReplacementBytes(edit.replacementText.replace("\n", separator.text)))
            rawCursor = rawEnd
        }
        output.write(rawPreimage, rawCursor, rawPreimage.size - rawCursor)
        return output.toByteArray()
    }

    private fun validateEdits(
        edits: List<NormalizedUtf16TextEdit>,
        source: DecodedSource,
    ) {
        var previousStart = -1
        var previousEnd = 0
        edits.forEachIndexed { index, edit ->
            val start = edit.start.value
            val end = edit.end.value
            if (end < start || end > source.normalizedLength) {
                fail(
                    ExactTextEditReplayFailure.INVALID_UTF16_RANGE,
                    "A text edit is outside the normalized UTF-16 document range",
                )
            }
            val duplicateZeroLengthEdit = index > 0 &&
                start == end && previousStart == previousEnd && start == previousStart
            if (index > 0 && (start < previousStart || start < previousEnd || duplicateZeroLengthEdit)) {
                fail(
                    ExactTextEditReplayFailure.EDITS_NOT_SORTED_OR_OVERLAP,
                    "Text edits must be sorted and non-overlapping within each file",
                )
            }
            if (source.rawBoundaries[start] == ILLEGAL_BOUNDARY ||
                source.rawBoundaries[end] == ILLEGAL_BOUNDARY
            ) {
                fail(
                    ExactTextEditReplayFailure.SURROGATE_SPLIT,
                    "A text edit offset splits a non-BMP UTF-16 surrogate pair",
                )
            }
            if ('\r' in edit.replacementText) {
                fail(
                    ExactTextEditReplayFailure.REPLACEMENT_TEXT_NOT_NORMALIZED,
                    "Replacement text must use normalized LF line separators",
                )
            }
            strictReplacementBytes(edit.replacementText)
            previousStart = start
            previousEnd = end
        }
    }

    private fun decodeSource(rawPreimage: ByteArray): DecodedSource {
        val bomLength = if (rawPreimage.startsWithUtf8Bom()) UTF8_BOM.size else 0
        val decoded = strictUtf8Text(
            rawPreimage.copyOfRange(bomLength, rawPreimage.size),
            ExactTextEditReplayFailure.MALFORMED_PREIMAGE_UTF8,
            "The exact preimage is not strict UTF-8",
        )
        val boundaries = MutableList(decoded.length + 1) { ILLEGAL_BOUNDARY }
        boundaries[0] = bomLength
        var decodedOffset = 0
        var normalizedOffset = 0
        var rawOffset = bomLength
        var firstSeparator: RawLineSeparator? = null
        while (decodedOffset < decoded.length) {
            val char = decoded[decodedOffset]
            when {
                char == '\r' && decodedOffset + 1 < decoded.length && decoded[decodedOffset + 1] == '\n' -> {
                    decodedOffset += 2
                    rawOffset += 2
                    normalizedOffset += 1
                    boundaries[normalizedOffset] = rawOffset
                    if (firstSeparator == null) firstSeparator = RawLineSeparator.CRLF
                }

                char == '\r' -> {
                    decodedOffset += 1
                    rawOffset += 1
                    normalizedOffset += 1
                    boundaries[normalizedOffset] = rawOffset
                    if (firstSeparator == null) firstSeparator = RawLineSeparator.CR
                }

                char == '\n' -> {
                    decodedOffset += 1
                    rawOffset += 1
                    normalizedOffset += 1
                    boundaries[normalizedOffset] = rawOffset
                    if (firstSeparator == null) firstSeparator = RawLineSeparator.LF
                }

                else -> {
                    val codePoint = Character.codePointAt(decoded, decodedOffset)
                    val utf16Length = Character.charCount(codePoint)
                    decodedOffset += utf16Length
                    rawOffset += codePoint.utf8Length()
                    if (utf16Length == 2) boundaries[normalizedOffset + 1] = ILLEGAL_BOUNDARY
                    normalizedOffset += utf16Length
                    boundaries[normalizedOffset] = rawOffset
                }
            }
        }
        check(rawOffset == rawPreimage.size) { "Strict UTF-8 mapping must consume the exact preimage" }
        return DecodedSource(
            normalizedLength = normalizedOffset,
            rawBoundaries = boundaries.take(normalizedOffset + 1).toIntArray(),
            firstLineSeparator = firstSeparator,
        )
    }

    private fun strictReplacementBytes(text: String): ByteArray = try {
        val encoded = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(text))
        ByteArray(encoded.remaining()).also(encoded::get)
    } catch (_: CharacterCodingException) {
        fail(
            ExactTextEditReplayFailure.MALFORMED_REPLACEMENT_TEXT,
            "Replacement text contains malformed UTF-16",
        )
    }

    private fun strictUtf8Text(
        bytes: ByteArray,
        failure: ExactTextEditReplayFailure,
        message: String,
    ): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        fail(failure, message)
    }

    private fun TextEdit.toNormalizedEdit(): NormalizedUtf16TextEdit {
        if (startOffset < 0 || endOffset < 0) {
            fail(
                ExactTextEditReplayFailure.INVALID_UTF16_RANGE,
                "Text edit UTF-16 offsets must be non-negative",
            )
        }
        return NormalizedUtf16TextEdit(
            start = Utf16CodeUnitOffset(startOffset),
            end = Utf16CodeUnitOffset(endOffset),
            replacementText = newText,
        )
    }

    private fun fail(failure: ExactTextEditReplayFailure, message: String): Nothing =
        throw ExactTextEditReplayException(failure, message)

    private fun Int.utf8Length(): Int = when {
        this <= 0x7F -> 1
        this <= 0x7FF -> 2
        this <= 0xFFFF -> 3
        else -> 4
    }

    private fun ByteArray.startsWithUtf8Bom(): Boolean =
        size >= UTF8_BOM.size && UTF8_BOM.indices.all { index -> this[index] == UTF8_BOM[index] }

    @JvmInline
    private value class Utf16CodeUnitOffset(val value: Int)

    private data class NormalizedUtf16TextEdit(
        val start: Utf16CodeUnitOffset,
        val end: Utf16CodeUnitOffset,
        val replacementText: String,
    )

    private data class DecodedSource(
        val normalizedLength: Int,
        val rawBoundaries: IntArray,
        val firstLineSeparator: RawLineSeparator?,
    )

    private enum class RawLineSeparator(val text: String) {
        LF("\n"),
        CRLF("\r\n"),
        CR("\r"),
    }

    private const val ILLEGAL_BOUNDARY: Int = -1
    private val UTF8_BOM: ByteArray = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
}
