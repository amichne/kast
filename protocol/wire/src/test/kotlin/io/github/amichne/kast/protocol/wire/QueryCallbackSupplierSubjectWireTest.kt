package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.QueryCallableObservationDocument
import io.github.amichne.kast.protocol.contract.QueryCallableTargetDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBodyBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBodyDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBodySupplyDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackInvocationScanDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackOwnerExecutionDocument
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

    internal fun nestedInventory(): QueryCallbackSupplierInventoryWireDocument.Exhaustive {
        val target =
            parameterObservation(nested = true).toWireDocument().target
                as QueryCallableTargetWireDocument.ParameterInvocation
        return target.suppliers as QueryCallbackSupplierInventoryWireDocument.Exhaustive
    }

    @Test
    fun `nested supplier keeps execution proof and rejects absent or unrelated proof`() {
        val wire = parameterObservation(nested = true).toWireDocument()
        val target = wire.target as QueryCallableTargetWireDocument.ParameterInvocation
        val inventory = target.suppliers as QueryCallbackSupplierInventoryWireDocument.Exhaustive
        val partition = inventory.partitions.single()
        val owner = partition.ownerBindings.single()
        val execution = (owner.execution as QueryCallbackOwnerExecutionWireDocument.Observed).flow
        val binding = owner.binding as QueryCallbackBindingWireDocument.Direct
        val foreign =
            binding.copy(
                owner =
                    QueryCallbackBodyWireDocument.Named(
                        fixture.callable("Seed.kt", 0, 40, "sample.other", emptyList()).callbackWire()
                    )
            )
        assertTrue(wire.toContract() is WireDocumentConversion.Converted)
        val encoded = wireJson.encodeToString(QueryCallbackSupplierInventoryWireDocument.serializer(), inventory)
        val decoded = wireJson.decodeFromString(QueryCallbackSupplierInventoryWireDocument.serializer(), encoded)
        assertEquals(inventory, decoded)
        assertTrue(decoded.toContract() is WireDocumentConversion.Converted)
        for (owners in
            listOf(
                emptyList(),
                listOf(owner, owner),
                listOf(owner.copy(execution = QueryCallbackOwnerExecutionWireDocument.Unproven)),
                listOf(
                    owner.copy(
                        binding = foreign,
                        execution = QueryCallbackOwnerExecutionWireDocument.Observed(execution.copy(binding = foreign)),
                    )
                ),
            )) {
            assertEquals(
                WireDocumentConversion.Rejected,
                inventory.copy(partitions = listOf(partition.copy(ownerBindings = owners))).toContract(),
            )
        }
    }

    @Test
    fun `unproven membership still binds the exhaustive supplier basis`() {
        val wire = parameterObservation().toWireDocument()
        val target = wire.target as QueryCallableTargetWireDocument.ParameterInvocation
        val unproven =
            wire.copy(
                target =
                    target.copy(
                        resultProjection =
                            QueryCallbackResultProjectionWireDocument.Unproven(
                                io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument.OUTSIDE_DOMAIN
                            )
                    )
            )
        assertTrue(unproven.toContract() is WireDocumentConversion.Converted)
        val foreign =
            io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument.Published(
                fixture.text("/workspace"),
                (io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument.parse(2L)
                        as Refinement.Refined)
                    .value,
            )
        assertEquals(WireDocumentConversion.Rejected, unproven.copy(basis = foreign).toContract())
    }

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

    private fun parameterObservation(nested: Boolean = false): QueryCallableObservationDocument {
        val callback = fixture.callbackDocument()
        val flow = callback.flow as QueryCallbackFlowDocument.Observed
        val value = QueryImmutableCallbackValueWireTest().fixture()
        val argument = value.destination.role as ImpactValueRoleDocument.Argument
        val parent =
            QueryCallbackBodyDocument.Anonymous(
                fixture.occurrence("Seed.kt", 27, 39),
                fixture.signature("anonymous@Seed.kt#27:39", emptyList()),
            )
        val binding =
            (flow.binding as QueryCallbackBindingDocument.Bound).copy(
                invocation = argument.invocation,
                invocationOccurrence = fixture.occurrence("Seed.kt", 28, 38),
                invocationOwner = if (nested) parent else QueryCallbackBodyDocument.Named(callback.lexicalOwner),
            )
        val owner = nestedOwner(flow, parent, callback.lexicalOwner)
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
                    bounded(listOf(partition(formal, supplier, if (nested) listOf(owner) else emptyList()))),
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
                flow.basis,
            )
            .value()
    }

    private fun nestedOwner(
        flow: QueryCallbackFlowDocument.Observed,
        parent: QueryCallbackBodyDocument.Anonymous,
        lexicalOwner: io.github.amichne.kast.protocol.contract.QueryCallbackCallableDocument,
    ): QueryCallbackBodyBindingDocument {
        val direct =
            QueryCallbackBindingDocument.Direct(
                flow.basis,
                fixture.occurrence("Seed.kt", 26, 40),
                QueryCallbackBodyDocument.Named(lexicalOwner),
            )
        return QueryCallbackBodyBindingDocument(
            parent,
            QueryCallbackBodySupplyDocument.DirectInvocation(direct.occurrence),
            direct,
            bounded(emptyList()),
            QueryCallbackOwnerExecutionDocument.Observed(
                QueryCallbackFlowDocument.Observed(
                    flow.basis,
                    parent,
                    direct,
                    bounded(emptyList()),
                    bounded(emptyList()),
                    bounded(emptyList()),
                    QueryCallbackInvocationScanDocument.NOT_APPLICABLE,
                )
            ),
        )
    }

    private fun partition(
        formal: QueryCallbackParameterIdentityDocument,
        supplier: QueryCallbackParameterSupplierDocument,
        owners: List<QueryCallbackBodyBindingDocument>,
    ) = QueryCallbackSupplierPartitionDocument(formal, bounded(listOf(supplier)), bounded(emptyList()), bounded(owners))

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
