package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.limits.FileSizeLimit
import com.intellij.psi.SingleRootFileViewProvider
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBlockCause
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDeclarationKinds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryProgress
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
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

/** A source file outside content-index eligibility cannot disappear from complete discovery. */
class ExactNameIndexEligibilityNativeTest : HeavyPlatformTestCase() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    override fun runInDispatchThread() = false

    fun testOversizedSourcePreservesExhaustiveCoverage() {
        val source = "//" + "x".repeat(FileSizeLimit.getIntellisenseLimit("kt") + 1) + "\nclass OversizedTarget\n"
        val file = prepareSource(source)
        ApplicationManager.getApplication().runReadAction {
            assertTrue(SingleRootFileViewProvider.isTooLargeForIntelligence(file))
            val request = request()
            val compiled =
                IntellijSearchScopeCompiler(request.constraints).compile(project, request.scope, model(request))
            assertTrue(compiled.toString(), compiled is IntellijSearchScopeCompilation.Compiled)
            val scope = (compiled as IntellijSearchScopeCompilation.Compiled).capability
            val query =
                IntellijNativeDiscoveryQuery(
                    environmentState = { project.discoveryEnvironmentState() },
                    cancellationCheck = {},
                )
            val route = IntellijDirectoryDiscoveryPlan.select(scope, request)
            assertTrue(route.toString(), route is IntellijDirectoryDiscoveryRoute.Selected)
            val exhaustive =
                query
                    .discoverDirectory(
                        project = project,
                        plan = (route as IntellijDirectoryDiscoveryRoute.Selected).plan,
                        allowance = IntellijDeclarationDiscoveryAllowance(request),
                        partitions = NativeIntellijDeclarationPartitionFiles,
                    )
                    .produced()
            val indexed = query.discoverNative(project, scope, request).produced()
            println("oversized-coverage bytes=${file.length} exhaustive=$exhaustive indexed=$indexed")
            for (outcome in listOf(exhaustive, indexed)) {
                assertTrue(outcome.toString(), outcome is SymbolDiscoveryOutcome.Qualified)
                val qualified = outcome as SymbolDiscoveryOutcome.Qualified
                assertEquals(
                    setOf(SymbolDiscoveryQualification.PROVIDER_FAILURE),
                    qualified.qualifications.values.toSet(),
                )
                assertEquals(
                    SymbolDiscoveryProgress.Blocked(SymbolDiscoveryBlockCause.PROVIDER_UNAVAILABLE),
                    qualified.progress,
                )
                assertTrue(qualified.batch.candidates.isEmpty())
            }
        }
    }

    private fun prepareSource(source: String): VirtualFile {
        lateinit var file: VirtualFile
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                val root = getOrCreateProjectBaseDir().createChildDirectory(this, "src")
                ModuleRootModificationUtil.updateModel(module) { model ->
                    model.addContentEntry(root).addSourceFolder(root, false)
                }
                file = root.createChildData(this, "Oversized.kt")
                file.setBinaryContent(source.toByteArray())
            }
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        return file
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
                    SymbolNameDiscoveryKind.CLASS,
                    SymbolDiscoveryPattern.parse("OversizedTarget").refined(),
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
                    SymbolDiscoveryDirectoryConstraint(
                        SymbolDiscoveryDirectory.parse(".").refined(),
                        SymbolDiscoveryContainment.DESCENDANTS,
                    ),
                    null,
                    SymbolDiscoveryDeclarationKinds.from(setOf(CompilerSymbolKind.CLASSLIKE)).refined(),
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

    private fun IntellijNativeDiscoveryExecution.produced(): SymbolDiscoveryOutcome {
        assertTrue(toString(), this is IntellijNativeDiscoveryExecution.Produced)
        return (this as IntellijNativeDiscoveryExecution.Produced).outcome
    }

    private fun <V, F> Refinement<V, F>.refined(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejected: $failure")
        }

    private companion object {
        const val RESULT_GRANT = 20
        const val WORK_GRANT = 100_000L
        const val ELAPSED_GRANT = 60_000L
        const val BYTE_GRANT = 100_000L
    }
}
