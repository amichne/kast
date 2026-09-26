package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable

private const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
private const val PIPELINE_PATTERN = "query:v1:$UUID_PATTERN"
private const val OUTPUT_PATTERN = "query-output:v1:$UUID_PATTERN"
private const val EXECUTION_PATTERN = "(?:query|query-output):v1:$UUID_PATTERN"
private const val PIPELINE_LENGTH = 45
private const val OUTPUT_LENGTH = 52

enum class QueryExecutionContinuationFailure {
    MALFORMED
}

/** Resumption of unfinished query execution or retained output, never a semantic result reference. */
@Serializable(with = QueryExecutionContinuationSerializer::class)
sealed interface QueryExecutionContinuation {
    val value: String

    @Serializable(with = QueryPipelineContinuationSerializer::class)
    @JvmInline
    value class Pipeline private constructor(override val value: String) : QueryExecutionContinuation {
        companion object {
            private val syntax = Regex(PIPELINE_PATTERN)

            fun parse(raw: String): Refinement<Pipeline, QueryExecutionContinuationFailure> =
                if (syntax.matches(raw)) Refinement.Refined(Pipeline(raw))
                else Refinement.Rejected(QueryExecutionContinuationFailure.MALFORMED)
        }
    }

    @Serializable(with = QueryOutputContinuationSerializer::class)
    @JvmInline
    value class Output private constructor(override val value: String) : QueryExecutionContinuation {
        companion object {
            private val syntax = Regex(OUTPUT_PATTERN)

            fun parse(raw: String): Refinement<Output, QueryExecutionContinuationFailure> =
                if (syntax.matches(raw)) Refinement.Refined(Output(raw))
                else Refinement.Rejected(QueryExecutionContinuationFailure.MALFORMED)
        }
    }

    companion object {
        fun parse(raw: String): Refinement<QueryExecutionContinuation, QueryExecutionContinuationFailure> =
            when {
                raw.startsWith("query:v1:") -> Pipeline.parse(raw)
                raw.startsWith("query-output:v1:") -> Output.parse(raw)
                else -> Refinement.Rejected(QueryExecutionContinuationFailure.MALFORMED)
            }
    }
}

internal object QueryExecutionContinuationSerializer :
    RefiningStringSerializer<QueryExecutionContinuation>(
        serialName = "QueryExecutionContinuation",
        minimumLength = PIPELINE_LENGTH,
        maximumLength = OUTPUT_LENGTH,
        pattern = EXECUTION_PATTERN,
    ) {
    override fun raw(value: QueryExecutionContinuation): String = value.value

    override fun refine(raw: String): Refinement<QueryExecutionContinuation, *> = QueryExecutionContinuation.parse(raw)
}

internal object QueryPipelineContinuationSerializer :
    RefiningStringSerializer<QueryExecutionContinuation.Pipeline>(
        serialName = "QueryPipelineContinuation",
        minimumLength = PIPELINE_LENGTH,
        maximumLength = PIPELINE_LENGTH,
        pattern = PIPELINE_PATTERN,
    ) {
    override fun raw(value: QueryExecutionContinuation.Pipeline): String = value.value

    override fun refine(raw: String): Refinement<QueryExecutionContinuation.Pipeline, *> =
        QueryExecutionContinuation.Pipeline.parse(raw)
}

internal object QueryOutputContinuationSerializer :
    RefiningStringSerializer<QueryExecutionContinuation.Output>(
        serialName = "QueryOutputContinuation",
        minimumLength = OUTPUT_LENGTH,
        maximumLength = OUTPUT_LENGTH,
        pattern = OUTPUT_PATTERN,
    ) {
    override fun raw(value: QueryExecutionContinuation.Output): String = value.value

    override fun refine(raw: String): Refinement<QueryExecutionContinuation.Output, *> =
        QueryExecutionContinuation.Output.parse(raw)
}
