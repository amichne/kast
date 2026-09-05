package io.github.amichne.kast.topology.build

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.KastObservability
import io.github.amichne.kast.kernel.KastSpanCompletion
import io.github.amichne.kast.kernel.KastSpanEvent
import io.github.amichne.kast.kernel.KastSpanFailure
import io.github.amichne.kast.kernel.KastSpanName
import io.github.amichne.kast.kernel.KastSpanObservation
import io.github.amichne.kast.kernel.KastTopologyCacheDisposition
import io.github.amichne.kast.kernel.KastTopologyIdentityStage
import io.github.amichne.kast.kernel.KastTopologySourceRange
import io.github.amichne.kast.kernel.KastTraceSpan
import io.github.amichne.kast.topology.contract.TopologyBindingFailure
import io.github.amichne.kast.kernel.KastTopologyBindingFailure
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.topology.contract.TopologyBuildFailure
import io.github.amichne.kast.topology.contract.TopologyBuildResult
import io.github.amichne.kast.topology.contract.TopologyCacheDisposition
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumeration
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumerationFailure
import io.github.amichne.kast.topology.contract.TopologyCandidateSet
import io.github.amichne.kast.topology.contract.TopologyExtractionFailure
import io.github.amichne.kast.topology.contract.TopologyFileExtraction
import io.github.amichne.kast.topology.contract.TopologyFileExtractionFailure
import io.github.amichne.kast.topology.contract.TopologyIdentityMismatchEvidence
import io.github.amichne.kast.topology.contract.TopologyIdentityStage
import io.github.amichne.kast.topology.contract.TopologyPublicationResult
import io.github.amichne.kast.topology.contract.TopologySnapshotContentRead
import io.github.amichne.kast.topology.contract.TopologySnapshotEligibility
import io.github.amichne.kast.topology.contract.TopologySnapshotStore
import io.github.amichne.kast.topology.contract.TopologySourceFile
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.GradleSourceRootEvidence
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import io.github.amichne.kast.workspace.contract.SemanticReadLeaseGuard
import io.github.amichne.kast.workspace.contract.SemanticReadLeaseUse
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class TopologyBuildInstrumentationTest {
    @Test
    fun `identity mismatch retains public rejection and records exact diagnostic event`() = runTest {
        val fixture = instrumentationFixture()
        val evidence = mismatchEvidence(fixture.file)
        val trace = RecordingObservability()
        val operations = TopologyBuildService.create(
            ready(fixture.workspace),
            CurrentGuard(fixture.workspace.readLease),
            { TopologyCandidateEnumeration.Complete(fixture.candidates) },
            { TopologyFileExtraction.IdentityMismatch(evidence, TopologyCacheDisposition.COMPUTED) },
            RejectingSnapshotStore,
            trace,
        )

        assertEquals(
            TopologyBuildResult.Rejected(
                TopologyBuildFailure.Extraction(
                    fixture.file.path,
                    TopologyExtractionFailure.COMPILER_IDENTITY_MISMATCH,
                ),
            ),
            operations.build(),
        )

        val observation = trace.observations.single { candidate -> candidate.events.isNotEmpty() }
        assertEquals(
            KastSpanCompletion.Rejected(KastSpanFailure.TOPOLOGY_EXTRACTION),
            observation.completion,
        )
        assertEquals(
            KastSpanEvent.TopologyIdentityMismatch(
                stage = KastTopologyIdentityStage.REFERENCE_TARGET,
                cacheDisposition = KastTopologyCacheDisposition.COMPUTED,
                sourceFile = fixture.file.path.value,
                sourceOccurrence = KastTopologySourceRange(0, 7),
                targetFile = fixture.file.path.value,
                targetDeclaration = KastTopologySourceRange(0, 7),
                reason = KastTopologyBindingFailure.DECLARATION_MISMATCH,
            ),
            observation.events.single(),
        )
    }

    @Test
    fun `identity mismatch must belong to the exact extraction request`() = runTest {
        val fixture = instrumentationFixture()
        val other = TopologySourceFile.admit(
            fixture.workspace,
            fixture.file.sourceRoot,
            WorkspaceSourcePath.parse("alpha/src/main/kotlin/Other.kt").refined(),
            WorkspaceSourceContentHash.parse("b".repeat(64)).refined(),
        ).refined()
        val candidates = TopologyCandidateSet.admit(
            fixture.workspace,
            listOf(fixture.file, other),
        ).refined()
        val trace = RecordingObservability()
        val operations = TopologyBuildService.create(
            ready(fixture.workspace),
            CurrentGuard(fixture.workspace.readLease),
            { TopologyCandidateEnumeration.Complete(candidates) },
            {
                TopologyFileExtraction.IdentityMismatch(
                    mismatchEvidence(other),
                    TopologyCacheDisposition.COMPUTED,
                )
            },
            RejectingSnapshotStore,
            trace,
        )

        assertEquals(
            TopologyBuildResult.Rejected(TopologyBuildFailure.ExtractionContractViolation),
            operations.build(),
        )
        assertEquals(0, trace.observations.sumOf { observation -> observation.events.size })
    }

    @Test
    fun `workspace rejection records bounded topology terminal state`() = runTest {
        val trace = RecordingObservability()
        val operations = TopologyBuildService.create(
            WorkspaceInspectionOperations { WorkspaceRuntimeState.Reconciling },
            MovedLeaseGuard,
            { TopologyCandidateEnumeration.Rejected(
                TopologyCandidateEnumerationFailure.WORKSPACE_UNAVAILABLE,
            ) },
            { request -> TopologyFileExtraction.Failed(
                request.file,
                TopologyFileExtractionFailure.PROJECT_UNAVAILABLE,
            ) },
            RejectingSnapshotStore,
            trace,
        )

        assertEquals(
            TopologyBuildResult.Rejected(TopologyBuildFailure.WorkspaceNotReady),
            operations.build(),
        )
        assertEquals(listOf(KastSpanName.TOPOLOGY_BUILD), trace.names)
        assertEquals(
            KastSpanObservation(
                KastSpanCompletion.Rejected(KastSpanFailure.TOPOLOGY_WORKSPACE_NOT_READY),
            ),
            trace.observations.single(),
        )
    }
}

private data class InstrumentationFixture(
    val workspace: PublishedWorkspace,
    val file: TopologySourceFile,
    val candidates: TopologyCandidateSet,
)

private fun instrumentationFixture(): InstrumentationFixture {
    val root = SourceRoot.admit(
        GradleSourceRootEvidence(
            ideaModuleName = "alpha.main",
            workspaceRelativeBuildRoot = ".",
            gradleProjectPath = ":alpha",
            sourceSetName = "main",
            workspaceRelativeSourceRoot = "alpha/src/main/kotlin",
            provenance = SourceRootProvenance.Authored,
        ),
    ).refined()
    val candidate = WorkspaceCandidate(
        CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
        WorkspaceStateIdentity.parse("instrumentation-state").refined(),
    )
    val reconciled = ReconciledWorkspace.admit(
        candidate,
        WorkspaceEvidenceKind.entries.toSet(),
        listOf(root),
    ).refined()
    val workspace = PublishedWorkspace.publish(
        reconciled,
        EvidenceGeneration.parse(1).refined(),
    )
    val file = TopologySourceFile.admit(
        workspace,
        root,
        WorkspaceSourcePath.parse("alpha/src/main/kotlin/EventConsumer.kt").refined(),
        WorkspaceSourceContentHash.parse("a".repeat(64)).refined(),
    ).refined()
    return InstrumentationFixture(
        workspace,
        file,
        TopologyCandidateSet.admit(workspace, listOf(file)).refined(),
    )
}

private fun mismatchEvidence(file: TopologySourceFile): TopologyIdentityMismatchEvidence {
    val evidence = compilerEvidence(file, listOf("kotlin.String"))
    return TopologyIdentityMismatchEvidence(
        TopologyIdentityStage.REFERENCE_TARGET, file, evidence.range,
        file, evidence.range, TopologyBindingFailure.DECLARATION_MISMATCH,
    )
}


private fun compilerEvidence(
    file: TopologySourceFile,
    parameterTypes: List<String>,
): CompilerGroundedSymbolEvidence {
    val absolute = Path.of(file.workspace.lease.workspaceRoot.value).resolve(file.path.value)
    val fileIdentity = SymbolDiscoveryFileIdentity.fromBoundary(
        file.workspace.lease.workspaceRoot,
        absolute,
        absolute.toUri().toString(),
    ).refined()
    val signature = CanonicalCompilerSignature.function(
        rawQualifiedIdentity = "sample.consume",
        rawReceiverType = null,
        rawContextReceiverTypes = emptyList(),
        rawValueParameterTypes = parameterTypes,
        rawTypeParameterCount = 0,
    ).refined()
    return CompilerGroundedSymbolEvidence.fromBoundary(
        fileIdentity,
        0,
        7,
        "consume",
        "sample.consume",
        CompilerSymbolKind.FUNCTION,
        signature,
    ).refined()
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> error(failure.toString())
}

private class RecordingObservability : KastObservability, KastTraceSpan {
    val names = mutableListOf<KastSpanName>()
    val observations = mutableListOf<KastSpanObservation>()

    override suspend fun <Value> inSpan(
        name: KastSpanName,
        operation: suspend (KastTraceSpan) -> Value,
    ): Value {
        names += name
        return operation(this)
    }

    override suspend fun <Value> child(
        name: KastSpanName,
        operation: suspend (KastTraceSpan) -> Value,
    ): Value = inSpan(name, operation)

    override fun observe(observation: KastSpanObservation) {
        observations += observation
    }
}

private data object RejectingSnapshotStore : TopologySnapshotStore {
    override fun eligible(identity: io.github.amichne.kast.topology.contract.TopologyWorkspaceIdentity) =
        TopologySnapshotEligibility.Unavailable

    override fun read(snapshot: io.github.amichne.kast.topology.contract.PublishedTopologySnapshot) =
        TopologySnapshotContentRead.Rejected(
            io.github.amichne.kast.topology.contract.TopologySnapshotReadFailure.STORAGE_UNAVAILABLE,
        )

    override fun publish(
        generation: io.github.amichne.kast.topology.contract.CompleteTopologyGeneration,
    ) = TopologyPublicationResult.Rejected(
        io.github.amichne.kast.topology.contract.TopologyPublicationFailure.STORAGE_UNAVAILABLE,
    )
}

private data object MovedLeaseGuard : SemanticReadLeaseGuard {
    override fun <Value> whileCurrent(
        expected: io.github.amichne.kast.workspace.contract.SemanticReadLease,
        operation: () -> Value,
    ): SemanticReadLeaseUse<Value> = SemanticReadLeaseUse.Moved
}
