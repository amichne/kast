package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
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

/** Selector evidence supplies the production scope's address; native resolution is asserted independently. */
internal fun nativeRelationRequest(root: Path, file: Path, offset: Int): RelationRequest {
    val lease =
        SemanticReadLease(
            CanonicalWorkspaceRoot.fromCanonicalPath(root).nativeRefined(),
            EvidenceGeneration.parse(1L).nativeRefined(),
        )
    val scope =
        SymbolSearchScope.Workspace(
            SymbolSourceKindPolicy.PRODUCTION_ONLY,
            SymbolGeneratedSourcePolicy.EXCLUDE,
            SymbolLibraryPolicy.EXCLUDE,
        )
    val resources =
        ResourceBudget(
            ResultLimit.parse(8).nativeRefined(),
            WorkUnitLimit.parse(10_000L).nativeRefined(),
            ElapsedTimeLimitMillis.parse(60_000L).nativeRefined(),
        )
    val selection =
        nativeDiscoverySelection(lease = lease, file = file, offset = offset, scope = scope, resources = resources)
    val location = selection.candidate.location as SymbolDiscoveryCandidateLocation.Declaration
    val evidence =
        CompilerGroundedSymbolEvidence.fromBoundary(
                file = location.file,
                rawStartInclusive = offset,
                rawEndExclusive = offset + "target".length,
                rawName = "target",
                rawQualifiedIdentity = "proof.target",
                kind = CompilerSymbolKind.FUNCTION,
                signature =
                    CanonicalCompilerSignature.function(
                            rawQualifiedIdentity = "proof.target",
                            rawReceiverType = null,
                            rawContextReceiverTypes = emptyList(),
                            rawValueParameterTypes = emptyList(),
                            rawTypeParameterCount = 0,
                        )
                        .nativeRefined(),
            )
            .nativeRefined()
    return RelationRequest.start(
        SymbolSelector.issue(selection, evidence).nativeRefined(),
        RelationMeaning.References,
        RelationBudget(resources, RelationByteLimit.parse(NATIVE_RELATION_BYTE_LIMIT).nativeRefined()),
    )
}

private const val NATIVE_RELATION_BYTE_LIMIT = 100_000L

private fun nativeDiscoverySelection(
    lease: SemanticReadLease,
    file: Path,
    offset: Int,
    scope: SymbolSearchScope,
    resources: ResourceBudget,
): SymbolDiscoverySelection {
    val discovery =
        SymbolDiscoveryRequest(
            SymbolSearchScopeRequest(lease, scope),
            SymbolDiscoveryTarget.Name(
                SymbolNameDiscoveryKind.SYMBOL,
                SymbolDiscoveryPattern.parse("target").nativeRefined(),
                SymbolDiscoveryMatch.EXACT_NAME,
            ),
            SymbolDiscoveryBudget(resources, SymbolDiscoveryByteLimit.parse(10_000L).nativeRefined()),
        )
    val candidate =
        SymbolDiscoveryCandidate.fromBoundary(
                kind = SymbolDiscoveryKind.SYMBOL,
                rawName = "target",
                lease = lease,
                nativePath = file,
                virtualFileUrl = file.toUri().toString(),
                rawOffset = offset,
            )
            .nativeRefined()
    val batch =
        SymbolDiscoveryBatch.create(
                request = discovery,
                candidates = listOf(candidate),
                encodedBytes = SymbolDiscoveryByteCount.parse(candidate.projectedUtf8Size().value).nativeRefined(),
                examinedWorkUnits = SymbolDiscoveryWorkCount.parse(1L).nativeRefined(),
                timings =
                    SymbolDiscoveryTimings(
                        SymbolDiscoveryElapsedNanoseconds.parse(1L).nativeRefined(),
                        SymbolDiscoveryElapsedNanoseconds.parse(1L).nativeRefined(),
                    ),
            )
            .nativeRefined()
    return SymbolDiscoverySelection.select(batch, 0).nativeRefined()
}

internal fun <Value, Failure> Refinement<Value, Failure>.nativeRefined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
