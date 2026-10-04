package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
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
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ValueSiteRevalidationContractTest {
    @Test
    fun `site proof preserves exact role and refuses same signature in another source file`() {
        val owner = endpoint("owner", 0, 200)
        val request = request(owner, ValueSiteRoleClaim.Return)
        val site = ValueSite.fromCompiler(owner, range(30, 35), ValueRole.Return).refined()
        assertSame(site, RevalidatedValueSite.fromCompiler(request, site).refined().site)
        val other = endpoint("owner", 0, 200, fileName = "Other.kt")
        assertEquals(owner.compilerIdentity, other.compilerIdentity)
        assertEquals(
            ValueSiteRevalidationFailure.ENCLOSING_MISMATCH,
            RevalidatedValueSite.fromCompiler(request, ValueSite.fromCompiler(other, site.range, site.role).refined())
                .failure(),
        )
        assertEquals(
            ValueSiteRevalidationFailure.ROLE_MISMATCH,
            RevalidatedValueSite.fromCompiler(
                    request,
                    ValueSite.fromCompiler(owner, site.range, ValueRole.ExpressionResult).refined(),
                )
                .failure(),
        )
        val stale = endpoint("owner", 0, 200, generation = 2)
        assertEquals(
            ValueSiteRevalidationFailure.BASIS_MISMATCH,
            RevalidatedValueSite.fromCompiler(request, ValueSite.fromCompiler(stale, site.range, site.role).refined())
                .failure(),
        )
    }

    @Test
    fun `argument proof retains whole invocation exact callable and native formal parameter`() {
        val owner = endpoint("owner", 0, 200)
        val callable = endpoint("submit", 210, 250, parameters = listOf("String", "String"))
        val zero = ValueArgumentPosition.parse(0).refined()
        val claim = ValueSiteRoleClaim.Argument(range(20, 50), selector(callable), zero)
        val request = request(owner, claim)
        val invocation = ValueInvocation.fromCompiler(owner, claim.invocationAnchor, callable).refined()
        val argument = ValueSite.fromCompiler(owner, request.anchor, ValueRole.Argument(invocation, zero)).refined()
        assertSame(argument, RevalidatedValueSite.fromCompiler(request, argument).refined().site)
        val anotherCall = ValueInvocation.fromCompiler(owner, range(25, 55), callable).refined()
        assertEquals(
            ValueSiteRevalidationFailure.INVOCATION_MISMATCH,
            RevalidatedValueSite.fromCompiler(
                    request,
                    ValueSite.fromCompiler(owner, request.anchor, ValueRole.Argument(anotherCall, zero)).refined(),
                )
                .failure(),
        )
        val otherCallable = endpoint("submit", 210, 250, parameters = listOf("String", "String"), fileName = "Other.kt")
        val otherInvocation = ValueInvocation.fromCompiler(owner, claim.invocationAnchor, otherCallable).refined()
        assertEquals(
            ValueSiteRevalidationFailure.CALLABLE_MISMATCH,
            RevalidatedValueSite.fromCompiler(
                    request,
                    ValueSite.fromCompiler(owner, request.anchor, ValueRole.Argument(otherInvocation, zero)).refined(),
                )
                .failure(),
        )
        val one = ValueArgumentPosition.parse(1).refined()
        assertEquals(
            ValueSiteRevalidationFailure.ARGUMENT_POSITION_MISMATCH,
            RevalidatedValueSite.fromCompiler(
                    request,
                    ValueSite.fromCompiler(owner, request.anchor, ValueRole.Argument(invocation, one)).refined(),
                )
                .failure(),
        )
    }

    private fun request(owner: RelationEndpoint.Resolved, role: ValueSiteRoleClaim) =
        ValueSiteRevalidationRequest.create(
                selector(owner),
                range(30, 35),
                role,
                RelationBudget(
                    ResourceBudget(
                        ResultLimit.parse(20).refined(),
                        WorkUnitLimit.parse(10).refined(),
                        ElapsedTimeLimitMillis.parse(1000).refined(),
                    ),
                    RelationByteLimit.parse(100000).refined(),
                ),
            )
            .refined()

    private fun selector(endpoint: RelationEndpoint.Resolved) =
        SymbolSelector.issue(endpoint.lease, endpoint.scope, endpoint.evidence)

    private fun endpoint(
        name: String,
        start: Int,
        end: Int,
        parameters: List<String> = emptyList(),
        generation: Long = 1,
        fileName: String = "Fixture.kt",
    ): RelationEndpoint.Resolved {
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
            .refined() as RelationEndpoint.Resolved
    }

    private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).refined()

    private fun <V, F> Refinement<V, F>.refined(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }

    private fun <V, F> Refinement<V, F>.failure(): F =
        when (this) {
            is Refinement.Refined -> error("expected rejection")
            is Refinement.Rejected -> failure
        }
}
