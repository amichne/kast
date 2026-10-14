package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class CallbackDeferredSupplierTest {
    private val fixture = CallbackInvocationFlowFixture()

    @Test
    fun `accessor and initializer preserve execution regions and reject wrong declarations or containment`() =
        with(fixture) {
            val property =
                declaration(
                    CompilerSymbolKind.PROPERTY,
                    CanonicalCompilerSignature.property("fixture.Owner", null, emptyList(), "Unit").value(),
                )
            val klass =
                declaration(CompilerSymbolKind.CLASSLIKE, CanonicalCompilerSignature.classLike("fixture.Owner").value())
            val supply = occurrence(40, 60)
            val region = occurrence(20, 100)
            val getter =
                CallbackDeferredSupplier.accessor(lease.identity, supply, property, CallbackAccessorKind.GETTER, region)
                    .value()
            assertSame(region, (getter.execution as CallbackDeferredExecution.Accessor).occurrence)
            assertSame(property, getter.evidence)
            assertSame(
                region,
                (CallbackDeferredSupplier.initializer(lease.identity, supply, klass, region).value().execution
                        as CallbackDeferredExecution.Initializer)
                    .occurrence,
            )
            assertEquals(
                CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE,
                CallbackDeferredSupplier.accessor(lease.identity, supply, klass, CallbackAccessorKind.GETTER, region)
                    .failure(),
            )
            assertEquals(
                CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE,
                CallbackDeferredSupplier.initializer(lease.identity, supply, property, region).failure(),
            )
            assertEquals(
                CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE,
                CallbackDeferredSupplier.accessor(
                        lease.identity,
                        supply,
                        property,
                        CallbackAccessorKind.SETTER,
                        occurrence(70, 100),
                    )
                    .failure(),
            )
        }

    @Test
    fun `direct execution preserves every step and rejects missing parent or changed basis`() =
        with(fixture) {
            val outer = anonymous(20, 150)
            val inner = anonymous(40, 100)
            val first =
                CallbackDirectInvocationBinding.fromCompiler(lease.identity, occurrence(10, 160), supplyingOwner)
                    .value()
            val second =
                CallbackDirectInvocationBinding.fromCompiler(lease.identity, occurrence(30, 110), outer).value()
            val route = CallbackDirectExecutionRoute.start(first, outer).value().append(second, inner).value()
            assertEquals(listOf(first, second), route.steps.map { it.binding })
            assertSame(supplyingOwner, route.owner)
            assertSame(
                route,
                (CallbackDeferredSupplier.invoked(occurrence(60, 80), route).value().execution
                        as CallbackDeferredExecution.Invoked)
                    .route,
            )
            assertEquals(
                CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION,
                CallbackDirectExecutionRoute.start(second, inner).failure(),
            )
            assertEquals(
                CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION,
                CallbackDirectExecutionRoute.start(first, outer).value().append(first, inner).failure(),
            )
            val later = SemanticReadLease(root, EvidenceGeneration.parse(2).value()).identity
            val moved = CallbackDirectInvocationBinding.fromCompiler(later, occurrence(30, 110), outer).value()
            assertEquals(
                CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION,
                CallbackDirectExecutionRoute.start(first, outer).value().append(moved, inner).failure(),
            )
            assertEquals(
                CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE,
                CallbackDeferredSupplier.invoked(occurrence(120, 130), route).failure(),
            )
        }

    private fun declaration(kind: CompilerSymbolKind, signature: CanonicalCompilerSignature) =
        with(fixture) {
            CompilerGroundedSymbolEvidence.fromBoundary(file, 0, 200, "Owner", "fixture.Owner", kind, signature).value()
        }
}
