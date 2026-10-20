package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.ValuePredicate
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

/** These isolated source positions do not replace the original S2 seed or establish a let result. */
class NativePacketSafeCallReceiverTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-safe-call-receiver-jdk"

    fun testOriginalCheckedThisSuppliesTheExactLetExtensionReceiverWithItsOwnNonNullGuard() {
        val (source, checked) = source()
        val read = source.read(checked)
        val receivers = read.transfers.filter { it.kind == ValueTransferKind.RECEIVER }
        assertEquals(
            "The actual safe call needs its exact receiver. Causes=${read.obligations.map { it.cause }}. " +
                "let callback transport and C2.8/C2.9 remain NOT_ASSERTED",
            1,
            receivers.size,
        )
        val receiver = receivers.single().target
        val role = receiver.role as ValueRole.Receiver
        assertEquals(ValueReceiverKind.EXTENSION, role.kind)
        assertEquals(checked.range, receiver.range)
        assertEquals(exact(LET_START, LET_END), role.call.range)
        assertEquals(expectedLet(), role.call.signature)
        assertEquals(checked.enclosing, receiver.enclosing)
        assertEquals(checked.executionOwner, receiver.executionOwner)
        assertEquals(checked.valuePath, receiver.valuePath)
        assertEquals(checked.callStack, receiver.callStack)
        assertTrue(receiver.predicates.entries.containsAll(checked.predicates.entries))
        val nonNull = receiver.predicates.entries.filterIsInstance<ValuePredicate.NonNull>()
        assertEquals("The safe call establishes one checked value, without a caller-alias claim", 1, nonNull.size)
        assertCanonicalSubject(checked, nonNull.single().subject.occurrence)
        println("SAFE_CALL_RECEIVER_RESULT exact_this=GUARDED matched_let=ADMITTED callback_transport=NOT_ASSERTED")
    }

    fun testOriginalSafeCallEmitsNoNonNullGuardForAnotherValue() {
        val (source, checked) = source()
        val read = source.read(checked)
        val invented =
            read.transfers.flatMap { it.target.predicates.entries }.filterIsInstance<ValuePredicate.NonNull>()
        invented.forEach { predicate -> assertCanonicalSubject(checked, predicate.subject.occurrence) }
        println("SAFE_CALL_RECEIVER_RESULT foreign_subject_guard=FORBIDDEN exhaustion=NOT_ASSERTED")
    }

    fun testOriginalSafeCallSuppliesNoDirectReceiverToLetResultShortcut() {
        val (source, checked) = source()
        val read = source.read(checked)
        assertTrue(
            "The reviewed let rule needs the supplied callback payload chain",
            read.transfers.none { transfer ->
                transfer.target.role == ValueRole.ExpressionResult && transfer.target.range == exact(LET_START, LET_END)
            },
        )
        assertTrue(
            "A model must not replace the callback with a receiver-to-result edge",
            read.applications.none { application -> application.target.range == exact(LET_START, LET_END) },
        )
        println("SAFE_CALL_RECEIVER_RESULT receiver_result_shortcut=FORBIDDEN exhaustion=NOT_ASSERTED")
    }

    private fun source(): Pair<NativePacketThisReadSource, ValueSite> {
        val fixture = packetFixture(NativePacketCompositeSuccessSource.PATHS)
        val base = getOrCreateProjectBaseDir()
        NativePacketNullableAliasSource(project, base, fixture).capture()
        val source = NativePacketThisReadSource(project, base, fixture)
        val formal = source.requiredFormal()
        val transfers = source.read(formal).transfers
        val checked = transfers.single { transfer ->
            transfer.kind == ValueTransferKind.LOCAL_READ && transfer.target.range == exact(THIS_START, THIS_END)
        }
        assertEquals(ValueRole.LocalRead, checked.target.role)
        assertEquals(formal.valuePath, checked.target.valuePath)
        assertEquals(formal.predicates, checked.target.predicates)
        return source to checked.target
    }

    private fun expectedLet() =
        CanonicalCompilerSignature.function(
                rawQualifiedIdentity = "kotlin.let",
                rawReceiverType = "T",
                rawContextReceiverTypes = emptyList(),
                rawValueParameterTypes = listOf("(T)->R"),
                rawTypeParameterCount = LET_TYPE_PARAMETERS,
            )
            .nativeRefined()

    private fun assertCanonicalSubject(checked: ValueSite, subject: ValueSite) {
        assertEquals(checked.enclosing, subject.enclosing)
        assertEquals(checked.range, subject.range)
        assertEquals(checked.role, subject.role)
        assertEquals(checked.executionOwner, subject.executionOwner)
        assertEquals(checked.executionOwner.enclosing, subject.executionOwner.enclosing)
        assertEquals(checked.executionOwner.body, subject.executionOwner.body)
        assertEquals(checked.valuePath, subject.valuePath)
        assertEquals(checked.callStack, subject.callStack)
        assertTrue("A predicate subject omits its conjunction", subject.predicates.entries.isEmpty())
    }

    private fun exact(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).nativeRefined()

    private companion object {
        const val THIS_START = 1622
        const val THIS_END = 1626
        const val LET_START = 1622
        const val LET_END = 1655
        const val LET_TYPE_PARAMETERS = 2
    }
}
