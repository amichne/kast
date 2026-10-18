package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
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
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall

/** Real indexed scope/grant comparison. Authored sources define the oracle independently of discovery. */
class DiscoveryScopeGrantNativeTest : HeavyPlatformTestCase() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    override fun runInDispatchThread() = false

    fun testExactTargetCompletesUnderBothScopesAndBothGrants() {
        val fixture = prepare()
        val vectors = mutableMapOf<Pair<Selection, Grant>, DiscoveryGrantObservation>()
        for (grant in listOf(ORIGINAL, ENLARGED)) {
            for (selection in Selection.entries) {
                val result = discover(fixture, selection, grant)
                val batch = complete(result.outcome)
                assertEquals(listOf(TARGET_NAME), batch.candidates.map { it.name.value })
                assertEquals(listOf(fixture.target.path), batch.candidates.map { it.location.file.stableValue })
                assertEquals(
                    TARGET_SOURCE.indexOf("val $TARGET_NAME"),
                    (batch.candidates.single().location as SymbolDiscoveryCandidateLocation.Declaration).offset.value,
                )
                assertEquals(fixture.scope.lease, batch.candidates.single().lease)
                result.observation.assertFinished()
                vectors[selection to grant] = result.observation
                println(
                    "discovery-matrix scope=$selection elapsedGrant=${grant.elapsedMillis} " +
                        "workGrant=${grant.workUnits} outcome=COMPLETE work=${batch.examinedWorkUnits.value} " +
                        "candidateBytes=${batch.encodedBytes.value} " +
                        "countedCalls=${result.observation.calls.values.sum()} " +
                        "calls=${result.observation.calls} counters=${result.observation.counters} " +
                        "preCallbackProbes=${result.observation.preCallbackProbes} " +
                        "namedCallbacks=${result.observation.namedCallbacks} " +
                        "stops=${result.observation.stops}"
                )
                assertCompilerOracle(result, batch)
            }
        }
        for (selection in Selection.entries) {
            val original = vectors.getValue(selection to ORIGINAL)
            val enlarged = vectors.getValue(selection to ENLARGED)
            assertEquals(original.calls, enlarged.calls)
            assertEquals(original.counters, enlarged.counters)
            assertEquals(original.preCallbackProbes, enlarged.preCallbackProbes)
            assertEquals(original.namedCallbacks, enlarged.namedCallbacks)
        }
        assertEquals(
            1,
            vectors.getValue(Selection.DIRECTORY to ORIGINAL).calls[IntellijReadCall.SCOPED_FILE_INDEX] ?: 0,
        )
        assertEquals(1, vectors.getValue(Selection.PACKAGE to ORIGINAL).calls[IntellijReadCall.EXACT_PACKAGE_INDEX])
    }

    fun testPackageIsANarrowerQuestionWhenAnotherPackageDefinesTheSameName() {
        val fixture = prepare(contrastingName = true)
        val directory = complete(discover(fixture, Selection.DIRECTORY, ORIGINAL).outcome)
        val selectedPackage = complete(discover(fixture, Selection.PACKAGE, ORIGINAL).outcome)
        assertEquals(2, directory.candidates.size)
        assertEquals(listOf(fixture.target.path), selectedPackage.candidates.map { it.location.file.stableValue })
        assertEquals(2, directory.candidates.map { it.location.file.stableValue }.toSet().size)
    }

    fun testWorkStopNeverBecomesCompleteEmptyDiscovery() {
        val result = discover(prepare(), Selection.DIRECTORY, Grant(2_000, 1))
        assertTrue(result.outcome.toString(), result.outcome is SymbolDiscoveryOutcome.Qualified)
        val qualified = result.outcome as SymbolDiscoveryOutcome.Qualified
        assertTrue(SymbolDiscoveryQualification.WORK_LIMIT_REACHED in qualified.qualifications.values)
        assertTrue(qualified.batch.candidates.isEmpty())
        result.observation.assertFinished()
    }

    private fun assertCompilerOracle(result: Result, batch: SymbolDiscoveryBatch) {
        val observation = DiscoveryGrantObservation()
        val query =
            IntellijSymbolSelectorQuery(
                lookup =
                    IntellijKotlinCompilerSymbolLookup(
                        IntellijPsiExactDeclarationLookup(project, observation),
                        observation,
                    ),
                environmentState = { project.discoveryEnvironmentState() },
                cancellationCheck = {},
                observation = observation,
            )
        ApplicationManager.getApplication().runReadAction {
            val resolved = query.resolve(result.scope, SymbolDiscoverySelection.select(batch, 0).refined())
            assertTrue(resolved.toString(), resolved is IntellijSymbolSelectorResolution.Resolved)
            val selector = (resolved as IntellijSymbolSelectorResolution.Resolved).selector
            assertEquals(CompilerSymbolKind.PROPERTY, selector.kind)
            assertEquals(
                "proof.caching.$TARGET_NAME",
                (selector.qualifiedIdentity as ExactDeclarationQualifiedIdentity.Available).value,
            )
            assertEquals(TARGET_SOURCE.indexOf("val $TARGET_NAME"), selector.range.startInclusive)
            assertEquals(TARGET_SOURCE.trimEnd().length, selector.range.endExclusive)
            assertEquals(result.scope.lease, selector.lease)
        }
        observation.assertFinished()
        assertEquals(1, observation.calls[IntellijReadCall.K2_ANALYSIS])
        println("discovery-oracle calls=${observation.calls} counters=${observation.counters}")
    }

    private fun discover(fixture: Fixture, selection: Selection, grant: Grant): Result {
        val request = nativeRequest(fixture.scope, selection, grant)
        val observation = DiscoveryGrantObservation()
        return ApplicationManager.getApplication().runReadAction<Result> {
            val compiled =
                IntellijSearchScopeCompiler(request.constraints, observation = observation)
                    .compile(project, request.scope, fixture.model)
            assertTrue(compiled.toString(), compiled is IntellijSearchScopeCompilation.Compiled)
            val scope = (compiled as IntellijSearchScopeCompilation.Compiled).capability
            val execution =
                IntellijNativeDiscoveryQuery(
                        environmentState = { project.discoveryEnvironmentState() },
                        cancellationCheck = {},
                        observation = observation,
                    )
                    .discoverNative(
                        project,
                        scope,
                        request,
                        indexes = observation.indexes(NativeIntellijDeclarationIndexes(project)),
                    )
            assertTrue(execution.toString(), execution is IntellijNativeDiscoveryExecution.Produced)
            Result((execution as IntellijNativeDiscoveryExecution.Produced).outcome, scope, observation)
        }
    }

    private fun nativeRequest(scope: SymbolSearchScopeRequest, selection: Selection, grant: Grant) =
        SymbolDiscoveryRequest(
            scope = scope,
            target =
                SymbolDiscoveryTarget.Name(
                    SymbolNameDiscoveryKind.SYMBOL,
                    SymbolDiscoveryPattern.parse(TARGET_NAME).refined(),
                    SymbolDiscoveryMatch.EXACT_NAME,
                ),
            budget =
                SymbolDiscoveryBudget(
                    ResourceBudget(
                        ResultLimit.parse(RESULT_GRANT).refined(),
                        WorkUnitLimit.parse(grant.workUnits).refined(),
                        ElapsedTimeLimitMillis.parse(grant.elapsedMillis).refined(),
                    ),
                    SymbolDiscoveryByteLimit.parse(BYTE_GRANT).refined(),
                ),
            constraints =
                when (selection) {
                    Selection.DIRECTORY ->
                        SymbolDiscoveryConstraints(
                            SymbolDiscoveryDirectoryConstraint(
                                SymbolDiscoveryDirectory.parse("src/bsc-common").refined(),
                                SymbolDiscoveryContainment.DESCENDANTS,
                            ),
                            null,
                        )
                    Selection.PACKAGE ->
                        SymbolDiscoveryConstraints(
                            null,
                            SymbolDiscoveryPackageConstraint(
                                SymbolDiscoveryPackage.parse("proof.caching").refined(),
                                SymbolDiscoveryContainment.DIRECT,
                            ),
                        )
                },
        )

    private fun prepare(contrastingName: Boolean = false): Fixture {
        lateinit var fixture: Fixture
        ApplicationManager.getApplication().invokeAndWait {
            val base = getOrCreateProjectBaseDir()
            val source = folder(base, "src")
            val sdk = JavaSdk.getInstance().createJdk("discovery-grant-jdk", System.getProperty("java.home"), false)
            registerTestProjectJdk(sdk)
            ModuleRootModificationUtil.updateModel(module) { model ->
                model.sdk = sdk
                model.addContentEntry(source).addSourceFolder(source, false)
            }
            val broad = folder(source, "bsc-common")
            val target = file(folder(broad, "path-does-not-name-package"), "Cache.kt", TARGET_SOURCE)
            val noise = folder(broad, "noise")
            repeat(NOISE_FILES) { file(noise, "Noise$it.kt", "package proof.other\nval noise$it = $it\n") }
            if (contrastingName) file(noise, "OtherCache.kt", "package proof.other\nval $TARGET_NAME = 2\n")
            file(folder(source, "excluded"), "Other.kt", "package excluded\nval noise = 0\n")
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            val root = CanonicalWorkspaceRoot.fromCanonicalPath(base.toNioPath()).refined()
            val scope =
                SymbolSearchScopeRequest(
                    SemanticReadLease(root, EvidenceGeneration.parse(1).refined()),
                    SymbolSearchScope.Workspace(
                        SymbolSourceKindPolicy.PRODUCTION_ONLY,
                        SymbolGeneratedSourcePolicy.EXCLUDE,
                        SymbolLibraryPolicy.EXCLUDE,
                    ),
                )
            val model =
                WorkspaceSearchScopeModel.compile(
                    root,
                    ImportedWorkspaceModelState.COMPLETE,
                    listOf(
                        WorkspaceSourceRootBoundary(
                            ideaModuleName = module.name,
                            linkedBuildRoot = base.toNioPath(),
                            gradleProjectPath = ":app",
                            sourceSetName = "main",
                            sourceRoot = source.toNioPath(),
                            sourceKind = WorkspaceSourceRootKind.PRODUCTION,
                            provenance = WorkspaceSourceRootProvenance.AUTHORED,
                        )
                    ),
                )
            fixture = Fixture(scope, model, target)
        }
        return fixture
    }

    private fun folder(parent: VirtualFile, name: String): VirtualFile =
        WriteCommandAction.writeCommandAction(project).compute<VirtualFile, RuntimeException> {
            parent.createChildDirectory(this, name)
        }

    private fun file(parent: VirtualFile, name: String, text: String): VirtualFile =
        WriteCommandAction.writeCommandAction(project).compute<VirtualFile, RuntimeException> {
            parent.createChildData(this, name).also { it.setBinaryContent(text.toByteArray()) }
        }

    private fun complete(outcome: SymbolDiscoveryOutcome): SymbolDiscoveryBatch {
        assertTrue(outcome.toString(), outcome is SymbolDiscoveryOutcome.Complete)
        return (outcome as SymbolDiscoveryOutcome.Complete).batch
    }

    private fun <V, F> Refinement<V, F>.refined(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejected: $failure")
        }

    private enum class Selection {
        DIRECTORY,
        PACKAGE,
    }

    private data class Grant(val elapsedMillis: Long, val workUnits: Long)

    private data class Fixture(
        val scope: SymbolSearchScopeRequest,
        val model: WorkspaceSearchScopeModelCompilation,
        val target: VirtualFile,
    )

    private data class Result(
        val outcome: SymbolDiscoveryOutcome,
        val scope: CompiledIntellijSearchScope,
        val observation: DiscoveryGrantObservation,
    )

    private companion object {
        const val TARGET_NAME = "httpCacheCallFactory"
        const val TARGET_SOURCE = "package proof.caching\nval httpCacheCallFactory = 1\n"
        const val NOISE_FILES = 100
        const val RESULT_GRANT = 128
        const val BYTE_GRANT = 49_152L
        val ORIGINAL = Grant(2_000, 100_000)
        val ENLARGED = Grant(30_000, 2_000_000)
    }
}
