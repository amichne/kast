package io.github.amichne.kast.evidence.sqlite.historical

import io.github.amichne.kast.kernel.Refinement
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.sql.ResultSet

/** Exact SQLite TEXT bytes decoded without replacement. SQL NULL remains a distinct optional value. */
internal class HistoricalSqliteText private constructor(val value: String) {
    companion object {
        fun read(rows: ResultSet, column: String): Refinement<HistoricalOptionalText, HistoricalStorageFailure> {
            when (rows.getObject(column)) {
                null -> return Refinement.Refined(HistoricalOptionalText.Absent)
                is String -> Unit
                else -> return Refinement.Rejected(HistoricalStorageFailure.CONTENT_REJECTED)
            }
            val bytes = rows.getBytes(column) ?: return Refinement.Rejected(HistoricalStorageFailure.CONTENT_REJECTED)
            val decoder =
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
            val input = ByteBuffer.wrap(bytes)
            val output = CharBuffer.allocate(minOf(bytes.size, DECODE_CHARACTERS))
            val text = StringBuilder()
            do {
                val result = decoder.decode(input, output, true)
                if (result.isError) return Refinement.Rejected(HistoricalStorageFailure.CONTENT_REJECTED)
                output.flip()
                text.append(output)
                output.clear()
            } while (result.isOverflow)
            return Refinement.Refined(HistoricalOptionalText.Present(HistoricalSqliteText(text.toString())))
        }
    }
}

internal sealed interface HistoricalOptionalText {
    data object Absent : HistoricalOptionalText

    data class Present(val text: HistoricalSqliteText) : HistoricalOptionalText
}

internal fun historicalText(
    rows: ResultSet,
    column: String,
): Refinement<HistoricalSqliteText, HistoricalStorageFailure> =
    HistoricalSqliteText.read(rows, column).flatMap { optional ->
        when (optional) {
            HistoricalOptionalText.Absent -> Refinement.Rejected(HistoricalStorageFailure.CONTENT_REJECTED)
            is HistoricalOptionalText.Present -> Refinement.Refined(optional.text)
        }
    }

internal fun <T, F> historicalText(
    rows: ResultSet,
    column: String,
    parse: (String) -> Refinement<T, F>,
): Refinement<T, HistoricalStorageFailure> = historicalText(rows, column).flatMap { parse(it.value).storage() }

internal inline fun <reified T : Enum<T>> historicalEnum(
    rows: ResultSet,
    column: String,
    failure: HistoricalStorageFailure = HistoricalStorageFailure.CONTENT_REJECTED,
): Refinement<T, HistoricalStorageFailure> =
    historicalText(rows, column).flatMap { historicalEnum<T>(it.value, failure) }

private const val DECODE_CHARACTERS = 4096
