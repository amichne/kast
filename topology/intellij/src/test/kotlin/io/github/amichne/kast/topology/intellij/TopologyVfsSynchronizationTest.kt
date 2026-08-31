package io.github.amichne.kast.topology.intellij

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.CompleteTopologyFile
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumeration
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumerator
import io.github.amichne.kast.topology.contract.TopologyExtractionFailure
import io.github.amichne.kast.topology.contract.TopologyFileExtraction
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.GradleSourceRootEvidence
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class TopologyVfsSynchronizationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `all admitted roots synchronize before authored and generated candidates are hashed`() {
        write("src/main/kotlin/Authored.kt", "class Authored")
        write("build/generated/kotlin/Generated.kt", "class Generated")
        val authored = sourceRoot(
            "root.main",
            "src/main/kotlin",
            SourceRootProvenance.Authored,
        )
        val generated = sourceRoot(
            "root.generated",
            "build/generated/kotlin",
            SourceRootProvenance.Generated,
        )
        val workspace = workspace(authored, generated)
        val events = mutableListOf<String>()
        val synchronizedRoots = mutableListOf<SourceRoot>()
        val synchronizer = TopologySourceRootVfsSynchronizer { observed, roots ->
            assertEquals(workspace, observed)
            events += "synchronize"
            synchronizedRoots += roots
            TopologySourceRootVfsSynchronization.Synchronized
        }
        val delegate = TopologyCandidateEnumerator { observed ->
            events += "enumerate"
            AdmittedSourceRootEnumerator().enumerate(observed)
        }

        val result = assertInstanceOf(
            TopologyCandidateEnumeration.Complete::class.java,
            SourceRootSynchronizedTopologyCandidateEnumerator(
                synchronizer,
                delegate,
            ).enumerate(workspace),
        )

        assertEquals(listOf("synchronize", "enumerate"), events)
        assertEquals(workspace.sourceRoots, synchronizedRoots)
        assertEquals(
            listOf(
                "build/generated/kotlin/Generated.kt",
                "src/main/kotlin/Authored.kt",
            ),
            result.candidates.files.map { it.path.value },
        )
        assertEquals(
            setOf(SourceRootProvenance.Authored, SourceRootProvenance.Generated),
            result.candidates.files.map { it.sourceRoot.provenance }.toSet(),
        )
    }

    @Test
    fun `VFS mismatch refreshes the failing source root and retries once`() = runTest {
        write("src/main/kotlin/Authored.kt", "class Authored")
        write("build/generated/kotlin/Generated.kt", "class Generated")
        val workspace = workspace(
            sourceRoot("root.main", "src/main/kotlin", SourceRootProvenance.Authored),
            sourceRoot(
                "root.generated",
                "build/generated/kotlin",
                SourceRootProvenance.Generated,
            ),
        )
        val candidates = AdmittedSourceRootEnumerator().enumerate(workspace).complete()
        val requested = candidates.files.first { it.path.value.endsWith("Authored.kt") }
        val failing = candidates.files.first { it.path.value.endsWith("Generated.kt") }
        val request = candidates.extractionRequest(requested).refined()
        val complete = CompleteTopologyFile.admit(requested, emptyList(), emptyList()).refined()
        val attempts = AtomicInteger()
        val synchronizedRoots = mutableListOf<List<SourceRoot>>()
        val retrier = TopologyVfsMismatchRetrier(
            TopologySourceRootVfsSynchronizer { observed, roots ->
                assertEquals(workspace, observed)
                synchronizedRoots += roots
                TopologySourceRootVfsSynchronization.Synchronized
            },
        )

        val result = retrier.extract(workspace, request) {
            if (attempts.incrementAndGet() == 1) {
                TopologyFileExtraction.Failed(
                    failing,
                    TopologyExtractionFailure.VFS_CONTENT_MISMATCH,
                )
            } else {
                TopologyFileExtraction.Complete(complete)
            }
        }

        assertEquals(TopologyFileExtraction.Complete(complete), result)
        assertEquals(2, attempts.get())
        assertEquals(listOf(listOf(failing.sourceRoot)), synchronizedRoots)
    }

    @Test
    fun `dirty and uncommitted documents remain terminal without refresh`() = runTest {
        write("src/main/kotlin/Authored.kt", "class Authored")
        val workspace = workspace(
            sourceRoot("root.main", "src/main/kotlin", SourceRootProvenance.Authored),
        )
        val candidates = AdmittedSourceRootEnumerator().enumerate(workspace).complete()
        val request = candidates.extractionRequest(candidates.files.single()).refined()

        listOf(
            TopologyExtractionFailure.DOCUMENT_DIRTY,
            TopologyExtractionFailure.PSI_DOCUMENT_UNCOMMITTED,
        ).forEach { failure ->
            val attempts = AtomicInteger()
            val synchronizations = AtomicInteger()
            val expected = TopologyFileExtraction.Failed(request.file, failure)
            val retrier = TopologyVfsMismatchRetrier(
                TopologySourceRootVfsSynchronizer { _, _ ->
                    synchronizations.incrementAndGet()
                    TopologySourceRootVfsSynchronization.Synchronized
                },
            )

            val result = retrier.extract(workspace, request) {
                attempts.incrementAndGet()
                expected
            }

            assertEquals(expected, result)
            assertEquals(1, attempts.get())
            assertEquals(0, synchronizations.get())
        }
    }

    @Test
    fun `repeated VFS mismatch is bounded to one refresh and two attempts`() = runTest {
        write("src/main/kotlin/Authored.kt", "class Authored")
        val workspace = workspace(
            sourceRoot("root.main", "src/main/kotlin", SourceRootProvenance.Authored),
        )
        val candidates = AdmittedSourceRootEnumerator().enumerate(workspace).complete()
        val request = candidates.extractionRequest(candidates.files.single()).refined()
        val attempts = AtomicInteger()
        val synchronizations = AtomicInteger()
        val mismatch = TopologyFileExtraction.Failed(
            request.file,
            TopologyExtractionFailure.VFS_CONTENT_MISMATCH,
        )
        val retrier = TopologyVfsMismatchRetrier(
            TopologySourceRootVfsSynchronizer { _, _ ->
                synchronizations.incrementAndGet()
                TopologySourceRootVfsSynchronization.Synchronized
            },
        )

        val result = retrier.extract(workspace, request) {
            attempts.incrementAndGet()
            mismatch
        }

        assertEquals(mismatch, result)
        assertEquals(2, attempts.get())
        assertEquals(1, synchronizations.get())
    }

    private fun write(relative: String, content: String) {
        val file = tempDir.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    private fun workspace(vararg roots: SourceRoot): PublishedWorkspace {
        val candidate = WorkspaceCandidate(
            CanonicalWorkspaceRoot.fromCanonicalPath(tempDir.toRealPath()).refined(),
            WorkspaceStateIdentity.parse("vfs-synchronization-state").refined(),
        )
        return PublishedWorkspace.publish(
            ReconciledWorkspace.admit(
                candidate,
                WorkspaceEvidenceKind.entries.toSet(),
                roots.toList(),
            ).refined(),
            EvidenceGeneration.parse(11).refined(),
        )
    }

    private fun sourceRoot(
        module: String,
        location: String,
        provenance: SourceRootProvenance,
    ): SourceRoot = SourceRoot.admit(
        GradleSourceRootEvidence(module, ".", ":", "main", location, provenance),
    ).refined()

    private fun TopologyCandidateEnumeration.complete() = assertInstanceOf(
        TopologyCandidateEnumeration.Complete::class.java,
        this,
    ).candidates

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
