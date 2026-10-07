package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.MovingLiveReadAuthorityFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class CallbackSupplierReadmissionTest {
    private val fixture = CallbackInvocationFlowFixture()
    private val owner = MovingLiveReadAuthorityFixture(fixture.root)

    @Test
    fun `supplier restoration preserves complete negative closure and moves every binding basis`() =
        with(fixture) {
            val oldAuthority = owner.admit()
            val oldCaller =
                RelationEndpoint.resolve(oldAuthority, caller.scope, caller.evidence, caller.constraints).value()
            val oldTarget =
                RelationEndpoint.resolve(oldAuthority, target.scope, target.evidence, target.constraints).value()
            val supplier = anonymousSupplier(oldCaller, oldTarget)
            val old = supplierInventory(supplier)
            assertEquals(setOf(oldCaller, oldTarget), old.requiredEndpoints().toSet())
            assertEquals(setOf(oldCaller.file), old.requiredSourceFiles())
            val current = owner.advance()
            val mapped =
                old.requiredEndpoints().associateWith { previous ->
                    val resolved = previous as RelationEndpoint.Resolved
                    RelationEndpoint.resolve(current, resolved.scope, resolved.evidence, resolved.constraints).value()
                }
            val restored = CallbackEndpointReadmissions.fromCompiler(current, mapped).value().readmit(old).value()
            val accepted = restored.partitions.single().suppliers.single()
            assertEquals(current, restored.root.callable.lease)
            assertEquals(current.identity, accepted.binding.invocation.basis)
            assertEquals(current.identity, accepted.value.source.basis)
            assertEquals(current.identity, accepted.value.destination.basis)
            assertEquals(body, (accepted.value.origin as ImmutableCallbackValueOrigin.Anonymous).body)
            assertEquals(old.domain, restored.domain)
            assertEquals(oldAuthority, old.root.callable.lease)
        }

    @Test
    fun `empty supplier partition cannot be restored with missing endpoint or stale current authority`(): Unit =
        with(fixture) {
            val prior = owner.admit()
            val oldTarget = RelationEndpoint.resolve(prior, target.scope, target.evidence, target.constraints).value()
            val formal = CallbackParameterIdentity.fromCompiler(oldTarget, binding.position, binding.parameter).value()
            val partition =
                CompleteCallbackSupplierPartition.fromCompiler(
                        formal,
                        emptyList(),
                        emptyList(),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .value()
            val old =
                CompleteCallbackSupplierInventory.fromCompiler(
                        formal,
                        RelationScopeFingerprint.from(oldTarget),
                        listOf(partition),
                    )
                    .value()
            val current = owner.advance()
            assertEquals(
                CallbackSummaryReadmissionFailure.MissingEndpoint(oldTarget.valueIdentity),
                (CallbackEndpointReadmissions.fromCompiler(current, emptyMap()).value().readmit(old)
                        as Refinement.Rejected)
                    .failure,
            )
            val mapped = RelationEndpoint.resolve(current, target.scope, target.evidence, target.constraints).value()
            val proof = CallbackEndpointReadmissions.fromCompiler(current, mapOf(oldTarget to mapped)).value()
            owner.advance()
            assertInstanceOf(
                CallbackSummaryReadmissionFailure.Authority::class.java,
                (proof.readmit(old) as Refinement.Rejected).failure,
            )
        }

    private fun anonymousSupplier(oldCaller: RelationEndpoint.Resolved, oldTarget: RelationEndpoint.Resolved) =
        with(fixture) {
            val oldCall = ValueInvocation.fromCompiler(oldCaller, invocation.range, oldTarget).value()
            val oldBinding =
                CallbackArgumentBinding.fromCompiler(
                        oldCall,
                        binding.invocationOwner,
                        binding.position,
                        binding.parameter,
                    )
                    .value()
            val source = ValueSite.fromCompiler(oldCaller, body.range, ValueRole.ExpressionResult).value()
            val destination =
                ValueSite.fromCompiler(oldCaller, body.range, ValueRole.Argument(oldCall, binding.position)).value()
            val value =
                ImmutableCallbackValue.fromCompiler(
                        ImmutableCallbackValueOrigin.Anonymous(body),
                        source,
                        destination,
                        listOf(ValueTransfer.fromCompiler(source, destination, ValueTransferKind.ARGUMENT).value()),
                    )
                    .value()
            return@with CallbackParameterSupplier.fromCompiler(
                    oldBinding,
                    CallbackSupplierSelection.Explicit(destination),
                    value,
                )
                .value()
        }
}
