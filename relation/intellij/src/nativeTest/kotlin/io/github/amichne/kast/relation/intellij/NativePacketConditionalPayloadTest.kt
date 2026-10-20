package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueTransferKind

/** These supplemental controls never replace the original S2 seed or its positive rows. */
class NativePacketConditionalPayloadTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-conditional-payload-jdk"

    fun testRenamedCarrierPreservesTheConditionalPayloadThroughItsSuppliedIdentityAndReplacementStorage() {
        val control = control()
        val prefix = control.route.payload(NativePacketConditionalPayloadSource.IDENTITY)
        val formal = control.route.callback(prefix, NativePacketConditionalPayloadSource.IDENTITY)
        val result = control.route.identityResult(prefix, formal)
        control.route.replacement(prefix, result)
        println("CONDITIONAL_PAYLOAD_RESULT control=renamed-identity status=ASSERTED later=NOT_RUN")
    }

    fun testFieldSpecificInputEmitsNoSameTypedRejectedPayloadWitness() {
        val control = control()
        val prefix = control.route.payload(NativePacketConditionalPayloadSource.IDENTITY)
        val fields = prefix.fields.transfers.filter { it.kind == ValueTransferKind.FIELD_READ }
        assertEquals(listOf(control.source.acceptedRead()), fields.map { it.target.range })
        assertFalse(fields.any { it.target.range == control.source.rejectedRead() })
        assertFalse(control.source.acceptedField() == control.source.rejectedField())
        println("CONDITIONAL_PAYLOAD_RESULT control=sibling-field-exclusion status=ASSERTED")
    }

    fun testConstantCallbackAdmitsTheRealUnusedImplicitFormalWithoutAnInputPayloadResultEdge() {
        val control = control()
        val prefix = control.route.payload(NativePacketConditionalPayloadSource.CONSTANT)
        val formal = control.route.callback(prefix, NativePacketConditionalPayloadSource.CONSTANT)
        val read = control.route.step(formal, "constant unused implicit formal")
        assertTrue("The constant callback acquired an input-payload edge: ${read.transfers}", read.transfers.isEmpty())
        assertTrue("The genuine unused formal must exhaust: ${read.obligations}", read.obligations.isEmpty())
        assertEquals(ValueFlowTerminal.SupportedDomainExhausted, read.terminal)
        println("CONDITIONAL_PAYLOAD_RESULT control=constant-no-input-result status=ASSERTED")
    }

    private fun control(): ConditionalPayloadControl {
        val fixture = packetFixture(listOf(NativePacketConditionalPayloadSource.PATH))
        val source = NativePacketConditionalPayloadSource(project, getOrCreateProjectBaseDir(), fixture)
        return ConditionalPayloadControl(source, NativePacketConditionalPayloadRoute(project, fixture, source))
    }
}

private data class ConditionalPayloadControl(
    val source: NativePacketConditionalPayloadSource,
    val route: NativePacketConditionalPayloadRoute,
)
