package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.workspace.contract.MovingLiveReadAuthorityFixture
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CallbackSupplierOwnerAdmissionTest {
    private val fixture = CallbackInvocationFlowFixture()

    @Test
    fun `anonymous supplier needs its complete exact owner route`() =
        with(fixture) {
            val frame = Frame()
            assertEquals(CallbackSupplierFailure.UNPROVEN_OWNER, frame.partition(emptyList()).failure())
            assertEquals(CallbackSupplierFailure.UNPROVEN_OWNER, frame.partition(listOf(frame.mapping())).failure())
            val complete = frame.mapping().withExecution(frame.flow()).value()
            val admitted = frame.partition(listOf(complete)).value()
            assertEquals(listOf(complete), admitted.ownerBindings)
            assertEquals(frame.parent, admitted.suppliers.single().binding.invocationOwner)
            val inventory = frame.inventory(admitted)
            assertTrue(inventory.retainedBytes > complete.retainedBytes)
            assertEquals(setOf(caller, target), inventory.requiredEndpoints().toSet())
            assertEquals(setOf(file), inventory.requiredSourceFiles())
        }

    @Test
    fun `another root basis duplicate or unrelated owner cannot prove a supplier`() =
        with(fixture) {
            val frame = Frame()
            val complete = frame.mapping().withExecution(frame.flow()).value()
            val otherRoot =
                RelationCallableBody.Named.fromCompiler(endpoint("other", 0, 200, emptyList()).evidence).value()
            val otherBasis = SemanticReadLease(root, EvidenceGeneration.parse(2).value()).identity
            val foreignRoot = frame.mapping(otherRoot).withExecution(frame.flow(otherRoot)).value()
            val foreignBasis = frame.mapping(basis = otherBasis).withExecution(frame.flow(basis = otherBasis)).value()
            val other = Frame(parent = anonymous(15, 100))
            val unrelated = other.mapping().withExecution(other.flow()).value()
            for (owners in
                listOf(listOf(foreignRoot), listOf(foreignBasis), listOf(unrelated), listOf(complete, complete))) {
                assertEquals(CallbackSupplierFailure.UNPROVEN_OWNER, frame.partition(owners).failure())
            }
        }

    @Test
    fun `supplier provenance and fresh readmission preserve owner execution`() =
        with(fixture) {
            val authority = MovingLiveReadAuthorityFixture(root)
            val old = authority.admit()
            val frame =
                Frame(
                    RelationEndpoint.resolve(old, caller.scope, caller.evidence, caller.constraints).value(),
                    RelationEndpoint.resolve(old, target.scope, target.evidence, target.constraints).value(),
                )
            val first = frame.mapping().withExecution(frame.flow()).value()
            val second = frame.mapping(start = 4).withExecution(frame.flow(start = 4)).value()
            val inventory = frame.inventory(frame.partition(listOf(first)).value())
            val changed = frame.inventory(frame.partition(listOf(second)).value())
            assertNotEquals(
                CallbackSupplierInventoryEvidence.Exhaustive(inventory).canonicalProjection(),
                CallbackSupplierInventoryEvidence.Exhaustive(changed).canonicalProjection(),
            )
            val current = authority.advance()
            val endpoints =
                inventory.requiredEndpoints().associateWith {
                    val resolved = it as RelationEndpoint.Resolved
                    RelationEndpoint.resolve(current, resolved.scope, resolved.evidence, resolved.constraints).value()
                }
            val restored =
                CallbackEndpointReadmissions.fromCompiler(current, endpoints).value().readmit(inventory).value()
            val execution =
                restored.partitions.single().ownerBindings.single().execution as CallbackOwnerExecution.Observed
            assertEquals(current.identity, execution.flow.basis)
            assertEquals(current.identity, (execution.flow.binding as CallbackBindingEvidence.Direct).binding.basis)
            assertEquals(
                emptySet<CallbackInvocationFlowCause>(),
                restored.partitions.single().ownerBindings.single().obligations,
            )
            assertEquals(old.identity, (first.execution as CallbackOwnerExecution.Observed).flow.basis)
        }

    private inner class Frame(
        val caller: RelationEndpoint.Resolved = fixture.caller,
        target: RelationEndpoint.Resolved = fixture.target,
        val parent: RelationCallableBody.Anonymous = fixture.anonymous(10, 100),
    ) {
        val supplier =
            with(fixture) {
                val call = ValueInvocation.fromCompiler(this@Frame.caller, invocation.range, target).value()
                val mapped =
                    CallbackArgumentBinding.fromCompiler(call, parent, binding.position, binding.parameter).value()
                val source = ValueSite.fromCompiler(this@Frame.caller, body.range, ValueRole.ExpressionResult).value()
                val argument =
                    ValueSite.fromCompiler(this@Frame.caller, body.range, ValueRole.Argument(call, binding.position))
                        .value()
                val value =
                    ImmutableCallbackValue.fromCompiler(
                            ImmutableCallbackValueOrigin.Anonymous(body),
                            source,
                            argument,
                            listOf(ValueTransfer.fromCompiler(source, argument, ValueTransferKind.ARGUMENT).value()),
                        )
                        .value()
                CallbackParameterSupplier.fromCompiler(mapped, CallbackSupplierSelection.Explicit(argument), value)
                    .value()
            }

        fun flow(
            owner: RelationCallableBody.Named =
                with(fixture) { RelationCallableBody.Named.fromCompiler(caller.evidence).value() },
            basis: io.github.amichne.kast.workspace.contract.SemanticReadIdentity = caller.lease.identity,
            start: Int = 5,
        ) =
            with(fixture) {
                CallbackInvocationFlow.fromCompiler(
                        basis,
                        parent,
                        CallbackBindingEvidence.Direct(
                            CallbackDirectInvocationBinding.fromCompiler(basis, occurrence(start, 105), owner).value()
                        ),
                        emptyList(),
                        emptySet(),
                        CallbackInvocationScan.NOT_APPLICABLE,
                    )
                    .value()
            }

        fun mapping(
            owner: RelationCallableBody.Named =
                with(fixture) { RelationCallableBody.Named.fromCompiler(caller.evidence).value() },
            basis: io.github.amichne.kast.workspace.contract.SemanticReadIdentity = caller.lease.identity,
            start: Int = 5,
        ) =
            with(fixture) {
                CallbackBodyBinding.fromCompiler(
                        parent,
                        CallbackBodySupply.DirectInvocation(occurrence(start, 105)),
                        flow(owner, basis, start).binding,
                        setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                    )
                    .value()
            }

        fun partition(owners: List<CallbackBodyBinding>) =
            CompleteCallbackSupplierPartition.fromCompiler(
                supplier.formal,
                listOf(supplier),
                emptyList(),
                CallbackInvocationScan.EXHAUSTIVE,
                owners,
            )

        fun inventory(partition: CompleteCallbackSupplierPartition) =
            with(fixture) {
                CompleteCallbackSupplierInventory.fromCompiler(
                        supplier.formal,
                        RelationScopeFingerprint.from(supplier.formal.callable),
                        listOf(partition),
                    )
                    .value()
            }
    }
}
