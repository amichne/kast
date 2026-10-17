package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/** Detached facts exercise result identity and exact graph membership, without a native membership claim. */
class CallbackResultProjectionTest {
    private val fixture = CallbackInvocationFlowFixture()
    private val budget =
        RelationBudget(
            ResourceBudget(
                ResultLimit.parse(8).refined(),
                WorkUnitLimit.parse(32).refined(),
                ElapsedTimeLimitMillis.parse(1000).refined(),
            ),
            RelationByteLimit.parse(100_000).refined(),
        )
    private val request = RelationRequest.start(fixture.caller, RelationMeaning.Callees, budget)
    private val occurrence = fixture.occurrence(40, 45)

    @Test
    fun `the selected target witness stays attached to the original result frame`() {
        val projection = admitted(request, CallbackResultSite.Target(fixture.target.evidence))
        val result = observation(request).projectResult(request, projection).refined()
        assertEquals(projection, result.resultProjection)
        assertEquals(request.scopeFingerprint, projection.membership.domain)
        assertEquals(request.subject.fingerprint, projection.membership.subject)
        assertEquals(request.subject.lease, projection.membership.basis)
    }

    @Test
    fun `a foreign domain authority or subject cannot qualify an identical target site`() {
        val expanded =
            RelationRequest.start(
                fixture.caller,
                RelationMeaning.Callees,
                budget,
                RelationSearchBoundary.Explicit(
                    (fixture.caller.scope as SymbolSearchScope.Workspace).copy(
                        generatedSources = SymbolGeneratedSourcePolicy.INCLUDE
                    )
                ),
            )
        val moved = fixture.lease.copy(generation = EvidenceGeneration.parse(2).refined())
        val current = RelationEndpoint.resolve(moved, fixture.caller.scope, fixture.caller.evidence).refined()
        val otherAuthority = RelationRequest.start(current, RelationMeaning.Callees, budget)
        val otherSubject =
            RelationRequest.start(fixture.endpoint("other", 410, 500, emptyList()), RelationMeaning.Callees, budget)
        assertNotEquals(request.scopeFingerprint, expanded.scopeFingerprint)
        assertEquals(request.scopeFingerprint, otherAuthority.scopeFingerprint)
        for (foreign in listOf(expanded, otherAuthority, otherSubject)) {
            val projection = admitted(foreign, CallbackResultSite.Target(fixture.target.evidence))
            assertEquals(
                Refinement.Rejected(RelationCallbackObservationFailure.RESULT_PROJECTION_MISMATCH),
                observation(request).projectResult(request, projection),
            )
        }
    }

    @Test
    fun `an owned declaration absent from the callback graph cannot qualify its result`() {
        val different = fixture.endpoint("differentTarget", 410, 500, emptyList())
        assertEquals(
            Refinement.Rejected(RelationCallbackObservationFailure.RESULT_PROJECTION_MISMATCH),
            observation(request)
                .projectResult(request, admitted(request, CallbackResultSite.Target(different.evidence))),
        )
    }

    @Test
    fun `a caller witness identifies the exact retained invocation rather than a nearby site`() {
        val callers = RelationRequest.start(fixture.target, RelationMeaning.Callers, budget)
        val flow =
            CallbackInvocationFlowRead.Observed(
                fixture.observed(listOf(fixture.nested), setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION))
            )
        val raw = observation(callers, flow)
        val valid = admitted(callers, CallbackResultSite.Invocation(fixture.nested))
        assertEquals(valid, raw.projectResult(callers, valid).refined().resultProjection)
        val nearby =
            CallbackParameterInvocation.fromCompiler(fixture.occurrence(276, 281), fixture.nested.owner).refined()
        assertEquals(
            Refinement.Rejected(RelationCallbackObservationFailure.RESULT_PROJECTION_MISMATCH),
            raw.projectResult(callers, admitted(callers, CallbackResultSite.Invocation(nearby))),
        )
    }

    @Test
    fun `result direction excludes incompatible witness roles`() {
        assertEquals(
            Refinement.Rejected(CallbackResultMembershipFailure.MEANING_MISMATCH),
            CallbackResultMembership.fromNativeScope(request, CallbackResultSite.Invocation(fixture.nested)),
        )
        val callers = RelationRequest.start(fixture.target, RelationMeaning.Callers, budget)
        assertEquals(
            Refinement.Rejected(CallbackResultMembershipFailure.MEANING_MISMATCH),
            CallbackResultMembership.fromNativeScope(callers, CallbackResultSite.Target(fixture.target.evidence)),
        )
    }

    @Test
    fun `source proof policy preserves the selected production and generated result filters`() {
        val selected =
            SymbolSearchScope.Workspace(
                SymbolSourceKindPolicy.PRODUCTION_ONLY,
                SymbolGeneratedSourcePolicy.EXCLUDE,
                SymbolLibraryPolicy.EXCLUDE,
            )
        val directory =
            SymbolDiscoveryDirectoryConstraint(
                SymbolDiscoveryDirectory.parse("client").refined(),
                SymbolDiscoveryContainment.DESCENDANTS,
            )
        val scoped =
            RelationRequest.start(
                fixture.caller,
                RelationMeaning.Callees,
                budget,
                RelationSearchBoundary.Explicit(selected, directory),
            )
        val evidence = CallbackEvidenceDomain.forRequest(scoped)
        assertEquals(SymbolSourceKindPolicy.PRODUCTION_AND_TEST, evidence.sources.sourceKinds)
        assertEquals(SymbolGeneratedSourcePolicy.INCLUDE, evidence.sources.generatedSources)
        assertEquals(SymbolLibraryPolicy.EXCLUDE, evidence.sources.libraries)
        assertEquals(SymbolDiscoveryConstraints.None, evidence.constraints)
        assertNotEquals(scoped.scopeFingerprint, evidence.fingerprint)
        assertEquals(selected, scoped.searchScope)
        assertEquals(directory, scoped.searchConstraints.directory)
        assertEquals(scoped.subject.lease, evidence.basis)
    }

    private fun observation(
        read: RelationRequest,
        flow: CallbackInvocationFlowRead =
            CallbackInvocationFlowRead.Unavailable(CallbackInvocationFlowCause.STORED_CALLBACK),
    ): RelationCallbackObservation =
        RelationCallbackObservation.fromNativeBoundary(
                request = read,
                occurrence = occurrence,
                target = fixture.target.evidence,
                lexicalOwner = fixture.caller.evidence,
                callbackBody = fixture.occurrence(30, 60),
                policy =
                    CallbackNamedCallPolicy.Excluded(
                        CallbackExclusionReason.NON_INLINE_ARGUMENT,
                        fixture.occurrence(30, 60),
                    ),
                flow = flow,
                resultProjection = CallbackResultProjection.Unproven(CallbackInvocationFlowCause.OUTSIDE_DOMAIN),
            )
            .refined()

    private fun admitted(read: RelationRequest, site: CallbackResultSite): CallbackResultProjection.Admitted =
        CallbackResultProjection.Admitted(CallbackResultMembership.fromNativeScope(read, site).refined())

    private fun <V, F> Refinement<V, F>.refined(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Invalid fixture input: $failure")
        }
}
