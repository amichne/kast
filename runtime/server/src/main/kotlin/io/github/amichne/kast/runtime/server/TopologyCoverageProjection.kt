package io.github.amichne.kast.runtime.server

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.TopologyCoverageCandidateEvidenceMismatch
import io.github.amichne.kast.protocol.contract.TopologyCoverageFailure
import io.github.amichne.kast.protocol.contract.TopologyCoverageFileEvidence
import io.github.amichne.kast.protocol.contract.TopologyCoverageNode
import io.github.amichne.kast.protocol.contract.TopologyCoverageProjectionRejection
import io.github.amichne.kast.protocol.contract.TopologyCoverageQualifiedIdentity
import io.github.amichne.kast.protocol.contract.TopologyCoverageSourceHash
import io.github.amichne.kast.protocol.contract.TopologyCoverageSourceRootEvidence
import io.github.amichne.kast.protocol.contract.TopologyCoverageSourceRootProvenance
import io.github.amichne.kast.protocol.contract.TopologyCoverageSymbol
import io.github.amichne.kast.protocol.contract.TopologyCoverageSymbolKind
import io.github.amichne.kast.protocol.contract.TopologyCoverageWorkspaceEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import io.github.amichne.kast.topology.contract.TopologyCandidateEvidenceMismatch
import io.github.amichne.kast.topology.contract.TopologyGenerationCoverageFailure
import io.github.amichne.kast.topology.contract.TopologyNodeIdentity
import io.github.amichne.kast.topology.contract.TopologySourceFile
import io.github.amichne.kast.topology.contract.TopologySymbol
import io.github.amichne.kast.workspace.contract.ProvenanceFailure
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath

/**
 * Proof transition from complete domain coverage failure to its public protocol representation.
 * Every exact path, location-bearing node, and contradictory endpoint is retained.
 */
fun TopologyGenerationCoverageFailure.toProtocolCoverage(): Refinement<
    TopologyCoverageFailure,
    TopologyCoverageProjectionRejection,
> {
    val projectedMissing = when (val result = missing.project(WorkspaceSourcePath::toProtocol)) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return result
    }
    val projectedUnexpected = when (val result = unexpected.project(WorkspaceSourcePath::toProtocol)) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return result
    }
    val projectedDuplicateCandidates = when (
        val result = duplicateCandidates.project(WorkspaceSourcePath::toProtocol)
    ) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return result
    }
    val projectedDuplicateCompletions = when (
        val result = duplicateCompletions.project(WorkspaceSourcePath::toProtocol)
    ) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return result
    }
    val projectedWorkspaceMismatches = when (
        val result = workspaceMismatches.project(WorkspaceSourcePath::toProtocol)
    ) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return result
    }
    val projectedCandidateEvidenceMismatches = when (
        val result = candidateEvidenceMismatches.project(
            TopologyCandidateEvidenceMismatch::toProtocol,
        )
    ) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return result
    }
    val projectedDuplicateSymbols = when (
        val result = duplicateSymbols.project(TopologyNodeIdentity::toProtocol)
    ) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return result
    }
    val projectedMissingEdgeTargets = when (
        val result = missingEdgeTargets.project(TopologyNodeIdentity::toProtocol)
    ) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return result
    }
    val projectedMismatchedEndpoints = when (
        val result = mismatchedEdgeEndpoints.project(TopologySymbol::toProtocol)
    ) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return result
    }
    return when (val admitted = TopologyCoverageFailure.admit(
        projectedMissing,
        projectedUnexpected,
        projectedDuplicateCandidates,
        projectedDuplicateCompletions,
        projectedWorkspaceMismatches,
        projectedCandidateEvidenceMismatches,
        projectedDuplicateSymbols,
        projectedMissingEdgeTargets,
        projectedMismatchedEndpoints,
    )) {
        is Refinement.Refined -> Refinement.Refined(admitted.value)
        is Refinement.Rejected -> Refinement.Rejected(
            TopologyCoverageProjectionRejection.EMPTY_FAILURE,
        )
    }
}

private fun WorkspaceSourcePath.toProtocol(): Refinement<
    ProtocolText,
    TopologyCoverageProjectionRejection,
> = value.toProtocolText()

private fun TopologyNodeIdentity.toProtocol(): Refinement<
    TopologyCoverageNode,
    TopologyCoverageProjectionRejection,
> {
    val compiler = when (val result = compilerIdentity.value.toProtocolText()) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return result
    }
    val path = when (val result = file.value.toProtocolText()) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return result
    }
    val start = when (val result = ProtocolOffset.parse(range.startInclusive)) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return Refinement.Rejected(
            TopologyCoverageProjectionRejection.UNREPRESENTABLE_RANGE,
        )
    }
    val end = when (val result = ProtocolOffset.parse(range.endExclusive)) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return Refinement.Rejected(
            TopologyCoverageProjectionRejection.UNREPRESENTABLE_RANGE,
        )
    }
    val projectedRange = when (val result = SourceRangeDocument.create(start, end)) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return Refinement.Rejected(
            TopologyCoverageProjectionRejection.UNREPRESENTABLE_RANGE,
        )
    }
    return Refinement.Refined(TopologyCoverageNode(compiler, path, projectedRange))
}

private fun TopologySymbol.toProtocol(): Refinement<
    TopologyCoverageSymbol,
    TopologyCoverageProjectionRejection,
> {
    val node = when (val result = nodeIdentity.toProtocol()) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return result
    }
    val fileEvidence = when (val result = file.toProtocol()) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return result
    }
    val name = when (val result = evidence.name.value.toProtocolText()) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return result
    }
    val qualified = when (val identity = evidence.qualifiedIdentity) {
        is ExactDeclarationQualifiedIdentity.Available -> when (
            val result = identity.value.toProtocolText()
        ) {
            is Refinement.Refined -> TopologyCoverageQualifiedIdentity.Available(result.value)
            is Refinement.Rejected -> return result
        }
        ExactDeclarationQualifiedIdentity.Unavailable ->
            TopologyCoverageQualifiedIdentity.Unavailable
    }
    return Refinement.Refined(
        TopologyCoverageSymbol(
            node,
            fileEvidence,
            name,
            qualified,
            when (evidence.kind) {
                CompilerSymbolKind.CLASSLIKE -> TopologyCoverageSymbolKind.CLASSLIKE
                CompilerSymbolKind.CONSTRUCTOR -> TopologyCoverageSymbolKind.CONSTRUCTOR
                CompilerSymbolKind.FUNCTION -> TopologyCoverageSymbolKind.FUNCTION
                CompilerSymbolKind.PROPERTY -> TopologyCoverageSymbolKind.PROPERTY
                CompilerSymbolKind.TYPE_ALIAS -> TopologyCoverageSymbolKind.TYPE_ALIAS
            },
        ),
    )
}

private fun TopologyCandidateEvidenceMismatch.toProtocol(): Refinement<
    TopologyCoverageCandidateEvidenceMismatch,
    TopologyCoverageProjectionRejection,
> {
    val projectedCandidate = when (val result = candidate.toProtocol()) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return result
    }
    val projectedCompleted = when (val result = completed.toProtocol()) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return result
    }
    return Refinement.Refined(
        TopologyCoverageCandidateEvidenceMismatch(projectedCandidate, projectedCompleted),
    )
}

private fun TopologySourceFile.toProtocol(): Refinement<
    TopologyCoverageFileEvidence,
    TopologyCoverageProjectionRejection,
> {
    val workspaceRoot = when (val result = workspace.lease.workspaceRoot.value.toProtocolText()) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return result
    }
    val sourceState = when (val result = workspace.sourceState.value.toProtocolText()) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return result
    }
    val module = when (val result = sourceRoot.owner.module.value.toProtocolText()) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return result
    }
    val buildRoot = when (val result = sourceRoot.owner.project.buildRoot.value.toProtocolText()) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return result
    }
    val projectPath = when (val result = sourceRoot.owner.project.projectPath.value.toProtocolText()) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return result
    }
    val sourceSet = when (val result = sourceRoot.owner.sourceSet.value.toProtocolText()) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return result
    }
    val location = when (val result = sourceRoot.location.value.toProtocolText()) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return result
    }
    val projectedPath = when (val result = path.value.toProtocolText()) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return result
    }
    val projectedHash = when (val result = TopologyCoverageSourceHash.parse(contentHash.value)) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return Refinement.Rejected(
            TopologyCoverageProjectionRejection.UNREPRESENTABLE_CONTENT_HASH,
        )
    }
    val provenance = when (val value = sourceRoot.provenance) {
        SourceRootProvenance.Authored -> TopologyCoverageSourceRootProvenance.AUTHORED
        SourceRootProvenance.Generated -> TopologyCoverageSourceRootProvenance.GENERATED
        is SourceRootProvenance.Unknown -> when (value.reason) {
            ProvenanceFailure.ExcludedFromSourceModel ->
                TopologyCoverageSourceRootProvenance.UNKNOWN_EXCLUDED_FROM_SOURCE_MODEL
        }
    }
    return Refinement.Refined(
        TopologyCoverageFileEvidence(
            TopologyCoverageWorkspaceEvidence(
                workspaceRoot,
                workspace.lease.generation,
                sourceState,
            ),
            TopologyCoverageSourceRootEvidence(
                module,
                buildRoot,
                projectPath,
                sourceSet,
                location,
                provenance,
            ),
            projectedPath,
            projectedHash,
        ),
    )
}

private fun String.toProtocolText(): Refinement<ProtocolText, TopologyCoverageProjectionRejection> =
    when (val parsed = ProtocolText.parse(this)) {
        is Refinement.Refined -> Refinement.Refined(parsed.value)
        is Refinement.Rejected -> Refinement.Rejected(
            TopologyCoverageProjectionRejection.UNREPRESENTABLE_TEXT,
        )
    }

private fun <Input, Output> Iterable<Input>.project(
    projection: (Input) -> Refinement<Output, TopologyCoverageProjectionRejection>,
): Refinement<Set<Output>, TopologyCoverageProjectionRejection> {
    val projected = linkedSetOf<Output>()
    for (value in this) {
        when (val result = projection(value)) {
            is Refinement.Refined -> projected += result.value
            is Refinement.Rejected -> return result
        }
    }
    return Refinement.Refined(projected)
}
