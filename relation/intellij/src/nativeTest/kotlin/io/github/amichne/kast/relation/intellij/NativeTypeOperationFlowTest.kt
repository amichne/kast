package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import kotlinx.coroutines.runBlocking

/** Type operations preserve payload identity only on a compiler-proven normal cast path. */
class NativeTypeOperationFlowTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "type-operation-flow-jdk"

    fun testSuccessfulCastPreservesExactPayloadUse() = assertPreserved("upcast")

    fun testSuccessfulSafeCastPreservesExactPayloadUse() = assertPreserved("safeUpcast")

    fun testDisjointUnsafeCastHasNoNormalPayloadSuccessor() =
        assertStopped("failedCast", ValueFlowUnsupportedCause.ABRUPT_COMPLETION)

    fun testDisjointSafeCastNullIsNotThePayload() = assertStopped("failedSafeCast")

    fun testUnknownDowncastKeepsItsUnprovedNormalEdge() =
        assertStopped("unknownCast", ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)

    fun testTypeTestBooleanIsNotThePayload() = assertStopped("typeTest")

    fun testNegatedTypeTestBooleanIsNotThePayload() = assertStopped("negatedTypeTest")

    fun testWholeFailedCastCannotInventANormalResult() =
        assertStopped("wholeFailedCast", ValueFlowUnsupportedCause.ABRUPT_COMPLETION, "cast")

    fun testSelectedSafeCastResultKeepsItsOwnNullValue() = assertPreserved("wholeFailedSafeCast", "cast")

    fun testParenthesizedWholeFailedCastCannotInventANormalResult() =
        assertStopped("parenthesizedWholeFailedCast", ValueFlowUnsupportedCause.ABRUPT_COMPLETION, "cast")

    fun testNestedWholeFailedCastCannotInventANormalResult() =
        assertStopped("nestedWholeFailedCast", ValueFlowUnsupportedCause.ABRUPT_COMPLETION, "cast")

    fun testNothingOperandCannotInventANormalResult() =
        assertStopped("nothingOperand", ValueFlowUnsupportedCause.ABRUPT_COMPLETION, "cast")

    fun testNullableNothingRemainsTheNullValue() = assertPreserved("nullableNothingOperand", "cast")

    fun testUnsafeGenericInnerCastKeepsItsErasedTypeObligation() =
        assertStopped("unsafeGenericInnerCast", ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)

    fun testSafeGenericInnerCastKeepsItsErasedTypeObligation() =
        assertStopped("safeGenericInnerCast", ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)

    private fun assertPreserved(name: String, marker: String = "input") {
        val fixture = packetFixture(listOf(CONTROLS))
        val source = source(fixture, name, marker)
        val arrival = step(fixture, source)
        assertTrue("Unexpected cast obligations: ${arrival.obligations}", arrival.obligations.isEmpty())
        assertEquals(listOf(ValueTransferKind.LOCAL_BINDING), arrival.transfers.map { it.kind })
        val binding = arrival.transfers.single().target
        assertEquals(source.valuePath, binding.valuePath)
        assertEquals(source.callStack, binding.callStack)
        assertEquals(source.executionOwner, binding.executionOwner)
        val reads = step(fixture, binding)
        assertTrue(reads.obligations.isEmpty())
        assertEquals(listOf(ValueTransferKind.LOCAL_READ), reads.transfers.map { it.kind })
        val use = reads.transfers.single().target
        assertEquals(fixture.markedExpression(owner(name), "use").anchor, use.range)
        assertEquals(source.valuePath, use.valuePath)
        assertEquals(source.callStack, use.callStack)
    }

    private fun assertStopped(name: String, cause: ValueFlowUnsupportedCause? = null, marker: String = "input") {
        val fixture = packetFixture(listOf(CONTROLS))
        val read = step(fixture, source(fixture, name, marker))
        assertTrue("Type operation invented a payload transfer: ${read.transfers}", read.transfers.isEmpty())
        assertEquals(listOfNotNull(cause), read.obligations.map { it.cause })
    }

    private fun source(fixture: NativePacketValueFixture, name: String, marker: String = "input"): ValueSite {
        val input = fixture.markedExpression(owner(name), marker)
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .resolve(
                    project = project,
                    current = fixture.lease,
                    enclosing = input.enclosing,
                    anchor = input.anchor,
                    domain = RelationRequest.start(input.enclosing, RelationMeaning.References, fixture.budget),
                    model = fixture.model,
                )
        }
        assertTrue("Type operation source did not resolve: $result", result is ValueSiteResolution.Found)
        return (result as ValueSiteResolution.Found).site
    }

    private fun step(fixture: NativePacketValueFixture, source: ValueSite): ValueFlowStep {
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(source, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
        }
        assertTrue("Type operation flow did not run: $result", result is ValueFlowRead.Observed)
        return (result as ValueFlowRead.Observed).step
    }

    private fun owner(name: String) = PacketDeclaration(CONTROLS, "", name)

    private companion object {
        const val CONTROLS = "src/main/kotlin/fixture/controls/TypeOperationControls.kt"
    }
}
