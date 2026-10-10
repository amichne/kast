@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument
import io.github.amichne.kast.protocol.contract.MAX_PROTOCOL_ITEMS
import io.github.amichne.kast.protocol.contract.ProtocolCollectionConstraint
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCompletionCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionPolicyProgressDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionRetentionFailure
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryOriginalFailureDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryStaticModelDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class QueryCompletionRejectionCliDocument(
    val model: QueryStaticModelDocument,
    val cause: QueryCompletionCauseDocument,
    val originalCoverage: QueryCompletionCoverageDocument,
    val stop: QueryInvocationStop,
    val evidence: QueryCompletionEvidenceCliDocument,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val policyProgress: QueryCompletionPolicyProgressDocument = QueryCompletionPolicyProgressDocument.EvidenceOnly,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val originalFailure: QueryOriginalFailureDocument? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val diagnosticReadId: io.github.amichne.kast.protocol.contract.QueryDiagnosticReadIdentity? = null,
)

@Serializable
internal sealed interface QueryCompletionEvidenceCliDocument {
    @Serializable
    @SerialName("RETAINED")
    data class Retained(
        val result: QueryResultReference,
        @ProtocolCollectionConstraint(maximumItems = MAX_PROTOCOL_ITEMS) val preview: List<QueryResultItemCliDocument>,
        val question: QueryQuestionDocument,
        val nextQuery: QueryCompletionPublicReadQuery,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val seedQuery: QueryCompletionPublicSeedQuery? = null,
    ) : QueryCompletionEvidenceCliDocument

    @Serializable
    @SerialName("UNAVAILABLE")
    data class Unavailable(val cause: QueryCompletionRetentionFailure) : QueryCompletionEvidenceCliDocument
}

/** Copyable query_symbols arguments, encoded from retained result identity and its original output. */
@Serializable internal data class QueryCompletionPublicReadQuery(val request: QueryCompletionPublicReadRequest)

@Serializable
internal enum class QueryCompletionPublicReadAction {
    READ_RESULT
}

@Serializable
internal data class QueryCompletionPublicReadRequest(
    val result: QueryResultReference,
    val output: QueryCompletionPublicReadOutput,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val type: QueryCompletionPublicReadAction = QueryCompletionPublicReadAction.READ_RESULT,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val cursor: QueryResultCursor = QueryResultCursor.Start,
)

@Serializable
internal enum class QueryCompletionPublicSymbolField {
    NAME,
    LOCATION,
    SIGNATURE,
    SOURCE,
}

@Serializable
internal sealed interface QueryCompletionPublicReadOutput {
    @Serializable
    @SerialName("SYMBOLS")
    data class Symbols(
        @ProtocolCollectionConstraint(maximumItems = 4, uniqueItems = true)
        val fields: List<QueryCompletionPublicSymbolField>
    ) : QueryCompletionPublicReadOutput

    @Serializable @SerialName("OCCURRENCES") data object Occurrences : QueryCompletionPublicReadOutput

    @Serializable @SerialName("TRAVERSAL_RECORDS") data object TraversalRecords : QueryCompletionPublicReadOutput

    @Serializable @SerialName("BINDING_ROWS") data object BindingRows : QueryCompletionPublicReadOutput

    @Serializable @SerialName("VALUE_PATHS") data object ValuePaths : QueryCompletionPublicReadOutput

    @Serializable
    @SerialName("IMPACT_WITNESS")
    data class ImpactWitness(val section: ImpactWitnessSectionDocument) : QueryCompletionPublicReadOutput
}

internal fun QueryRunRejection.CompletionUnproven.toCompletionCli() =
    QueryCompletionRejectionCliDocument(
        model,
        cause,
        originalCoverage,
        stop,
        when (val retained = evidence) {
            is QueryCompletionEvidenceDocument.Retained ->
                QueryCompletionEvidenceCliDocument.Retained(
                    retained.result,
                    retained.preview.values.map { it.toCliDocument() },
                    retained.question,
                    QueryCompletionPublicReadQuery(
                        QueryCompletionPublicReadRequest(
                            retained.result,
                            retained.question.output.toCompletionPublicOutput(),
                        )
                    ),
                    completionSeedQuery(retained),
                )
            is QueryCompletionEvidenceDocument.Unavailable ->
                QueryCompletionEvidenceCliDocument.Unavailable(retained.cause)
        },
        policyProgress,
        originalFailure,
        diagnosticReadId,
    )

internal fun QueryOutputDocument.toCompletionPublicOutput(): QueryCompletionPublicReadOutput =
    when (this) {
        is QueryOutputDocument.Symbols ->
            QueryCompletionPublicReadOutput.Symbols(
                fields.values.map {
                    when (it) {
                        QuerySymbolFieldDocument.NAME -> QueryCompletionPublicSymbolField.NAME
                        QuerySymbolFieldDocument.LOCATION -> QueryCompletionPublicSymbolField.LOCATION
                        QuerySymbolFieldDocument.SIGNATURE -> QueryCompletionPublicSymbolField.SIGNATURE
                        QuerySymbolFieldDocument.SOURCE -> QueryCompletionPublicSymbolField.SOURCE
                    }
                }
            )
        QueryOutputDocument.Occurrences -> QueryCompletionPublicReadOutput.Occurrences
        QueryOutputDocument.TraversalRecords -> QueryCompletionPublicReadOutput.TraversalRecords
        QueryOutputDocument.BindingRows -> QueryCompletionPublicReadOutput.BindingRows
        QueryOutputDocument.ValuePaths -> QueryCompletionPublicReadOutput.ValuePaths
        is QueryOutputDocument.ImpactWitness -> QueryCompletionPublicReadOutput.ImpactWitness(section)
    }

@Serializable internal data class QueryCompletionPublicSeedQuery(val request: QueryCompletionPublicSeedRequest)

@Serializable
internal enum class QueryCompletionPublicSeedAction {
    RUN
}

@Serializable
internal enum class QueryCompletionPublicSeedSourceKind {
    SYMBOL_REFS
}

@Serializable
internal data class QueryCompletionPublicSeedSource(
    @ProtocolCollectionConstraint(minimumItems = 1, maximumItems = 1) val symbolRefs: List<ProtocolText>,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val type: QueryCompletionPublicSeedSourceKind = QueryCompletionPublicSeedSourceKind.SYMBOL_REFS,
)

@Serializable
internal data class QueryCompletionPublicSeedRequest(
    val source: QueryCompletionPublicSeedSource,
    val output: QueryCompletionPublicReadOutput,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val type: QueryCompletionPublicSeedAction = QueryCompletionPublicSeedAction.RUN,
)

/** An exact seed is a separate, smaller question, never a replacement for original discovery coverage. */
private fun completionSeedQuery(retained: QueryCompletionEvidenceDocument.Retained): QueryCompletionPublicSeedQuery? {
    val source = retained.question.from as? QueryFromDocument.Symbols ?: return null
    val match = source.match as? QueryMatchDocument.Name ?: return null
    if (match.matching != SymbolDiscoveryMatchDocument.EXACT_NAME || retained.question.steps.values.isNotEmpty())
        return null
    val output = retained.question.output as? QueryOutputDocument.Symbols ?: return null
    val row =
        retained.preview.values.firstOrNull { it is QueryResultItemDocument.ExactSymbol }
            as? QueryResultItemDocument.ExactSymbol ?: return null
    return QueryCompletionPublicSeedQuery(
        QueryCompletionPublicSeedRequest(
            QueryCompletionPublicSeedSource(listOf(row.ref.token)),
            output.toCompletionPublicOutput(),
        )
    )
}
