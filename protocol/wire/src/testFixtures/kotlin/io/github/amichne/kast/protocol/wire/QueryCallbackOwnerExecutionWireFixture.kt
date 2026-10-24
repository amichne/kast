package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackInvocationScanDocument
import kotlinx.serialization.Serializable

class QueryCallbackOwnerExecutionWireFixture {
    private val fixture = QueryCallbackWireFixture()

    fun completeObservationExample(): String =
        wireJson.encodeToString(QueryCallbackObservationWireDocument.serializer(), composed())

    fun immutableObservationExample(): String =
        wireJson.encodeToString(QueryCallbackObservationWireDocument.serializer(), immutable())

    fun nestedImmutableObservationExample(): String =
        wireJson.encodeToString(QueryCallbackObservationWireDocument.serializer(), nestedImmutable())

    fun missingOwnerExecutionExample(): String {
        val owner = (composed().flow as QueryCallbackFlowWireDocument.Observed).ownerBindings.single()
        return wireJson.encodeToString(
            MissingCallbackOwnerExecutionDocument.serializer(),
            MissingCallbackOwnerExecutionDocument(
                body = owner.body,
                supply = owner.supply,
                binding = owner.binding,
                obligations = owner.obligations,
            ),
        )
    }

    internal fun nestedImmutable(): QueryCallbackObservationWireDocument {
        val wire = immutable()
        val flow = wire.flow as QueryCallbackFlowWireDocument.Observed
        val stored = flow.ownerBindings.single()
        val execution = stored.execution as QueryCallbackOwnerExecutionWireDocument.Immutable
        val direct = execution.flow.uses.single() as QueryImmutableCallbackUseWireDocument.Direct
        val composedFlow = composed().flow as QueryCallbackFlowWireDocument.Observed
        val inner = composedFlow.ownerBindings.single().execution as QueryCallbackOwnerExecutionWireDocument.Observed
        val outer = inner.flow.ownerBindings.single()
        return wire.copy(
            flow =
                flow.copy(
                    ownerBindings =
                        listOf(
                            stored.copy(
                                execution =
                                    execution.copy(
                                        flow =
                                            execution.flow.copy(
                                                uses =
                                                    listOf(
                                                        direct.copy(
                                                            binding =
                                                                (direct.binding
                                                                        as QueryCallbackBindingWireDocument.Direct)
                                                                    .copy(owner = outer.body)
                                                        )
                                                    ),
                                                ownerBindings = listOf(outer),
                                            )
                                    )
                            )
                        )
                )
        )
    }

    internal fun immutable(): QueryCallbackObservationWireDocument {
        val wire = composed()
        val flow = wire.flow as QueryCallbackFlowWireDocument.Observed
        val inner = flow.ownerBindings.single()
        val bound = flow.binding as QueryCallbackBindingWireDocument.Bound
        val owner = QueryCallbackBodyWireDocument.Named(bound.callable)
        val body = fixture.occurrence("Boundary.kt", 10, 25)
        val source =
            ImpactValueSiteReferenceDocument(
                bound.invocation.callable,
                ImpactSourceRangeDocument(body.range.startInclusive, body.range.endExclusive),
                ImpactValueRoleDocument.ExpressionResult,
            )
        val value =
            QueryImmutableCallbackValueWireDocument(
                QueryImmutableCallbackValueOriginWireDocument.Anonymous(inner.body),
                source,
                source,
                emptyList(),
                emptyList(),
                emptyList(),
            )
        val immutable =
            QueryImmutableCallbackFlowWireDocument(
                value,
                listOf(
                    QueryImmutableCallbackUseWireDocument.Direct(
                        value,
                        QueryCallbackBindingWireDocument.Direct(flow.basis, occurrence(9, 26), owner),
                    )
                ),
                emptyList(),
                QueryCallbackInvocationScanDocument.EXHAUSTIVE,
            )
        val stored =
            inner.copy(
                supply = QueryCallbackBodySupplyWireDocument.Stored,
                binding = QueryCallbackBindingWireDocument.Unavailable(QueryCallbackFlowCauseDocument.STORED_CALLBACK),
                execution = QueryCallbackOwnerExecutionWireDocument.Immutable(immutable, owner),
            )
        return wire.copy(flow = flow.copy(ownerBindings = listOf(stored)))
    }

    internal fun composed(): QueryCallbackObservationWireDocument {
        val wire = fixture.callbackDocument().toWireDocument()
        val flow = wire.flow as QueryCallbackFlowWireDocument.Observed
        val named = flow.invocations.single().owner
        val outer = directOwner(flow, anonymous(9, 26), occurrence(8, 27), named, emptyList())
        val inner = directOwner(flow, anonymous(10, 25), occurrence(9, 26), outer.body, listOf(outer))
        return wire.copy(
            flow =
                flow.copy(
                    invocations = listOf(flow.invocations.single().copy(owner = inner.body)),
                    ownerBindings = listOf(inner),
                    scan = QueryCallbackInvocationScanDocument.EXHAUSTIVE,
                )
        )
    }

    private fun directOwner(
        template: QueryCallbackFlowWireDocument.Observed,
        body: QueryCallbackBodyWireDocument.Anonymous,
        call: RelationOccurrenceWireDocument,
        owner: QueryCallbackBodyWireDocument,
        owners: List<QueryCallbackBodyBindingWireDocument>,
    ): QueryCallbackBodyBindingWireDocument {
        val binding = QueryCallbackBindingWireDocument.Direct(template.basis, call, owner)
        val flow =
            template.copy(
                body = body,
                binding = binding,
                invocations = emptyList(),
                ownerBindings = owners,
                scan = QueryCallbackInvocationScanDocument.NOT_APPLICABLE,
            )
        return QueryCallbackBodyBindingWireDocument(
            body,
            QueryCallbackBodySupplyWireDocument.DirectInvocation(call),
            binding,
            emptyList(),
            QueryCallbackOwnerExecutionWireDocument.Observed(flow),
        )
    }

    internal fun anonymous(start: Int, end: Int) =
        QueryCallbackBodyWireDocument.Anonymous(
            occurrence(start, end),
            fixture.signature("anonymous@Boundary.kt#$start:$end", emptyList()).toWireDocument(),
        )

    private fun occurrence(start: Int, end: Int) = fixture.occurrence("Boundary.kt", start, end).callbackWire()
}

/** Deliberately malformed owner proof omits only the required execution field. */
@Serializable
private data class MissingCallbackOwnerExecutionDocument(
    val body: QueryCallbackBodyWireDocument.Anonymous,
    val supply: QueryCallbackBodySupplyWireDocument,
    val binding: QueryCallbackBindingWireDocument,
    val obligations: List<QueryCallbackFlowCauseDocument>,
)
