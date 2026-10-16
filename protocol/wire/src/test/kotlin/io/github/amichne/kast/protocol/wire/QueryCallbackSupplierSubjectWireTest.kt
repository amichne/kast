package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.QueryCallableObservationDocument
import io.github.amichne.kast.protocol.contract.QueryCallableTargetDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackParameterIdentityDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackParameterSupplierDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackResultProjectionDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackResultSiteDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackSupplierInventoryDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackSupplierPartitionDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackSupplierSelectionDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRelationCoverageDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationProviderDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryCallbackSupplierSubjectWireTest {
    private val fixture = QueryCallbackWireFixture()

    @Test
    fun `supplier projection rejects another exact subject in its relation envelope`() {
        val callback = fixture.callbackDocument()
        val wire =
            QueryRelationObservationWireDocument(
                QueryReferenceWireDocument.ExactSymbol("exact:seed"),
                RelationKindDocument.CALLEES,
                RelationProviderDocument.INTELLIJ_CALLEES_V2,
                callback.requestedDomain,
                callback.effectiveDomain,
                callback.domainFingerprint,
                QueryRelationCoverageDocument.Exhausted,
                emptyList(),
                emptyList(),
                listOf(parameterObservation().toWireDocument()),
            )
        assertTrue(decode(wire) is WireDocumentConversion.Converted)
        val callable = wire.callableObservations.single()
        val target = callable.target as QueryCallableTargetWireDocument.ParameterInvocation
        val projection = target.resultProjection as QueryCallbackResultProjectionWireDocument.Admitted
        val foreign =
            callable.copy(
                target =
                    target.copy(
                        resultProjection =
                            projection.copy(subject = QueryReferenceWireDocument.ExactSymbol("exact:other"))
                    )
            )
        assertEquals(WireDocumentConversion.Rejected, decode(wire.copy(callableObservations = listOf(foreign))))
    }

    private fun parameterObservation(): QueryCallableObservationDocument {
        val callback = fixture.callbackDocument()
        val flow = callback.flow as QueryCallbackFlowDocument.Observed
        val value = QueryImmutableCallbackValueWireTest().fixture()
        val argument = value.destination.role as ImpactValueRoleDocument.Argument
        val binding =
            (flow.binding as QueryCallbackBindingDocument.Bound).copy(
                invocation = argument.invocation,
                invocationOccurrence = fixture.occurrence("Seed.kt", 28, 38),
            )
        val formal = QueryCallbackParameterIdentityDocument(binding.callable, binding.position, binding.parameter)
        val supplier =
            QueryCallbackParameterSupplierDocument.create(
                    binding,
                    QueryCallbackSupplierSelectionDocument.Explicit(value.destination),
                    value,
                )
                .value()
        val inventory =
            QueryCallbackSupplierInventoryDocument.Exhaustive.create(
                    formal,
                    flow.basis,
                    callback.domainFingerprint,
                    bounded(
                        listOf(
                            QueryCallbackSupplierPartitionDocument(
                                formal,
                                bounded(listOf(supplier)),
                                bounded(emptyList()),
                            )
                        )
                    ),
                )
                .value()
        val invocation = flow.invocations.values.single()
        return QueryCallableObservationDocument.create(
                invocation.occurrence,
                binding.callable,
                invocation.owner,
                QueryCallableTargetDocument.ParameterInvocation(
                    formal,
                    inventory,
                    invocation,
                    projection(flow, callback.domainFingerprint),
                ),
            )
            .value()
    }

    private fun projection(
        flow: QueryCallbackFlowDocument.Observed,
        domain: io.github.amichne.kast.protocol.contract.QueryRelationDomainFingerprint,
    ) =
        QueryCallbackResultProjectionDocument.Admitted(
            QueryReferenceDocument.ExactSymbol(fixture.text("exact:seed")),
            flow.basis,
            domain,
            RelationKindDocument.CALLEES,
            QueryCallbackResultSiteDocument.AnonymousTarget(flow.body),
        )

    private fun decode(
        wire: QueryRelationObservationWireDocument
    ): WireDocumentConversion<io.github.amichne.kast.protocol.contract.QueryRelationObservationDocument> {
        val raw = wireJson.encodeToString(QueryRelationObservationWireDocument.serializer(), wire)
        return wireJson.decodeFromString(QueryRelationObservationWireDocument.serializer(), raw).toContract()
    }

    private fun <V> bounded(values: List<V>) = BoundedProtocolList.create(values).value()

    private fun <V, F> Refinement<V, F>.value(): V = (this as Refinement.Refined).value
}
