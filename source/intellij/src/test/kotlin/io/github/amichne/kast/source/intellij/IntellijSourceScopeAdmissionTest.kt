package io.github.amichne.kast.source.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.source.contract.DeclarationKind
import io.github.amichne.kast.source.contract.SourceReadScope
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDeclarationKinds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPackage
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPackageConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IntellijSourceScopeAdmissionTest {
    private val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
    private val model =
        (WorkspaceSearchScopeModel.compile(
                root,
                ImportedWorkspaceModelState.COMPLETE,
                listOf(
                    WorkspaceSourceRootBoundary(
                        "app.main",
                        Path.of("/workspace"),
                        ":app",
                        "main",
                        Path.of("/workspace/src"),
                        WorkspaceSourceRootKind.PRODUCTION,
                        WorkspaceSourceRootProvenance.AUTHORED,
                    ),
                    WorkspaceSourceRootBoundary(
                        "app.test",
                        Path.of("/workspace"),
                        ":app",
                        "test",
                        Path.of("/workspace/src/test"),
                        WorkspaceSourceRootKind.TEST,
                        WorkspaceSourceRootProvenance.AUTHORED,
                    ),
                    WorkspaceSourceRootBoundary(
                        "app.main",
                        Path.of("/workspace"),
                        ":app",
                        "generatedMain",
                        Path.of("/workspace/src/generated"),
                        WorkspaceSourceRootKind.PRODUCTION,
                        WorkspaceSourceRootProvenance.GENERATED,
                    ),
                ),
            ) as WorkspaceSearchScopeModelCompilation.Compiled)
            .model

    @Test
    fun `fresh child candidates retain source-set scope and carry their native declaration kind`() {
        val retained =
            SymbolDiscoveryConstraints.None.copy(
                directory =
                    SymbolDiscoveryDirectoryConstraint(
                        SymbolDiscoveryDirectory.parse("src").refined(),
                        SymbolDiscoveryContainment.DESCENDANTS,
                    ),
                packageName =
                    SymbolDiscoveryPackageConstraint(
                        SymbolDiscoveryPackage.parse("sample").refined(),
                        SymbolDiscoveryContainment.DIRECT,
                    ),
                sourceSets = SymbolDiscoverySourceSets.Exact.from(setOf(model.sourceRoots.first().sourceSet)).refined(),
                declarationKinds = SymbolDiscoveryDeclarationKinds.from(setOf(CompilerSymbolKind.CLASSLIKE)).refined(),
            )
        val child = retained.forSourceDeclaration(DeclarationKind.FUNCTION)
        assertEquals(retained.sourceSets, child.sourceSets)
        assertEquals(retained.directory, child.directory)
        assertEquals(retained.packageName, child.packageName)
        assertEquals(setOf(CompilerSymbolKind.FUNCTION), child.declarationKinds?.values)
        assertEquals(setOf(CompilerSymbolKind.CLASSLIKE), retained.declarationKinds?.values)
    }

    @Test
    fun `retained exact-file scope rejects an otherwise valid different source file`() {
        val allowed = Path.of("/workspace/src/Allowed.kt")
        val scope =
            SourceReadScope.Constrained(
                SymbolSearchScope.ExactFile(
                    CanonicalWorkspaceFilePath.fromCanonicalPath(root, allowed).refined(),
                    SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    SymbolGeneratedSourcePolicy.INCLUDE,
                ),
                SymbolDiscoveryConstraints.None,
            )
        assertTrue(admitsSourceReadScope(model, scope, allowed))
        assertFalse(admitsSourceReadScope(model, scope, Path.of("/workspace/src/Other.kt")))
    }

    @Test
    fun `most specific test and generated ownership cannot inherit an allowed authored parent`() {
        val scope =
            SourceReadScope.Constrained(
                SymbolSearchScope.Workspace(
                    SymbolSourceKindPolicy.PRODUCTION_ONLY,
                    SymbolGeneratedSourcePolicy.EXCLUDE,
                    SymbolLibraryPolicy.EXCLUDE,
                ),
                SymbolDiscoveryConstraints.None,
            )
        assertTrue(admitsSourceReadScope(model, scope, Path.of("/workspace/src/Main.kt")))
        assertFalse(admitsSourceReadScope(model, scope, Path.of("/workspace/src/test/Test.kt")))
        assertFalse(admitsSourceReadScope(model, scope, Path.of("/workspace/src/generated/Generated.kt")))
    }

    @Test
    fun `retained module and source-set owners must own the actual source file`() {
        val owner = model.sourceRoots.single { it.sourceSet.value == "test" }
        val scopes =
            listOf(
                SymbolSearchScope.Module(
                    owner.module,
                    SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    SymbolGeneratedSourcePolicy.INCLUDE,
                ),
                SymbolSearchScope.SourceSet(
                    owner.project,
                    owner.sourceSet,
                    SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    SymbolGeneratedSourcePolicy.INCLUDE,
                ),
            )
        for (scope in scopes) {
            val constrained = SourceReadScope.Constrained(scope, SymbolDiscoveryConstraints.None)
            assertTrue(admitsSourceReadScope(model, constrained, Path.of("/workspace/src/test/Test.kt")))
            assertFalse(admitsSourceReadScope(model, constrained, Path.of("/workspace/src/Main.kt")))
        }
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Expected refinement, got $failure")
        }
}
