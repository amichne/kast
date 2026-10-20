package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import kotlinx.coroutines.runBlocking

class NativePacketStorageTransferTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-storage-transfer-jdk"

    fun testV8ConstructorStoresTheDeclaredDestinationProperty() {
        val fixture = packetFixture(listOf(V8))
        val expression =
            fixture.argumentExpression(
                PacketDeclaration(V8, "BackendClient", "fetch"),
                "WireRequest",
                "request.destination",
            )
        val resolved = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .resolve(
                    project = project,
                    current = fixture.lease,
                    enclosing = expression.enclosing,
                    anchor = expression.anchor,
                    domain = RelationRequest.start(expression.enclosing, RelationMeaning.References, fixture.budget),
                    model = fixture.model,
                )
        }
        assertTrue(resolved is ValueSiteResolution.Found)
        val argument = step(fixture, (resolved as ValueSiteResolution.Found).site).transfers.single().target
        val formal = argument.role as ValueRole.Argument
        assertEquals(1, formal.position.value)
        val storageStep = step(fixture, argument)
        assertEquals(
            "V8 constructor storage is absent. Frontier=${storageStep.obligations.map { it.cause }}",
            listOf(ValueTransferKind.CONSTRUCTOR_FIELD),
            storageStep.transfers.map { it.kind },
        )
        val transfer = storageStep.transfers.single()
        val operation = (transfer.evidence as ValueSourceTransfer).operation as ValueSourceOperation.ConstructField
        assertEquals(1, operation.storage.parameter.position.value)
        assertEquals("destination", operation.storage.parameter.name.value)
        assertEquals(formal.call.range, transfer.target.range)
        assertEquals(argument.callStack, transfer.target.callStack)
        val path = transfer.target.valuePath.segments.single() as ValuePathSegment.Field
        assertEquals(
            "fixture.variants.v8.WireRequest.destination",
            (path.field.declaration.qualifiedIdentity as ExactDeclarationQualifiedIdentity.Available).value,
        )
    }

    fun testOriginalV0FormalReadKeepsItsCallFrameThroughFieldProjection() {
        val fixture = packetFixture(listOf(V0))
        val request =
            fixture.selection(
                PacketDeclaration(V0, "Feature", "handle"),
                "ClientRequest",
                PacketDeclaration(V0, "ClientRequest", "<init>"),
            )
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .seed(project = project, current = fixture.lease, request = request, model = fixture.model)
        }
        assertTrue(result is ValueProducerSeedRead.Seeded)
        var site = (result as ValueProducerSeedRead.Seeded).seed.site
        for (kind in
            listOf(
                ValueTransferKind.LOCAL_BINDING,
                ValueTransferKind.LOCAL_READ,
                ValueTransferKind.ARGUMENT,
                ValueTransferKind.SOURCE_CALL_ENTRY,
            )) {
            val transfer = step(fixture, site).transfers.single()
            assertEquals(kind, transfer.kind)
            site = transfer.target
        }
        val formal = (site.role as ValueRole.Formal).position as ValueFormalPosition.Parameter
        assertEquals("request", formal.parameter.name.value)
        assertEquals(0, formal.parameter.position.value)
        assertEquals(1, site.callStack.frames.size)
        val read = step(fixture, site).transfers.single().target
        assertEquals(ValueRole.LocalRead, read.role)
        assertEquals(site.callStack, read.callStack)
        val projected = step(fixture, read).transfers.single()
        assertEquals(ValueTransferKind.FIELD_READ, projected.kind)
        assertEquals(site.callStack, projected.target.callStack)
        val expected =
            fixture.argumentExpression(
                PacketDeclaration(V0, "BackendClient", "fetch"),
                "WireRequest",
                "request.destination",
            )
        assertEquals(expected.anchor, projected.target.range)
    }

    private fun step(fixture: NativePacketValueFixture, site: ValueSite): ValueFlowStep {
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(site, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
        }
        assertTrue("Native storage read failed: $result", result is ValueFlowRead.Observed)
        return (result as ValueFlowRead.Observed).step
    }

    private companion object {
        const val V0 = "src/main/kotlin/fixture/variants/v0/V0.kt"
        const val V8 = "src/main/kotlin/fixture/variants/v8/V8.kt"
    }
}
