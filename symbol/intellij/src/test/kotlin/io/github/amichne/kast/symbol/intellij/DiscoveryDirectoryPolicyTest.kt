package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.module.Module
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.LightVirtualFile
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DiscoveryDirectoryPolicyTest {
    @Test
    fun `precomputed direct descendant and unrestricted predicates preserve live admission and library policy`() {
        for (containment in listOf(null, SymbolDiscoveryContainment.DIRECT, SymbolDiscoveryContainment.DESCENDANTS)) {
            val case = Case(containment)
            val expected =
                when (containment) {
                    null -> listOf(true, true, true, true)
                    SymbolDiscoveryContainment.DIRECT -> listOf(true, false, false, false)
                    SymbolDiscoveryContainment.DESCENDANTS -> listOf(true, true, false, true)
                }
            for ((raw, admitted) in PATHS.zip(expected)) {
                case.path = IntellijVirtualFilePath.classify(Path.of(raw))
                assertEquals(admitted, case.contains(), "$raw $containment")
            }
            assertNativeAuthority(case, expected.count { it })
            assertEquals(
                if (containment == null) 0 else 1,
                case.observation.counters[IntellijReadCounter.DISCOVERY_DIRECTORY_PATH_CONVERSIONS] ?: 0,
            )
        }
    }

    private fun assertNativeAuthority(case: Case, expectedChecks: Int) {
        case.path = IntellijVirtualFilePath.Unavailable
        assertEquals(false, case.contains())
        case.path = IntellijVirtualFilePath.Relative
        assertEquals(false, case.contains())
        case.path = IntellijVirtualFilePath.classify(Path.of(PATHS.first()))
        case.liveSource = false
        assertEquals(false, case.contains())
        assertEquals(expectedChecks, case.detachedChecks)
        // Library authority still bypasses local directory/source admission as before.
        case.library = IntellijLibraryMembership.LIBRARY
        assertEquals(true, case.contains())
        assertEquals(expectedChecks, case.detachedChecks)
    }

    private class Case(containment: SymbolDiscoveryContainment?) {
        val observation = RecordingDiscoveryObservation { 0L }
        var path: IntellijVirtualFilePath = IntellijVirtualFilePath.Unavailable
        var liveSource = true
        var library = IntellijLibraryMembership.NOT_LIBRARY
        var detachedChecks = 0
        private val file = LightVirtualFile("File.kt")
        private val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
        private val model =
            WorkspaceSearchScopeModel.compile(
                root,
                ImportedWorkspaceModelState.COMPLETE,
                listOf(
                    WorkspaceSourceRootBoundary(
                        "module",
                        Path.of("/workspace"),
                        ":",
                        "main",
                        Path.of("/workspace/src"),
                        WorkspaceSourceRootKind.PRODUCTION,
                        WorkspaceSourceRootProvenance.AUTHORED,
                    )
                ),
            )
        private val request =
            SymbolSearchScopeRequest(
                SemanticReadLease(root, EvidenceGeneration.parse(1).refined()),
                SymbolSearchScope.Workspace(
                    SymbolSourceKindPolicy.PRODUCTION_ONLY,
                    SymbolGeneratedSourcePolicy.EXCLUDE,
                    SymbolLibraryPolicy.INCLUDE,
                ),
            )
        private val constraints =
            SymbolDiscoveryConstraints.None.copy(
                directory =
                    containment?.let {
                        SymbolDiscoveryDirectoryConstraint(SymbolDiscoveryDirectory.parse("src/narrow").refined(), it)
                    }
            )
        private val scope =
            (IntellijSearchScopeCompiler(
                        constraints,
                        observation = observation,
                        fileAdmission = {
                            detachedChecks++
                            true
                        },
                    )
                    .compile(
                        request,
                        model,
                        object : GlobalSearchScope() {
                            override fun contains(file: VirtualFile) = true

                            override fun isSearchInModuleContent(module: Module) = true

                            override fun isSearchInLibraries() = true
                        },
                        { path },
                        { library },
                        { liveSource },
                    ) as IntellijSearchScopeCompilation.Compiled)
                .capability
                .nativeScope

        fun contains() = scope.contains(file)
    }

    private companion object {
        val PATHS =
            listOf(
                "/workspace/src/narrow/File.kt",
                "/workspace/src/narrow/nested/File.kt",
                "/workspace/src/narrow-other/File.kt",
                "/workspace/src/narrow",
            )

        fun <V, F> Refinement<V, F>.refined(): V =
            when (this) {
                is Refinement.Refined -> value
                is Refinement.Rejected -> error("Fixture rejected: $failure")
            }
    }
}
