package io.github.amichne.kast.idea.edit

import io.github.amichne.kast.api.contract.ExactByteImage
import io.github.amichne.kast.api.contract.ExactFileImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

@JvmInline
internal value class IdeaUtf16Offset(
    val value: Int,
) {
    init {
        require(value >= 0) { "IntelliJ UTF-16 offsets must be non-negative" }
    }
}

internal data class IdeaNormalizedTextEdit(
    val startOffset: IdeaUtf16Offset,
    val endOffset: IdeaUtf16Offset,
    val replacementText: String,
)

internal enum class IdeaLineSeparator(
    val text: String,
) {
    LF("\n"),
    CRLF("\r\n"),
    CR("\r"),
}

internal enum class IdeaTextImageLimitation {
    MALFORMED_UTF8,
    NORMALIZED_DOCUMENT_MISMATCH,
    INVALID_UTF16_RANGE,
    SURROGATE_SPLIT,
    EDITS_NOT_SORTED_OR_OVERLAP,
    REPLACEMENT_TEXT_NOT_NORMALIZED,
    MALFORMED_REPLACEMENT_TEXT,
}

internal class IdeaTextImagePlanningException(
    val limitation: IdeaTextImageLimitation,
    message: String,
) : IllegalArgumentException(message)

internal class IdeaTextImagePlan private constructor(
    val preimage: ExactByteImage,
    val postimage: ExactByteImage,
    val replacementLineSeparator: IdeaLineSeparator,
) {
    fun resultBytes(): ByteArray = postimage.copyBytes()

    fun exactFileImage(filePath: String): ExactFileImage = ExactFileImage.of(
        filePath = filePath,
        preimageBytes = preimage.copyBytes(),
        postimageBytes = postimage.copyBytes(),
    )

    companion object {
        fun of(
            preimageBytes: ByteArray,
            postimageBytes: ByteArray,
            replacementLineSeparator: IdeaLineSeparator,
        ): IdeaTextImagePlan = IdeaTextImagePlan(
            preimage = ExactByteImage.of(preimageBytes),
            postimage = ExactByteImage.of(postimageBytes),
            replacementLineSeparator = replacementLineSeparator,
        )
    }
}

internal object IdeaTextImagePlanner {
    fun plan(
        rawPreimage: ByteArray,
        normalizedDocumentText: String,
        edits: List<IdeaNormalizedTextEdit>,
        replacementLineSeparator: IdeaLineSeparator? = null,
    ): IdeaTextImagePlan {
        val source = decodeSource(rawPreimage)
        if (source.normalizedText != normalizedDocumentText) {
            fail(
                IdeaTextImageLimitation.NORMALIZED_DOCUMENT_MISMATCH,
                "The normalized IntelliJ document does not match the exact raw file image",
            )
        }
        validateEdits(edits, source)
        val selectedSeparator = replacementLineSeparator ?: source.firstLineSeparator ?: IdeaLineSeparator.LF
        val postimage = applyEdits(rawPreimage, edits, source.rawBoundaries, selectedSeparator)
        return IdeaTextImagePlan.of(rawPreimage, postimage, selectedSeparator)
    }

    private fun validateEdits(
        edits: List<IdeaNormalizedTextEdit>,
        source: DecodedIdeaSource,
    ) {
        var previousStart = -1
        var previousEnd = 0
        edits.forEachIndexed { index, edit ->
            val start = edit.startOffset.value
            val end = edit.endOffset.value
            if (end < start || end > source.normalizedText.length) {
                fail(
                    IdeaTextImageLimitation.INVALID_UTF16_RANGE,
                    "An IntelliJ text edit is outside the normalized UTF-16 document range",
                )
            }
            val duplicateZeroLengthEdit = index > 0 &&
                start == end && previousStart == previousEnd && start == previousStart
            if (index > 0 && (start < previousStart || start < previousEnd || duplicateZeroLengthEdit)) {
                fail(
                    IdeaTextImageLimitation.EDITS_NOT_SORTED_OR_OVERLAP,
                    "IntelliJ text edits must be sorted and non-overlapping",
                )
            }
            if (source.rawBoundaries[start] == ILLEGAL_BOUNDARY ||
                source.rawBoundaries[end] == ILLEGAL_BOUNDARY
            ) {
                fail(
                    IdeaTextImageLimitation.SURROGATE_SPLIT,
                    "An IntelliJ text edit offset splits a non-BMP UTF-16 surrogate pair",
                )
            }
            if ('\r' in edit.replacementText) {
                fail(
                    IdeaTextImageLimitation.REPLACEMENT_TEXT_NOT_NORMALIZED,
                    "IntelliJ replacement text must use normalized LF line separators",
                )
            }
            strictUtf8Bytes(
                edit.replacementText,
                IdeaTextImageLimitation.MALFORMED_REPLACEMENT_TEXT,
                "IntelliJ replacement text contains malformed UTF-16",
            )
            previousStart = start
            previousEnd = end
        }
    }

    private fun applyEdits(
        rawPreimage: ByteArray,
        edits: List<IdeaNormalizedTextEdit>,
        rawBoundaries: IntArray,
        lineSeparator: IdeaLineSeparator,
    ): ByteArray {
        val output = ByteArrayOutputStream(rawPreimage.size)
        var rawCursor = 0
        edits.forEach { edit ->
            val rawStart = rawBoundaries[edit.startOffset.value]
            val rawEnd = rawBoundaries[edit.endOffset.value]
            output.write(rawPreimage, rawCursor, rawStart - rawCursor)
            val replacement = edit.replacementText.replace("\n", lineSeparator.text)
            output.write(
                strictUtf8Bytes(
                    replacement,
                    IdeaTextImageLimitation.MALFORMED_REPLACEMENT_TEXT,
                    "IntelliJ replacement text contains malformed UTF-16",
                ),
            )
            rawCursor = rawEnd
        }
        output.write(rawPreimage, rawCursor, rawPreimage.size - rawCursor)
        return output.toByteArray()
    }

    private fun decodeSource(rawPreimage: ByteArray): DecodedIdeaSource {
        val bomLength = if (rawPreimage.startsWithUtf8Bom()) UTF8_BOM.size else 0
        val decoded = strictUtf8Text(rawPreimage.copyOfRange(bomLength, rawPreimage.size))
        val normalized = StringBuilder(decoded.length)
        val boundaries = MutableList(decoded.length + 1) { ILLEGAL_BOUNDARY }
        boundaries[0] = bomLength
        var decodedOffset = 0
        var normalizedOffset = 0
        var rawOffset = bomLength
        var firstSeparator: IdeaLineSeparator? = null
        while (decodedOffset < decoded.length) {
            val char = decoded[decodedOffset]
            when {
                char == '\r' && decodedOffset + 1 < decoded.length && decoded[decodedOffset + 1] == '\n' -> {
                    normalized.append('\n')
                    decodedOffset += 2
                    rawOffset += 2
                    normalizedOffset += 1
                    boundaries[normalizedOffset] = rawOffset
                    if (firstSeparator == null) firstSeparator = IdeaLineSeparator.CRLF
                }

                char == '\r' -> {
                    normalized.append('\n')
                    decodedOffset += 1
                    rawOffset += 1
                    normalizedOffset += 1
                    boundaries[normalizedOffset] = rawOffset
                    if (firstSeparator == null) firstSeparator = IdeaLineSeparator.CR
                }

                char == '\n' -> {
                    normalized.append('\n')
                    decodedOffset += 1
                    rawOffset += 1
                    normalizedOffset += 1
                    boundaries[normalizedOffset] = rawOffset
                    if (firstSeparator == null) firstSeparator = IdeaLineSeparator.LF
                }

                else -> {
                    val codePoint = Character.codePointAt(decoded, decodedOffset)
                    val utf16Length = Character.charCount(codePoint)
                    val scalar = String(Character.toChars(codePoint))
                    val rawLength = strictUtf8Bytes(
                        scalar,
                        IdeaTextImageLimitation.MALFORMED_UTF8,
                        "The exact file image is not strict UTF-8",
                    ).size
                    normalized.append(scalar)
                    decodedOffset += utf16Length
                    rawOffset += rawLength
                    if (utf16Length == 2) {
                        boundaries[normalizedOffset + 1] = ILLEGAL_BOUNDARY
                    }
                    normalizedOffset += utf16Length
                    boundaries[normalizedOffset] = rawOffset
                }
            }
        }
        check(rawOffset == rawPreimage.size) { "Strict UTF-8 mapping must consume the exact raw image" }
        return DecodedIdeaSource(
            normalizedText = normalized.toString(),
            rawBoundaries = boundaries.take(normalized.length + 1).toIntArray(),
            firstLineSeparator = firstSeparator,
        )
    }

    private fun strictUtf8Text(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        fail(
            IdeaTextImageLimitation.MALFORMED_UTF8,
            "The exact file image is not strict UTF-8",
        )
    }

    private fun strictUtf8Bytes(
        text: String,
        limitation: IdeaTextImageLimitation,
        message: String,
    ): ByteArray = try {
        val encoded = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(text))
        ByteArray(encoded.remaining()).also(encoded::get)
    } catch (_: Exception) {
        fail(limitation, message)
    }

    private fun fail(limitation: IdeaTextImageLimitation, message: String): Nothing =
        throw IdeaTextImagePlanningException(limitation, message)

    private data class DecodedIdeaSource(
        val normalizedText: String,
        val rawBoundaries: IntArray,
        val firstLineSeparator: IdeaLineSeparator?,
    )

    private const val ILLEGAL_BOUNDARY: Int = -1
    private val UTF8_BOM: ByteArray = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    private fun ByteArray.startsWithUtf8Bom(): Boolean =
        size >= UTF8_BOM.size && UTF8_BOM.indices.all { index -> this[index] == UTF8_BOM[index] }
}
