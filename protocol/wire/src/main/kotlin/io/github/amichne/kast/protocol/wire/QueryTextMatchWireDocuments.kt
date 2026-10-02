@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint
import io.github.amichne.kast.protocol.contract.ProtocolSourceText
import io.github.amichne.kast.protocol.contract.ProtocolStringConstraint
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryTextMatchDocument
import io.github.amichne.kast.protocol.contract.SourceLineNumberDocument
import kotlinx.serialization.Serializable

@Serializable
internal enum class QueryTextMatchTypeWireDocument {
    INDEXED_WORD
}

@Serializable
internal data class QueryTextMatchWireDocument(
    val type: QueryTextMatchTypeWireDocument,
    @ProtocolStringConstraint(pattern = "^[A-Za-z_][A-Za-z0-9_]*$", minimumLength = 1, maximumLength = 256)
    val word: String,
    @ProtocolStringConstraint(
        minimumLength = 1,
        maximumLength = 1048576,
        pattern = QueryTextMatchDocument.IndexedWord.FILE_PATTERN,
    )
    val file: String,
    val range: SourceRangeWireDocument,
    @ProtocolStringConstraint(
        minimumLength = 1,
        maximumLength = 512,
        pattern = "^[^\\x00-\\x08\\x0A-\\x1F\\x7F-\\x9F]*$",
    )
    val context: String,
    val contextRange: SourceRangeWireDocument,
    @ProtocolIntegerConstraint(minimum = 1, maximum = 2147483648) val line: Long,
)

internal fun QueryTextMatchDocument.toWireDocument(): QueryTextMatchWireDocument =
    when (this) {
        is QueryTextMatchDocument.IndexedWord ->
            QueryTextMatchWireDocument(
                QueryTextMatchTypeWireDocument.INDEXED_WORD,
                word.value,
                file.value,
                range.toWireDocument(),
                context.value,
                contextRange.toWireDocument(),
                line.value,
            )
    }

internal fun QueryTextMatchWireDocument.toContract(): WireDocumentConversion<QueryTextMatchDocument> =
    when (type) {
        QueryTextMatchTypeWireDocument.INDEXED_WORD ->
            ProtocolText.parse(word).toWireDocumentConversion().flatMapConverted { admittedWord ->
                ProtocolText.parse(file).toWireDocumentConversion().flatMapConverted { admittedFile ->
                    range.toContract().flatMapConverted { admittedRange ->
                        ProtocolSourceText.parse(context).toWireDocumentConversion().flatMapConverted { admittedContext
                            ->
                            contextRange.toContract().flatMapConverted { admittedContextRange ->
                                SourceLineNumberDocument.parse(line).toWireDocumentConversion().flatMapConverted {
                                    admittedLine ->
                                    QueryTextMatchDocument.IndexedWord.create(
                                            admittedWord,
                                            admittedFile,
                                            admittedRange,
                                            admittedContext,
                                            admittedContextRange,
                                            admittedLine,
                                        )
                                        .toWireDocumentConversion()
                                }
                            }
                        }
                    }
                }
            }
    }
