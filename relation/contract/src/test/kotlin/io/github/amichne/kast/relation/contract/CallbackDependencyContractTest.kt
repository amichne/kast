package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CallbackDependencyContractTest {
    private val fixture = CallbackInvocationFlowFixture()

    @Test
    fun `declared contract preserves binary identity without a dependency body invocation`(): Unit =
        with(fixture) {
            val contract = contract()
            val flow = flow(contract).value()
            assertSame(contract, (flow.binding as CallbackBindingEvidence.DependencyContract).binding)
            assertTrue(flow.invocations.isEmpty())
            assertTrue(flow.obligations.isEmpty())
            assertTrue(flow.canonicalProjection().contains("DEPENDENCY_CONTRACT"))
            assertTrue(flow.canonicalProjection().contains(contract.classDigest.value))
            assertEquals(CallbackDependencyContractProvenance.KOTLIN_BINARY_CONTRACT, contract.provenance)
            assertEquals(CallbackDependencyInvocationKind.EXACTLY_ONCE, contract.invocationKind)
            assertEquals("jar:///library.jar!/fixture/Boundary.class", contract.target.file.stableValue)
        }

    @Test
    fun `contract rejects authored targets invalid formal position and invalid class digest`(): Unit =
        with(fixture) {
            assertEquals(CallbackInvocationFlowFailure.INVALID_DEPENDENCY_CONTRACT, create(target.evidence).failure())
            assertEquals(
                CallbackInvocationFlowFailure.INVALID_DEPENDENCY_CONTRACT,
                create(external(), position = 2).failure(),
            )
            for (digest in listOf("", "a".repeat(63), "A".repeat(64), "g".repeat(64))) assertEquals(
                CallbackInvocationFlowFailure.INVALID_DEPENDENCY_CONTRACT,
                DependencyClassDigest.parse(digest).failure(),
            )
            assertEquals(
                CallbackInvocationFlowFailure.INVOCATION_OUTSIDE_OWNER,
                create(external(), occurrence = occurrence(200, 201)).failure(),
            )
        }

    @Test
    fun `contract cannot erase source containment scan or nested activation obligations`(): Unit =
        with(fixture) {
            assertEquals(
                CallbackInvocationFlowFailure.BODY_OUTSIDE_ARGUMENT,
                flow(contract(), callbackBody = anonymous(100, 110)).failure(),
            )
            assertEquals(
                CallbackInvocationFlowFailure.INVALID_SCAN_PROOF,
                flow(contract(), scan = CallbackInvocationScan.INCOMPLETE).failure(),
            )
            assertEquals(
                CallbackInvocationFlowFailure.UNBOUND_INVOCATION,
                flow(contract(), invocations = listOf(nested)).failure(),
            )
            val nestedContract = create(external(), occurrence = occurrence(32, 55), owner = body).value()
            assertEquals(
                CallbackInvocationFlowFailure.MISSING_OBLIGATION,
                flow(nestedContract, callbackBody = anonymous(35, 50)).failure(),
            )
            assertEquals(
                setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                flow(
                        nestedContract,
                        callbackBody = anonymous(35, 50),
                        obligations = setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                    )
                    .value()
                    .obligations,
            )
        }

    private fun CallbackInvocationFlowFixture.external(): CompilerGroundedSymbolEvidence =
        CompilerGroundedSymbolEvidence.fromBoundary(
                SymbolDiscoveryFileIdentity.External(
                    DetachedVirtualFileUrl.parse("jar:///library.jar!/fixture/Boundary.class").value()
                ),
                210,
                400,
                target.evidence.name.value,
                "fixture.nativeBoundary",
                target.evidence.kind,
                target.evidence.signature,
            )
            .value()

    private fun CallbackInvocationFlowFixture.create(
        target: CompilerGroundedSymbolEvidence,
        position: Int = 1,
        occurrence: RelationOccurrence = occurrence(20, 80),
        owner: RelationCallableBody = supplyingOwner,
    ) =
        CallbackDependencyContract.fromCompiler(
            lease.identity,
            occurrence,
            owner,
            target,
            ValueArgumentPosition.parse(position).value(),
            DependencyClassDigest.parse("ab".repeat(32)).value(),
            CallbackDependencyContractProvenance.KOTLIN_BINARY_CONTRACT,
            CallbackDependencyInvocationKind.EXACTLY_ONCE,
        )

    private fun CallbackInvocationFlowFixture.contract() = create(external()).value()

    private fun CallbackInvocationFlowFixture.flow(
        contract: CallbackDependencyContract,
        callbackBody: RelationCallableBody.Anonymous = body,
        scan: CallbackInvocationScan = CallbackInvocationScan.EXHAUSTIVE,
        invocations: List<CallbackParameterInvocation> = emptyList(),
        obligations: Set<CallbackInvocationFlowCause> = emptySet(),
    ) =
        CallbackInvocationFlow.fromCompiler(
            lease.identity,
            callbackBody,
            CallbackBindingEvidence.DependencyContract(contract),
            invocations,
            obligations,
            scan,
        )
}
