package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDeclarationKinds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
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

/**
 * Enum entries have no supported compiler declaration kind. Exact-name selection under the public default kinds filters
 * a proven enum entry before candidate accounting, so a name that matches only an enum entry is a complete, empty
 * selection. A query relation hop from that seed rejects with `RELATION_SEED_EMPTY` in the query service.
 */
class EnumEntrySelectionNativeTest : HeavyPlatformTestCase() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    override fun runInDispatchThread() = false

    fun testEnumEntryNameIsACompleteEmptySelection() {
        prepareSources(mapOf("Outcome.kt" to "package sample\n\nenum class Outcome { $TARGET, ACCEPTED }\n"))
        ApplicationManager.getApplication().runReadAction {
            val outcome = discover()
            assertTrue(outcome.toString(), outcome is SymbolDiscoveryOutcome.Complete)
            assertEquals(emptyList<String>(), (outcome as SymbolDiscoveryOutcome.Complete).batch.candidates)
        }
    }

    fun testSameNamePropertyIsSelectedBesideTheFilteredEnumEntry() {
        prepareSources(
            mapOf(
                "Outcome.kt" to "package sample\n\nenum class Outcome { $TARGET, ACCEPTED }\n",
                "Codes.kt" to "package sample\n\nval $TARGET: Int = 1\n",
            )
        )
        ApplicationManager.getApplication().runReadAction {
            val outcome = discover()
            assertTrue(outcome.toString(), outcome is SymbolDiscoveryOutcome.Complete)
            val candidates = (outcome as SymbolDiscoveryOutcome.Complete).batch.candidates
            assertEquals(listOf(TARGET), candidates.map { it.name.value })
            assertTrue(candidates.single().location.file.stableValue.endsWith("/src/Codes.kt"))
        }
    }

    private fun discover(): SymbolDiscoveryOutcome {
        val request = request()
        val compiled = IntellijSearchScopeCompiler(request.constraints).compile(project, request.scope, model(request))
        assertTrue(compiled.toString(), compiled is IntellijSearchScopeCompilation.Compiled)
        val scope = (compiled as IntellijSearchScopeCompilation.Compiled).capability
        val execution =
            IntellijNativeDiscoveryQuery(
                    environmentState = { project.discoveryEnvironmentState() },
                    cancellationCheck = {},
                )
                .discoverNative(project, scope, request)
        assertTrue(execution.toString(), execution is IntellijNativeDiscoveryExecution.Produced)
        return (execution as IntellijNativeDiscoveryExecution.Produced).outcome
    }

    private fun prepareSources(files: Map<String, String>) {
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                val root = getOrCreateProjectBaseDir().createChildDirectory(this, "src")
                ModuleRootModificationUtil.updateModel(module) { model ->
                    model.addContentEntry(root).addSourceFolder(root, false)
                }
                for ((name, text) in files) {
                    root.createChildData(this, name).setBinaryContent(text.toByteArray())
                }
            }
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
    }

    private fun request(): SymbolDiscoveryRequest {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(getOrCreateProjectBaseDir().toNioPath()).refined()
        return SymbolDiscoveryRequest(
            scope =
                SymbolSearchScopeRequest(
                    SemanticReadLease(root, EvidenceGeneration.parse(1).refined()),
                    SymbolSearchScope.Workspace(
                        SymbolSourceKindPolicy.PRODUCTION_ONLY,
                        SymbolGeneratedSourcePolicy.EXCLUDE,
                        SymbolLibraryPolicy.EXCLUDE,
                    ),
                ),
            target =
                SymbolDiscoveryTarget.Name(
                    SymbolNameDiscoveryKind.SYMBOL,
                    SymbolDiscoveryPattern.parse(TARGET).refined(),
                    SymbolDiscoveryMatch.EXACT_NAME,
                ),
            budget =
                SymbolDiscoveryBudget(
                    ResourceBudget(
                        ResultLimit.parse(RESULT_GRANT).refined(),
                        WorkUnitLimit.parse(WORK_GRANT).refined(),
                        ElapsedTimeLimitMillis.parse(ELAPSED_GRANT).refined(),
                    ),
                    SymbolDiscoveryByteLimit.parse(BYTE_GRANT).refined(),
                ),
            constraints =
                SymbolDiscoveryConstraints(
                    null,
                    null,
                    // The public default declaration kinds for a name selection.
                    SymbolDiscoveryDeclarationKinds.from(
                            setOf(
                                CompilerSymbolKind.CLASSLIKE,
                                CompilerSymbolKind.FUNCTION,
                                CompilerSymbolKind.PROPERTY,
                                CompilerSymbolKind.TYPE_ALIAS,
                            )
                        )
                        .refined(),
                ),
        )
    }

    private fun model(request: SymbolDiscoveryRequest) =
        WorkspaceSearchScopeModel.compile(
            request.scope.lease.workspaceRoot,
            ImportedWorkspaceModelState.COMPLETE,
            listOf(
                WorkspaceSourceRootBoundary(
                    ideaModuleName = module.name,
                    linkedBuildRoot = getOrCreateProjectBaseDir().toNioPath(),
                    gradleProjectPath = ":app",
                    sourceSetName = "main",
                    sourceRoot = getOrCreateProjectBaseDir().toNioPath().resolve("src"),
                    sourceKind = WorkspaceSourceRootKind.PRODUCTION,
                    provenance = WorkspaceSourceRootProvenance.AUTHORED,
                )
            ),
        )

    private fun <V, F> Refinement<V, F>.refined(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejected: $failure")
        }

    private companion object {
        const val TARGET = "MIGRATION_REJECTED"
        const val RESULT_GRANT = 20
        const val WORK_GRANT = 100_000L
        const val ELAPSED_GRANT = 60_000L
        const val BYTE_GRANT = 100_000L
    }
}
