package io.github.amichne.kast.topology.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.GradleSourceRootEvidence
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HostedWorkspaceSourceStateTest {
    @TempDir
    lateinit var tempDir: Path

    private val coldStart = HostedWorkspaceColdStartIdentity.testing("test-project-session")

    @Test
    fun `startup source state does not enumerate or read repository source files`() {
        val startupClosure = listOf(
            "HostedWorkspaceSourceStateKt.class",
            "HostedWorkspaceSourceStateSession.class",
            "HostedWorkspaceSourceVfsListener.class",
            "HostedWorkspaceSourceEventCounter.class",
            "HostedWorkspacePhysicalSourceRoots.class",
        )
        startupClosure.forEach { classFile ->
            val resource = "io/github/amichne/kast/topology/intellij/$classFile"
            val bytes = HostedWorkspaceSourceStateTest::class.java.classLoader
                .getResourceAsStream(resource)
                ?.use { it.readAllBytes() }

            assertNotNull(bytes, resource)
            val constantPool = checkNotNull(bytes).toString(Charsets.ISO_8859_1)
            listOf(
                "java/nio/file/Files",
                "walk",
                "readAllBytes",
                "com/intellij/openapi/vfs/newvfs/ManagingFS",
                "filesystemModificationCount",
            ).forEach { forbidden ->
                assertFalse(forbidden in constantPool, "$resource: $forbidden")
            }
        }
    }

    @Test
    fun `unchanged bounded basis is stable and a source event advances resumed state`() {
        val root = canonicalRoot()
        val sourceRoots = listOf(sourceRoot("root.main", ":", "src/main/kotlin"))

        val first = basis(root, sourceRoots).identity
        val reopened = basis(root, sourceRoots).identity
        val events = HostedWorkspaceSourceEventCounter()
        val observations = liveHostedWorkspaceSourceStateOperations(first, events)
        val unchanged = observations.observed()
        events.advance()
        val changed = observations.observed()

        assertEquals(first, reopened)
        assertEquals(first, unchanged)
        assertNotEquals(first, changed)
    }

    @Test
    fun `bounded source ownership excludes unrelated workspace events`() {
        val root = canonicalRoot()
        val roots = basis(
            root,
            listOf(sourceRoot("root.main", ":", "src/main/kotlin")),
        ).roots
        val physicalRoot = Path.of(root.value)

        assertEquals(true, roots.contains(physicalRoot.resolve("src/main/kotlin/Example.kt")))
        assertEquals(true, roots.contains(physicalRoot.resolve("src/main")))
        assertEquals(false, roots.contains(physicalRoot.resolve("build/generated/Example.kt")))
    }

    @Test
    fun `bounded basis includes source provenance`() {
        val root = canonicalRoot()
        val authored = basis(
            root,
            listOf(sourceRoot("root.main", ":", "src/main/kotlin")),
        ).identity
        val generated = basis(
            root,
            listOf(
                sourceRoot(
                    "root.main",
                    ":",
                    "src/main/kotlin",
                    SourceRootProvenance.Generated,
                ),
            ),
        ).identity

        assertNotEquals(authored, generated)
    }

    @Test
    fun `cold project restart advances the bounded basis without scanning source content`() {
        val root = canonicalRoot()
        val sourceRoots = listOf(sourceRoot("root.main", ":", "src/main/kotlin"))
        val first = basis(root, sourceRoots).identity
        val restarted = assertInstanceOf(
            HostedWorkspaceSourceBasisAdmission.Admitted::class.java,
            admitHostedWorkspaceSourceBasis(
                root,
                sourceRoots,
                HostedWorkspaceColdStartIdentity.testing("restarted-project-session"),
            ),
        ).basis.identity

        assertNotEquals(first, restarted)
    }

    @Test
    fun `proven targeted transition advances state when no VFS event was observed`() {
        val initial = basis(
            canonicalRoot(),
            listOf(sourceRoot("root.main", ":", "src/main/kotlin")),
        ).identity
        val observations = liveHostedWorkspaceSourceStateOperations(
            initial,
            HostedWorkspaceSourceEventCounter(),
        )

        assertEquals(HostedWorkspaceSourceInvalidation.Invalidated, observations.invalidate())

        assertNotEquals(initial, observations.observed())
    }

    @Test
    fun `overlapping source ownership rejects instead of selecting one`() {
        val result = assertInstanceOf(
            HostedWorkspaceSourceBasisAdmission.Rejected::class.java,
            admitHostedWorkspaceSourceBasis(
                canonicalRoot(),
                listOf(
                    sourceRoot("root.main", ":", "src/main/kotlin"),
                    sourceRoot("shared.main", ":shared", "src/main/kotlin"),
                ),
                coldStart,
            ),
        )

        assertEquals(
            HostedWorkspaceSourceStateAdmissionFailure.AmbiguousSourceRootOwner,
            result.failure,
        )
    }

    private fun basis(
        root: CanonicalWorkspaceRoot,
        sourceRoots: List<SourceRoot>,
    ): HostedWorkspaceSourceBasis = assertInstanceOf(
        HostedWorkspaceSourceBasisAdmission.Admitted::class.java,
        admitHostedWorkspaceSourceBasis(root, sourceRoots, coldStart),
    ).basis

    private fun HostedWorkspaceSourceStateOperations.observed():
        io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity = assertInstanceOf(
        HostedWorkspaceSourceStateObservation.Observed::class.java,
        observe(),
    ).sourceState

    private fun canonicalRoot(): CanonicalWorkspaceRoot =
        CanonicalWorkspaceRoot.fromCanonicalPath(tempDir.toRealPath()).refined()

    private fun sourceRoot(
        module: String,
        project: String,
        location: String,
        provenance: SourceRootProvenance = SourceRootProvenance.Authored,
    ): SourceRoot =
        SourceRoot.admit(
            GradleSourceRootEvidence(
                module,
                ".",
                project,
                "main",
                location,
                provenance,
            ),
        ).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
