package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.SourceLessCallableOrigin
import io.github.amichne.kast.relation.contract.ValueProducerOrigin
import io.github.amichne.kast.relation.contract.ValueProducerSeed
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRejection
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest
import io.github.amichne.kast.relation.contract.ValueRole
import kotlinx.coroutines.runBlocking

/** Supplemental suppliers discriminate source-property origins without replacing the original adapt seed. */
class NativePacketSourceFunctionOriginControlTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-source-function-origin-controls-jdk"

    fun testTwoActualCallsKeepTheirOwnInvocationAndReferenceToTheSameProperty() {
        val fixture = fixture()
        val source = source(fixture)
        val first = source.call(method = "twoCalls", supplier = PRIMARY, index = 0)
        val second = source.call(method = "twoCalls", supplier = PRIMARY, index = 1)
        assertFalse(first.request.anchor == second.request.anchor)
        assertFalse(first.reference == second.reference)
        val firstSeed = admitted(fixture, first)
        val secondSeed = admitted(fixture, second)
        val firstOrigin = firstSeed.origin as ValueProducerOrigin.SourceFunction
        val secondOrigin = secondSeed.origin as ValueProducerOrigin.SourceFunction
        assertEquals(firstOrigin.property.endpoint.valueIdentity, secondOrigin.property.endpoint.valueIdentity)
        assertFalse(firstOrigin.invocation.identity == secondOrigin.invocation.identity)
        assertFalse(firstOrigin.reference == secondOrigin.reference)
    }

    fun testTheSameTypedSiblingKeepsItsOwnAdmittedPropertyOrigin() {
        val fixture = fixture()
        val actual = source(fixture).call(method = "siblingCall", supplier = SIBLING)
        admitted(fixture, actual)
    }

    fun testTheSameTypedWrongExpectedPropertyRejectsTheActualPrimaryCall() {
        val fixture = fixture()
        val source = source(fixture)
        val actual = source.call(method = "twoCalls", supplier = PRIMARY)
        val wrong =
            ValueProducerSeedRequest.create(
                    enclosing = actual.request.enclosing,
                    anchor = actual.request.anchor,
                    expectedCallable = source.sibling(),
                    budget = actual.request.budget,
                    boundary = actual.request.boundary,
                )
                .nativeRefined()
        assertEquals(ValueProducerSeedRead.Rejected(ValueProducerSeedRejection.CALLABLE_MISMATCH), seed(fixture, wrong))
    }

    fun testTheActuallyReassignedMutableSupplierCannotAcquireAnImmutableSourceOrigin() {
        val fixture = fixture()
        val actual = source(fixture).call(method = "replaceAndCall", supplier = MUTABLE)
        assertEquals(
            ValueProducerSeedRead.Rejected(ValueProducerSeedRejection.UNSUPPORTED_INVOCATION),
            seed(fixture, actual.request),
        )
    }

    private fun admitted(fixture: NativePacketValueFixture, expected: SourceFunctionControlCall): ValueProducerSeed {
        val read = seed(fixture, expected.request)
        assertTrue(
            "The K2-proved immutable supplier needs a source-function seed: ${status(read)}",
            read is ValueProducerSeedRead.Seeded,
        )
        val seed = (read as ValueProducerSeedRead.Seeded).seed
        assertTrue(seed.origin is ValueProducerOrigin.SourceFunction)
        val origin = seed.origin as ValueProducerOrigin.SourceFunction
        val property = origin.property.endpoint
        assertEquals(expected.request.expectedCallable.compilerIdentity, property.compilerIdentity)
        assertEquals(expected.request.expectedCallable.file, property.file)
        assertEquals(expected.request.expectedCallable.range, property.range)
        assertEquals(expected.request.expectedCallable.signature, property.signature)
        assertEquals(expected.request.enclosing.lease.identity, property.lease.identity)
        assertEquals(expected.request.enclosing.file, origin.reference.file)
        assertEquals(expected.reference, origin.reference.range)
        assertEquals(expected.request.anchor, origin.invocation.range)
        assertEquals(expected.signature, origin.invocation.signature)
        assertEquals(SourceLessCallableOrigin.LIBRARY, origin.invocation.callable.origin)
        assertEquals(expected.request.enclosing.compilerIdentity, origin.invocation.enclosing.compilerIdentity)
        assertEquals(origin.invocation, seed.invocation)
        assertEquals(origin.invocation.range, seed.site.range)
        assertEquals(origin.invocation.executionOwner, seed.site.executionOwner)
        assertEquals(ValueRole.ExpressionResult, seed.site.role)
        return seed
    }

    private fun seed(fixture: NativePacketValueFixture, request: ValueProducerSeedRequest): ValueProducerSeedRead =
        runBlocking {
            IntellijValueFlowCompilerAdapter()
                .seed(
                    project = project,
                    current = fixture.lease,
                    request = request,
                    model = fixture.model,
                )
        }

    private fun fixture() =
        packetFixture(NativePacketCompositeSuccessSource.PATHS + NativePacketSourceFunctionOriginControlSource.PATH)

    private fun source(fixture: NativePacketValueFixture) =
        NativePacketSourceFunctionOriginControlSource(project, getOrCreateProjectBaseDir(), fixture)

    private fun status(read: ValueProducerSeedRead) =
        when (read) {
            is ValueProducerSeedRead.Seeded -> "SEEDED"
            is ValueProducerSeedRead.Rejected -> "REJECTED:${read.cause}"
            is ValueProducerSeedRead.ContractRejected -> "CONTRACT_REJECTED:${read.cause}"
        }

    private companion object {
        const val PRIMARY = NativePacketSourceFunctionOriginControlSource.PRIMARY
        const val SIBLING = NativePacketSourceFunctionOriginControlSource.SIBLING
        const val MUTABLE = NativePacketSourceFunctionOriginControlSource.MUTABLE
    }
}
