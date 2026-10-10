package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.module.Module
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Real scope compilation and SDK hint dispatch; no native index or latency claim. */
class RelationModuleSearchScopeTest {
    private val request = RelationReadTest().request(RelationMeaning.Callers)
    private val root = request.subject.lease.workspaceRoot
    private val model =
        (WorkspaceSearchScopeModel.compile(
                root,
                ImportedWorkspaceModelState.COMPLETE,
                listOf(
                    boundary("app.main", ":app", "main", "app/src/main/kotlin"),
                    boundary("app.test", ":app", "test", "app/src/test/kotlin", WorkspaceSourceRootKind.TEST),
                    boundary("app.generated", ":app", "main", "app/generated", generated = true),
                    boundary("feature.main", ":feature", "main", "app/src/main/kotlin/feature"),
                    boundary("other.main", ":other", "main", "other/src/main/kotlin"),
                    boundary("combined", ":combined", "main", "combined/src/main/kotlin"),
                    boundary("combined", ":combined", "test", "combined/src/test/kotlin", WorkspaceSourceRootKind.TEST),
                ) +
                    (1..256).map {
                        boundary("unrelated.$it", ":noise$it", "main", "noise$it/src/main/kotlin")
                    },
            ) as WorkspaceSearchScopeModelCompilation.Compiled)
            .model

    @Test
    fun `exact file excludes unrelated modules before candidate enumeration`() {
        val sdk = RelationScopeSdkFixture()
        val file =
            CanonicalWorkspaceFilePath.fromCanonicalPath(root, Path.of("/workspace/app/src/main/kotlin/A.kt")).refined()
        val scope = compile(sdk, SymbolSearchScope.ExactFile(file, bothKinds, authored))
        assertTrue(scope.isSearchInModuleContent(sdk.module("app.main")))
        assertTrue(scope.isSearchInModuleContent(sdk.module("app.main"), false))
        assertFalse(scope.isSearchInModuleContent(sdk.module("app.main"), true))
        val candidates = (1..256).map { sdk.module("unrelated.$it") } + sdk.module("app.main")
        assertEquals(listOf("app.main"), candidates.filter(scope::isSearchInModuleContent).map(Module::getName))
        assertFalse(scope.isSearchInLibraries)
        sdk.assertConsumed()
    }

    @Test
    fun `module source set and Gradle project preserve modeled owners`() {
        val sdk = RelationScopeSdkFixture()
        val owner = model.sourceRoots.first { it.module.value == "app.main" }
        val module = compile(sdk, SymbolSearchScope.Module(owner.module, bothKinds, authored))
        assertTrue(module.isSearchInModuleContent(sdk.module("app.main")))
        assertFalse(module.isSearchInModuleContent(sdk.module("app.test")))
        val sourceSet = compile(sdk, SymbolSearchScope.SourceSet(owner.project, owner.sourceSet, bothKinds, authored))
        assertTrue(sourceSet.isSearchInModuleContent(sdk.module("app.main")))
        assertFalse(sourceSet.isSearchInModuleContent(sdk.module("app.generated")))
        assertFalse(sourceSet.isSearchInModuleContent(sdk.module("app.test")))
        val project = compile(sdk, SymbolSearchScope.GradleProject(owner.project, bothKinds, authored))
        assertTrue(project.isSearchInModuleContent(sdk.module("app.main")))
        assertTrue(project.isSearchInModuleContent(sdk.module("app.test"), true))
        assertFalse(project.isSearchInModuleContent(sdk.module("other.main")))
        sdk.assertConsumed()
    }

    @Test
    fun `source kind overloads retain both roots of one native module`() {
        val sdk = RelationScopeSdkFixture()
        val owner = model.sourceRoots.first { it.module.value == "combined" }
        val both = compile(sdk, SymbolSearchScope.Module(owner.module, bothKinds, authored))
        assertTrue(both.isSearchInModuleContent(sdk.module("combined"), false))
        assertTrue(both.isSearchInModuleContent(sdk.module("combined"), true))
        val test = compile(sdk, SymbolSearchScope.Module(owner.module, SymbolSourceKindPolicy.TEST_ONLY, authored))
        assertFalse(test.isSearchInModuleContent(sdk.module("combined"), false))
        assertTrue(test.isSearchInModuleContent(sdk.module("combined"), true))
        assertNotEquals(both, test)
        sdk.assertConsumed()
    }

    @Test
    fun `named source sets narrow workspace module hints`() {
        val sdk = RelationScopeSdkFixture()
        val testSet = model.sourceRoots.first { it.module.value == "app.test" }.sourceSet
        val constraints =
            SymbolDiscoveryConstraints.None.copy(
                sourceSets = SymbolDiscoverySourceSets.Exact.from(setOf(testSet)).refined()
            )
        val scope = compile(sdk, workspace(), constraints)
        assertFalse(scope.isSearchInModuleContent(sdk.module("app.main")))
        assertTrue(scope.isSearchInModuleContent(sdk.module("app.test"), true))
        assertTrue(scope.isSearchInModuleContent(sdk.module("combined"), true))
        assertFalse(scope.isSearchInModuleContent(sdk.module("combined"), false))
        sdk.assertConsumed()
    }

    @Test
    fun `generated policy retains only admitted module roots`() {
        val sdk = RelationScopeSdkFixture()
        val exclude = compile(sdk, workspace())
        val include = compile(sdk, workspace().copy(generatedSources = SymbolGeneratedSourcePolicy.INCLUDE))
        assertFalse(exclude.isSearchInModuleContent(sdk.module("app.generated")))
        assertTrue(include.isSearchInModuleContent(sdk.module("app.generated")))
        assertTrue(exclude.isSearchInModuleContent(sdk.module("app.main")))
        sdk.assertConsumed()
    }

    @Test
    fun `descendant directory keeps intersecting roots and excludes disjoint modules`() {
        val sdk = RelationScopeSdkFixture()
        val scope = compile(sdk, workspace(), directory("app/src/main/kotlin", SymbolDiscoveryContainment.DESCENDANTS))
        assertTrue(scope.isSearchInModuleContent(sdk.module("app.main")))
        assertTrue(scope.isSearchInModuleContent(sdk.module("feature.main")))
        assertFalse(scope.isSearchInModuleContent(sdk.module("app.test")))
        assertFalse(scope.isSearchInModuleContent(sdk.module("other.main")))
        sdk.assertConsumed()
    }

    @Test
    fun `direct directory excludes descendant roots but retains its containing root`() {
        val sdk = RelationScopeSdkFixture()
        val scope = compile(sdk, workspace(), directory("app/src/main/kotlin", SymbolDiscoveryContainment.DIRECT))
        assertTrue(scope.isSearchInModuleContent(sdk.module("app.main")))
        assertFalse(scope.isSearchInModuleContent(sdk.module("feature.main")))
        assertFalse(scope.isSearchInModuleContent(sdk.module("other.main")))
        val ancestor = compile(sdk, workspace(), directory("app", SymbolDiscoveryContainment.DIRECT))
        assertFalse(ancestor.isSearchInModuleContent(sdk.module("app.main")))
        assertFalse(ancestor.isSearchInModuleContent(sdk.module("feature.main")))
        sdk.assertConsumed()
    }

    @Test
    fun `directory containment remains part of native scope identity`() {
        val sdk = RelationScopeSdkFixture()
        val direct = compile(sdk, workspace(), directory("other/src/main/kotlin", SymbolDiscoveryContainment.DIRECT))
        val descendants =
            compile(sdk, workspace(), directory("other/src/main/kotlin", SymbolDiscoveryContainment.DESCENDANTS))
        assertTrue(direct.isSearchInModuleContent(sdk.module("other.main")))
        assertTrue(descendants.isSearchInModuleContent(sdk.module("other.main")))
        assertNotEquals(direct, descendants)
        sdk.assertConsumed()
    }

    @Test
    fun `exact file outside intersected directory admits no module search`() {
        val sdk = RelationScopeSdkFixture()
        val file =
            CanonicalWorkspaceFilePath.fromCanonicalPath(root, Path.of("/workspace/app/src/main/kotlin/A.kt")).refined()
        val scope =
            compile(
                sdk,
                SymbolSearchScope.ExactFile(file, bothKinds, authored),
                directory("other", SymbolDiscoveryContainment.DESCENDANTS),
            )
        assertFalse(scope.isSearchInModuleContent(sdk.module("app.main")))
        sdk.assertConsumed()
    }

    @Test
    fun `explicit library policy survives source module narrowing`() {
        val sdk = RelationScopeSdkFixture()
        val include =
            compile(
                sdk,
                workspace().copy(libraries = SymbolLibraryPolicy.INCLUDE),
                directory("app", SymbolDiscoveryContainment.DESCENDANTS),
            )
        val exclude = compile(sdk, workspace(), directory("app", SymbolDiscoveryContainment.DESCENDANTS))
        assertTrue(include.isSearchInLibraries)
        assertFalse(exclude.isSearchInLibraries)
        assertNotEquals(include, exclude)
        assertTrue(include.isSearchInModuleContent(sdk.module("app.main")))
        assertFalse(include.isSearchInModuleContent(sdk.module("other.main")))
        sdk.assertConsumed()
    }

    @Test
    fun `unknown disposed and foreign project modules fail closed`() {
        val sdk = RelationScopeSdkFixture()
        val scope = compile(sdk, workspace())
        assertFalse(scope.isSearchInModuleContent(sdk.module("unknown")))
        assertFalse(scope.isSearchInModuleContent(sdk.module("app.main", disposed = true)))
        assertFalse(scope.isSearchInModuleContent(sdk.module("app.main", project = RelationScopeSdkFixture().project)))
        assertFalse(scope.isSearchInModuleContent(sdk.module("app.main", disposed = true), false))
        assertFalse(
            scope.isSearchInModuleContent(sdk.module("app.main", project = RelationScopeSdkFixture().project), false)
        )
        sdk.assertConsumed()
    }

    private fun compile(
        sdk: RelationScopeSdkFixture,
        selected: SymbolSearchScope,
        constraints: SymbolDiscoveryConstraints = SymbolDiscoveryConstraints.None,
    ): GlobalSearchScope =
        (IntellijRelationScopeCompiler()
                .compile(
                    sdk.project,
                    request,
                    WorkspaceSearchScopeModelCompilation.Compiled(model),
                    selected,
                    constraints,
                ) as IntellijRelationScopeCompilation.Compiled)
            .scope
            .nativeScope

    private fun workspace() = SymbolSearchScope.Workspace(bothKinds, authored, SymbolLibraryPolicy.EXCLUDE)

    private fun directory(path: String, containment: SymbolDiscoveryContainment) =
        SymbolDiscoveryConstraints.None.copy(
            directory = SymbolDiscoveryDirectoryConstraint(SymbolDiscoveryDirectory.parse(path).refined(), containment)
        )

    private fun boundary(
        module: String,
        project: String,
        sourceSet: String,
        path: String,
        kind: WorkspaceSourceRootKind = WorkspaceSourceRootKind.PRODUCTION,
        generated: Boolean = false,
    ) =
        WorkspaceSourceRootBoundary(
            module,
            Path.of(root.value),
            project,
            sourceSet,
            Path.of(root.value).resolve(path),
            kind,
            if (generated) WorkspaceSourceRootProvenance.GENERATED else WorkspaceSourceRootProvenance.AUTHORED,
        )

    private fun <T, F> Refinement<T, F>.refined(): T = (this as Refinement.Refined).value

    private val bothKinds = SymbolSourceKindPolicy.PRODUCTION_AND_TEST
    private val authored = SymbolGeneratedSourcePolicy.EXCLUDE
}
