package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryResultInterpretationDocument
import io.github.amichne.kast.protocol.contract.QueryStepDocument

/** The original question owns completion semantics across execution and retained presentation. */
internal enum class QueryCallbackCompletion(val interpretation: QueryResultInterpretationDocument) {
    INVOCATION_GRAPH(QueryResultInterpretationDocument.QueryResult),
    SOURCE_RELATIONSHIPS(QueryResultInterpretationDocument.SourceRelationships());

    companion object {
        fun from(question: QueryQuestionDocument): QueryCallbackCompletion {
            when (question.from) {
                is QueryFromDocument.Result,
                is QueryFromDocument.Impact -> return INVOCATION_GRAPH
                is QueryFromDocument.Symbols,
                is QueryFromDocument.TextWord,
                is QueryFromDocument.Location,
                is QueryFromDocument.References -> Unit
            }
            if (question.steps.values.lastOrNull() !is QueryStepDocument.Trace) return INVOCATION_GRAPH
            for (step in question.steps.values) when (step) {
                is QueryStepDocument.Trace,
                is QueryStepDocument.Where,
                QueryStepDocument.Distinct -> Unit
                is QueryStepDocument.Related,
                is QueryStepDocument.Walk,
                is QueryStepDocument.ProjectBinding,
                is QueryStepDocument.Join,
                is QueryStepDocument.Concat,
                is QueryStepDocument.Intersect,
                is QueryStepDocument.Union,
                is QueryStepDocument.Difference -> return INVOCATION_GRAPH
            }
            return SOURCE_RELATIONSHIPS
        }
    }
}
