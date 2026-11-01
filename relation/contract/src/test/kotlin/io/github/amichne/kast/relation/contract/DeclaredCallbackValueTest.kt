package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeclaredCallbackValueTest {
    @Test
    fun `declared result preserves exact path stack and conditional source origin for both invocation kinds`() {
        val f = DeclaredValueFixture()
        for (kind in
            listOf(CallbackDependencyInvocationKind.EXACTLY_ONCE, CallbackDependencyInvocationKind.AT_MOST_ONCE)) {
            val binding = f.binding(contract = f.contract(kind = kind))
            val caller = flowEndpoint("outer", 0, 100)
            val call = ValueInvocation.fromCompiler(caller, flowRange(20, 60), f.owner).flowRefined()
            val frame = ValueCallFrame.direct(call, ValueExecutionOwner.declaration(f.owner)).flowRefined()
            val source =
                f.source(binding)
                    .withState(
                        valuePath = flowPath(ValuePathSegment.Element),
                        callStack = ValueCallStack.Empty.enter(frame).flowRefined(),
                    )
                    .flowRefined()
            val transfer = ValueTransfer.declaredCallbackResult(source, binding).flowRefined()
            assertSame(source, transfer.source)
            assertEquals(ValueTransferKind.SOURCE_CALL_RETURN, transfer.kind)
            assertEquals(
                ValueSourceOperation.DeclaredCallbackResult(binding),
                (transfer.evidence as ValueSourceTransfer).operation,
            )
            assertEquals(ValueRole.ExpressionResult, transfer.target.role)
            assertEquals(ValueExecutionOwner.declaration(f.owner), transfer.target.executionOwner)
            assertEquals(flowRange(20, 90), transfer.target.range)
            assertEquals(source.valuePath, transfer.target.valuePath)
            assertEquals(listOf(frame), transfer.target.callStack.frames)
            val condition = transfer.target.conditions.facts.single() as ValueCondition.DeclaredCallbackResult
            assertSame(binding, condition.binding)
            assertTrue(condition.origin.matches(source))
            assertEquals(flowRange(20, 90), condition.useRange)
            assertEquals(kind, condition.binding.contract.invocationKind)
            assertEquals("ab".repeat(32), condition.binding.contract.classDigest.value)
        }
    }

    @Test
    fun `calls in place without result effect permits a body but never result dependence`() {
        val f = DeclaredValueFixture()
        assertEquals(
            setOf("RETURNS_RESULT_OF", "UNSPECIFIED"),
            DeclaredCallbackResultEffect.entries.map { it.name }.toSet(),
        )
        for (kind in CallbackDependencyInvocationKind.entries) {
            val binding = f.binding(f.contract(kind = kind), effect = DeclaredCallbackResultEffect.UNSPECIFIED)
            val source = f.source(binding)
            assertEquals(binding.body.range, source.executionOwner.range)
            assertEquals(
                Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
                ValueTransfer.declaredCallbackResult(source, binding),
            )
        }
    }

    @Test
    fun `admission rejects foreign basis supplier and body outside selected occurrence`() {
        val f = DeclaredValueFixture()
        val foreign = DeclaredValueFixture(generation = 2)
        assertEquals(
            Refinement.Rejected(DeclaredCallbackValueFailure.BASIS_MISMATCH),
            DeclaredCallbackValueBinding.admit(
                f.owner,
                f.body,
                foreign.contract(),
                DeclaredCallbackResultEffect.RETURNS_RESULT_OF,
            ),
        )
        val other = flowEndpoint("other", 0, 100) as RelationEndpoint.Resolved
        val wrongSupplier = f.contract(owner = RelationCallableBody.Named.fromCompiler(other.evidence).flowRefined())
        for (contract in listOf(wrongSupplier, f.contract(start = 50, end = 90))) {
            assertEquals(
                Refinement.Rejected(DeclaredCallbackValueFailure.OWNER_MISMATCH),
                DeclaredCallbackValueBinding.admit(
                    f.owner,
                    f.body,
                    contract,
                    DeclaredCallbackResultEffect.RETURNS_RESULT_OF,
                ),
            )
        }
    }

    @Test
    fun `return rejects replacement body formal supplier class digest effect and epoch`() {
        val f = DeclaredValueFixture()
        val binding = f.binding()
        val source = f.source(binding)
        val changes =
            listOf(
                f.binding(f.contract(position = 1)),
                f.binding(f.contract(digest = "cd".repeat(32))),
                f.binding(f.contract(start = 19, end = 90)),
                f.binding(body = f.body(31, 80)),
                f.binding(effect = DeclaredCallbackResultEffect.UNSPECIFIED),
                DeclaredValueFixture(generation = 2).binding(),
            )
        for (changed in changes) {
            assertNotEquals(binding, changed)
            assertEquals(
                Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
                ValueTransfer.declaredCallbackResult(source, changed),
            )
        }
        val lexical = ValueSite.fromCompiler(f.owner, source.range, ValueRole.Return).flowRefined()
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
            ValueTransfer.declaredCallbackResult(lexical, binding),
        )
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
            ValueTransfer.declaredCallbackResult(f.source(binding, ValueRole.ExpressionResult), binding),
        )
    }

    @Test
    fun `declared result rejects erased proof and conserves condition on downstream edges`() {
        val f = DeclaredValueFixture()
        val binding = f.binding()
        val transfer = ValueTransfer.declaredCallbackResult(f.source(binding), binding).flowRefined()
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(transfer.source, transfer.target, transfer.kind),
        )
        assertEquals(
            Refinement.Rejected(ValueSiteFailure.CONDITIONS_NOT_PRESERVED),
            transfer.target.withState(conditions = ValueConditions.Unconditional),
        )
        val erased = ValueSite.fromCompiler(f.owner, transfer.target.range, transfer.target.role).flowRefined()
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(transfer.source, erased, transfer.kind, transfer.evidence),
        )
        val next =
            ValueSite.fromCompiler(
                    f.owner,
                    flowRange(20, 95),
                    ValueRole.Return,
                    conditions = transfer.target.conditions,
                )
                .flowRefined()
        assertTrue(ValueTransfer.fromCompiler(transfer.target, next, ValueTransferKind.RETURN) is Refinement.Refined)
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(
                transfer.target,
                ValueSite.fromCompiler(f.owner, next.range, next.role).flowRefined(),
                ValueTransferKind.RETURN,
            ),
        )
        assertTrue(transfer.target.retainedBytes > erased.retainedBytes)
    }
}

private class DeclaredValueFixture(generation: Long = 1) {
    val owner = flowEndpoint("host", 0, 100, generation = generation) as RelationEndpoint.Resolved
    val body = body(30, 80)
    private val target =
        CompilerGroundedSymbolEvidence.fromBoundary(
                SymbolDiscoveryFileIdentity.External(
                    DetachedVirtualFileUrl.parse("jar:///effects.jar!/fixture/EffectsKt.class").flowRefined()
                ),
                1000,
                1100,
                "select",
                "fixture.select",
                CompilerSymbolKind.FUNCTION,
                CanonicalCompilerSignature.function("fixture.select", null, emptyList(), listOf("()->T", "()->T"), 1)
                    .flowRefined(),
            )
            .flowRefined()

    fun body(start: Int, end: Int): RelationCallableBody.Anonymous {
        val range = flowRange(start, end)
        val signature =
            CanonicalCompilerSignature.function(
                    RelationCallableBody.Anonymous.sourceIdentity(owner.file, range),
                    null,
                    emptyList(),
                    emptyList(),
                    0,
                )
                .flowRefined() as CanonicalCompilerSignature.Function
        return RelationCallableBody.Anonymous.fromCompiler(owner.file, range, signature).flowRefined()
    }

    fun contract(
        position: Int = 0,
        digest: String = "ab".repeat(32),
        start: Int = 20,
        end: Int = 90,
        owner: RelationCallableBody = RelationCallableBody.Named.fromCompiler(this.owner.evidence).flowRefined(),
        kind: CallbackDependencyInvocationKind = CallbackDependencyInvocationKind.EXACTLY_ONCE,
    ): CallbackDependencyContract =
        CallbackDependencyContract.fromCompiler(
                this.owner.lease.identity,
                RelationOccurrence.fromBoundary(this.owner.file, start, end).flowRefined(),
                owner,
                target,
                ValueArgumentPosition.parse(position).flowRefined(),
                DependencyClassDigest.parse(digest).flowRefined(),
                CallbackDependencyContractProvenance.KOTLIN_BINARY_CONTRACT,
                kind,
            )
            .flowRefined()

    fun binding(
        contract: CallbackDependencyContract = contract(),
        body: RelationCallableBody.Anonymous = this.body,
        effect: DeclaredCallbackResultEffect = DeclaredCallbackResultEffect.RETURNS_RESULT_OF,
    ): DeclaredCallbackValueBinding = DeclaredCallbackValueBinding.admit(owner, body, contract, effect).flowRefined()

    fun source(binding: DeclaredCallbackValueBinding, role: ValueRole = ValueRole.Return): ValueSite =
        ValueSite.fromCompiler(owner, flowRange(40, 50), role, ValueExecutionOwner.declaredCallback(binding))
            .flowRefined()
}
