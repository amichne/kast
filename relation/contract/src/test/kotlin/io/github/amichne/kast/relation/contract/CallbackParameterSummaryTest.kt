package io.github.amichne.kast.relation.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class CallbackParameterSummaryTest {
    private val fixture = CallbackInvocationFlowFixture()

    @Test
    fun `one formal summary preserves two independent supplier bindings and bodies`(): Unit =
        with(fixture) {
            val formal = CallbackParameterIdentity.fromCompiler(target, binding.position, binding.parameter).value()
            val terminal =
                CallbackParameterInvocation.fromCompiler(
                        occurrence(320, 330),
                        RelationCallableBody.Named.fromCompiler(target.evidence).value(),
                    )
                    .value()
            val summary =
                CallbackParameterSummary.fromCompiler(
                        formal,
                        listOf(terminal),
                        emptySet(),
                        scan = CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .value()
            val secondBinding =
                CallbackArgumentBinding.fromCompiler(
                        ValueInvocation.fromCompiler(caller, range(100, 180), target).value(),
                        supplyingOwner,
                        binding.position,
                        binding.parameter,
                    )
                    .value()
            val secondBody = anonymous(120, 150)
            val first = summary.instantiate(body, binding).value()
            val second = summary.instantiate(secondBody, secondBinding).value()
            assertEquals(body, first.body)
            assertEquals(secondBody, second.body)
            assertEquals(CallbackBindingEvidence.Bound(binding), first.binding)
            assertEquals(CallbackBindingEvidence.Bound(secondBinding), second.binding)
            assertEquals(listOf(terminal), first.invocations)
            assertEquals(listOf(terminal), second.invocations)
            assertNotEquals(first.canonicalProjection(), second.canonicalProjection())
            assertEquals(CallbackInvocationScan.EXHAUSTIVE, second.scan)
        }

    @Test
    fun `supplier activation obligation is applied after summary reuse`(): Unit =
        with(fixture) {
            val formal = CallbackParameterIdentity.fromCompiler(target, binding.position, binding.parameter).value()
            val terminal =
                CallbackParameterInvocation.fromCompiler(
                        occurrence(320, 330),
                        RelationCallableBody.Named.fromCompiler(target.evidence).value(),
                    )
                    .value()
            val summary =
                CallbackParameterSummary.fromCompiler(
                        formal,
                        listOf(terminal),
                        emptySet(),
                        scan = CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .value()
            val nestedBinding =
                CallbackArgumentBinding.fromCompiler(
                        invocation,
                        anonymous(10, 100),
                        binding.position,
                        binding.parameter,
                    )
                    .value()
            assertEquals(
                setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                summary.instantiate(body, nestedBinding).value().obligations,
            )
            assertEquals(
                emptySet<CallbackInvocationFlowCause>(),
                summary.instantiate(body, binding).value().obligations,
            )
            assertEquals(emptySet<CallbackInvocationFlowCause>(), summary.obligations)
        }

    @Test
    fun `different formal cannot instantiate summary`(): Unit =
        with(fixture) {
            val formal = CallbackParameterIdentity.fromCompiler(target, binding.position, binding.parameter).value()
            val summary =
                CallbackParameterSummary.fromCompiler(
                        formal,
                        emptyList(),
                        emptySet(),
                        scan = CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .value()
            val other =
                CallbackArgumentBinding.fromCompiler(
                        invocation,
                        supplyingOwner,
                        ValueArgumentPosition.parse(0).value(),
                        occurrence(215, 223),
                    )
                    .value()
            assertEquals(
                CallbackInvocationFlowFailure.INVALID_FORWARDING_PATH,
                summary.instantiate(body, other).failure(),
            )
        }

    @Test
    fun `another semantic basis cannot use a summary`(): Unit =
        with(fixture) {
            val formal = CallbackParameterIdentity.fromCompiler(target, binding.position, binding.parameter).value()
            val summary =
                CallbackParameterSummary.fromCompiler(
                        formal,
                        emptyList(),
                        emptySet(),
                        scan = CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .value()
            val anotherLease =
                io.github.amichne.kast.workspace.contract.SemanticReadLease(
                    root,
                    io.github.amichne.kast.kernel.EvidenceGeneration.parse(2).value(),
                )
            val scope =
                io.github.amichne.kast.symbol.contract.SymbolSearchScope.Workspace(
                    io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy.EXCLUDE,
                    io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy.EXCLUDE,
                )
            val otherCaller = RelationEndpoint.resolve(anotherLease, scope, caller.evidence).value()
            val otherTarget = RelationEndpoint.resolve(anotherLease, scope, target.evidence).value()
            val otherBinding =
                CallbackArgumentBinding.fromCompiler(
                        ValueInvocation.fromCompiler(otherCaller, range(20, 80), otherTarget).value(),
                        supplyingOwner,
                        binding.position,
                        binding.parameter,
                    )
                    .value()
            assertEquals(
                CallbackInvocationFlowFailure.BASIS_MISMATCH,
                summary.instantiate(body, otherBinding).failure(),
            )
        }

    @Test
    fun `scan proof is required and owner bindings cannot smuggle an unrelated supplier`(): Unit =
        with(fixture) {
            val formal = CallbackParameterIdentity.fromCompiler(target, binding.position, binding.parameter).value()
            assertEquals(
                CallbackInvocationFlowFailure.INVALID_SCAN_PROOF,
                CallbackParameterSummary.fromCompiler(
                        formal,
                        emptyList(),
                        emptySet(),
                        scan = CallbackInvocationScan.INCOMPLETE,
                    )
                    .failure(),
            )
            val owner =
                CallbackBodyBinding.fromCompiler(
                        body,
                        CallbackBodySupply.Invocation(occurrence(20, 80)),
                        CallbackBindingEvidence.Bound(binding),
                        setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                    )
                    .value()
            assertEquals(
                CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH,
                CallbackParameterSummary.fromCompiler(
                        formal,
                        emptyList(),
                        emptySet(),
                        listOf(owner),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .failure(),
            )
        }

    @Test
    fun `incomplete or resource limited scans cannot become reusable exhaustive summaries`(): Unit =
        with(fixture) {
            val formal = CallbackParameterIdentity.fromCompiler(target, binding.position, binding.parameter).value()
            assertEquals(
                CallbackInvocationFlowFailure.INVALID_SCAN_PROOF,
                CallbackParameterSummary.fromCompiler(
                        formal,
                        emptyList(),
                        setOf(CallbackInvocationFlowCause.WORK_LIMIT_REACHED),
                        scan = CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .failure(),
            )
            assertEquals(
                CallbackInvocationFlowFailure.MISSING_OBLIGATION,
                CallbackParameterSummary.fromCompiler(
                        formal,
                        listOf(nested),
                        emptySet(),
                        scan = CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .failure(),
            )
            val summary =
                CallbackParameterSummary.fromCompiler(
                        formal,
                        listOf(nested),
                        setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                        scan = CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .value()
            assertEquals(
                setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                summary.instantiate(body, binding).value().obligations,
            )
        }
}
