package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem
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
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDeclarationKinds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
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
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** The current checkout's complete production Kotlin source set defines the indexed fixture dataset. */
class KastProductionDiscoveryNativeTest : HeavyPlatformTestCase() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    override fun runInDispatchThread() = false

    fun testRootExactClassDiscoveryCompletesForAllProductionSources() {
        val dataset = KastProductionSourceDataset.capture()
        val sourceRoot = prepareSource(dataset)
        ApplicationManager.getApplication().runReadAction {
            val request = request()
            val compiled =
                IntellijSearchScopeCompiler(request.constraints).compile(project, request.scope, model(request))
            assertTrue(compiled.toString(), compiled is IntellijSearchScopeCompilation.Compiled)
            val scope = (compiled as IntellijSearchScopeCompilation.Compiled).capability
            val baseline = discover(scope = scope, request = request, exhaustive = true)
            println(
                "kast-production route=EXHAUSTIVE outcome=${baseline.outcome} " +
                    "calls=${baseline.observation.calls} counters=${baseline.observation.counters}"
            )
            assertTrue(baseline.outcome.toString(), baseline.outcome is SymbolDiscoveryOutcome.Qualified)
            val qualified = baseline.outcome as SymbolDiscoveryOutcome.Qualified
            assertTrue(SymbolDiscoveryQualification.WORK_LIMIT_REACHED in qualified.qualifications.values)
            val candidate = discover(scope = scope, request = request, exhaustive = false)
            println(
                "kast-production route=INDEXED outcome=${candidate.outcome} " +
                    "calls=${candidate.observation.calls} counters=${candidate.observation.counters}"
            )
            assertTrue(candidate.outcome.toString(), candidate.outcome is SymbolDiscoveryOutcome.Complete)
            val batch = (candidate.outcome as SymbolDiscoveryOutcome.Complete).batch
            assertEquals(listOf(TARGET_NAME), batch.candidates.map { it.name.value })
            val declaration = batch.candidates.single()
            assertEquals(sourceRoot.resolve(TARGET_PATH).toString(), declaration.location.file.stableValue)
            val targetSource = dataset.files.single { it.relative == TARGET_PATH }.bytes.toString(Charsets.UTF_8)
            assertEquals(
                targetSource.indexOf("/** Literal ordered assignments."),
                (declaration.location as SymbolDiscoveryCandidateLocation.Declaration).offset.value,
            )
            assertEquals(request.scope.lease, declaration.lease)
            assertTrue(batch.examinedWorkUnits.value < qualified.batch.examinedWorkUnits.value)
            baseline.observation.assertFinished()
            candidate.observation.assertFinished()
            assertCompilerOracle(scope, batch, targetSource)
        }
    }

    private fun assertCompilerOracle(scope: CompiledIntellijSearchScope, batch: SymbolDiscoveryBatch, source: String) {
        val observation = KastProductionObservation()
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
        val resolution = query.resolve(scope, SymbolDiscoverySelection.select(batch, 0).refined())
        assertTrue(resolution.toString(), resolution is IntellijSymbolSelectorResolution.Resolved)
        val selector = (resolution as IntellijSymbolSelectorResolution.Resolved).selector
        assertEquals(CompilerSymbolKind.CLASSLIKE, selector.kind)
        assertEquals(
            "io.github.amichne.kast.distribution.contract.configuration.$TARGET_NAME",
            (selector.qualifiedIdentity as ExactDeclarationQualifiedIdentity.Available).value,
        )
        assertEquals(source.indexOf("/** Literal ordered assignments."), selector.range.startInclusive)
        assertEquals(source.trimEnd().length, selector.range.endExclusive)
        assertEquals(scope.lease, selector.lease)
        observation.assertFinished()
        assertEquals(1, observation.calls[IntellijReadCall.K2_ANALYSIS])
        println("kast-production-compiler signature=${selector.signature} calls=${observation.calls}")
    }

    private fun discover(
        scope: CompiledIntellijSearchScope,
        request: SymbolDiscoveryRequest,
        exhaustive: Boolean,
    ): Result {
        val observation = KastProductionObservation()
        val query =
            IntellijNativeDiscoveryQuery(
                environmentState = { project.discoveryEnvironmentState() },
                cancellationCheck = {},
                observation = observation,
            )
        val execution =
            if (exhaustive) {
                val selected = IntellijDirectoryDiscoveryPlan.select(scope, request)
                assertTrue(selected.toString(), selected is IntellijDirectoryDiscoveryRoute.Selected)
                query.discoverDirectory(
                    project = project,
                    plan = (selected as IntellijDirectoryDiscoveryRoute.Selected).plan,
                    allowance = IntellijDeclarationDiscoveryAllowance(request),
                    partitions = NativeIntellijDeclarationPartitionFiles,
                )
            } else query.discoverNative(project, scope, request)
        assertTrue(execution.toString(), execution is IntellijNativeDiscoveryExecution.Produced)
        return Result((execution as IntellijNativeDiscoveryExecution.Produced).outcome, observation)
    }

    private fun prepareSource(dataset: KastProductionSourceDataset): Path {
        lateinit var root: Path
        ApplicationManager.getApplication().invokeAndWait {
            root = getOrCreateProjectBaseDir().toNioPath().resolve("src")
            Files.createDirectories(root)
            for (source in dataset.files) {
                val target = root.resolve(source.relative)
                Files.createDirectories(target.parent)
                Files.write(target, source.bytes)
            }
            val native = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root))
            native.refresh(false, true)
            val sdk = JavaSdk.getInstance().createJdk("kast-production-jdk", System.getProperty("java.home"), false)
            registerTestProjectJdk(sdk)
            WriteCommandAction.runWriteCommandAction(project) {
                ModuleRootModificationUtil.updateModel(module) { model ->
                    model.sdk = sdk
                    model.addContentEntry(native).addSourceFolder(native, false)
                }
            }
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        return root
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
                    SymbolDiscoveryPattern.parse(TARGET_NAME).refined(),
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

    private fun <V, F> Refinement<V, F>.refined(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejected: $failure")
        }

    private data class Result(val outcome: SymbolDiscoveryOutcome, val observation: KastProductionObservation)

    private companion object {
        const val TARGET_NAME = "SavedConfigurationDocument"
        const val TARGET_PATH =
            "distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/SavedConfigurationDocument.kt"
        const val RESULT_GRANT = 20
        const val WORK_GRANT = 999_999L
        const val ELAPSED_GRANT = 15_000L
        const val BYTE_GRANT = 100_000L
    }
}

private class KastProductionSourceDataset(val files: List<Source>) {
    data class Source(val relative: String, val bytes: ByteArray)

    companion object {
        fun capture(): KastProductionSourceDataset {
            val root =
                generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
                    .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
            val files = mutableListOf<Source>()
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                        if (dir.fileName.toString() in setOf(".git", ".gradle", "build", ".idea"))
                            FileVisitResult.SKIP_SUBTREE
                        else FileVisitResult.CONTINUE

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val relative = root.relativize(file).toString()
                        if (relative.endsWith(".kt") && "/src/main/kotlin/" in "/$relative")
                            files += Source(relative, Files.readAllBytes(file))
                        return FileVisitResult.CONTINUE
                    }
                },
            )
            val ordered = files.sortedBy { it.relative }
            check(ordered.isNotEmpty())
            val digest = MessageDigest.getInstance("SHA-256")
            for (source in ordered) {
                digest.update(source.relative.toByteArray())
                digest.update(0)
                digest.update(source.bytes)
                digest.update(0)
            }
            println(
                "kast-production-dataset root=$root files=${ordered.size} " +
                    "bytes=${ordered.sumOf { it.bytes.size.toLong() }} sha256=${digest.digest().toHexString()}"
            )
            return KastProductionSourceDataset(ordered)
        }
    }
}

private class KastProductionObservation(private val delegate: DiscoveryGrantObservation = DiscoveryGrantObservation()) :
    IntellijReadObservation by delegate {
    val calls
        get() = delegate.calls

    val counters = mutableMapOf<IntellijReadCounter, Long>()

    override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
        counters[counter] = (counters[counter] ?: 0L) + amount
    }

    fun assertFinished() = delegate.assertFinished()
}
