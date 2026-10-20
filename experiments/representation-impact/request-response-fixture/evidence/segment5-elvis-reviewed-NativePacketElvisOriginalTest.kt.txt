package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValuePredicate
import io.github.amichne.kast.relation.contract.ValuePredicates
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueTransferEvidence
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

/** Original source positions preserve the genuine factory payload and all prior scoped predicates. */
class NativePacketElvisOriginalTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-elvis-original-jdk"

    fun testOriginalLetIsTheExactImmediateK2LeftOperandOfTheSourceElvis() {
        source().capture()
    }

    fun testOriginalLetPayloadReachesOnlyTheWholeElvisWithItsExactLeftNonNullSubject() {
        val source = source()
        source.capture()
        val actual = source.payloadResult()
        val read = source.read(actual)
        val branches = read.transfers.filter { it.kind == ValueTransferKind.BRANCH_ALTERNATIVE }
        assertEquals(
            "Original Let1622:1655 needs whole Elvis1622:1681 with its exact left guard; " +
                "causes=${read.obligations.map { it.cause }}. C2.8/C2.9 remain NOT_REACHED",
            1,
            branches.size,
        )
        val edge = branches.single()
        assertEquals(actual, edge.source)
        val result = edge.target
        assertEquals(exact(LET_START, ELVIS_END), result.range)
        assertEquals(ValueRole.ExpressionResult, result.role)
        assertEquals(actual.enclosing, result.enclosing)
        assertEquals(actual.executionOwner, result.executionOwner)
        assertEquals(actual.callStack, result.callStack)
        assertEquals(actual.valuePath, result.valuePath)
        assertTrue(result.predicates.entries.containsAll(actual.predicates.entries))
        val added = result.predicates.entries - actual.predicates.entries.toSet()
        assertEquals(1, added.size)
        val nonNull = added.single() as ValuePredicate.NonNull
        val expected =
            actual.withState(valuePath = ValueAccessPath.Whole, predicates = ValuePredicates.Empty).nativeRefined()
        assertEquals(expected, nonNull.subject.occurrence)
        assertTrue(edge.evidence is ValueTransferEvidence.Guarded)
        assertTrue(
            "Left payload must not feed the fallback expression",
            read.transfers.none { transfer ->
                transfer.target.range == exact(FALLBACK_START, ELVIS_END)
            },
        )
        assertTrue("Elvis transport is native expression flow, not a library model", read.applications.isEmpty())
    }

    private fun source(): NativePacketElvisOriginalSource {
        val fixture = packetFixture(NativePacketCompositeSuccessSource.PATHS)
        return NativePacketElvisOriginalSource(project, getOrCreateProjectBaseDir(), fixture)
    }

    private fun exact(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).nativeRefined()

    private companion object {
        const val LET_START = 1622
        const val FALLBACK_START = 1659
        const val ELVIS_END = 1681
    }
}
