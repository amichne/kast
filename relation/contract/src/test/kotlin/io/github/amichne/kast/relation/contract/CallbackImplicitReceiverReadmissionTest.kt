package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.workspace.contract.MovingLiveReadAuthorityFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CallbackImplicitReceiverReadmissionTest {
    private val fixture = CallbackInvocationFlowFixture()
    private val owner = MovingLiveReadAuthorityFixture(fixture.root)

    @Test
    fun `implicit receiver source declaration requires its own current compiler readmission`() =
        with(fixture) {
            val prior = owner.admit()
            val receiver = receiver()
            val inventory = inventory(prior, receiver)
            val value = inventory.partitions.single().suppliers.single().value
            assertEquals(setOf(file, receiver.file), inventory.requiredSourceFiles())
            assertEquals(setOf(receiver), inventory.requiredCompilerDeclarations())
            assertEquals(setOf(receiver), value.requiredCompilerDeclarations())
            val current = owner.advance()
            val mapped =
                inventory.requiredEndpoints().associateWith { previous ->
                    val endpoint = previous as RelationEndpoint.Resolved
                    RelationEndpoint.resolve(current, endpoint.scope, endpoint.evidence, endpoint.constraints).value()
                }
            val missing =
                CallbackEndpointReadmissions.fromCompiler(current, mapped).value().readmit(inventory).failure()
            assertEquals(
                CallbackSummaryReadmissionFailure.MissingEndpoint(
                    ValueDeclarationIdentity(receiver.compilerIdentity, receiver.file, receiver.range)
                ),
                missing,
            )
            val restored =
                CallbackEndpointReadmissions.fromCompiler(current, mapped, mapOf(receiver to receiver))
                    .value()
                    .readmit(inventory)
                    .value()
            val restoredOrigin =
                restored.partitions.single().suppliers.single().value.origin as ImmutableCallbackValueOrigin.Named
            assertEquals(current, restoredOrigin.target.lease)
            assertEquals(CallbackReferenceReceiver.Implicit(receiver), restoredOrigin.receivers.dispatch)
        }

    private fun receiver(): io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence =
        with(fixture) {
            val receiverFile =
                io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity.fromBoundary(
                        root,
                        java.nio.file.Path.of("/fixture/Receiver.kt"),
                        "file:///fixture/Receiver.kt",
                    )
                    .value()
            return@with io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence.fromBoundary(
                    receiverFile,
                    0,
                    100,
                    "Receiver",
                    "fixture.Receiver",
                    io.github.amichne.kast.symbol.contract.CompilerSymbolKind.CLASSLIKE,
                    io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature.classLike("fixture.Receiver")
                        .value(),
                )
                .value()
        }

    private fun inventory(
        prior: io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority,
        receiver: io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence,
    ) =
        with(fixture) {
            fun live(endpoint: RelationEndpoint.Resolved) =
                RelationEndpoint.resolve(prior, endpoint.scope, endpoint.evidence, endpoint.constraints).value()
            val oldCaller = live(caller)
            val oldTarget = live(target)
            val action = live(endpoint("action", 610, 700, emptyList()))
            val call = ValueInvocation.fromCompiler(oldCaller, invocation.range, oldTarget).value()
            val bound =
                CallbackArgumentBinding.fromCompiler(call, supplyingOwner, binding.position, binding.parameter).value()
            val source = ValueSite.fromCompiler(oldCaller, body.range, ValueRole.ExpressionResult).value()
            val selected =
                ValueSite.fromCompiler(oldCaller, body.range, ValueRole.Argument(call, binding.position)).value()
            val origin =
                ImmutableCallbackValueOrigin.Named(
                    occurrence(30, 60),
                    action,
                    CallbackReferenceReceivers(
                        CallbackReferenceReceiver.Implicit(receiver),
                        CallbackReferenceReceiver.Absent,
                    ),
                )
            val value =
                ImmutableCallbackValue.fromCompiler(
                        origin,
                        source,
                        selected,
                        listOf(ValueTransfer.fromCompiler(source, selected, ValueTransferKind.ARGUMENT).value()),
                    )
                    .value()
            val supplier =
                CallbackParameterSupplier.fromCompiler(bound, CallbackSupplierSelection.Explicit(selected), value)
                    .value()
            supplierInventory(supplier)
        }
}
