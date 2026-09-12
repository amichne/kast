package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.workspace.contract.*
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NamedGradleSourceScopeTest {
    private val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).value()
    private val main = entry("main", "code")
    private val custom = entry("integrationTest", "checks", WorkspaceSourceRootKind.TEST)

    @Test
    fun `exact Gradle names are independent of folder spelling and production test kind`() {
        val scope = scope(listOf(main, custom))
        assertTrue(scope.contains(Path.of("/workspace/code/Main.kt"), names("main", "test")))
        assertFalse(scope.contains(Path.of("/workspace/checks/Test.kt"), names("main", "test")))
        assertTrue(scope.contains(Path.of("/workspace/checks/Test.kt"), names("integrationTest")))
        assertFalse(scope.contains(Path.of("/workspace/checks/Test.kt"), names("IntegrationTest")))
    }

    @Test
    fun `known model with unmatched source set admits a complete empty scope`() {
        val scope = scope(listOf(main))
        assertFalse(scope.contains(Path.of("/workspace/code/Main.kt"), names("missing")))
    }

    @Test
    fun `missing cached model and missing nested ownership cannot become empty scopes`() {
        assertEquals(
            NamedGradleSourceScopeFailure.MODEL_UNAVAILABLE,
            NamedGradleSourceScope.admit(root, NamedGradleModelObservation.Unavailable, emptyList()).failure(),
        )
        val roots =
            listOf(
                ide(main),
                IdeCodeSourceRoot(
                    path = Path.of("/workspace/code/nested"),
                    kind = WorkspaceSourceRootKind.PRODUCTION,
                    provenance = WorkspaceSourceRootProvenance.AUTHORED,
                    evidence = evidence(Path.of("/workspace/code/nested")),
                ),
            )
        assertEquals(
            NamedGradleSourceScopeFailure.RootMapping(
                IdeRootMappingFailure.GradleOwnerMissing(evidence(Path.of("/workspace/code/nested")))
            ),
            NamedGradleSourceScope.admit(root, NamedGradleModelObservation.Captured(listOf(main)), roots).failure(),
        )
    }

    @Test
    fun `most specific owner wins before source set and generated exclusions`() {
        val nested = entry("integrationTest", "code/nested", WorkspaceSourceRootKind.TEST)
        val generated = entry("main", "code/generated", provenance = WorkspaceSourceRootProvenance.GENERATED)
        val scope = scope(listOf(main, nested, generated))
        assertFalse(scope.contains(Path.of("/workspace/code/nested/Test.kt"), names("main", "test")))
        assertTrue(scope.contains(Path.of("/workspace/code/nested/Test.kt"), names("integrationTest")))
        assertFalse(scope.contains(Path.of("/workspace/code/generated/Gen.kt"), SymbolDiscoverySourceSets.All))
        assertFalse(scope.contains(Path.of("/workspace/code-other/Other.kt"), SymbolDiscoverySourceSets.All))
    }

    @Test
    fun `excluded and resource roots cannot inherit an allowed ancestor`() {
        val nested = entry("main", "code/nested")
        val entries = listOf(main, nested)
        val admitted =
            NamedGradleSourceScope.admit(
                    root,
                    NamedGradleModelObservation.Captured(
                        entries,
                        setOf(nested.sourceRoot, Path.of("/workspace/code/resources")),
                    ),
                    entries.map(::ide),
                )
                .value()
        assertFalse(admitted.contains(Path.of("/workspace/code/nested/Hidden.kt"), names("main")))
        assertFalse(admitted.contains(Path.of("/workspace/code/resources/Resource.kt"), names("main")))
        assertTrue(admitted.contains(Path.of("/workspace/code/Visible.kt"), names("main")))
    }

    @Test
    fun `inconsistent IDE and imported root classifications reject`() {
        val rootObservation = ide(main).copy(provenance = WorkspaceSourceRootProvenance.GENERATED)
        assertEquals(
            NamedGradleSourceScopeFailure.RootMapping(
                IdeRootMappingFailure.RootClassificationMismatch(
                    rootObservation.evidence,
                    CodeSourceRootClassification(rootObservation.kind, rootObservation.provenance),
                    CodeSourceRootClassification(main.sourceKind, main.provenance),
                )
            ),
            NamedGradleSourceScope.admit(
                    root,
                    NamedGradleModelObservation.Captured(listOf(main)),
                    listOf(rootObservation),
                )
                .failure(),
        )
    }

    @Test
    fun `foreign imported roots and partially mapped models reject`() {
        val rejected =
            assertInstanceOf(
                NamedGradleSourceScopeFailure.ModelRejected::class.java,
                NamedGradleSourceScope.admit(
                        root,
                        NamedGradleModelObservation.Captured(listOf(main.copy(sourceRoot = Path.of("/foreign/code")))),
                        emptyList(),
                    )
                    .failure(),
            )
        assertEquals(
            setOf(
                WorkspaceSearchScopeModelFailure.SOURCE_ROOT_OUTSIDE_WORKSPACE,
                WorkspaceSearchScopeModelFailure.NO_SOURCE_ROOTS,
            ),
            rejected.cause.failures,
        )
    }

    private fun scope(entries: List<WorkspaceSourceRootBoundary>) =
        NamedGradleSourceScope.admit(
                root,
                NamedGradleModelObservation.Captured(entries),
                entries.map(::ide),
            )
            .value()

    private fun ide(entry: WorkspaceSourceRootBoundary) =
        IdeCodeSourceRoot(
            path = entry.sourceRoot,
            kind = entry.sourceKind,
            provenance = entry.provenance,
            evidence = evidence(entry.sourceRoot),
        )

    private fun evidence(path: Path) =
        IdeSourceRootEvidence(BoundedModuleName.observe("module"), BoundedSourceRootIdentity.observe("file://$path"))

    private fun names(vararg values: String) =
        SymbolDiscoverySourceSets.Exact.from(values.map { WorkspaceSourceSetName.parse(it).value() }.toSet()).value()

    private fun entry(
        name: String,
        path: String,
        kind: WorkspaceSourceRootKind = WorkspaceSourceRootKind.PRODUCTION,
        provenance: WorkspaceSourceRootProvenance = WorkspaceSourceRootProvenance.AUTHORED,
    ) =
        WorkspaceSourceRootBoundary(
            "module",
            Path.of("/workspace"),
            ":",
            name,
            Path.of("/workspace/$path"),
            kind,
            provenance,
        )

    private fun <V, F> Refinement<V, F>.value(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Expected refinement: $failure")
        }

    private fun <V, F> Refinement<V, F>.failure(): F =
        when (this) {
            is Refinement.Refined -> error("Expected rejection")
            is Refinement.Rejected -> failure
        }
}
