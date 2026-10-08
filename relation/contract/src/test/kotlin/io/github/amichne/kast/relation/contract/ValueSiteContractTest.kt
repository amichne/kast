package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
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

class ValueSiteContractTest {
    @Test
    fun `normal branch result preserves exact conditional provenance and cannot become a catch fallback`() {
        val owner = endpoint("investigate", 0, 200)
        val source = ValueSite.fromCompiler(owner, range(20, 35), ValueRole.ExpressionResult).refined()
        val target = ValueSite.fromCompiler(owner, range(10, 180), ValueRole.ExpressionResult).refined()
        val proof =
            ValueTransferEvidence.NormalBranchResult.fromCompiler(
                    target.range,
                    range(15, 70),
                    ValueTryBranchAlternative.TryBody,
                )
                .refined()
        val edge = ValueTransfer.fromCompiler(source, target, ValueTransferKind.BRANCH_ALTERNATIVE, proof).refined()
        assertSame(proof, edge.evidence)
        val fallback =
            ValueTransferEvidence.NormalBranchResult.fromCompiler(
                    target.range,
                    range(90, 180),
                    ValueTryBranchAlternative.CatchBody(ValueCatchBranchIndex.parse(0).refined()),
                )
                .refined()
        assertEquals(
            ValueTransferFailure.EVIDENCE_MISMATCH,
            ValueTransfer.fromCompiler(source, target, ValueTransferKind.BRANCH_ALTERNATIVE, fallback).failure(),
        )
        val obligation = ValueFlowObligation(source, ValueFlowUnsupportedCause.FINALLY_UNSUPPORTED)
        val step =
            ValueFlowStep.fromCompiler(
                    source,
                    listOf(edge),
                    listOf(obligation),
                    ValueFlowTerminal.Unresolved,
                    domain(source),
                    RelationWorkCount.parse(1).refined(),
                )
                .refined()
        val shared = step.shareCallableEvidence(emptyList())
        assertEquals(listOf(edge), shared.transfers)
        assertEquals(listOf(obligation), shared.obligations)
        assertSame(proof, shared.transfers.single().evidence)
        val direct = ValueTransfer.fromCompiler(source, target, ValueTransferKind.BRANCH_ALTERNATIVE).refined()
        assertEquals(
            4096L,
            ValueFlowStep.detachedByteCount(source, domain(source), listOf(edge), emptyList()).value -
                ValueFlowStep.detachedByteCount(source, domain(source), listOf(direct), emptyList()).value,
        )
    }

    @Test
    fun `normal branch anchors and catch index reject invalid ranges`() {
        val target =
            ValueSite.fromCompiler(endpoint("investigate", 0, 200), range(10, 180), ValueRole.ExpressionResult)
                .refined()
        assertEquals(
            ValueTransferFailure.EVIDENCE_MISMATCH,
            ValueTransferEvidence.NormalBranchResult.fromCompiler(
                    target.range,
                    range(5, 70),
                    ValueTryBranchAlternative.TryBody,
                )
                .failure(),
        )
        assertEquals(ValueTransferFailure.EVIDENCE_MISMATCH, ValueCatchBranchIndex.parse(1024).failure())
    }

    @Test
    fun `callable sharing preserves invocation identity and excludes different scope file and authority`() {
        val original = endpoint("submit", 210, 250, parameters = listOf("String")) as RelationEndpoint.Resolved
        val copies =
            listOf(
                endpoint("submit", 210, 250, parameters = listOf("String")),
                endpoint("submit", 210, 250, parameters = listOf("String"), fileName = "Other.kt"),
                endpoint("submit", 210, 250, parameters = listOf("String"), generation = 2),
                RelationEndpoint.resolve(
                        original.lease,
                        SymbolSearchScope.ExactFile(
                            (original.file
                                    as io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity.Workspace)
                                .path,
                            SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                            SymbolGeneratedSourcePolicy.EXCLUDE,
                        ),
                        original.evidence,
                    )
                    .refined(),
            )
        for ((index, candidate) in copies.withIndex()) {
            val owner = endpoint("investigate", 0, 200, generation = if (index == 2) 2 else 1)
            val invocation = ValueInvocation.fromCompiler(owner, range(20, 50), candidate).refined()
            val pool = linkedMapOf(original to original)
            val shared = invocation.shareCallableEvidence(pool)
            assertEquals(invocation.identity, shared.identity)
            assertSame(owner, shared.enclosing)
            assertSame(invocation.range, shared.range)
            assertSame(if (index == 0) original else candidate, shared.callable)
            assertSame(owner.valueIdentity, shared.identity.owner)
            assertSame(shared.callable.valueIdentity, shared.identity.callable)
        }
    }

    @Test
    fun `producer proof retains exact selected invocation and rejects same signature from another file`() {
        val owner = endpoint("investigate", 0, 200) as RelationEndpoint.Resolved
        val callable = endpoint("encrypt", 210, 250) as RelationEndpoint.Resolved
        val request =
            ValueProducerSeedRequest.create(
                    SymbolSelector.issue(owner.lease, owner.scope, owner.evidence),
                    range(20, 35),
                    SymbolSelector.issue(callable.lease, callable.scope, callable.evidence),
                    domain(ValueSite.fromCompiler(owner, range(20, 35), ValueRole.ExpressionResult).refined()).budget,
                    RelationSearchBoundary.WORKSPACE_EXPANSION,
                )
                .refined()
        val call = ValueInvocation.fromCompiler(owner, range(20, 35), callable).refined()
        val site = ValueSite.fromCompiler(owner, range(20, 35), ValueRole.ExpressionResult).refined()
        val seed = ValueProducerSeed.fromCompiler(request, site, call).refined()
        assertSame(call, seed.invocation)
        assertSame(site, seed.site)
        val other = endpoint("encrypt", 210, 250, fileName = "Other.kt")
        assertEquals(callable.compilerIdentity, other.compilerIdentity)
        assertEquals(
            ValueProducerSeedFailure.CALLABLE_MISMATCH,
            ValueProducerSeed.fromCompiler(
                    request,
                    site,
                    ValueInvocation.fromCompiler(owner, call.range, other).refined(),
                )
                .failure(),
        )
        val second = ValueInvocation.fromCompiler(owner, range(60, 75), callable).refined()
        val secondSite = ValueSite.fromCompiler(owner, second.range, ValueRole.ExpressionResult).refined()
        assertEquals(
            ValueProducerSeedFailure.ANCHOR_MISMATCH,
            ValueProducerSeed.fromCompiler(request, secondSite, second).failure(),
        )
        val binding = ValueSite.fromCompiler(owner, request.anchor, ValueRole.LocalBinding).refined()
        assertEquals(
            ValueProducerSeedFailure.ROLE_MISMATCH,
            ValueProducerSeed.fromCompiler(request, binding, call).failure(),
        )
    }

    @Test
    fun `transparent wrapper return remains bound to its actual call and formal argument`() {
        val owner = endpoint("investigate", 0, 200)
        val wrapper = endpoint("wrapper", 210, 250, parameters = listOf("String"))
        val invocation = ValueInvocation.fromCompiler(owner, range(20, 50), wrapper).refined()
        val other = ValueInvocation.fromCompiler(owner, range(60, 90), wrapper).refined()
        val argument =
            ValueSite.fromCompiler(
                    owner,
                    range(30, 35),
                    ValueRole.Argument(invocation, ValueArgumentPosition.parse(0).refined()),
                )
                .refined()
        val transfer =
            ValueTransfer.fromCompiler(argument, invocation.resultSite(), ValueTransferKind.WRAPPER_RETURN).refined()
        assertEquals(invocation.range, transfer.target.range)
        assertEquals(
            ValueTransferFailure.ROLE_MISMATCH,
            ValueTransfer.fromCompiler(argument, other.resultSite(), ValueTransferKind.WRAPPER_RETURN).failure(),
        )
        val unprovenReturn = ValueSite.fromCompiler(owner, range(100, 110), ValueRole.Return).refined()
        assertEquals(
            ValueTransferFailure.ROLE_MISMATCH,
            ValueTransfer.fromCompiler(unprovenReturn, invocation.resultSite(), ValueTransferKind.WRAPPER_RETURN)
                .failure(),
        )
    }

    @Test
    fun `search domain changes coverage without changing value identity`() {
        val broad = endpoint("investigate", 0, 200)
        val narrow =
            RelationEndpoint.resolve(
                    broad.lease,
                    SymbolSearchScope.ExactFile(
                        CanonicalWorkspaceFilePath.fromCanonicalPath(
                                broad.lease.workspaceRoot,
                                Path.of("/fixture/Fixture.kt"),
                            )
                            .refined(),
                        SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                        SymbolGeneratedSourcePolicy.EXCLUDE,
                    ),
                    (broad as RelationEndpoint.Resolved).evidence,
                )
                .refined()
        assertNotEquals(broad.fingerprint, narrow.fingerprint)
        val first = ValueSite.fromCompiler(broad, range(20, 35), ValueRole.ExpressionResult).refined()
        val same = ValueSite.fromCompiler(narrow, range(20, 35), ValueRole.ExpressionResult).refined()
        assertEquals(first.identity, same.identity)
    }

    @Test
    fun `same callable different invocations remain distinct values`() {
        val owner = endpoint("investigate", 0, 200)
        val encrypt = endpoint("encrypt", 210, 250, parameters = listOf("String"))
        val first = ValueInvocation.fromCompiler(owner, range(20, 35), encrypt).refined()
        val second = ValueInvocation.fromCompiler(owner, range(60, 75), encrypt).refined()
        assertNotEquals(first.identity, second.identity)
        val firstResult = ValueSite.fromCompiler(owner, first.range, ValueRole.ExpressionResult).refined()
        val secondResult = ValueSite.fromCompiler(owner, second.range, ValueRole.ExpressionResult).refined()
        assertNotEquals(firstResult.identity, secondResult.identity)
        assertEquals(firstResult.basis, secondResult.basis)
    }

    @Test
    fun `argument role preserves call and formal parameter position`() {
        val owner = endpoint("investigate", 0, 200)
        val submit = endpoint("submit", 210, 250, parameters = listOf("String", "String"))
        val call = ValueInvocation.fromCompiler(owner, range(20, 50), submit).refined()
        val zero = ValueArgumentPosition.parse(0).refined()
        val one = ValueArgumentPosition.parse(1).refined()
        val first = ValueSite.fromCompiler(owner, range(30, 35), ValueRole.Argument(call, zero)).refined()
        val second = ValueSite.fromCompiler(owner, range(30, 35), ValueRole.Argument(call, one)).refined()
        assertNotEquals(first.identity, second.identity)
        assertEquals(
            ValueSiteFailure.INVALID_ARGUMENT_POSITION,
            ValueSite.fromCompiler(
                    owner,
                    range(30, 35),
                    ValueRole.Argument(call, ValueArgumentPosition.parse(2).refined()),
                )
                .failure(),
        )
        assertEquals(
            ValueSiteFailure.ARGUMENT_OUTSIDE_INVOCATION,
            ValueSite.fromCompiler(owner, range(60, 65), ValueRole.Argument(call, zero)).failure(),
        )
    }

    @Test
    fun `source anchor cannot escape exact owner or current basis`() {
        val owner = endpoint("investigate", 0, 200)
        assertEquals(
            ValueSiteFailure.ANCHOR_OUTSIDE_OWNER,
            ValueSite.fromCompiler(owner, range(200, 210), ValueRole.ExpressionResult).failure(),
        )
        val stale = endpoint("encrypt", 210, 250, generation = 2)
        assertEquals(
            ValueInvocationFailure.BASIS_MISMATCH,
            ValueInvocation.fromCompiler(owner, range(20, 35), stale).failure(),
        )
    }
}

class ValueFlowStepContractTest {
    @Test
    fun `read sharing keeps every obligation and its current domain and work evidence`() {
        val owner = endpoint("investigate", 0, 200)
        fun read(callable: RelationEndpoint, start: Int): ValueFlowStep {
            val invocation = ValueInvocation.fromCompiler(owner, range(start, start + 20), callable).refined()
            val site =
                ValueSite.fromCompiler(
                        owner,
                        range(start + 5, start + 10),
                        ValueRole.Argument(invocation, ValueArgumentPosition.parse(0).refined()),
                    )
                    .refined()
            return ValueFlowStep.fromCompiler(
                    site,
                    emptyList(),
                    listOf(ValueFlowObligation(site, ValueFlowUnsupportedCause.UNMODELED_CALL)),
                    ValueFlowTerminal.Unresolved,
                    domain(site),
                    RelationWorkCount.parse(2).refined(),
                )
                .refined()
        }
        val first = read(endpoint("submit", 210, 250, parameters = listOf("String")), 20)
        val second = read(endpoint("submit", 210, 250, parameters = listOf("String")), 60)
        val shared = second.shareCallableEvidence(listOf(first))
        assertSame(
            (first.source.role as ValueRole.Argument).call.callable,
            (shared.source.role as ValueRole.Argument).call.callable,
        )
        assertEquals(second.source.identity, shared.source.identity)
        assertEquals(second.obligations, shared.obligations)
        assertSame(second.domain, shared.domain)
        assertEquals(second.examinedWorkUnits, shared.examinedWorkUnits)
        assertEquals(second.terminal, shared.terminal)
    }

    @Test
    fun `unsupported arrivals cannot be constructed as exhausted supported flow`() {
        val owner = endpoint("investigate", 0, 200)
        val source = ValueSite.fromCompiler(owner, range(20, 35), ValueRole.ExpressionResult).refined()
        val obligation = ValueFlowObligation(source, ValueFlowUnsupportedCause.UNMODELED_CALL)
        assertEquals(
            ValueFlowStepFailure.UNRESOLVED_OBLIGATIONS,
            ValueFlowStep.fromCompiler(
                    source,
                    emptyList(),
                    listOf(obligation),
                    ValueFlowTerminal.SupportedDomainExhausted,
                    domain(source),
                    RelationWorkCount.parse(1).refined(),
                )
                .failure(),
        )
        assertEquals(
            ValueFlowStepFailure.MISSING_OBLIGATION,
            ValueFlowStep.fromCompiler(
                    source,
                    emptyList(),
                    emptyList(),
                    ValueFlowTerminal.Unresolved,
                    domain(source),
                    RelationWorkCount.parse(1).refined(),
                )
                .failure(),
        )
        val arrivals = mutableListOf(obligation)
        val observed =
            ValueFlowStep.fromCompiler(
                    source,
                    emptyList(),
                    arrivals,
                    ValueFlowTerminal.Unresolved,
                    domain(source),
                    RelationWorkCount.parse(1).refined(),
                )
                .refined()
        arrivals.clear()
        assertEquals(listOf(obligation), observed.obligations)
    }

    @Test
    fun `transfer retains exact source and rejects changing its basis or role`() {
        val owner = endpoint("investigate", 0, 200)
        val source = ValueSite.fromCompiler(owner, range(20, 35), ValueRole.ExpressionResult).refined()
        val binding = ValueSite.fromCompiler(owner, range(10, 35), ValueRole.LocalBinding).refined()
        val edge = ValueTransfer.fromCompiler(source, binding, ValueTransferKind.LOCAL_BINDING).refined()
        assertSame(source, edge.source)
        assertEquals(
            ValueTransferFailure.ROLE_MISMATCH,
            ValueTransfer.fromCompiler(source, binding, ValueTransferKind.ARGUMENT).failure(),
        )
        val staleOwner = endpoint("investigate", 0, 200, generation = 2)
        val stale = ValueSite.fromCompiler(staleOwner, range(10, 35), ValueRole.LocalBinding).refined()
        assertEquals(
            ValueTransferFailure.BASIS_MISMATCH,
            ValueTransfer.fromCompiler(source, stale, ValueTransferKind.LOCAL_BINDING).failure(),
        )
        val other = ValueSite.fromCompiler(owner, range(60, 75), ValueRole.ExpressionResult).refined()
        assertEquals(
            ValueFlowStepFailure.SOURCE_MISMATCH,
            ValueFlowStep.fromCompiler(
                    other,
                    listOf(edge),
                    emptyList(),
                    ValueFlowTerminal.SupportedDomainExhausted,
                    domain(other),
                    RelationWorkCount.parse(1).refined(),
                )
                .failure(),
        )
    }

    @Test
    fun `native step cannot reuse another enclosing declaration domain`() {
        val owner = endpoint("investigate", 0, 200)
        val source = ValueSite.fromCompiler(owner, range(20, 35), ValueRole.ExpressionResult).refined()
        val otherOwner = endpoint("other", 210, 300)
        val other = ValueSite.fromCompiler(otherOwner, range(220, 235), ValueRole.ExpressionResult).refined()
        assertEquals(
            ValueFlowStepFailure.DOMAIN_MISMATCH,
            ValueFlowStep.fromCompiler(
                    source,
                    emptyList(),
                    emptyList(),
                    ValueFlowTerminal.SupportedDomainExhausted,
                    domain(other),
                    RelationWorkCount.parse(1).refined(),
                )
                .failure(),
        )
    }

    @Test
    fun `native work cannot exceed its admitted grant`() {
        val source =
            ValueSite.fromCompiler(endpoint("investigate", 0, 200), range(20, 35), ValueRole.ExpressionResult).refined()
        assertEquals(
            ValueFlowStepFailure.WORK_LIMIT_EXCEEDED,
            ValueFlowStep.fromCompiler(
                    source,
                    emptyList(),
                    emptyList(),
                    ValueFlowTerminal.SupportedDomainExhausted,
                    domain(source),
                    RelationWorkCount.parse(129).refined(),
                )
                .failure(),
        )
    }

    @Test
    fun `native retained evidence cannot escape detached capacity`() {
        val source =
            ValueSite.fromCompiler(endpoint("investigate", 0, 200), range(20, 35), ValueRole.ExpressionResult).refined()
        val original = domain(source)
        val tiny =
            RelationRequest.start(
                (original.subject as RelationEndpoint.Subject).selector,
                original.meaning,
                original.budget.copy(returnedBytes = RelationByteLimit.parse(1).refined()),
                original.boundary,
            )
        assertEquals(
            ValueFlowStepFailure.DETACHED_CAPACITY_EXCEEDED,
            ValueFlowStep.fromCompiler(
                    source,
                    emptyList(),
                    emptyList(),
                    ValueFlowTerminal.SupportedDomainExhausted,
                    tiny,
                    RelationWorkCount.parse(1).refined(),
                )
                .failure(),
        )
    }
}

private fun domain(site: ValueSite): RelationRequest {
    val endpoint = site.enclosing as RelationEndpoint.Resolved
    val selector = SymbolSelector.issue(endpoint.lease, endpoint.scope, endpoint.evidence)
    val budget =
        RelationBudget(
            io.github.amichne.kast.kernel.ResourceBudget(
                io.github.amichne.kast.kernel.ResultLimit.parse(32).refined(),
                io.github.amichne.kast.kernel.WorkUnitLimit.parse(128).refined(),
                io.github.amichne.kast.kernel.ElapsedTimeLimitMillis.parse(1_000).refined(),
            ),
            RelationByteLimit.parse(100_000).refined(),
        )
    return RelationRequest.start(
        selector,
        RelationMeaning.References,
        budget,
        RelationSearchBoundary.WORKSPACE_EXPANSION,
    )
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
