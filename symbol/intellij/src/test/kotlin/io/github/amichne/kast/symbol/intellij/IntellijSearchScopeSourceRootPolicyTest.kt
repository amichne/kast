package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.module.Module
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.LightVirtualFile
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolReadableSources
import io.github.amichne.kast.symbol.contract.SymbolSearchOwner
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.IdentityHashMap

class IntellijSearchScopeSourceRootPolicyTest {
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
            request(model, SymbolReadableSources.AUTHORED_ONLY),
            compiled(model),
            ALL_FILES,
            { paths[it].toNativePath() },
        ) {
            queryInvocations += 1
            listOf(it.nativeScope.contains(authoredFile), it.nativeScope.contains(generatedFile))
        }
        val includingGenerated = adapter.execute(
            request(model, SymbolReadableSources.AUTHORED_AND_GENERATED),
            compiled(model),
            ALL_FILES,
            { paths[it].toNativePath() },
        ) {
            queryInvocations += 1
            listOf(it.nativeScope.contains(authoredFile), it.nativeScope.contains(generatedFile))
        }

        assertEquals(listOf(true, false), authoredOnly.completedValue())
        assertEquals(listOf(true, true), includingGenerated.completedValue())
        assertEquals(2, queryInvocations)
    }

    @Test
    fun `rejected or unavailable ownership never reaches native query`() {
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
        ) {
            queryInvocations += 1
        }
        val otherModel = model(boundary(gradleProjectPath = ":other"))
        val unavailable = adapter.execute(
            request(model).copy(
                owner = SymbolSearchOwner.GradleProject(otherModel.sourceRoots.single().project),
            ),
            compiled(model),
            ALL_FILES,
            { IntellijVirtualFilePath.Unavailable },
        ) {
            queryInvocations += 1
        }

        assertTrue(rejected is IntellijScopedQueryResult.Rejected)
        assertTrue(unavailable is IntellijScopedQueryResult.Rejected)
        assertEquals(0, queryInvocations)
        assertFalse(rejected is IntellijScopedQueryResult.Completed)
    }

    private fun request(
        model: WorkspaceSearchScopeModel,
        readableSources: SymbolReadableSources = SymbolReadableSources.AUTHORED_ONLY,
    ): SymbolSearchScopeRequest = SymbolSearchScopeRequest(
        lease = SemanticReadLease(model.workspaceRoot, EvidenceGeneration.parse(7).refined()),
        owner = SymbolSearchOwner.GradleProject(model.sourceRoots.first().project),
        readableSources = readableSources,
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
        gradleProjectPath: String = ":app",
        sourceRoot: String = "/workspace/app/src/main/kotlin",
        provenance: WorkspaceSourceRootProvenance = WorkspaceSourceRootProvenance.AUTHORED,
    ): WorkspaceSourceRootBoundary = WorkspaceSourceRootBoundary(
        ideaModuleName = "app.main",
        linkedBuildRoot = Path.of("/workspace"),
        gradleProjectPath = gradleProjectPath,
        sourceSetName = "main",
        sourceRoot = Path.of(sourceRoot),
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
