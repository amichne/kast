package io.github.amichne.kast.topology.intellij

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumeration
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumerationFailure
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.GradleSourceRootEvidence
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Spliterator
import java.util.Spliterators
import java.util.concurrent.CancellationException
import java.util.stream.Stream
import java.util.stream.StreamSupport

class AdmittedSourceRootEnumeratorTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `enumeration reads only Kotlin files below admitted multi project source roots`() {
        write("alpha/src/main/kotlin/sample/Alpha.kt", "package sample\nclass Alpha")
        write("beta/src/main/kotlin/sample/Beta.kts", "package sample\nclass Beta")
        write("alpha/src/main/java/sample/Ignored.java", "class Ignored {}")
        write("outside/Hidden.kt", "class Hidden")
        val workspace = workspace(
            sourceRoot("alpha.main", ":alpha", "alpha/src/main/kotlin"),
            sourceRoot("beta.main", ":beta", "beta/src/main/kotlin"),
            sourceRoot("gamma.main", ":gamma", "gamma/src/main/kotlin"),
        )

        val result = assertInstanceOf(
            TopologyCandidateEnumeration.Complete::class.java,
            AdmittedSourceRootEnumerator().enumerate(workspace),
        )

        assertEquals(
            listOf(
                "alpha/src/main/kotlin/sample/Alpha.kt",
                "beta/src/main/kotlin/sample/Beta.kts",
            ),
            result.candidates.files.map { it.path.value },
        )
        assertEquals(2, result.candidates.files.map { it.sourceRoot.owner.project }.distinct().size)
    }

    @Test
    fun `overlapping admitted owners fail closed instead of selecting one`() {
        write("src/main/kotlin/Shared.kt", "class Shared")
        val workspace = workspace(
            sourceRoot("root.main", ":", "src/main/kotlin"),
            sourceRoot("shared.main", ":shared", "src/main/kotlin"),
        )

        val result = assertInstanceOf(
            TopologyCandidateEnumeration.Rejected::class.java,
            AdmittedSourceRootEnumerator().enumerate(workspace),
        )

        assertEquals(
            io.github.amichne.kast.topology.contract.TopologyCandidateEnumerationFailure
                .AMBIGUOUS_SOURCE_ROOT_OWNER,
            result.failure,
        )
    }

    @Test
    fun `lazy traversal failure is closed source content data`() {
        Files.createDirectories(tempDir.resolve("src/main/kotlin"))
        val workspace = workspace(sourceRoot("root.main", ":", "src/main/kotlin"))
        val enumerator = AdmittedSourceRootEnumerator { root -> lazyFailureAfter(root) }

        val result = assertInstanceOf(
            TopologyCandidateEnumeration.Rejected::class.java,
            enumerator.enumerate(workspace),
        )

        assertEquals(TopologyCandidateEnumerationFailure.SOURCE_CONTENT_UNAVAILABLE, result.failure)
    }

    @Test
    fun `cancellation from source traversal propagates`() {
        Files.createDirectories(tempDir.resolve("src/main/kotlin"))
        val workspace = workspace(sourceRoot("root.main", ":", "src/main/kotlin"))
        val enumerator = AdmittedSourceRootEnumerator { _ ->
            throw CancellationException("injected cancellation")
        }

        assertThrows(CancellationException::class.java) { enumerator.enumerate(workspace) }
    }

    private fun write(relative: String, content: String) {
        val file = tempDir.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    private fun lazyFailureAfter(first: Path): Stream<Path> {
        val paths = object : Iterator<Path> {
            private var emitted = false

            override fun hasNext(): Boolean = if (emitted) {
                throw UncheckedIOException(IOException("injected lazy traversal failure"))
            } else {
                true
            }

            override fun next(): Path = first.also { emitted = true }
        }
        return StreamSupport.stream(
            Spliterators.spliteratorUnknownSize(paths, Spliterator.ORDERED),
            false,
        )
    }

    private fun workspace(vararg roots: SourceRoot): PublishedWorkspace {
        val canonical = tempDir.toRealPath()
        val candidate = WorkspaceCandidate(
            CanonicalWorkspaceRoot.fromCanonicalPath(canonical).refined(),
            WorkspaceStateIdentity.parse("enumeration-state").refined(),
        )
        return PublishedWorkspace.publish(
            ReconciledWorkspace.admit(
                candidate,
                WorkspaceEvidenceKind.entries.toSet(),
                roots.toList(),
            ).refined(),
            EvidenceGeneration.parse(9).refined(),
        )
    }

    private fun sourceRoot(module: String, project: String, location: String): SourceRoot =
        SourceRoot.admit(
            GradleSourceRootEvidence(
                module,
                ".",
                project,
                "main",
                location,
                SourceRootProvenance.Authored,
            ),
        ).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
