package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPackage
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPackageConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
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
import java.nio.file.Path

/** A library search selects compiled Kotlin, and a Java declaration it cannot select never reads as absent. */
class LibraryDiscoveryNativeTest : HeavyPlatformTestCase() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    override fun runInDispatchThread() = false

    override fun setUpProject() {
        super.setUpProject()
        // The class-file mirror of a selected Java member can initialize this application-lifetime service.
        com.intellij.codeInsight.ExternalAnnotationsManager.getInstance(
            com.intellij.openapi.project.ProjectManager.getInstance().defaultProject
        )
    }

    fun testExactNameSelectsEveryCompiledKotlinOverload() {
        val outcome = discover("kotlin.collections", "listOfNotNull")
        assertTrue(outcome.toString(), outcome is SymbolDiscoveryOutcome.Complete)
        val candidates = outcome.batch().candidates
        assertEquals(2, candidates.size)
        for (candidate in candidates) {
            val file = (candidate.location as SymbolDiscoveryCandidateLocation.Declaration).file
            assertTrue(file.toString(), file is SymbolDiscoveryFileIdentity.External)
        }
    }

    fun testAnAbsentNameIsComplete() {
        val outcome = discover("kotlin.collections", "noSuchLibraryFunction")
        assertTrue(outcome.toString(), outcome is SymbolDiscoveryOutcome.Complete)
        assertEquals(0, outcome.batch().candidates.size)
    }

    /** An accessor and a convention method are declarations by their own name, like any other Java member. */
    fun testCompiledJavaDeclarationsAreSelectedByTheirName() {
        val expected =
            mapOf(
                "File" to CompilerSymbolKind.CLASSLIKE,
                "mkdirs" to CompilerSymbolKind.FUNCTION,
                "separatorChar" to CompilerSymbolKind.PROPERTY,
                "getName" to CompilerSymbolKind.FUNCTION,
                "compareTo" to CompilerSymbolKind.FUNCTION,
            )
        for ((name, kind) in expected) {
            val discovered = discoverWithScope("java.io", name)
            val outcome = discovered.outcome
            assertTrue("$name: $outcome", outcome is SymbolDiscoveryOutcome.Complete)
            val batch = outcome.batch()
            assertTrue("$name selected nothing", batch.candidates.isNotEmpty())
            for (candidate in batch.candidates) {
                val file = (candidate.location as SymbolDiscoveryCandidateLocation.Declaration).file
                assertTrue("$name: $file", file is SymbolDiscoveryFileIdentity.External)
            }
            // A selected candidate must also resolve to its compiler identity, which a relation subject needs.
            val kinds = selectedKinds(discovered.scope, batch)
            assertEquals(name, setOf(kind), kinds.toSet())
        }
    }

    /** Kotlin writes `name` for the accessor `getName`, and no declaration has that name. */
    fun testAPropertyNameReadThroughACompiledAccessorLeavesTheSearchIncomplete() {
        for ((owner, name) in listOf("java.io" to "name", "java.io" to "parentFile")) {
            val outcome = discover(owner, name)
            assertTrue("$owner $name: $outcome", outcome is SymbolDiscoveryOutcome.Qualified)
            assertEquals(
                "$owner $name",
                setOf(SymbolDiscoveryQualification.UNSUPPORTED_ITEM),
                (outcome as SymbolDiscoveryOutcome.Qualified).qualifications.values,
            )
        }
    }

    private fun discover(owner: String, name: String): SymbolDiscoveryOutcome = discoverWithScope(owner, name).outcome

    private class Discovered(val outcome: SymbolDiscoveryOutcome, val scope: CompiledIntellijSearchScope)

    private fun discoverWithScope(owner: String, name: String): Discovered {
        prepare()
        lateinit var discovered: Discovered
        ApplicationManager.getApplication().runReadAction {
            val request = request(owner, name)
            val compiled =
                IntellijSearchScopeCompiler(request.constraints).compile(project, request.scope, model(request))
            assertTrue(compiled.toString(), compiled is IntellijSearchScopeCompilation.Compiled)
            val scope = (compiled as IntellijSearchScopeCompilation.Compiled).capability
            val execution =
                IntellijNativeDiscoveryQuery(
                        environmentState = { project.discoveryEnvironmentState() },
                        cancellationCheck = {},
                    )
                    .discoverNative(project, scope, request)
            assertTrue(execution.toString(), execution is IntellijNativeDiscoveryExecution.Produced)
            discovered = Discovered((execution as IntellijNativeDiscoveryExecution.Produced).outcome, scope)
        }
        return discovered
    }

    private fun selectedKinds(
        scope: CompiledIntellijSearchScope,
        batch: SymbolDiscoveryBatch,
    ): List<CompilerSymbolKind> {
        val query =
            IntellijSymbolSelectorQuery(
                lookup = IntellijKotlinCompilerSymbolLookup(IntellijPsiExactDeclarationLookup(project)),
                environmentState = { project.discoveryEnvironmentState() },
                cancellationCheck = {},
            )
        val kinds = mutableListOf<CompilerSymbolKind>()
        ApplicationManager.getApplication().runReadAction {
            for (ordinal in batch.candidates.indices) {
                val selection = SymbolDiscoverySelection.select(batch, ordinal).refined()
                val resolved = query.resolve(scope, selection)
                assertTrue(resolved.toString(), resolved is IntellijSymbolSelectorResolution.Resolved)
                kinds += (resolved as IntellijSymbolSelectorResolution.Resolved).selector.kind
            }
        }
        return kinds
    }

    private fun prepare() {
        ApplicationManager.getApplication().invokeAndWait {
            if (getOrCreateProjectBaseDir().findChild("src") == null) prepareLibraries()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
    }

    private fun prepareLibraries() {
        val sdk = JavaSdk.getInstance().createJdk("library-discovery-jdk", System.getProperty("java.home"), false)
        registerTestProjectJdk(sdk)
        WriteCommandAction.runWriteCommandAction(project) {
            val root = getOrCreateProjectBaseDir().createChildDirectory(this, "src")
            root.createChildData(this, "Use.kt").setBinaryContent("package consumer\nfun use() = 1\n".toByteArray())
            ModuleRootModificationUtil.updateModel(module) { model ->
                model.sdk = sdk
                model.addContentEntry(root).addSourceFolder(root, false)
                val library = model.moduleLibraryTable.createLibrary("discovery-stdlib").modifiableModel
                val jar = Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar")
                library.addRoot("jar://$jar!/", OrderRootType.CLASSES)
                library.commit()
            }
        }
    }

    private fun request(owner: String, name: String): SymbolDiscoveryRequest {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(getOrCreateProjectBaseDir().toNioPath()).refined()
        return SymbolDiscoveryRequest(
            scope =
                SymbolSearchScopeRequest(
                    SemanticReadLease(root, EvidenceGeneration.parse(1).refined()),
                    SymbolSearchScope.Workspace(
                        SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                        SymbolGeneratedSourcePolicy.EXCLUDE,
                        SymbolLibraryPolicy.INCLUDE,
                    ),
                ),
            target =
                SymbolDiscoveryTarget.Name(
                    SymbolNameDiscoveryKind.SYMBOL,
                    SymbolDiscoveryPattern.parse(name).refined(),
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
                    directory = null,
                    packageName =
                        SymbolDiscoveryPackageConstraint(
                            SymbolDiscoveryPackage.parse(owner).refined(),
                            SymbolDiscoveryContainment.DIRECT,
                        ),
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

    private fun SymbolDiscoveryOutcome.batch() =
        when (this) {
            is SymbolDiscoveryOutcome.Complete -> batch
            is SymbolDiscoveryOutcome.Qualified -> batch
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
