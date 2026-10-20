package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueCallStack
import io.github.amichne.kast.relation.contract.ValueCallbackTarget
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy

/** The original seed has no caller context. Every admitted callback must name an actual source supplier. */
class NativePacketS3SeedCallbackTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-s3-seed-callback-jdk"

    fun testOriginalEmptyStackLogArgumentEntersItsActualSuppliedImplicitFormal() {
        val source = source()
        val argument = source.argument()
        val facts = NativePacketS3SeedCallbackFacts(project, getOrCreateProjectBaseDir()).read(argument)
        val step = source.read(argument)
        val entries = step.transfers.filter { it.kind == ValueTransferKind.SOURCE_CALL_ENTRY }
        assertEquals(
            "Original empty-stack log793:799 needs its actual supplier925:943. " +
                "Causes=${step.obligations.map { it.cause }}; callback return/logger payload=NOT_REACHED",
            1,
            entries.size,
        )
        val edge = entries.single()
        assertEquals(argument, edge.source)
        assertEquals(ValueCallStack.Empty, edge.source.callStack)
        val target = edge.target
        assertEquals(ValueRole.Formal(ValueFormalPosition.Parameter(facts.formal)), target.role)
        assertEquals(facts.formal.range, target.range)
        assertEquals(facts.formal.executionOwner, target.executionOwner)
        assertEquals(facts.formal.executionOwner.enclosing, target.enclosing)
        assertEquals(argument.valuePath, target.valuePath)
        assertEquals(argument.predicates, target.predicates)
        assertEquals(CALLBACK_STACK_DEPTH, target.callStack.frames.size)
        val frame = target.callStack.frames.last()
        assertEquals((argument.role as ValueRole.FunctionArgument).call, frame.invocation)
        assertEquals(target.executionOwner, frame.callee)
        val callback = frame.target as ValueCallbackTarget
        assertEquals(listOf(callback.supplyingFrame, frame), target.callStack.frames)
        assertEquals(argument.executionOwner, callback.supplyingFrame.callee)
        assertEquals(facts.caller.valueIdentity, callback.supplyingFrame.invocation.enclosing.valueIdentity)
        assertEquals(facts.callerRange, callback.supplyingFrame.invocation.range)
        val binding = (callback.flow.binding as CallbackBindingEvidence.Bound).binding
        assertEquals(callback.supplyingFrame.invocation, binding.invocation)
        assertEquals(facts.parameter, binding.parameter)
        assertEquals(facts.selected, callback.selected.occurrence)
        assertTrue(callback.selected in callback.flow.invocations)
        assertTrue(step.applications.isEmpty())
    }

    fun testOriginalLogArgumentNeverSelectsTheDifferentActualTransformInvocation() {
        val source = source()
        val argument = source.argument()
        val facts = NativePacketS3SeedCallbackFacts(project, getOrCreateProjectBaseDir()).read(argument)
        assertFalse(facts.selected == facts.wrongInvocation)
        val step = source.read(argument)
        assertTrue(
            step.transfers.none { transfer ->
                transfer.target.callStack.frames.any { frame ->
                    (frame.target as? ValueCallbackTarget)?.selected?.occurrence == facts.wrongInvocation
                }
            }
        )
    }

    fun testOriginalLogArgumentNeverEntersTheDifferentActualMapSupplierBody() {
        val source = source()
        val argument = source.argument()
        val facts = NativePacketS3SeedCallbackFacts(project, getOrCreateProjectBaseDir()).read(argument)
        assertFalse(facts.formal.executionOwner == facts.wrongSupplier)
        val step = source.read(argument)
        assertTrue(step.transfers.none { transfer -> transfer.target.executionOwner == facts.wrongSupplier })
    }

    fun testSourceScopeExclusionCannotInventTheKnownClientSupplier() {
        val source = source()
        val argument = source.argument()
        val facts = NativePacketS3SeedCallbackFacts(project, getOrCreateProjectBaseDir()).read(argument)
        val file = argument.enclosing.file as SymbolDiscoveryFileIdentity.Workspace
        val onlyWire =
            RelationSearchBoundary.Explicit(
                SymbolSearchScope.ExactFile(
                    file.path,
                    SymbolSourceKindPolicy.PRODUCTION_ONLY,
                    SymbolGeneratedSourcePolicy.EXCLUDE,
                )
            )
        val step = source.read(argument, onlyWire)
        assertTrue(step.transfers.none { transfer -> transfer.target.executionOwner == facts.formal.executionOwner })
        assertTrue(step.applications.isEmpty())
        println("S3_SEED_CALLBACK_SCOPE known_supplier=EXCLUDED claim=NO_EMITTED_WITNESS completion=NOT_ASSERTED")
    }

    private fun source(): NativePacketS3SeedCallbackSource {
        val fixture = packetFixture(NativePacketCompositeSuccessSource.PATHS)
        fixture.assertNoFixtureErrors()
        return NativePacketS3SeedCallbackSource(project, getOrCreateProjectBaseDir(), fixture)
    }

    private companion object {
        const val CALLBACK_STACK_DEPTH = 2
    }
}
