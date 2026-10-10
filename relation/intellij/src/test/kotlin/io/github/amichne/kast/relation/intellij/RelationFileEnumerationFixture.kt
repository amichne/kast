package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.module.Module
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallOutcome
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals

/** Case-owned observations shared by the inventory and SDK-intersection consumers. */
internal open class RelationFileEnumerationFixture {
    protected val request = RelationReadTest().request(RelationMeaning.References, workLimit = 200)
    protected val path = Path.of("/workspace/app/src")
    protected val a = EnumerationTestFile(path.resolve("A.kt"), 11)
    protected val b = EnumerationTestFile(path.resolve("B.kt"), 12)
    protected val root = EnumerationTestFile(path, 10, listOf(a, b))
    protected val model =
        WorkspaceSearchScopeModel.compile(
            request.subject.lease.workspaceRoot,
            ImportedWorkspaceModelState.COMPLETE,
            listOf(boundary("app.main", ":app", "app/src"), boundary("noise.main", ":noise", "noise/src")),
        )
    protected val roots = (model as WorkspaceSearchScopeModelCompilation.Compiled).model.sourceRoots
    protected val sourceScope =
        SymbolSearchScope.SourceSet(
            roots[0].project,
            roots[0].sourceSet,
            SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
            SymbolGeneratedSourcePolicy.EXCLUDE,
        )

    protected fun plan() =
        RelationFileEnumerationPlan.compile(sourceScope, roots.take(1), "/workspace", SymbolDiscoveryConstraints.None) {
            true
        }

    protected fun prepare(
        plan: RelationFileEnumerationPlan,
        allowance: IntellijRelationAllowance = IntellijRelationAllowance { 0L },
        limits: ReadLimits = ReadLimits.Default,
        observation: IntellijReadObservation = IntellijReadObservation.None,
        lookupRoot: EnumerationTestFile = root,
    ): RelationFileEnumerationPreparation {
        val lookups = mutableListOf<Path>()
        val result =
            CompleteRelationFileUniverse.prepare(
                plan,
                request.budget.resources,
                allowance,
                limits,
                observation,
                lookup = { expected ->
                    lookups.add(expected)
                    check(expected == path)
                    RelationNativeFileLookup.Found(lookupRoot)
                },
            )
        assertEquals(listOf(path), lookups)
        return result
    }

    protected fun RelationFileEnumerationPreparation.complete(): CompleteRelationFileUniverse =
        (this as RelationFileEnumerationPreparation.Complete).universe

    protected fun nativeScope(contains: (VirtualFile) -> Boolean) =
        object : GlobalSearchScope() {
            override fun contains(file: VirtualFile): Boolean = contains(file)

            override fun isSearchInModuleContent(module: Module): Boolean = true

            override fun isSearchInLibraries(): Boolean = false
        }

    protected fun boundary(module: String, project: String, directory: String) =
        WorkspaceSourceRootBoundary(
            module,
            Path.of("/workspace"),
            project,
            "main",
            Path.of("/workspace").resolve(directory),
            WorkspaceSourceRootKind.PRODUCTION,
            WorkspaceSourceRootProvenance.AUTHORED,
        )

    protected fun directory(value: String, containment: SymbolDiscoveryContainment) =
        SymbolDiscoveryConstraints.None.copy(
            directory =
                SymbolDiscoveryDirectoryConstraint(
                    (SymbolDiscoveryDirectory.parse(value) as Refinement.Refined).value,
                    containment,
                )
        )
}

internal class EnumerationObservation : IntellijReadObservation {
    val counts = mutableMapOf<IntellijReadCounter, Long>()
    val finished = mutableListOf<Pair<IntellijReadCall, IntellijReadCallOutcome>>()

    override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
        counts[counter] = (counts[counter] ?: 0L) + amount
    }

    override fun enterCall(call: IntellijReadCall): IntellijReadCallScope =
        object : IntellijReadCallScope {
            private var closed = false

            override fun finish(outcome: IntellijReadCallOutcome) {
                check(!closed)
                closed = true
                finished += call to outcome
            }
        }

    override fun terminated(
        reason: io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination,
        contributor: IntellijReadContributor,
    ) = error("Unexpected termination")
}
