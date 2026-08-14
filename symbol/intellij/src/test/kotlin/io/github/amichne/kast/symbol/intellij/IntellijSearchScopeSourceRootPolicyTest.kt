package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.module.Module
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.LightVirtualFile
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelFailure
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.IdentityHashMap

class IntellijSearchScopeSourceRootPolicyTest {
    @Test
    fun `typed exact module source-set project workspace and library policies compile before query`() {
        val model = model(
            boundary(),
            boundary(
                ideaModuleName = "app.test",
                sourceSetName = "test",
                sourceRoot = "/workspace/app/src/test/kotlin",
                sourceKind = WorkspaceSourceRootKind.TEST,
            ),
            boundary(
                sourceSetName = "generatedMain",
                sourceRoot = "/workspace/custom/generated-outside-output",
                provenance = WorkspaceSourceRootProvenance.GENERATED,
            ),
        )
        val project = model.sourceRoots.first().project
        val testRoot = model.sourceRoots.single { it.sourceSet.value == "test" }
        val mainFile = LightVirtualFile("Main.kt")
        val testFile = LightVirtualFile("MainTest.kt")
        val generatedFile = LightVirtualFile("Generated.kt")
        val libraryFile = LightVirtualFile("Library.class")
        val paths = IdentityHashMap<VirtualFile, Path>().apply {
            put(mainFile, Path.of("/workspace/app/src/main/kotlin/Main.kt"))
            put(testFile, Path.of("/workspace/app/src/test/kotlin/MainTest.kt"))
            put(generatedFile, Path.of("/workspace/custom/generated-outside-output/Generated.kt"))
            put(libraryFile, Path.of("/libraries/Library.class"))
        }
        val policies = listOf(
            SymbolSearchScope.ExactFile(
                file = CanonicalWorkspaceFilePath.fromCanonicalPath(
                    model.workspaceRoot,
                    paths.getValue(mainFile),
                ).refined(),
                sourceKinds = SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                generatedSources = SymbolGeneratedSourcePolicy.INCLUDE,
            ) to listOf(true, false, false, false),
            SymbolSearchScope.Module(
                module = model.sourceRoots.first().module,
                sourceKinds = SymbolSourceKindPolicy.PRODUCTION_ONLY,
                generatedSources = SymbolGeneratedSourcePolicy.INCLUDE,
            ) to listOf(true, false, true, false),
            SymbolSearchScope.SourceSet(
                project = project,
                sourceSet = testRoot.sourceSet,
                sourceKinds = SymbolSourceKindPolicy.TEST_ONLY,
                generatedSources = SymbolGeneratedSourcePolicy.EXCLUDE,
            ) to listOf(false, true, false, false),
            SymbolSearchScope.GradleProject(
                project = project,
                sourceKinds = SymbolSourceKindPolicy.PRODUCTION_ONLY,
                generatedSources = SymbolGeneratedSourcePolicy.EXCLUDE,
            ) to listOf(true, false, false, false),
            SymbolSearchScope.Workspace(
                sourceKinds = SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                generatedSources = SymbolGeneratedSourcePolicy.INCLUDE,
                libraries = SymbolLibraryPolicy.EXCLUDE,
            ) to listOf(true, true, true, false),
            SymbolSearchScope.Workspace(
                sourceKinds = SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                generatedSources = SymbolGeneratedSourcePolicy.INCLUDE,
                libraries = SymbolLibraryPolicy.INCLUDE,
            ) to listOf(true, true, true, true),
        )
        val adapter = IntellijSearchScopeQueryAdapter()
        var queryInvocations = 0

        policies.forEach { (scope, expected) ->
            val result = adapter.execute(
                request = SymbolSearchScopeRequest(
                    lease = SemanticReadLease(model.workspaceRoot, EvidenceGeneration.parse(7).refined()),
                    scope = scope,
                ),
                modelCompilation = compiled(model),
                baseScope = ALL_FILES,
                nativePath = { paths[it].toNativePath() },
                libraryMembership = {
                    if (it === libraryFile) {
                        IntellijLibraryMembership.LIBRARY
                    } else {
                        IntellijLibraryMembership.NOT_LIBRARY
                    }
                },
            ) {
                queryInvocations += 1
                listOf(mainFile, testFile, generatedFile, libraryFile).map(it.nativeScope::contains)
            }
            assertEquals(expected, result.completedValue())
        }
        assertEquals(policies.size, queryInvocations)
    }

    @Test
    fun `compiled scope filters by model provenance before invoking native query`() {
        val model = model(
            boundary(
                sourceRoot = "/workspace/build/generated/authored-by-model",
                provenance = WorkspaceSourceRootProvenance.AUTHORED,
            ),
            boundary(
                sourceRoot = "/workspace/generated-outside-build",
                provenance = WorkspaceSourceRootProvenance.GENERATED,
            ),
        )
        val authoredFile = LightVirtualFile("Authored.kt")
        val generatedFile = LightVirtualFile("Generated.kt")
        val paths = IdentityHashMap<VirtualFile, Path>().apply {
            put(authoredFile, Path.of("/workspace/build/generated/authored-by-model/Authored.kt"))
            put(generatedFile, Path.of("/workspace/generated-outside-build/Generated.kt"))
        }
        val adapter = IntellijSearchScopeQueryAdapter()
        var queryInvocations = 0

        val authoredOnly = adapter.execute(
            request(model, SymbolGeneratedSourcePolicy.EXCLUDE),
            compiled(model),
            ALL_FILES,
            { paths[it].toNativePath() },
            { IntellijLibraryMembership.NOT_LIBRARY },
        ) {
            queryInvocations += 1
            listOf(it.nativeScope.contains(authoredFile), it.nativeScope.contains(generatedFile))
        }
        val includingGenerated = adapter.execute(
            request(model, SymbolGeneratedSourcePolicy.INCLUDE),
            compiled(model),
            ALL_FILES,
            { paths[it].toNativePath() },
            { IntellijLibraryMembership.NOT_LIBRARY },
        ) {
            queryInvocations += 1
            listOf(it.nativeScope.contains(authoredFile), it.nativeScope.contains(generatedFile))
        }

        assertEquals(listOf(true, false), authoredOnly.completedValue())
        assertEquals(listOf(true, true), includingGenerated.completedValue())
        assertEquals(2, queryInvocations)
    }

    @Test
    fun `rejected unavailable or unknown ownership never reaches native query`() {
        val model = model(boundary())
        val adapter = IntellijSearchScopeQueryAdapter()
        var queryInvocations = 0
        val rejectedModel = WorkspaceSearchScopeModel.compile(
            workspaceRoot(),
            ImportedWorkspaceModelState.INCOMPLETE,
            emptyList(),
        )

        val rejected = adapter.execute(
            request(model),
            rejectedModel,
            ALL_FILES,
            { IntellijVirtualFilePath.Unavailable },
            { IntellijLibraryMembership.NOT_LIBRARY },
        ) {
            queryInvocations += 1
        }
        val otherModel = model(boundary(gradleProjectPath = ":other"))
        val unavailable = adapter.execute(
            request(model).copy(
                scope = SymbolSearchScope.GradleProject(
                    project = otherModel.sourceRoots.single().project,
                    sourceKinds = SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    generatedSources = SymbolGeneratedSourcePolicy.EXCLUDE,
                ),
            ),
            compiled(model),
            ALL_FILES,
            { IntellijVirtualFilePath.Unavailable },
            { IntellijLibraryMembership.NOT_LIBRARY },
        ) {
            queryInvocations += 1
        }
        val unknownTarget = adapter.execute(
            request(model).copy(
                scope = SymbolSearchScope.ExactFile(
                    file = CanonicalWorkspaceFilePath.fromCanonicalPath(
                        model.workspaceRoot,
                        Path.of("/workspace/unowned/Unknown.kt"),
                    ).refined(),
                    sourceKinds = SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    generatedSources = SymbolGeneratedSourcePolicy.INCLUDE,
                ),
            ),
            compiled(model),
            ALL_FILES,
            { IntellijVirtualFilePath.Unavailable },
            { IntellijLibraryMembership.NOT_LIBRARY },
        ) {
            queryInvocations += 1
        }
        val ambiguousModel = model(
            boundary(sourceSetName = "main"),
            boundary(sourceSetName = "shared"),
        )
        val ambiguousTarget = adapter.execute(
            request(ambiguousModel).copy(
                scope = SymbolSearchScope.ExactFile(
                    file = CanonicalWorkspaceFilePath.fromCanonicalPath(
                        ambiguousModel.workspaceRoot,
                        Path.of("/workspace/app/src/main/kotlin/Ambiguous.kt"),
                    ).refined(),
                    sourceKinds = SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    generatedSources = SymbolGeneratedSourcePolicy.INCLUDE,
                ),
            ),
            compiled(ambiguousModel),
            ALL_FILES,
            { IntellijVirtualFilePath.Unavailable },
            { IntellijLibraryMembership.NOT_LIBRARY },
        ) {
            queryInvocations += 1
        }

        assertEquals(
            setOf(
                IntellijSearchScopeFailure.ProjectModelRejected(
                    setOf(WorkspaceSearchScopeModelFailure.MODEL_INCOMPLETE),
                ),
            ),
            (rejected as IntellijScopedQueryResult.Rejected).failures,
        )
        assertTrue(unavailable is IntellijScopedQueryResult.Rejected)
        assertEquals(
            setOf(IntellijSearchScopeFailure.TargetProvenanceUnknown),
            (unknownTarget as IntellijScopedQueryResult.Rejected).failures,
        )
        assertEquals(
            setOf(IntellijSearchScopeFailure.TargetOwnershipAmbiguous),
            (ambiguousTarget as IntellijScopedQueryResult.Rejected).failures,
        )
        assertEquals(0, queryInvocations)
    }

    private fun request(
        model: WorkspaceSearchScopeModel,
        generatedSources: SymbolGeneratedSourcePolicy = SymbolGeneratedSourcePolicy.EXCLUDE,
    ): SymbolSearchScopeRequest = SymbolSearchScopeRequest(
        lease = SemanticReadLease(model.workspaceRoot, EvidenceGeneration.parse(7).refined()),
        scope = SymbolSearchScope.GradleProject(
            project = model.sourceRoots.first().project,
            sourceKinds = SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
            generatedSources = generatedSources,
        ),
    )

    private fun model(vararg boundaries: WorkspaceSourceRootBoundary): WorkspaceSearchScopeModel =
        when (val compilation = WorkspaceSearchScopeModel.compile(
            workspaceRoot(),
            ImportedWorkspaceModelState.COMPLETE,
            boundaries.asList(),
        )) {
            is WorkspaceSearchScopeModelCompilation.Compiled -> compilation.model
            is WorkspaceSearchScopeModelCompilation.Rejected -> error(compilation.failures)
        }

    private fun compiled(model: WorkspaceSearchScopeModel): WorkspaceSearchScopeModelCompilation =
        WorkspaceSearchScopeModelCompilation.Compiled(model)

    private fun boundary(
        ideaModuleName: String = "app.main",
        gradleProjectPath: String = ":app",
        sourceSetName: String = "main",
        sourceRoot: String = "/workspace/app/src/main/kotlin",
        sourceKind: WorkspaceSourceRootKind = WorkspaceSourceRootKind.PRODUCTION,
        provenance: WorkspaceSourceRootProvenance = WorkspaceSourceRootProvenance.AUTHORED,
    ): WorkspaceSourceRootBoundary = WorkspaceSourceRootBoundary(
        ideaModuleName = ideaModuleName,
        linkedBuildRoot = Path.of("/workspace"),
        gradleProjectPath = gradleProjectPath,
        sourceSetName = sourceSetName,
        sourceRoot = Path.of(sourceRoot),
        sourceKind = sourceKind,
        provenance = provenance,
    )

    private fun workspaceRoot(): CanonicalWorkspaceRoot =
        CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()

    private fun Path?.toNativePath(): IntellijVirtualFilePath = when (this) {
        null -> IntellijVirtualFilePath.Unavailable
        else -> IntellijVirtualFilePath.classify(this)
    }

    private fun <Value> IntellijScopedQueryResult<Value>.completedValue(): Value = when (this) {
        is IntellijScopedQueryResult.Completed -> value
        is IntellijScopedQueryResult.Rejected -> error(failures)
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }

    private companion object {
        val ALL_FILES = object : GlobalSearchScope() {
            override fun contains(file: VirtualFile): Boolean = true

            override fun isSearchInModuleContent(aModule: Module): Boolean = true

            override fun isSearchInLibraries(): Boolean = false
        }
    }
}
