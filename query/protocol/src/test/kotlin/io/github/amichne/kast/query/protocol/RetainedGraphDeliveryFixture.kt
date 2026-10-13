package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOccurrence
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryTerminalReason
import io.github.amichne.kast.relation.contract.RelationConfirmedReferenceTarget
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationReferenceContext
import io.github.amichne.kast.relation.contract.RelationReferenceOccurrence
import io.github.amichne.kast.relation.contract.RelationReferenceOwnership
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolSelector

/** Detached compiler facts are starting inputs. This fixture does not claim native graph authority. */
internal class RetainedGraphDeliveryFixture {
    val source = RelationPagingFixture.published()
    val store = QueryStateStore(clock = { now }, ttlMillis = 100)
    var now = 0L
    var semanticCalls = 0
    val protocol =
        CanonicalQueryProtocol(
            io.github.amichne.kast.query.contract.QueryOperations {
                semanticCalls++
                error("Retained delivery cannot invoke the semantic provider")
            },
            source.references,
            store,
        )
    val budget =
        QueryBudget(
            ResourceBudget(
                ResultLimit.parse(100).graphFixtureValue(),
                WorkUnitLimit.parse(100_000).graphFixtureValue(),
                ElapsedTimeLimitMillis.parse(15_000).graphFixtureValue(),
            ),
            QueryByteLimit.parse(20_000).graphFixtureValue(),
        )
    private val owners = (0..1).map(::owner)
    val occurrences = (0 until 53).map(::occurrence)
    val request =
        QueryRunRequest.Run(
            from =
                QueryFromDocument.References(
                    graphFixtureList(listOf(QueryReferenceDocument.ExactSymbol(source.exact)))
                ),
            steps = graphFixtureList(emptyList()),
            output = QueryOutputDocument.Occurrences,
            execution =
                QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
        )

    fun issue(qualified: Boolean = false): QueryResultIssuance.Issued {
        val result =
            QueryResult(
                QueryRows.Occurrences.of(occurrences.map(QueryOccurrence::Reference)),
                emptyList(),
                referenceObservations = occurrences,
            )
        val count = QueryCount.parse(53).graphFixtureValue()
        val execution =
            if (qualified)
                QueryExecutionResult.Qualified(
                    result,
                    QueryCoverage.Qualified.create(count, setOf(QueryLimitation.RELATION_INCOMPLETE))
                        .graphFixtureValue(),
                    QueryContinuationState.Terminal(QueryTerminalReason.UPSTREAM_INCOMPLETE),
                )
            else QueryExecutionResult.Complete.create(result, QueryCoverage.Complete(count))
        val retained = QueryRetainedResult.capture(source.authority, execution).graphFixtureValue()
        return store.issueResult(request, retained) as QueryResultIssuance.Issued
    }

    private fun owner(index: Int): RelationEndpoint.Resolved {
        val name = "owner"
        val identity = "fixture.owner$index"
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    file = source.selector.file,
                    rawStartInclusive = 0,
                    rawEndExclusive = 2_000,
                    rawName = name,
                    rawQualifiedIdentity = identity,
                    kind = CompilerSymbolKind.FUNCTION,
                    signature =
                        CanonicalCompilerSignature.function(
                                rawQualifiedIdentity = identity,
                                rawReceiverType = null,
                                rawContextReceiverTypes = emptyList(),
                                rawValueParameterTypes = List(12) { "fixture.Type$it" },
                                rawTypeParameterCount = 0,
                            )
                            .graphFixtureValue(),
                )
                .graphFixtureValue()
        val selector = SymbolSelector.issue(source.authority, source.selector.scope, evidence)
        return RelationEndpoint.resolve(source.authority, selector.scope, evidence).graphFixtureValue()
    }

    private fun occurrence(index: Int): RelationReferenceOccurrence {
        val request = RelationRequest.start(source.selector, RelationMeaning.References, source.budget)
        val target =
            RelationConfirmedReferenceTarget.fromCompiler(
                    request.subject,
                    CompilerGroundedSymbolEvidence.fromSelector(source.selector),
                )
                .graphFixtureValue()
        return RelationReferenceOccurrence.confirmed(
                request = request,
                target = target,
                occurrence =
                    RelationOccurrence.fromBoundary(source.selector.file, 100 + index * 16, 108 + index * 16)
                        .graphFixtureValue(),
                context = RelationReferenceContext.CODE,
                ownership = RelationReferenceOwnership.DeclarationOwned(owners[index % 2]),
                provenance = RelationProvenance.K2_AUTHORED_SOURCE,
            )
            .graphFixtureValue()
    }
}

internal fun <Value> graphFixtureList(values: List<Value>): BoundedProtocolList<Value> =
    BoundedProtocolList.create(values).graphFixtureValue()

internal fun <Value, Failure> Refinement<Value, Failure>.graphFixtureValue(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Graph delivery fixture rejection: $failure")
    }
