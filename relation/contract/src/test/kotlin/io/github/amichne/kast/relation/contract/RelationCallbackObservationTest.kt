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
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteCount
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RelationCallbackObservationTest {
    @Test
    fun `callback exclusion preserves occurrence lexical owner and callback boundary in both directions`() {
        for (meaning in listOf(RelationMeaning.Callers, RelationMeaning.Callees)) {
            val read = request(meaning)
            val endpoint = read.subject
            val evidence = callbackEvidence(endpoint)
            val callback = RelationOccurrence.fromBoundary(endpoint.file, 42, 49).refined()
            val occurrence = RelationOccurrence.fromBoundary(endpoint.file, 43, 44).refined()
            val exclusion =
                RelationCallbackObservation.fromNativeBoundary(
                        read,
                        occurrence,
                        evidence,
                        evidence,
                        callback,
                        CallbackNamedCallPolicy.Excluded(CallbackExclusionReason.NON_INLINE_ARGUMENT, callback),
                        CallbackInvocationFlowRead.Unavailable(
                            CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE
                        ),
                    )
                    .refined()
            assertEquals(occurrence, exclusion.occurrence)
            assertEquals(callback, exclusion.callbackBody)
            assertEquals(evidence, exclusion.lexicalOwner)
            assertEquals(evidence, exclusion.target)
            assertEquals(true, exclusion.belongsTo(read))
            assertEquals(
                Refinement.Rejected(RelationCallbackObservationFailure.OCCURRENCE_OUTSIDE_CALLBACK),
                RelationCallbackObservation.fromNativeBoundary(
                    read,
                    RelationOccurrence.fromBoundary(endpoint.file, 49, 50).refined(),
                    evidence,
                    evidence,
                    callback,
                    CallbackNamedCallPolicy.Excluded(CallbackExclusionReason.STORED_CALLBACK, callback),
                    CallbackInvocationFlowRead.Unavailable(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE),
                ),
            )
            val other = request(RelationMeaning.References)
            assertEquals(false, exclusion.belongsTo(other))
        }
    }

    private fun callbackEvidence(endpoint: RelationEndpoint): CompilerGroundedSymbolEvidence {
        return CompilerGroundedSymbolEvidence.fromBoundary(
                endpoint.file,
                endpoint.range.startInclusive,
                endpoint.range.endExclusive,
                endpoint.name.value,
                "sample.Subject.run",
                endpoint.kind,
                endpoint.signature,
            )
            .refined()
    }

    private fun request(meaning: RelationMeaning): RelationRequest =
        RelationRequest.start(
            selector(),
            meaning,
            RelationBudget(
                ResourceBudget(
                    ResultLimit.parse(8).refined(),
                    WorkUnitLimit.parse(32L).refined(),
                    ElapsedTimeLimitMillis.parse(1_000L).refined(),
                ),
                RelationByteLimit.parse(100_000L).refined(),
            ),
        )

    private fun selector(): SymbolSelector {
        val lease =
            SemanticReadLease(
                CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
                EvidenceGeneration.parse(19L).refined(),
            )
        val selection = selection(lease)
        val location = selection.candidate.location as SymbolDiscoveryCandidateLocation.Declaration
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    location.file,
                    location.offset.value,
                    location.offset.value + 10,
                    selection.candidate.name.value,
                    "sample.Subject.run",
                    CompilerSymbolKind.FUNCTION,
                    CanonicalCompilerSignature.function(
                            "sample.Subject.run",
                            null,
                            emptyList(),
                            emptyList(),
                            0,
                        )
                        .refined(),
                )
                .refined()
        return SymbolSelector.issue(selection, evidence).refined()
    }

    private fun selection(lease: SemanticReadLease): SymbolDiscoverySelection {
        val request = discoveryRequest(lease)
        val candidate =
            SymbolDiscoveryCandidate.fromBoundary(
                    SymbolDiscoveryKind.SYMBOL,
                    "run",
                    lease,
                    Path.of("/workspace/src/Subject.kt"),
                    "file:///workspace/src/Subject.kt",
                    41,
                )
                .refined()
        val batch =
            SymbolDiscoveryBatch.create(
                    request,
                    listOf(candidate),
                    SymbolDiscoveryByteCount.parse(candidate.projectedUtf8Size().value).refined(),
                    SymbolDiscoveryWorkCount.parse(1L).refined(),
                    SymbolDiscoveryTimings(
                        SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
                        SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
                    ),
                )
                .refined()
        return SymbolDiscoverySelection.select(batch, 0).refined()
    }

    private fun discoveryRequest(lease: SemanticReadLease) =
        SymbolDiscoveryRequest(
            SymbolSearchScopeRequest(
                lease,
                SymbolSearchScope.Workspace(
                    SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    SymbolGeneratedSourcePolicy.INCLUDE,
                    SymbolLibraryPolicy.EXCLUDE,
                ),
            ),
            SymbolDiscoveryTarget.Name(
                SymbolNameDiscoveryKind.SYMBOL,
                SymbolDiscoveryPattern.parse("run").refined(),
                SymbolDiscoveryMatch.FUZZY,
            ),
            SymbolDiscoveryBudget(
                ResourceBudget(
                    ResultLimit.parse(1).refined(),
                    WorkUnitLimit.parse(8L).refined(),
                    ElapsedTimeLimitMillis.parse(1_000L).refined(),
                ),
                SymbolDiscoveryByteLimit.parse(10_000L).refined(),
            ),
        )

    private fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
