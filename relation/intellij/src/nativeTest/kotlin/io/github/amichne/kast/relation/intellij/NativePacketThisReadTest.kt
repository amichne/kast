package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

/** These isolated source contracts do not establish the full S2 route, let semantics, or a NonNull predicate. */
class NativePacketThisReadTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-explicit-this-read-jdk"

    fun testOriginalRequireNotNullFormalReadsItsExactWholeThisValue() {
        val source = source()
        val formal = source.requiredFormal()
        assertEquals(ValueAccessPath.Whole, formal.valuePath)
        assertLocalRead(source = source, formal = formal, reference = exact(REQUIRE_THIS_START, REQUIRE_THIS_END))
    }

    fun testOriginalBindExtensionFormalReadsItsExactThisValue() {
        val source = source()
        val formal = source.bindFormal(ValueReceiverKind.EXTENSION)
        assertLocalRead(source = source, formal = formal, reference = exact(BIND_THIS_START, BIND_THIS_END))
    }

    fun testOriginalBindDispatchFormalDoesNotReceiveTheExtensionThisOccurrence() {
        val source = source()
        val formal = source.bindFormal(ValueReceiverKind.DISPATCH)
        assertEquals(ValueRole.Formal(ValueFormalPosition.Receiver(ValueReceiverKind.DISPATCH)), formal.role)
        val result = source.read(formal)
        assertTrue(
            "A real dispatch receiver must not flow to the separately proved extension this1189:1193",
            result.transfers.none { transfer ->
                transfer.kind == ValueTransferKind.LOCAL_READ &&
                    transfer.target.range == exact(BIND_THIS_START, BIND_THIS_END)
            },
        )
        println("THIS_READ_RESULT case=DISPATCH_EXTENSION_THIS_FORBIDDEN exhaustion=NOT_ASSERTED")
    }

    private fun assertLocalRead(
        source: NativePacketThisReadSource,
        formal: ValueSite,
        reference: ExactDeclarationTextRange,
    ) {
        val result = source.read(formal)
        val selected =
            result.transfers.filter { transfer ->
                transfer.kind == ValueTransferKind.LOCAL_READ && transfer.target.range == reference
            }
        assertEquals(
            "The exact source receiver needs LOCAL_READ $reference. Causes=${result.obligations.map { it.cause }}. " +
                "let, NonNull, and full C2.8/C2.9 remain NOT_REACHED",
            1,
            selected.size,
        )
        val transfer = selected.single()
        assertEquals(formal, transfer.source)
        assertEquals(ValueRole.LocalRead, transfer.target.role)
        assertEquals(formal.enclosing, transfer.target.enclosing)
        assertEquals(formal.executionOwner, transfer.target.executionOwner)
        assertEquals(formal.valuePath, transfer.target.valuePath)
        assertEquals(formal.callStack, transfer.target.callStack)
        assertEquals(formal.predicates, transfer.target.predicates)
        println("THIS_READ_RESULT case=EXACT_SOURCE_THIS state=UNCHANGED full_S2=NOT_ASSERTED")
    }

    private fun source(): NativePacketThisReadSource {
        val fixture = packetFixture(NativePacketCompositeSuccessSource.PATHS)
        return NativePacketThisReadSource(project, getOrCreateProjectBaseDir(), fixture)
    }

    private fun exact(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).nativeRefined()

    private companion object {
        const val REQUIRE_THIS_START = 1622
        const val REQUIRE_THIS_END = 1626
        const val BIND_THIS_START = 1189
        const val BIND_THIS_END = 1193
    }
}
