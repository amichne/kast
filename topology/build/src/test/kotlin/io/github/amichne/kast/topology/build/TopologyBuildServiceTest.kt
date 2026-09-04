package io.github.amichne.kast.topology.build

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.CompleteTopologyFile
import io.github.amichne.kast.topology.contract.CompleteTopologyGeneration
import io.github.amichne.kast.topology.contract.PublishedTopologySnapshot
import io.github.amichne.kast.topology.contract.TopologyBuildFailure
import io.github.amichne.kast.topology.contract.TopologyBuildResult
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumeration
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumerator
import io.github.amichne.kast.topology.contract.TopologyCandidateSet
import io.github.amichne.kast.topology.contract.TopologyExtractionFailure
import io.github.amichne.kast.topology.contract.TopologyExtractionRequest
import io.github.amichne.kast.topology.contract.TopologyFileExtraction
import io.github.amichne.kast.topology.contract.TopologyFileExtractionFailure
import io.github.amichne.kast.topology.contract.TopologyFileExtractor
import io.github.amichne.kast.topology.contract.TopologyPublicationResult
import io.github.amichne.kast.topology.contract.TopologySnapshotContent
import io.github.amichne.kast.topology.contract.TopologySnapshotContentRead
import io.github.amichne.kast.topology.contract.TopologySnapshotEligibility
import io.github.amichne.kast.topology.contract.TopologySnapshotManifest
import io.github.amichne.kast.topology.contract.TopologySnapshotReadFailure
import io.github.amichne.kast.topology.contract.TopologySnapshotStore
import io.github.amichne.kast.topology.contract.TopologySourceFile
import io.github.amichne.kast.topology.contract.TopologyWorkspaceIdentity
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.GradleSourceRootEvidence
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.SemanticReadLeaseGuard
import io.github.amichne.kast.workspace.contract.SemanticReadLeaseUse
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

class TopologyBuildServiceTest {
    @Test
    fun `matching published snapshot is reused before enumeration or extraction`() = runTest {
        val fixture = fixture()
        val snapshot = fixture.snapshot()
        val enumerationCalls = AtomicInteger()
        val extractionCalls = AtomicInteger()
        val publicationCalls = AtomicInteger()
        val service = TopologyBuildService.create(
            ready(fixture.workspace),
            CurrentGuard(fixture.workspace.readLease),
            TopologyCandidateEnumerator {
                enumerationCalls.incrementAndGet()
                fixture.enumeration
            },
            TopologyFileExtractor {
                extractionCalls.incrementAndGet()
                TopologyFileExtraction.Complete(fixture.complete)
            },
            FixedSnapshots(
                TopologySnapshotEligibility.Eligible(snapshot),
                publicationCalls,
                snapshot,
            ),
        )

        val result = service.build()

        assertEquals(TopologyBuildResult.Reused(snapshot), result)
        assertEquals(0, enumerationCalls.get())
        assertEquals(0, extractionCalls.get())
        assertEquals(0, publicationCalls.get())
    }

    @Test
    fun `moved workspace prevents exact durable snapshot reuse`() = runTest {
        val fixture = fixture()
        val snapshot = fixture.snapshot()
        val enumerationCalls = AtomicInteger()
        val service = TopologyBuildService.create(
            ready(fixture.workspace),
            MovedGuard,
            TopologyCandidateEnumerator {
                enumerationCalls.incrementAndGet()
                fixture.enumeration
            },
            TopologyFileExtractor { TopologyFileExtraction.Complete(fixture.complete) },
            FixedSnapshots(
                TopologySnapshotEligibility.Eligible(snapshot),
                AtomicInteger(),
                snapshot,
            ),
        )

        assertEquals(TopologyBuildResult.WorkspaceMoved, service.build())
        assertEquals(0, enumerationCalls.get())
    }

    @Test
    fun `unchanged files rebind stale snapshot to current lease without K2 extraction`() = runTest {
        val prior = fixture()
        val priorSnapshot = prior.snapshot()
        val priorContent = TopologySnapshotContent.admit(
            priorSnapshot,
            listOf(prior.complete),
        ).refined()
        val currentWorkspace = workspace(
            prior.complete.file.sourceRoot,
            "workspace-state",
            8,
        )
        val currentFile = TopologySourceFile.admit(
            currentWorkspace,
            prior.complete.file.sourceRoot,
            prior.complete.file.path,
            prior.complete.file.contentHash,
        ).refined()
        val currentCandidates = TopologyCandidateSet.admit(
            currentWorkspace,
            listOf(currentFile),
        ).refined()
        val extractionCalls = AtomicInteger()
        val publicationCalls = AtomicInteger()
        val snapshots = object : TopologySnapshotStore {
            override fun eligible(identity: TopologyWorkspaceIdentity) =
                TopologySnapshotEligibility.Stale(priorSnapshot)

            override fun read(snapshot: PublishedTopologySnapshot) =
                TopologySnapshotContentRead.Loaded(priorContent)

            override fun publish(generation: CompleteTopologyGeneration): TopologyPublicationResult {
                publicationCalls.incrementAndGet()
                return TopologyPublicationResult.Published(
                    TestSnapshot(generation.identity, TopologySnapshotManifest.from(generation)),
                )
            }
        }
        val service = TopologyBuildService.create(
            ready(currentWorkspace),
            CurrentGuard(currentWorkspace.readLease),
            TopologyCandidateEnumerator {
                TopologyCandidateEnumeration.Complete(currentCandidates)
            },
            TopologyFileExtractor {
                extractionCalls.incrementAndGet()
                TopologyFileExtraction.Failed(
                    currentFile,
                    TopologyFileExtractionFailure.COMPILER_UNAVAILABLE,
                )
            },
            snapshots,
        )

        val result = service.build()

        val reused = assertInstanceOf(TopologyBuildResult.Reused::class.java, result)
        assertEquals(TopologyWorkspaceIdentity.from(currentWorkspace), reused.snapshot.identity)
        assertEquals(0, extractionCalls.get())
        assertEquals(1, publicationCalls.get())
    }

    @Test
    fun `source edit cannot reuse stale compiler facts`() {
        val prior = fixture()
        val currentWorkspace = workspace(prior.complete.file.sourceRoot, "edited-state", 8)
        val editedFile = TopologySourceFile.admit(
            currentWorkspace,
            prior.complete.file.sourceRoot,
            prior.complete.file.path,
            WorkspaceSourceContentHash.parse("b".repeat(64)).refined(),
        ).refined()
        val candidates = TopologyCandidateSet.admit(currentWorkspace, listOf(editedFile)).refined()
        val snapshot = prior.snapshot()
        val content = TopologySnapshotContent.admit(snapshot, listOf(prior.complete)).refined()

        assertEquals(
            TopologyGenerationReuse.SourceChanged,
            rebindUnchangedTopologyGeneration(currentWorkspace, candidates, content),
        )
    }

    @Test
    fun `partial two file extraction makes publication unreachable`() = runTest {
        val fixture = fixture()
        val second = TopologySourceFile.admit(
            fixture.workspace,
            fixture.complete.file.sourceRoot,
            WorkspaceSourcePath.parse("alpha/src/main/kotlin/Beta.kt").refined(),
            WorkspaceSourceContentHash.parse("b".repeat(64)).refined(),
        ).refined()
        val candidates = TopologyCandidateSet.admit(
            fixture.workspace,
            listOf(fixture.complete.file, second),
        ).refined()
        val extractionCalls = AtomicInteger()
        val publicationCalls = AtomicInteger()
        val service = TopologyBuildService.create(
            ready(fixture.workspace),
            CurrentGuard(fixture.workspace.readLease),
            TopologyCandidateEnumerator { TopologyCandidateEnumeration.Complete(candidates) },
            TopologyFileExtractor { request ->
                extractionCalls.incrementAndGet()
                if (request.file == fixture.complete.file) {
                    TopologyFileExtraction.Complete(fixture.complete)
                } else {
                    TopologyFileExtraction.Failed(
                        request.file,
                        TopologyFileExtractionFailure.COMPILER_UNAVAILABLE,
                    )
                }
            },
            FixedSnapshots(
                TopologySnapshotEligibility.Unavailable,
                publicationCalls,
                fixture.snapshot(),
            ),
        )

        val result = service.build()

        assertInstanceOf(TopologyBuildResult.Rejected::class.java, result)
        assertEquals(2, extractionCalls.get())
        assertEquals(0, publicationCalls.get())
    }

    @Test
    fun `registry failure externalizes the actual admitted candidate path`() = runTest {
        val fixture = fixture()
        val registryFailure = TopologySourceFile.admit(
            fixture.workspace,
            fixture.complete.file.sourceRoot,
            WorkspaceSourcePath.parse("alpha/src/main/kotlin/Beta.kt").refined(),
            WorkspaceSourceContentHash.parse("b".repeat(64)).refined(),
        ).refined()
        val candidates = TopologyCandidateSet.admit(
            fixture.workspace,
            listOf(fixture.complete.file, registryFailure),
        ).refined()
        val extractionCalls = AtomicInteger()
        val service = TopologyBuildService.create(
            ready(fixture.workspace),
            CurrentGuard(fixture.workspace.readLease),
            TopologyCandidateEnumerator { TopologyCandidateEnumeration.Complete(candidates) },
            TopologyFileExtractor {
                extractionCalls.incrementAndGet()
                TopologyFileExtraction.Failed(
                    registryFailure,
                    TopologyFileExtractionFailure.DOCUMENT_DIRTY,
                )
            },
            FixedSnapshots(
                TopologySnapshotEligibility.Unavailable,
                AtomicInteger(),
                fixture.snapshot(),
            ),
        )

        assertEquals(
            TopologyBuildResult.Rejected(
                TopologyBuildFailure.Extraction(
                    registryFailure.path,
                    TopologyExtractionFailure.DOCUMENT_DIRTY,
                ),
            ),
            service.build(),
        )
        assertEquals(1, extractionCalls.get())
    }

    @Test
    fun `foreign failing candidate is an extraction contract violation`() = runTest {
        val fixture = fixture()
        val foreign = TopologySourceFile.admit(
            fixture.workspace,
            fixture.complete.file.sourceRoot,
            WorkspaceSourcePath.parse("alpha/src/main/kotlin/Foreign.kt").refined(),
            WorkspaceSourceContentHash.parse("c".repeat(64)).refined(),
        ).refined()
        val service = TopologyBuildService.create(
            ready(fixture.workspace),
            CurrentGuard(fixture.workspace.readLease),
            TopologyCandidateEnumerator { fixture.enumeration },
            TopologyFileExtractor {
                TopologyFileExtraction.Failed(
                    foreign,
                    TopologyFileExtractionFailure.VFS_CONTENT_MISMATCH,
                )
            },
            FixedSnapshots(
                TopologySnapshotEligibility.Unavailable,
                AtomicInteger(),
                fixture.snapshot(),
            ),
        )

        assertEquals(
            TopologyBuildResult.Rejected(TopologyBuildFailure.ExtractionContractViolation),
            service.build(),
        )
    }

    @Test
    fun `cancelled extraction propagates and makes publication unreachable`() {
        val fixture = fixture()
        val publicationCalls = AtomicInteger()
        val service = TopologyBuildService.create(
            ready(fixture.workspace),
            CurrentGuard(fixture.workspace.readLease),
            TopologyCandidateEnumerator { fixture.enumeration },
            TopologyFileExtractor { throw CancellationException("cancelled") },
            FixedSnapshots(
                TopologySnapshotEligibility.Unavailable,
                publicationCalls,
                fixture.snapshot(),
            ),
        )

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.test.runTest { service.build() }
        }
        assertEquals(0, publicationCalls.get())
    }

    @Test
    fun `moved workspace prevents the complete generation from reaching publication`() = runTest {
        val fixture = fixture()
        val publicationCalls = AtomicInteger()
        val service = TopologyBuildService.create(
            ready(fixture.workspace),
            MovedGuard,
            TopologyCandidateEnumerator { fixture.enumeration },
            TopologyFileExtractor { TopologyFileExtraction.Complete(fixture.complete) },
            FixedSnapshots(
                TopologySnapshotEligibility.Unavailable,
                publicationCalls,
                fixture.snapshot(),
            ),
        )

        val result = service.build()

        assertEquals(TopologyBuildResult.WorkspaceMoved, result)
        assertEquals(0, publicationCalls.get())
    }

    @Test
    fun `complete stable generation publishes once`() = runTest {
        val fixture = fixture()
        val snapshot = fixture.snapshot()
        val publicationCalls = AtomicInteger()
        val service = TopologyBuildService.create(
            ready(fixture.workspace),
            CurrentGuard(fixture.workspace.readLease),
            TopologyCandidateEnumerator { fixture.enumeration },
            TopologyFileExtractor { TopologyFileExtraction.Complete(fixture.complete) },
            FixedSnapshots(
                TopologySnapshotEligibility.Unavailable,
                publicationCalls,
                snapshot,
            ),
        )

        val result = service.build()

        assertEquals(TopologyBuildResult.Published(snapshot), result)
        assertEquals(1, publicationCalls.get())
    }

    @Test
    fun `overlapping builds publish once and reuse the durable snapshot`() = runTest {
        val fixture = fixture()
        val snapshot = fixture.snapshot()
        val extractionEntered = CompletableDeferred<Unit>()
        val releaseExtraction = CompletableDeferred<Unit>()
        val extractionCalls = AtomicInteger()
        val publicationCalls = AtomicInteger()
        val snapshots = object : TopologySnapshotStore {
            private var published = false

            @Synchronized
            override fun eligible(identity: TopologyWorkspaceIdentity): TopologySnapshotEligibility =
                if (published) {
                    TopologySnapshotEligibility.Eligible(snapshot)
                } else {
                    TopologySnapshotEligibility.Unavailable
                }

            override fun read(snapshot: PublishedTopologySnapshot): TopologySnapshotContentRead =
                TopologySnapshotContentRead.Rejected(TopologySnapshotReadFailure.STORAGE_UNAVAILABLE)

            @Synchronized
            override fun publish(
                generation: CompleteTopologyGeneration,
            ): TopologyPublicationResult {
                publicationCalls.incrementAndGet()
                return if (published) {
                    TopologyPublicationResult.Unchanged(snapshot)
                } else {
                    published = true
                    TopologyPublicationResult.Published(snapshot)
                }
            }
        }
        val service = TopologyBuildService.create(
            ready(fixture.workspace),
            CurrentGuard(fixture.workspace.readLease),
            TopologyCandidateEnumerator { fixture.enumeration },
            TopologyFileExtractor {
                extractionCalls.incrementAndGet()
                extractionEntered.complete(Unit)
                releaseExtraction.await()
                TopologyFileExtraction.Complete(fixture.complete)
            },
            snapshots,
        )

        val first = async { service.build() }
        extractionEntered.await()
        val second = async { service.build() }
        yield()
        releaseExtraction.complete(Unit)
        val results = listOf(first.await(), second.await())

        assertEquals(1, results.count { result -> result is TopologyBuildResult.Published })
        assertEquals(1, results.count { result -> result is TopologyBuildResult.Reused })
        assertEquals(1, extractionCalls.get())
        assertEquals(1, publicationCalls.get())
    }

    @Test
    fun `source evidence moving after extraction makes publication unreachable`() = runTest {
        val fixture = fixture()
        val movedFile = TopologySourceFile.admit(
            fixture.workspace,
            fixture.complete.file.sourceRoot,
            fixture.complete.file.path,
            WorkspaceSourceContentHash.parse("b".repeat(64)).refined(),
        ).refined()
        val movedCandidates = TopologyCandidateSet.admit(
            fixture.workspace,
            listOf(movedFile),
        ).refined()
        val enumerationCalls = AtomicInteger()
        val publicationCalls = AtomicInteger()
        val service = TopologyBuildService.create(
            ready(fixture.workspace),
            CurrentGuard(fixture.workspace.readLease),
            TopologyCandidateEnumerator {
                if (enumerationCalls.incrementAndGet() == 1) {
                    fixture.enumeration
                } else {
                    TopologyCandidateEnumeration.Complete(movedCandidates)
                }
            },
            TopologyFileExtractor { TopologyFileExtraction.Complete(fixture.complete) },
            FixedSnapshots(
                TopologySnapshotEligibility.Unavailable,
                publicationCalls,
                fixture.snapshot(),
            ),
        )

        val result = service.build()

        assertEquals(
            TopologyBuildResult.Rejected(
                TopologyBuildFailure.Extraction(
                    fixture.complete.file.path,
                    TopologyExtractionFailure.SOURCE_CONTENT_CHANGED_DURING_BUILD,
                ),
            ),
            result,
        )
        assertEquals(2, enumerationCalls.get())
        assertEquals(0, publicationCalls.get())
    }

    private fun fixture(): Fixture {
        val sourceRoot = sourceRoot()
        val workspace = workspace(sourceRoot)
        val file = TopologySourceFile.admit(
            workspace,
            sourceRoot,
            WorkspaceSourcePath.parse("alpha/src/main/kotlin/Alpha.kt").refined(),
            WorkspaceSourceContentHash.parse("a".repeat(64)).refined(),
        ).refined()
        val candidates = TopologyCandidateSet.admit(workspace, listOf(file)).refined()
        val complete = CompleteTopologyFile.admit(file, emptyList(), emptyList()).refined()
        val generation = CompleteTopologyGeneration.admit(
            workspace,
            candidates.files,
            listOf(complete),
        ).refined()
        return Fixture(
            workspace,
            TopologyCandidateEnumeration.Complete(candidates),
            complete,
            generation,
        )
    }

    private fun workspace(
        sourceRoot: SourceRoot,
        state: String = "workspace-state",
        generation: Long = 7,
    ): PublishedWorkspace {
        val candidate = WorkspaceCandidate(
            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
            WorkspaceStateIdentity.parse(state).refined(),
        )
        val reconciled = ReconciledWorkspace.admit(
            candidate,
            WorkspaceEvidenceKind.entries.toSet(),
            listOf(sourceRoot),
        ).refined()
        return PublishedWorkspace.publish(reconciled, EvidenceGeneration.parse(generation).refined())
    }

    private fun sourceRoot(): SourceRoot = SourceRoot.admit(
        GradleSourceRootEvidence(
            "alpha.main",
            ".",
            ":alpha",
            "main",
            "alpha/src/main/kotlin",
            SourceRootProvenance.Authored,
        ),
    ).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
