package io.github.amichne.kast.cli.rpc

import io.github.amichne.kast.appserver.query.PublicToolCanonical
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.cli.MAXIMUM_PROTOCOL_TEXT_LENGTH
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryContainmentDocument
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.protocol.contract.TraversalExtentDocument
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
internal sealed interface QueryCostPlanReply {
    @Serializable
    @SerialName("planned")
    @OptIn(ExperimentalSerializationApi::class)
    data class Planned(
        val request: QueryRunRequest.Run,
        val advice: List<QueryCostAdvice>,
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) val basis: QueryCostPlanBasis = QueryCostPlanBasis.REQUEST_SHAPE_ONLY,
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) val estimate: QueryCostEstimate = QueryCostEstimate.NOT_MEASURED,
    ) : QueryCostPlanReply

    @Serializable @SerialName("rejected") data class Rejected(val failure: QueryCostPlanFailure) : QueryCostPlanReply
}

@Serializable
internal enum class QueryCostPlanBasis {
    REQUEST_SHAPE_ONLY
}

@Serializable
internal enum class QueryCostEstimate {
    NOT_MEASURED
}

@Serializable
internal enum class QueryCostPlanFailure {
    INVALID_ARGUMENTS,
    REQUEST_TOO_LARGE,
    RUN_REQUIRED,
}

@Serializable
internal enum class QueryCostAdvice {
    NARROW_DISCOVERY_SCOPE,
    USE_EXACT_NAME_OR_REFERENCE,
    RESTRICT_DECLARATION_KINDS,
    SET_EXPANSION_DOMAIN,
    TRACE_HAS_MULTIPLE_RELATION_PHASES,
    EXHAUSTIVE_WALK_HAS_NO_DEPTH_LIMIT,
    LATE_FILTER_DOES_NOT_NARROW_DISCOVERY,
}

/** Request-only advice uses the same schema admission as a call and consumes no host capabilities. */
internal fun planToolQuery(bytes: ByteArray): QueryCostPlanReply {
    if (bytes.size > MAXIMUM_PROTOCOL_TEXT_LENGTH)
        return QueryCostPlanReply.Rejected(QueryCostPlanFailure.REQUEST_TOO_LARGE)
    val input =
        when (val decoded = decodeToolRpcInput(bytes)) {
            is Refinement.Refined -> decoded.value
            is Refinement.Rejected -> return QueryCostPlanReply.Rejected(QueryCostPlanFailure.INVALID_ARGUMENTS)
        }
    val arguments =
        try {
            Json.decodeFromString<JsonObject>(input)
        } catch (_: SerializationException) {
            return QueryCostPlanReply.Rejected(QueryCostPlanFailure.INVALID_ARGUMENTS)
        }
    val canonical =
        when (val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, arguments)) {
            is Refinement.Refined -> admitted.value.canonical
            is Refinement.Rejected -> return QueryCostPlanReply.Rejected(QueryCostPlanFailure.INVALID_ARGUMENTS)
        }
    val request =
        when (canonical) {
            is PublicToolCanonical.Query -> canonical.request
            is PublicToolCanonical.Source,
            is PublicToolCanonical.Diagnostics,
            is PublicToolCanonical.Change -> return QueryCostPlanReply.Rejected(QueryCostPlanFailure.RUN_REQUIRED)
        }
    return when (request) {
        is QueryRunRequest.Run -> QueryCostPlanReply.Planned(request, request.costAdvice())
        is QueryRunRequest.Resume,
        is QueryRunRequest.ReadResult -> QueryCostPlanReply.Rejected(QueryCostPlanFailure.RUN_REQUIRED)
    }
}

internal fun QueryRunRequest.Run.costAdvice(): List<QueryCostAdvice> =
    (from.costAdvice() + steps.values.flatMap { it.costAdvice(from) }).distinct()

private fun QueryFromDocument.costAdvice(): List<QueryCostAdvice> = buildList {
    when (val source = this@costAdvice) {
        is QueryFromDocument.Symbols -> {
            discoveryAdvice(source.scope, source.declarationKinds.values.size)
            when (val match = source.match) {
                QueryMatchDocument.All -> add(QueryCostAdvice.USE_EXACT_NAME_OR_REFERENCE)
                is QueryMatchDocument.Name ->
                    if (match.matching == SymbolDiscoveryMatchDocument.FUZZY)
                        add(QueryCostAdvice.USE_EXACT_NAME_OR_REFERENCE)
                is QueryMatchDocument.TextWord -> Unit
            }
        }
        is QueryFromDocument.TextWord -> discoveryAdvice(source.scope, source.declarationKinds.values.size)
        is QueryFromDocument.Impact,
        is QueryFromDocument.Location,
        is QueryFromDocument.References,
        is QueryFromDocument.Result -> Unit
    }
}

private fun QueryStepDocument.costAdvice(source: QueryFromDocument): List<QueryCostAdvice> = buildList {
    when (val step = this@costAdvice) {
        is QueryStepDocument.Trace -> {
            expansionAdvice(step.expansionScope)
            add(QueryCostAdvice.TRACE_HAS_MULTIPLE_RELATION_PHASES)
        }
        is QueryStepDocument.Related -> expansionAdvice(step.expansionScope)
        is QueryStepDocument.Walk -> {
            expansionAdvice(step.expansionScope)
            when (step.extent) {
                TraversalExtentDocument.Exhaustive -> add(QueryCostAdvice.EXHAUSTIVE_WALK_HAS_NO_DEPTH_LIMIT)
                is TraversalExtentDocument.ThroughDepth -> Unit
            }
        }
        is QueryStepDocument.Where ->
            when (source) {
                is QueryFromDocument.Symbols,
                is QueryFromDocument.TextWord -> add(QueryCostAdvice.LATE_FILTER_DOES_NOT_NARROW_DISCOVERY)
                is QueryFromDocument.Impact,
                is QueryFromDocument.Location,
                is QueryFromDocument.References,
                is QueryFromDocument.Result -> Unit
            }
        is QueryStepDocument.Concat,
        is QueryStepDocument.Difference,
        QueryStepDocument.Distinct,
        is QueryStepDocument.Intersect,
        is QueryStepDocument.Join,
        is QueryStepDocument.ProjectBinding,
        is QueryStepDocument.Union -> Unit
    }
}

private fun MutableList<QueryCostAdvice>.discoveryAdvice(scope: QueryScopeDocument, declarationKinds: Int) {
    val directory = scope.directory
    val unrestrictedDirectory =
        directory == null ||
            directory.path.value == "." && directory.containment == QueryContainmentDocument.DESCENDANTS
    if (scope.packageName == null && unrestrictedDirectory) add(QueryCostAdvice.NARROW_DISCOVERY_SCOPE)
    if (declarationKinds > 1) add(QueryCostAdvice.RESTRICT_DECLARATION_KINDS)
}

private fun MutableList<QueryCostAdvice>.expansionAdvice(scope: QueryExpansionScopeDocument) {
    when (scope) {
        QueryExpansionScopeDocument.Workspace -> add(QueryCostAdvice.SET_EXPANSION_DOMAIN)
        QueryExpansionScopeDocument.RetainedSeed,
        is QueryExpansionScopeDocument.Sources -> Unit
    }
}
