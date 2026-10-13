package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** Independent content oracle for the pinned stdlib 2.3.20 classes, including every rejection position. */
class KotlinLazyBinaryEvidenceTest {
    private val fixture = CallbackInvocationFlowFixture()
    private val digests =
        KotlinLazyClassDigests(
            facade = digest("0f432817ab2bf3d06e5095a83b872c1e1e2632a2aea3bce3b1e0fbd50cbd32c4"),
            factory = digest("0b114cc831af3a8f239210550a7c827b220dcfabf7affbbfb39916a4a50c846a"),
            synchronized = digest("e1e31f2d66b92e44fd5232af6a0f40e0fd1a42ed93b3eb44e26fe111e0e10294"),
            publication = digest("f4bcf09dcc22d212208914c804a924fb94e9ae5336a2d4bfaf0b92072c48135a"),
            unsafe = digest("1b745b04099715a6eeb9d4df8d593c769aa58b8f116cc88063e8d17d35d759c3"),
            modeDispatch = digest("842bd12641b951e267c40fd2a57cb4fa4356167ccbf713722b541d8307429d7d"),
        )

    @Test
    fun `all reviewed class identities survive admission and each changed class rejects`() {
        assertSame(digests, KotlinLazyBinaryEvidence.admit(digests).lazyValue().classes)
        val changed = digest("a".repeat(64))
        val unknown =
            listOf(
                digests.copy(facade = changed),
                digests.copy(factory = changed),
                digests.copy(synchronized = changed),
                digests.copy(publication = changed),
                digests.copy(unsafe = changed),
                digests.copy(modeDispatch = changed),
            )
        unknown.forEach {
            assertEquals(
                CallbackInvocationFlowCause.DEPENDENCY_DEFERRED_MODEL_UNPROVEN,
                KotlinLazyBinaryEvidence.admit(it).lazyFailure(),
            )
        }
    }

    @Test
    fun `supplier admission preserves the source site and rejects absent containment`() =
        with(fixture) {
            val occurrence = occurrence(20, 80)
            val supplier =
                CallbackDeferredSupplier.fromCompiler(lease.identity, occurrence, caller.evidence).lazyValue()
            assertSame(lease.identity, supplier.basis)
            assertSame(occurrence, supplier.occurrence)
            assertSame(caller.evidence, supplier.evidence)
            assertEquals(
                CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE,
                CallbackDeferredSupplier.fromCompiler(lease.identity, occurrence(250, 260), caller.evidence)
                    .lazyFailure(),
            )
        }

    @Test
    fun `deferred binding keeps binary and supplier proofs without an immediate invocation`() =
        with(fixture) {
            val supplier =
                CallbackDeferredSupplier.fromCompiler(lease.identity, occurrence(20, 80), caller.evidence).lazyValue()
            val binary = KotlinLazyBinaryEvidence.admit(digests).lazyValue()
            val binding =
                CallbackDeferredDependency.fromCompiler(
                        owner = supplier,
                        target = external("kotlin.lazy"),
                        position = ValueArgumentPosition.parse(0).lazyValue(),
                        binary = binary,
                    )
                    .lazyValue()
            assertSame(supplier, binding.owner)
            assertSame(binary, binding.binary)
            assertEquals(
                CallbackInvocationFlowFailure.INVALID_DEPENDENCY_CONTRACT,
                CallbackDeferredDependency.fromCompiler(
                        owner = supplier,
                        target = external("fixture.lazy"),
                        position = binding.position,
                        binary = binary,
                    )
                    .lazyFailure(),
            )
            assertEquals(
                CallbackInvocationFlowFailure.INVALID_DEPENDENCY_CONTRACT,
                CallbackDeferredDependency.fromCompiler(
                        owner = supplier,
                        target = binding.target,
                        position = ValueArgumentPosition.parse(1).lazyValue(),
                        binary = binary,
                    )
                    .lazyFailure(),
            )
            assertFlow(binding)
        }

    private fun assertFlow(binding: CallbackDeferredDependency) =
        with(fixture) {
            val flow =
                CallbackInvocationFlow.fromCompiler(
                        basis = lease.identity,
                        body = body,
                        binding = CallbackBindingEvidence.DeferredDependency(binding),
                        invocations = emptyList(),
                        obligations = emptySet(),
                        scan = CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .lazyValue()
            assertEquals(emptyList<CallbackParameterInvocation>(), flow.invocations)
            assertEquals(
                CallbackInvocationFlowFailure.INVALID_SCAN_PROOF,
                CallbackInvocationFlow.fromCompiler(
                        basis = lease.identity,
                        body = body,
                        binding = flow.binding,
                        invocations = emptyList(),
                        obligations = emptySet(),
                        scan = CallbackInvocationScan.INCOMPLETE,
                    )
                    .lazyFailure(),
            )
        }

    private fun external(name: String): CompilerGroundedSymbolEvidence {
        val signature =
            CanonicalCompilerSignature.function(
                    rawQualifiedIdentity = name,
                    rawReceiverType = null,
                    rawContextReceiverTypes = emptyList(),
                    rawValueParameterTypes = listOf("kotlin.Function0<T>"),
                    rawTypeParameterCount = 1,
                )
                .lazyValue()
        return CompilerGroundedSymbolEvidence.fromBoundary(
                file =
                    SymbolDiscoveryFileIdentity.External(
                        DetachedVirtualFileUrl.parse("jar:///stdlib.jar!/kotlin/LazyKt.class").lazyValue()
                    ),
                rawStartInclusive = 210,
                rawEndExclusive = 400,
                rawName = "lazy",
                rawQualifiedIdentity = name,
                kind = CompilerSymbolKind.FUNCTION,
                signature = signature,
            )
            .lazyValue()
    }

    private fun digest(value: String) = DependencyClassDigest.parse(value).lazyValue()
}

private fun <Value, Failure> io.github.amichne.kast.kernel.Refinement<Value, Failure>.lazyValue(): Value =
    when (this) {
        is io.github.amichne.kast.kernel.Refinement.Refined -> value
        is io.github.amichne.kast.kernel.Refinement.Rejected ->
            throw AssertionError("Expected admitted fixture: $failure")
    }

private fun <Value, Failure> io.github.amichne.kast.kernel.Refinement<Value, Failure>.lazyFailure(): Failure =
    when (this) {
        is io.github.amichne.kast.kernel.Refinement.Refined -> throw AssertionError("Expected rejection")
        is io.github.amichne.kast.kernel.Refinement.Rejected -> failure
    }
