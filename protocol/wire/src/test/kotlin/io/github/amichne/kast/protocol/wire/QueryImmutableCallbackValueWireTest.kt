package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactCompilerTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferKindDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument
import io.github.amichne.kast.protocol.contract.QueryImmutableCallbackValueDocument
import io.github.amichne.kast.protocol.contract.QueryImmutableCallbackValueOriginDocument
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueryImmutableCallbackValueWireTest {
    @Test
    fun `immutable value encodes origin and exact ordered alias supply with signature evidence`() {
        val value = fixture()
        val wire = value.immutableCallbackWire()
        assertEquals(WireDocumentConversion.Converted(value), wire.toContract())
        val encoded =
            wireJson.encodeToJsonElement(QueryImmutableCallbackValueWireDocument.serializer(), wire).jsonObject
        assertEquals(
            setOf("origin", "source", "destination", "transfers", "invoked_callables", "factories"),
            encoded.keys,
        )
        assertEquals(JsonPrimitive("ANONYMOUS"), encoded.getValue("origin").jsonObject.getValue("type"))
        assertEquals(
            listOf("LOCAL_BINDING", "LOCAL_READ", "ARGUMENT"),
            encoded.getValue("transfers").jsonArray.map { it.jsonObject.getValue("kind").toString().trim('"') },
        )
        assertEquals(1, encoded.getValue("invoked_callables").jsonArray.size)
        assertEquals(WireDocumentConversion.Rejected, wire.copy(transfers = wire.transfers.reversed()).toContract())
        assertEquals(WireDocumentConversion.Rejected, wire.copy(invokedCallables = emptyList()).toContract())
    }

    @Test
    fun `argument position beyond exact signature rejects even when path stays connected`() {
        val wire = fixture().immutableCallbackWire()
        val argument = wire.destination.role as ImpactValueRoleDocument.Argument
        val invalid = wire.destination.copy(role = argument.copy(index = offset(1)))
        assertEquals(
            WireDocumentConversion.Rejected,
            wire
                .copy(
                    destination = invalid,
                    transfers = wire.transfers.dropLast(1) + wire.transfers.last().copy(target = invalid),
                )
                .toContract(),
        )
    }

    internal fun fixture(): QueryImmutableCallbackValueDocument {
        val callback = QueryCallbackWireFixture().callbackDocument()
        val flow = callback.flow as QueryCallbackFlowDocument.Observed
        val bound = flow.binding as QueryCallbackBindingDocument.Bound
        val enclosing = enclosing(callback.lexicalOwner, flow.basis)
        val source =
            ImpactValueSiteReferenceDocument(
                enclosing,
                ImpactSourceRangeDocument(offset(4), offset(18)),
                ImpactValueRoleDocument.ExpressionResult,
            )
        val local =
            ImpactValueSiteReferenceDocument(
                enclosing,
                ImpactSourceRangeDocument(offset(20), offset(25)),
                ImpactValueRoleDocument.LocalBinding,
            )
        val read =
            ImpactValueSiteReferenceDocument(
                enclosing,
                ImpactSourceRangeDocument(offset(30), offset(32)),
                ImpactValueRoleDocument.LocalRead,
            )
        val argument =
            read.copy(
                role =
                    ImpactValueRoleDocument.Argument(
                        ImpactInvocationReferenceDocument(
                            ImpactSourceRangeDocument(offset(28), offset(38)),
                            bound.invocation.callable,
                        ),
                        offset(0),
                    )
            )
        val edges =
            listOf(
                ImpactCompilerTransferDocument(source, local, ImpactTransferKindDocument.LOCAL_BINDING),
                ImpactCompilerTransferDocument(local, read, ImpactTransferKindDocument.LOCAL_READ),
                ImpactCompilerTransferDocument(read, argument, ImpactTransferKindDocument.ARGUMENT),
            )
        return (QueryImmutableCallbackValueDocument.create(
                QueryImmutableCallbackValueOriginDocument.Anonymous(flow.body),
                source,
                argument,
                bounded(edges),
                bounded(listOf(bound.callable)),
            ) as Refinement.Refined)
            .value
    }

    private fun enclosing(
        owner: io.github.amichne.kast.protocol.contract.QueryCallbackCallableDocument,
        basis: io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument,
    ) =
        ImpactDeclarationReferenceDocument(
            basis,
            owner.declaration.file,
            ImpactSourceRangeDocument(owner.declaration.range.startInclusive, owner.declaration.range.endExclusive),
            owner.compilerTarget.compilerEvidence.identity,
        )

    private fun offset(value: Int) = (ProtocolOffset.parse(value) as Refinement.Refined).value

    private fun <T> bounded(values: List<T>) = (BoundedProtocolList.create(values) as Refinement.Refined).value
}
