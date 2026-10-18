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
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDeclarationKinds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
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
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter

/** Real word indexes preserve local declarations while unrelated bodies consume no discovery work. */
class ExactNameCandidateFilesNativeTest : HeavyPlatformTestCase() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    override fun runInDispatchThread() = false

    fun testExactLocalNamesIgnoreUnrelatedBodiesWithinTheSameDirectory() {
        val source =
            """
            package proof.caching
            fun container() {
                class LocalTargetClass
                fun localTargetFunction() = 1
                val localTargetProperty = 2
            }
            """
                .trimIndent()
        val fixture = prepare(targetSource = source, largeBodies = true)
        val declarations =
            listOf(
                Triple("LocalTargetClass", "class LocalTargetClass", CompilerSymbolKind.CLASSLIKE),
                Triple("localTargetFunction", "fun localTargetFunction", CompilerSymbolKind.FUNCTION),
                Triple("localTargetProperty", "val localTargetProperty", CompilerSymbolKind.PROPERTY),
            )
        for ((name, declaration, kind) in declarations) {
            val result = discover(fixture = fixture, grant = INDEXED_GRANT, name = name, kind = kind)
            val batch = complete(result.outcome)
            assertAuthoredDeclaration(
                fixture = fixture,
                batch = batch,
                source = source,
                name = name,
                declaration = declaration,
            )
            assertEquals(1, result.observation.calls[IntellijReadCall.PSI_FIND_FILE])
            assertEquals(1, result.observation.calls[IntellijReadCall.DECLARATION_PSI_SCAN])
            assertTrue(batch.examinedWorkUnits.value < INDEXED_GRANT.workUnits)
            result.observation.assertFinished()
            println(
                "exact-local name=$name work=${batch.examinedWorkUnits.value} " +
                    "calls=${result.observation.calls} counters=${result.observation.counters}"
            )
        }
    }

    fun testAbsentExactNameAvoidsAllUnrelatedBodies() {
        val fixture = prepare(largeBodies = true)
        val result =
            discover(
                fixture = fixture,
                grant = INDEXED_GRANT,
                name = "AbsentTarget",
                kind = CompilerSymbolKind.PROPERTY,
            )
        val batch = complete(result.outcome)
        assertTrue(batch.candidates.isEmpty())
        assertEquals(0, result.observation.calls[IntellijReadCall.PSI_FIND_FILE] ?: 0)
        assertEquals(0, result.observation.calls[IntellijReadCall.DECLARATION_PSI_SCAN] ?: 0)
        assertEquals(0, result.observation.counters[IntellijReadCounter.DECLARATION_AST_NODES_VISITED] ?: 0)
        result.observation.assertFinished()
    }

    fun testExactNamesOutsideTheWordIndexGrammarKeepFullCoverage() {
        val longName = "n".repeat(101)
        val source =
            """
            package proof.caching
            fun container() {
                val café = 1
                val `two words` = 2
                val `$longName` = 3
                val `plainBacktick` = 4
            }
            """
                .trimIndent()
        val fixture = prepare(targetSource = source)
        for ((name, declaration) in
            listOf(
                "café" to "val café",
                "two words" to "val `two words`",
                longName to "val `$longName`",
                "plainBacktick" to "val `plainBacktick`",
            )) {
            val result =
                discover(fixture = fixture, grant = FALLBACK_GRANT, name = name, kind = CompilerSymbolKind.PROPERTY)
            assertAuthoredDeclaration(
                fixture = fixture,
                batch = complete(result.outcome),
                source = source,
                name = name,
                declaration = declaration,
            )
            result.observation.assertFinished()
        }
    }

    fun testImplicitCompanionKeepsItsNameWithoutAnIdentifierToken() {
        val source = "package proof.caching\nclass Outer { companion object }"
        val fixture = prepare(targetSource = source)
        val result =
            discover(
                fixture = fixture,
                grant = FALLBACK_GRANT,
                name = "Companion",
                kind = CompilerSymbolKind.CLASSLIKE,
            )
        assertAuthoredDeclaration(
            fixture = fixture,
            batch = complete(result.outcome),
            source = source,
            name = "Companion",
            declaration = "companion object",
        )
        result.observation.assertFinished()
    }

    private fun assertAuthoredDeclaration(
        fixture: Fixture,
        batch: SymbolDiscoveryBatch,
        source: String,
        name: String,
        declaration: String,
    ) {
        assertEquals(listOf(name), batch.candidates.map { it.name.value })
        val candidate = batch.candidates.single()
        assertEquals(fixture.target.path, candidate.location.file.stableValue)
        assertEquals(
            source.indexOf(declaration),
            (candidate.location as SymbolDiscoveryCandidateLocation.Declaration).offset.value,
        )
        assertEquals(fixture.scope.lease, candidate.lease)
    }

    private fun discover(
        fixture: Fixture,
        grant: Grant,
        name: String,
        kind: CompilerSymbolKind,
    ): Result {
        val request = nativeRequest(scope = fixture.scope, grant = grant, name = name, kind = kind)
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

    private fun nativeRequest(
        scope: SymbolSearchScopeRequest,
        grant: Grant,
        name: String,
        kind: CompilerSymbolKind,
    ) =
        SymbolDiscoveryRequest(
            scope = scope,
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
                        WorkUnitLimit.parse(grant.workUnits).refined(),
                        ElapsedTimeLimitMillis.parse(grant.elapsedMillis).refined(),
                    ),
                    SymbolDiscoveryByteLimit.parse(BYTE_GRANT).refined(),
                ),
            constraints =
                SymbolDiscoveryConstraints(
                    SymbolDiscoveryDirectoryConstraint(
                        SymbolDiscoveryDirectory.parse("src/bsc-common").refined(),
                        SymbolDiscoveryContainment.DESCENDANTS,
                    ),
                    null,
                    SymbolDiscoveryDeclarationKinds.from(setOf(kind)).refined(),
                ),
        )

    private fun noiseSource(index: Int, largeBodies: Boolean): String =
        if (largeBodies) {
            val declarations = (0 until 400).joinToString("\n") { "val value$it = $it" }
            "package proof.other\nfun noise$index() {\n$declarations\n}\n"
        } else "package proof.other\nval noise$index = $index\n"

    private fun prepare(
        targetSource: String = "package proof.caching\nval existing = 1",
        largeBodies: Boolean = false,
    ): Fixture {
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
            val target = file(folder(broad, "path-does-not-name-package"), "Cache.kt", targetSource)
            val noise = folder(broad, "noise")
            repeat(NOISE_FILES) { index -> file(noise, "Noise$index.kt", noiseSource(index, largeBodies)) }
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
        const val NOISE_FILES = 100
        const val RESULT_GRANT = 128
        const val BYTE_GRANT = 49_152L
        val INDEXED_GRANT = Grant(60_000, 6_000)
        val FALLBACK_GRANT = Grant(60_000, 100_000)
    }
}
