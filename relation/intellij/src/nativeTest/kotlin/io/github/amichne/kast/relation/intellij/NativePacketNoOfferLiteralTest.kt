package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueExecutionBody
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

/** These isolated constant-source cases do not establish empty-list selection or the complete error return. */
class NativePacketNoOfferLiteralTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-no-offer-literal-jdk"

    fun testOriginalNoOfferLiteralHasItsActualZeroArgumentCallbackAndReasonParameter() {
        val facts = source().facts()
        val body = (facts.owner.body as ValueExecutionBody.Anonymous).callable
        assertEquals(exact(CALLBACK_START, CALLBACK_END), body.range)
        assertTrue(body.signature.valueParameters.isEmpty())
        assertEquals(exact(LITERAL_START, LITERAL_END), facts.expression.anchor)
    }

    fun testOriginalNoOfferLiteralContributesToItsExactFeatureErrorReasonField() {
        val source = source()
        val facts = source.facts()
        val literal = source.resolve(facts)
        assertEquals(ValueAccessPath.Whole, literal.valuePath)
        val first = source.read(literal)
        val selected = first.transfers.filter { it.kind == ValueTransferKind.ARGUMENT }
        assertEquals(
            "The literal needs its real constructor argument. Causes=${first.obligations.map { it.cause }}",
            1,
            selected.size,
        )
        val argument = selected.single().target
        val actual = argument.role as ValueRole.Argument
        assertEquals(facts.constructor.compilerIdentity, actual.call.callable.compilerIdentity)
        assertEquals(exact(CONSTRUCTOR_START, CONSTRUCTOR_END), actual.call.range)
        assertEquals(0, actual.position.value)
        val stored = source.read(argument)
        val transfers = stored.transfers.filter { it.kind == ValueTransferKind.CONSTRUCTOR_FIELD }
        assertEquals(
            "The literal needs actual reason storage. Causes=${stored.obligations.map { it.cause }}",
            1,
            transfers.size,
        )
        val transfer = transfers.single()
        val storage = (transfer.evidence as ValueSourceTransfer).operation as ValueSourceOperation.ConstructField
        assertEquals(facts.reason, storage.storage.field)
        assertEquals("reason", storage.storage.parameter.name.value)
        assertEquals(0, storage.storage.parameter.position.value)
        assertEquals(facts.reason.declaration.range, storage.storage.parameter.range)
        assertEquals(listOf(ValuePathSegment.Field(facts.reason)), transfer.target.valuePath.segments)
        assertEquals(literal.executionOwner, transfer.target.executionOwner)
        assertEquals(literal.predicates, transfer.target.predicates)
        assertEquals(literal.callStack, transfer.target.callStack)
        println(
            "NO_OFFER_LITERAL positive=EXACT_CONSTANT_REASON_STORAGE " +
                "full_error_return=NOT_ASSERTED empty_fact=NOT_ASSERTED"
        )
    }

    private fun source(): NativePacketNoOfferLiteralSource {
        val fixture = packetFixture(NativePacketCompositeSuccessSource.PATHS)
        return NativePacketNoOfferLiteralSource(project, getOrCreateProjectBaseDir(), fixture)
    }

    private fun exact(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).nativeRefined()

    private companion object {
        const val LITERAL_START = 1007
        const val LITERAL_END = 1017
        const val CONSTRUCTOR_START = 985
        const val CONSTRUCTOR_END = 1018
        const val CALLBACK_START = 983
        const val CALLBACK_END = 1020
    }
}
