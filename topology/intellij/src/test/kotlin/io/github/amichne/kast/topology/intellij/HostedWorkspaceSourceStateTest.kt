package io.github.amichne.kast.topology.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.GradleSourceRootEvidence
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HostedWorkspaceSourceStateTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `unchanged exact source state is stable and content changes advance it`() {
        write("src/main/kotlin/sample/Example.kt", "package sample\nclass Example")
        write("src/main/java/sample/Ignored.java", "class Ignored {}")
        val root = canonicalRoot()
        val sourceRoots = listOf(sourceRoot("root.main", ":", "src/main/kotlin"))

        val first = admitted(root, sourceRoots)
        val reopened = admitted(root, sourceRoots)
        write("src/main/kotlin/sample/Example.kt", "package sample\nclass Example(val value: Int)")
        val changed = admitted(root, sourceRoots)

        assertEquals(first.sourceState, reopened.sourceState)
        assertNotEquals(first.sourceState, changed.sourceState)
    }

    @Test
    fun `overlapping source ownership rejects instead of selecting one`() {
        write("src/main/kotlin/Shared.kt", "class Shared")

        val result = assertInstanceOf(
            HostedWorkspaceSourceStateAdmission.Rejected::class.java,
            observeHostedWorkspaceSourceState(
                canonicalRoot(),
                listOf(
                    sourceRoot("root.main", ":", "src/main/kotlin"),
                    sourceRoot("shared.main", ":shared", "src/main/kotlin"),
                ),
            ),
        )

        assertEquals(
            HostedWorkspaceSourceStateAdmissionFailure.AmbiguousSourceRootOwner,
            result.failure,
        )
    }

    private fun admitted(
        root: CanonicalWorkspaceRoot,
        sourceRoots: List<SourceRoot>,
    ): HostedWorkspaceSourceStateAdmission.Admitted = assertInstanceOf(
        HostedWorkspaceSourceStateAdmission.Admitted::class.java,
        observeHostedWorkspaceSourceState(root, sourceRoots),
    )

    private fun canonicalRoot(): CanonicalWorkspaceRoot =
        CanonicalWorkspaceRoot.fromCanonicalPath(tempDir.toRealPath()).refined()

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

    private fun write(relative: String, content: String) {
        val file = tempDir.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
