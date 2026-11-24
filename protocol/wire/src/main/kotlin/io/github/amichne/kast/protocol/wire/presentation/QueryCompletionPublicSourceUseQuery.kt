@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.protocol.contract.QueryCompletionCauseDocument
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

@Serializable
internal enum class QueryCompletionPublicSourceUseRelation {
    REFERENCES
}

@Serializable
internal enum class QueryCompletionPublicSourceUseStepKind {
    EXPAND_RELATION
}

@Serializable
internal data class QueryCompletionPublicSourceUseStep(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val relation: QueryCompletionPublicSourceUseRelation = QueryCompletionPublicSourceUseRelation.REFERENCES,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val type: QueryCompletionPublicSourceUseStepKind = QueryCompletionPublicSourceUseStepKind.EXPAND_RELATION,
)

/**
 * A separate question that never completes the original caller question. Only an exact, one-hop caller question over
 * the default workspace keeps its symbols and domain unchanged.
 */
internal fun completionSourceUseQuery(
    cause: QueryCompletionCauseDocument,
    question: QueryQuestionDocument,
): QueryCompletionPublicSeedQuery? {
    if (cause !is QueryCompletionCauseDocument.CallbackGraphUnproven) return null
    val source = question.from as? QueryFromDocument.References ?: return null
    val callers = question.steps.values.singleOrNull() as? QueryStepDocument.Related ?: return null
    if (
        callers.relation != RelationKindDocument.CALLERS ||
            callers.expansionScope != QueryExpansionScopeDocument.Workspace
    )
        return null
    return QueryCompletionPublicSeedQuery(
        QueryCompletionPublicSeedRequest(
            source = QueryCompletionPublicSeedSource(source.values.values.map { it.token }),
            terminalStep = QueryCompletionPublicSeedTerminal(QueryCompletionPublicReadOutput.Occurrences),
            steps = listOf(QueryCompletionPublicSourceUseStep()),
        )
    )
}
