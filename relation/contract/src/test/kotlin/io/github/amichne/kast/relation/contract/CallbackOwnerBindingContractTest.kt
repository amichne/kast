package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CallbackOwnerBindingContractTest {
    private val case = CallbackInvocationFlowFixture()
    private val receiver = case.endpoint("readAction", 410, 500, listOf("()->Unit"))
    private val nestedInvocation =
        with(case) { ValueInvocation.fromCompiler(target, range(255, 310), receiver).value() }
    private val nestedBinding =
        with(case) {
            CallbackArgumentBinding.fromCompiler(
                    invocation = nestedInvocation,
                    invocationOwner = RelationCallableBody.Named.fromCompiler(target.evidence).value(),
                    position = ValueArgumentPosition.parse(0).value(),
                    parameter = occurrence(425, 445),
                )
                .value()
        }

    @Test
    fun `nested supplying call and formal mapping refine the operation owner without activating it`(): Unit =
        with(case) {
            val owner = boundOwner()
            val original = observed(listOf(nested), setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION))
            val refined = original.withOwnerBindings(listOf(owner)).value()
            assertSame(original.binding, refined.binding)
            assertEquals(
                range(255, 310),
                (refined.ownerBindings.single().supply as CallbackBodySupply.Invocation).occurrence.range,
            )
            assertEquals(range(260, 300), refined.ownerBindings.single().body.range)
            assertEquals(
                range(425, 445),
                ((refined.ownerBindings.single().binding as CallbackBindingEvidence.Bound).binding).parameter.range,
            )
            assertEquals(setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION), refined.obligations)
        }

    @Test
    fun `unavailable library mapping retains exact nested supply and excluded domain obligation`(): Unit =
        with(case) {
            val owner =
                CallbackBodyBinding.fromCompiler(
                        body = nested.owner as RelationCallableBody.Anonymous,
                        supply = CallbackBodySupply.Invocation(occurrence(255, 310)),
                        binding = CallbackBindingEvidence.Unavailable(CallbackInvocationFlowCause.EXTERNAL_CALLABLE),
                        obligations =
                            setOf(
                                CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION,
                                CallbackInvocationFlowCause.OUTSIDE_DOMAIN,
                                CallbackInvocationFlowCause.EXTERNAL_CALLABLE,
                            ),
                    )
                    .value()
            val refined =
                observed(listOf(nested), setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION))
                    .withOwnerBindings(listOf(owner))
                    .value()
            assertEquals(
                CallbackBindingEvidence.Unavailable(CallbackInvocationFlowCause.EXTERNAL_CALLABLE),
                refined.ownerBindings.single().binding,
            )
            assertEquals(owner.obligations, refined.obligations)
            assertEquals(occurrence(255, 310), (owner.supply as CallbackBodySupply.Invocation).occurrence)
        }

    @Test
    fun `owner binding rejects source mismatch and missing activation obligation`(): Unit =
        with(case) {
            assertEquals(
                CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH,
                CallbackBodyBinding.fromCompiler(
                        body = nested.owner as RelationCallableBody.Anonymous,
                        supply = CallbackBodySupply.Invocation(occurrence(250, 310)),
                        binding = CallbackBindingEvidence.Bound(nestedBinding),
                        obligations = setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                    )
                    .failure(),
            )
            assertEquals(
                CallbackInvocationFlowFailure.MISSING_OBLIGATION,
                CallbackBodyBinding.fromCompiler(
                        body = nested.owner as RelationCallableBody.Anonymous,
                        supply = CallbackBodySupply.Invocation(occurrence(255, 310)),
                        binding = CallbackBindingEvidence.Bound(nestedBinding),
                        obligations = emptySet(),
                    )
                    .failure(),
            )
        }

    @Test
    fun `owner binding rejects a stored body unrelated to the observed owners`(): Unit =
        with(case) {
            val stored =
                CallbackBodyBinding.fromCompiler(
                        body = anonymous(330, 350),
                        supply = CallbackBodySupply.Stored,
                        binding = CallbackBindingEvidence.Unavailable(CallbackInvocationFlowCause.STORED_CALLBACK),
                        obligations =
                            setOf(
                                CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION,
                                CallbackInvocationFlowCause.STORED_CALLBACK,
                            ),
                    )
                    .value()
            assertEquals(
                CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH,
                observed(listOf(nested), setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION))
                    .withOwnerBindings(listOf(stored))
                    .failure(),
            )
        }

    @Test
    fun `owner binding refinement freezes exact facts and rejects duplicate owner replacement`(): Unit =
        with(case) {
            val owner = boundOwner()
            val bindings = mutableListOf(owner)
            val flow =
                observed(listOf(nested), setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION))
                    .withOwnerBindings(bindings)
                    .value()
            bindings.clear()
            assertEquals(listOf(owner), flow.ownerBindings)
            assertThrows(UnsupportedOperationException::class.java) {
                (flow.ownerBindings as MutableList<CallbackBodyBinding>).clear()
            }
            assertThrows(UnsupportedOperationException::class.java) {
                (owner.obligations as MutableSet<CallbackInvocationFlowCause>).clear()
            }
            assertEquals(
                CallbackInvocationFlowFailure.DUPLICATE_OWNER_BINDING,
                flow.withOwnerBindings(listOf(owner)).failure(),
            )
        }

    @Test
    fun `owner mapping from another basis cannot refine current invocation evidence`(): Unit =
        with(case) {
            val moved = SemanticReadLease(root, EvidenceGeneration.parse(2).value())
            val owner = RelationEndpoint.resolve(moved, target.scope, target.evidence).value()
            val otherReceiver = RelationEndpoint.resolve(moved, receiver.scope, receiver.evidence).value()
            val mapped =
                CallbackArgumentBinding.fromCompiler(
                        invocation = ValueInvocation.fromCompiler(owner, range(255, 310), otherReceiver).value(),
                        invocationOwner = RelationCallableBody.Named.fromCompiler(target.evidence).value(),
                        position = ValueArgumentPosition.parse(0).value(),
                        parameter = occurrence(425, 445),
                    )
                    .value()
            val detached =
                CallbackBodyBinding.fromCompiler(
                        body = nested.owner as RelationCallableBody.Anonymous,
                        supply = CallbackBodySupply.Invocation(occurrence(255, 310)),
                        binding = CallbackBindingEvidence.Bound(mapped),
                        obligations = setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                    )
                    .value()
            val flow = observed(listOf(nested), setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION))
            assertEquals(
                CallbackInvocationFlowFailure.BASIS_MISMATCH,
                flow.withOwnerBindings(listOf(detached)).failure(),
            )
        }

    @Test
    fun `external library sites cannot create an inspectable bound owner mapping`(): Unit =
        with(case) {
            val external = externalReceiver()
            val mapped =
                CallbackArgumentBinding.fromCompiler(
                        invocation = ValueInvocation.fromCompiler(target, range(255, 310), external).value(),
                        invocationOwner = RelationCallableBody.Named.fromCompiler(target.evidence).value(),
                        position = ValueArgumentPosition.parse(0).value(),
                        parameter = RelationOccurrence.fromBoundary(external.file, 425, 445).value(),
                    )
                    .value()
            assertEquals(
                CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH,
                CallbackBodyBinding.fromCompiler(
                        body = nested.owner as RelationCallableBody.Anonymous,
                        supply = CallbackBodySupply.Invocation(occurrence(255, 310)),
                        binding = CallbackBindingEvidence.Bound(mapped),
                        obligations =
                            setOf(
                                CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION,
                                CallbackInvocationFlowCause.OUTSIDE_DOMAIN,
                            ),
                    )
                    .failure(),
            )
        }

    private fun externalReceiver() =
        with(case) {
            val externalFile =
                SymbolDiscoveryFileIdentity.fromBoundary(root, null, "jar:///fixture/library.jar!/Library.kt").value()
            val evidence =
                CompilerGroundedSymbolEvidence.fromBoundary(
                        file = externalFile,
                        rawStartInclusive = 410,
                        rawEndExclusive = 500,
                        rawName = receiver.name.value,
                        rawQualifiedIdentity = "fixture.readAction",
                        kind = receiver.kind,
                        signature = receiver.signature,
                    )
                    .value()
            RelationEndpoint.resolve(lease, receiver.scope, evidence).value()
        }

    private fun boundOwner() =
        with(case) {
            CallbackBodyBinding.fromCompiler(
                    body = nested.owner as RelationCallableBody.Anonymous,
                    supply = CallbackBodySupply.Invocation(occurrence(255, 310)),
                    binding = CallbackBindingEvidence.Bound(nestedBinding),
                    obligations = setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                )
                .value()
        }
}
