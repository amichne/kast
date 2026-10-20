package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ValueExecutionOwnerContractTest {
    @Test
    fun `qualified selector proof preserves one canonical invocation and rejects another selected range`() {
        val host = endpoint("investigate", 0, 200) as RelationEndpoint.Resolved
        val callable = endpoint("submit", 210, 250) as RelationEndpoint.Resolved
        val invocation = ValueInvocation.fromCompiler(host, range(20, 90), callable).refined()
        val selection = ValueInvocationSelection.fromCompiler(invocation, range(40, 90)).refined()
        val request =
            ValueProducerSeedRequest.create(
                    SymbolSelector.issue(host.lease, host.scope, host.evidence),
                    selection.selectedRange,
                    SymbolSelector.issue(callable.lease, callable.scope, callable.evidence),
                    budget(),
                    RelationSearchBoundary.WORKSPACE_EXPANSION,
                )
                .refined()
        val admitted = ValueProducerSeed.fromCompiler(request, invocation.resultSite(), invocation, selection).refined()
        assertEquals(ValueInvocationSelectionKind.QUALIFIED_SELECTOR, admitted.selection.kind)
        assertEquals(invocation.range, admitted.site.range)
        assertEquals(invocation.identity, admitted.invocation.identity)
        assertEquals(
            ValueProducerSeedFailure.ANCHOR_MISMATCH,
            ValueProducerSeed.fromCompiler(request, invocation.resultSite(), invocation).failure(),
        )
        assertEquals(
            ValueProducerSeedFailure.ANCHOR_MISMATCH,
            ValueInvocationSelection.fromCompiler(invocation, range(40, 80)).failure(),
        )
        val other = ValueInvocation.fromCompiler(host, range(100, 150), callable).refined()
        assertEquals(
            ValueProducerSeedFailure.ANCHOR_MISMATCH,
            ValueProducerSeed.fromCompiler(request, other.resultSite(), other, selection).failure(),
        )
    }

    private fun budget() =
        RelationBudget(
            io.github.amichne.kast.kernel.ResourceBudget(
                io.github.amichne.kast.kernel.ResultLimit.parse(32).refined(),
                io.github.amichne.kast.kernel.WorkUnitLimit.parse(128).refined(),
                io.github.amichne.kast.kernel.ElapsedTimeLimitMillis.parse(1_000).refined(),
            ),
            RelationByteLimit.parse(100_000).refined(),
        )

    @Test
    fun `anonymous execution preserves the selected host and the exact receiver identity`() {
        val host = endpoint("investigate", 0, 200)
        val execution = execution(host)
        val callable = endpoint("submit", 210, 250, parameters = listOf("String"))
        val call = ValueInvocation.fromCompiler(host, range(20, 50), callable, execution).refined()
        assertSame(host, call.enclosing)
        assertEquals(execution, call.resultSite().executionOwner)
        assertNotEquals(call.identity, ValueInvocation.fromCompiler(host, call.range, callable).refined().identity)
        val value = ValueSite.fromCompiler(host, range(25, 30), ValueRole.ExpressionResult, execution).refined()
        val receiver =
            ValueSite.fromCompiler(host, value.range, ValueRole.Receiver(call, ValueReceiverKind.DISPATCH), execution)
                .refined()
        assertEquals(execution.identity, receiver.identity.execution)
        assertEquals(
            ValueTransferKind.RECEIVER,
            ValueTransfer.fromCompiler(value, receiver, ValueTransferKind.RECEIVER).refined().kind,
        )
        assertEquals(
            ValueSiteFailure.INVOCATION_OWNER_MISMATCH,
            ValueSite.fromCompiler(host, value.range, ValueRole.Receiver(call, ValueReceiverKind.DISPATCH)).failure(),
        )
        val target = ValueSite.fromCompiler(host, range(10, 180), ValueRole.ExpressionResult).refined()
        val branch =
            ValueTransferEvidence.NormalBranchResult.fromCompiler(
                    target.range,
                    range(15, 150),
                    ValueTryBranchAlternative.TryBody,
                )
                .refined()
        assertEquals(
            ValueTransferFailure.EVIDENCE_MISMATCH,
            ValueTransfer.fromCompiler(value, target, ValueTransferKind.BRANCH_ALTERNATIVE, branch).failure(),
        )
    }

    @Test
    fun `anonymous execution rejects a different host file basis or body range`() {
        val host = endpoint("investigate", 0, 200)
        val body = (execution(host).body as ValueExecutionBody.Anonymous).callable
        val cases =
            listOf(
                endpoint("investigate", 0, 200, generation = 2) to ValueExecutionOwnerFailure.BASIS_MISMATCH,
                endpoint("investigate", 0, 200, fileName = "Other.kt") to ValueExecutionOwnerFailure.FILE_MISMATCH,
                endpoint("investigate", 0, 100) to ValueExecutionOwnerFailure.BODY_OUTSIDE_HOST,
            )
        for ((candidate, expected) in cases) {
            assertEquals(expected, ValueExecutionOwner.fromCompiler(candidate, body, host.lease.identity).failure())
        }
    }

    @Test
    fun `value positions reject another execution body or lexical host`() {
        val host = endpoint("investigate", 0, 200)
        val execution = execution(host)
        val callable = endpoint("submit", 210, 250)
        assertEquals(
            ValueSiteFailure.ANCHOR_OUTSIDE_EXECUTION_OWNER,
            ValueSite.fromCompiler(host, range(160, 170), ValueRole.ExpressionResult, execution).failure(),
        )
        assertEquals(
            ValueInvocationFailure.ANCHOR_OUTSIDE_EXECUTION_OWNER,
            ValueInvocation.fromCompiler(host, range(160, 170), callable, execution).failure(),
        )
        assertEquals(
            ValueInvocationFailure.EXECUTION_OWNER_MISMATCH,
            ValueInvocation.fromCompiler(endpoint("different", 0, 200), range(20, 50), callable, execution).failure(),
        )
    }

    private fun execution(host: RelationEndpoint): ValueExecutionOwner {
        val bodyRange = range(15, 150)
        val signature =
            CanonicalCompilerSignature.function(
                    RelationCallableBody.Anonymous.sourceIdentity(host.file, bodyRange),
                    null,
                    emptyList(),
                    emptyList(),
                    0,
                )
                .refined() as CanonicalCompilerSignature.Function
        val body = RelationCallableBody.Anonymous.fromCompiler(host.file, bodyRange, signature).refined()
        return ValueExecutionOwner.fromCompiler(host, body, host.lease.identity).refined()
    }
}

private fun endpoint(
    name: String,
    start: Int,
    end: Int,
    parameters: List<String> = emptyList(),
    generation: Long = 1,
    fileName: String = "Fixture.kt",
): RelationEndpoint {
    val lease =
        SemanticReadLease(
            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/fixture")).refined(),
            EvidenceGeneration.parse(generation).refined(),
        )
    val file =
        (SymbolDiscoveryCandidate.fromBoundary(
                    SymbolDiscoveryKind.SYMBOL,
                    name,
                    lease,
                    Path.of("/fixture/$fileName"),
                    "file:///fixture/$fileName",
                    start,
                )
                .refined()
                .location as SymbolDiscoveryCandidateLocation.Declaration)
            .file
    val evidence =
        CompilerGroundedSymbolEvidence.fromBoundary(
                file,
                start,
                end,
                name,
                "fixture.$name",
                CompilerSymbolKind.FUNCTION,
                CanonicalCompilerSignature.function("fixture.$name", null, emptyList(), parameters, 0).refined(),
            )
            .refined()
    return RelationEndpoint.resolve(
            lease,
            SymbolSearchScope.Workspace(
                SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                SymbolGeneratedSourcePolicy.EXCLUDE,
                SymbolLibraryPolicy.EXCLUDE,
            ),
            evidence,
        )
        .refined()
}

private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).refined()

private fun <V, F> Refinement<V, F>.refined(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }

private fun <V, F> Refinement<V, F>.failure(): F =
    when (this) {
        is Refinement.Refined -> error("Expected rejection")
        is Refinement.Rejected -> failure
    }
