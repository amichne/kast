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
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDeclarationKinds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
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

/**
 * Enum entries have no supported compiler declaration kind. An exact-name selection under the public default kinds
 * meets a same-named entry in the classlike family and cannot select it, so the selection is qualified with
 * `ENUM_ENTRY_UNSELECTABLE` instead of reporting a complete, empty or complete, partial answer. Kinds without the
 * classlike family prove the entry outside the selection.
 */
class EnumEntrySelectionNativeTest : HeavyPlatformTestCase() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    override fun runInDispatchThread() = false

    fun testEnumEntryNameIsAQualifiedEmptySelection() {
        prepareSources(mapOf("Outcome.kt" to "package sample\n\nenum class Outcome { $TARGET, ACCEPTED }\n"))
        ApplicationManager.getApplication().runReadAction {
            val outcome = discover()
            assertTrue(outcome.toString(), outcome is SymbolDiscoveryOutcome.Qualified)
            val qualified = outcome as SymbolDiscoveryOutcome.Qualified
            assertEquals(setOf(SymbolDiscoveryQualification.ENUM_ENTRY_UNSELECTABLE), qualified.qualifications.values)
            assertEquals(emptyList<String>(), qualified.batch.candidates)
        }
    }

    fun testSameNamePropertyIsSelectedBesideTheUnselectableEnumEntry() {
        prepareSources(
            mapOf(
                "Outcome.kt" to "package sample\n\nenum class Outcome { $TARGET, ACCEPTED }\n",
                "Codes.kt" to "package sample\n\nval $TARGET: Int = 1\n",
            )
        )
        ApplicationManager.getApplication().runReadAction {
            val outcome = discover()
            assertTrue(outcome.toString(), outcome is SymbolDiscoveryOutcome.Qualified)
            val qualified = outcome as SymbolDiscoveryOutcome.Qualified
            assertEquals(setOf(SymbolDiscoveryQualification.ENUM_ENTRY_UNSELECTABLE), qualified.qualifications.values)
            val candidates = qualified.batch.candidates
            assertEquals(listOf(TARGET), candidates.map { it.name.value })
            assertTrue(candidates.single().location.file.stableValue.endsWith("/src/Codes.kt"))
        }
    }

    fun testKindsWithoutTheClasslikeFamilyProveTheEntryOutsideTheSelection() {
        prepareSources(mapOf("Outcome.kt" to "package sample\n\nenum class Outcome { $TARGET, ACCEPTED }\n"))
        ApplicationManager.getApplication().runReadAction {
            val outcome = discover(setOf(CompilerSymbolKind.FUNCTION, CompilerSymbolKind.PROPERTY))
            assertTrue(outcome.toString(), outcome is SymbolDiscoveryOutcome.Complete)
            assertEquals(emptyList<String>(), (outcome as SymbolDiscoveryOutcome.Complete).batch.candidates)
        }
    }

    fun testScopedAndDirectoryRoutesMeetTheEntryToo() {
        prepareSources(mapOf("Outcome.kt" to "package sample\n\nenum class Outcome { $TARGET, ACCEPTED }\n"))
        val routes =
            listOf(
                Route(PUBLIC_DEFAULT_KINDS, SymbolDiscoveryMatch.FUZZY, directory = null),
                Route(PUBLIC_DEFAULT_KINDS, SymbolDiscoveryMatch.EXACT_NAME, directory = "src"),
                Route(setOf(CompilerSymbolKind.CLASSLIKE), SymbolDiscoveryMatch.EXACT_NAME, directory = "src"),
            )
        ApplicationManager.getApplication().runReadAction {
            for (route in routes) {
                val outcome = discover(route.kinds, route.match, route.directory)
                assertTrue("$route: $outcome", outcome is SymbolDiscoveryOutcome.Qualified)
                val qualified = outcome as SymbolDiscoveryOutcome.Qualified
                assertTrue(
                    "$route: ${qualified.qualifications}",
                    SymbolDiscoveryQualification.ENUM_ENTRY_UNSELECTABLE in qualified.qualifications.values,
                )
                assertEquals("$route", emptyList<String>(), qualified.batch.candidates.map { it.name.value })
            }
        }
    }

    private data class Route(
        val kinds: Set<CompilerSymbolKind>,
        val match: SymbolDiscoveryMatch,
        val directory: String?,
    )

    private fun discover(
        kinds: Set<CompilerSymbolKind> = PUBLIC_DEFAULT_KINDS,
        match: SymbolDiscoveryMatch = SymbolDiscoveryMatch.EXACT_NAME,
        directory: String? = null,
    ): SymbolDiscoveryOutcome {
        val request = request(kinds, match, directory)
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

    private fun request(
        kinds: Set<CompilerSymbolKind>,
        match: SymbolDiscoveryMatch,
        directory: String?,
    ): SymbolDiscoveryRequest {
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
                    match,
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
                    directory?.let { path ->
                        SymbolDiscoveryDirectoryConstraint(
                            SymbolDiscoveryDirectory.parse(path).refined(),
                            SymbolDiscoveryContainment.DESCENDANTS,
                        )
                    },
                    null,
                    SymbolDiscoveryDeclarationKinds.from(kinds).refined(),
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

        /** The public default declaration kinds for a name selection. */
        val PUBLIC_DEFAULT_KINDS =
            setOf(
                CompilerSymbolKind.CLASSLIKE,
                CompilerSymbolKind.FUNCTION,
                CompilerSymbolKind.PROPERTY,
                CompilerSymbolKind.TYPE_ALIAS,
            )
    }
}
