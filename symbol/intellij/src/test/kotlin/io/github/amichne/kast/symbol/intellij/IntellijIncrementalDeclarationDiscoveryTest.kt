package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SemanticFilePartition
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBlockCause
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDeclarationKinds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryProgress
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRemainder
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
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Physical PSI establishes discovery traversal, not K2 identity or real index scheduling. */
class IntellijIncrementalDeclarationDiscoveryTest {
    @Test
    fun `all limits one five and twenty exhaust exact independently specified finite declarations`(
        @TempDir home: Path
    ) {
        withParser(home) { project ->
            val text =
                """
                package sample
                class Container(val constructorProperty: Int = 0) {
                    fun member() = 1
                    val property = 2
                    class Nested
                }
                typealias Alias = Container
                fun outer() { fun local() = 1; local() }
                """
                    .trimIndent()
            val expected =
                listOf("Container", "constructorProperty", "member", "property", "Nested", "Alias", "outer", "local")
            val fixture = fixture(project, home.resolve("finite"), mapOf("Declarations.kt" to text))
            for (limit in listOf(1, 5, 20)) {
                val startingCollected = fixture.counters[IntellijReadCounter.CANDIDATES_COLLECTED] ?: 0
                val startingProjected = fixture.counters[IntellijReadCounter.CANDIDATES_PROJECTED] ?: 0
                val exhausted = exhaust(fixture, limit, 1_000)
                assertEquals(expected, exhausted.names, "limit $limit preserves file/source order")
                assertEquals(expected.size, exhausted.names.toSet().size)
                assertEquals(
                    expected.size,
                    fixture.counters[IntellijReadCounter.CANDIDATES_COLLECTED]!! - startingCollected,
                )
                assertEquals(
                    expected.size,
                    fixture.counters[IntellijReadCounter.CANDIDATES_PROJECTED]!! - startingProjected,
                )
                assertEquals(1, exhausted.inventories, "the inventory is established once")
                assertEquals(fixture.leafCount, exhausted.leaves, "successors do not replay consumed PSI leaves")
            }
        }
    }

    @Test
    fun `large single file advances direct source positions under bounded grants`(@TempDir home: Path) {
        withParser(home) { project ->
            val expected = List(400) { "function$it" }
            val fixture =
                fixture(
                    project,
                    home.resolve("large"),
                    mapOf("Large.kt" to expected.joinToString("\n") { "fun $it() = 1" }),
                )
            val exhausted = exhaust(fixture, 20, 300)
            assertEquals(expected, exhausted.names)
            assertEquals(1, exhausted.inventories)
            assertEquals(fixture.leafCount, exhausted.leaves)
            assertTrue(exhausted.pages.all { it.batch().examinedWorkUnits.value <= 300 })
            assertTrue(exhausted.pages.first().batch().candidates.isNotEmpty())
        }
    }

    @Test
    fun `multi file scope advances while excluded declaration containers retain eligible members`(@TempDir home: Path) {
        withParser(home) { project ->
            val fixture =
                fixture(
                    project,
                    home.resolve("broad"),
                    (0 until 600).associate { index ->
                        "File${index.toString().padStart(3, '0')}.kt" to
                            "class Container$index { fun function$index() = 1 }"
                    },
                    setOf(CompilerSymbolKind.FUNCTION),
                )
            val exhausted = exhaust(fixture, 5, 50)
            assertEquals(List(600) { "function$it" }, exhausted.names)
            assertEquals(1, exhausted.inventories)
            assertEquals(fixture.leafCount, exhausted.leaves)
        }
    }

    @Test
    fun `a grant exhausted during partition discovery preserves undiscovered files`(@TempDir home: Path) {
        withParser(home) { project ->
            val fixture = fixture(project, home.resolve("inventory"), mapOf("A.kt" to "class A", "B.kt" to "class B"))
            val result = fixture.page(20, 1, null).outcome()
            assertTrue(result.progress is SymbolDiscoveryProgress.Resumable)
            val retained = (result.progress as SymbolDiscoveryProgress.Resumable).remainder
            assertEquals(2, retained.frontier.size)
            assertEquals(0L, retained.completedFiles.value)
            assertTrue(result.batch().candidates.isEmpty())
            assertTrue(fixture.counters.isEmpty(), "partition progress has not reached any declaration leaf")
        }
    }

    @Test
    fun `the native VFS adapter resumes a broad admitted source root beyond one bounded work grant`(
        @TempDir home: Path
    ) {
        withParser(home) { project ->
            val names = List(600) { "Declaration$it" }
            val fixture =
                fixture(
                    project,
                    home.resolve("native-broad"),
                    names
                        .mapIndexed { index, name ->
                            "File${index.toString().padStart(3, '0')}.kt" to "class $name"
                        }
                        .toMap(),
                )
            val exhausted = exhaust(fixture, 20, 512, nativeBoundary = true)
            assertEquals(names, exhausted.names)
            assertEquals(
                600L,
                exhausted.pages.sumOf { it.batch().measurements.inventoryFiles.value },
                "each VFS file is detached once",
            )
            assertEquals(fixture.leafCount, exhausted.leaves)
            assertTrue(exhausted.pages.first().batch().candidates.isNotEmpty())
            assertTrue(exhausted.pages.all { it.batch().examinedWorkUnits.value <= 512 })
        }
    }

    @Test
    fun `lexical partition order handles files preceding descendants of an earlier directory`(@TempDir home: Path) {
        withParser(home) { project ->
            val fixture =
                fixture(
                    project,
                    home.resolve("lexical"),
                    linkedMapOf(
                        "a/Z.kt" to "class Descendant",
                        "z.kt" to "class Last",
                        "a.kt" to "class Dot",
                    ),
                )
            val exhausted = exhaust(fixture, 1, 20)
            assertEquals(listOf("Dot", "Descendant", "Last"), exhausted.names)
            assertEquals(2, exhausted.inventories)
            assertEquals(fixture.leafCount, exhausted.leaves)
        }
    }

    @Test
    fun `a byte grant smaller than one detached candidate terminates with the exact cause`(@TempDir home: Path) {
        withParser(home) { project ->
            val fixture = fixture(project, home.resolve("bytes"), mapOf("A.kt" to "class A"))
            val result = fixture.page(1, 100, null, 1).outcome()
            assertEquals(SymbolDiscoveryProgress.Blocked(SymbolDiscoveryBlockCause.ITEM_BYTE_LIMIT), result.progress)
            assertTrue(result.batch().candidates.isEmpty())
            assertEquals(1, fixture.counters[IntellijReadCounter.CANDIDATES_COLLECTED])
            assertEquals(null, fixture.counters[IntellijReadCounter.CANDIDATES_PROJECTED])
        }
    }

    @Test
    fun `deadline before the first partition returns finite evidence without an unusable successor`(
        @TempDir home: Path
    ) {
        withParser(home) { project ->
            val fixture = fixture(project, home.resolve("expired"), mapOf("A.kt" to "class A"))
            var started = false
            val clock = IntellijReadNanoClock {
                if (started) 10_000_000_000L
                else {
                    started = true
                    0L
                }
            }
            val result = fixture.page(1, 100, null, clock = clock).outcome()
            assertEquals(
                SymbolDiscoveryProgress.Blocked(SymbolDiscoveryBlockCause.INSUFFICIENT_EXECUTION_GRANT),
                result.progress,
            )
            assertTrue(result.batch().candidates.isEmpty())
            assertEquals(0, result.batch().measurements.examinedLeaves.value)
        }
    }

    private fun exhaust(fixture: Fixture, limit: Int, work: Long, nativeBoundary: Boolean = false): Exhaustion {
        val pages = mutableListOf<SymbolDiscoveryOutcome>()
        var remainder: SymbolDiscoveryRemainder? = null
        val startingInventories = fixture.inventories
        repeat(10_000) {
            val outcome = fixture.page(limit, work, remainder, nativeBoundary = nativeBoundary).outcome()
            pages += outcome
            when (val progress = outcome.progress) {
                SymbolDiscoveryProgress.Exhausted ->
                    return Exhaustion(
                        pages.flatMap { it.batch().candidates.map { it.name.value } },
                        fixture.inventories - startingInventories,
                        pages.sumOf { it.batch().measurements.examinedLeaves.value },
                        pages,
                    )
                is SymbolDiscoveryProgress.Resumable -> {
                    remainder?.let { assertTrue(progress.remainder.advancesFrom(it)) }
                    remainder = progress.remainder
                }
                is SymbolDiscoveryProgress.Blocked -> fail("A supported finite fixture blocked: ${progress.cause}")
            }
        }
        error("A finite independently specified fixture did not exhaust")
    }

    private data class Exhaustion(
        val names: List<String>,
        val inventories: Int,
        val leaves: Long,
        val pages: List<SymbolDiscoveryOutcome>,
    )

    private class Fixture(
        val scope: CompiledIntellijSearchScope,
        val files: List<KtFile>,
        val kinds: Set<CompilerSymbolKind>,
    ) {
        var inventories = 0
        val counters = mutableMapOf<IntellijReadCounter, Int>()
        private val observation =
            object : IntellijReadObservation {
                override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
                    counters[counter] = (counters[counter] ?: 0) + amount
                }

                override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) = Unit
            }
        val leafCount: Long = files.sumOf { file ->
            generateSequence(file.findElementAt(0)) { PsiTreeUtil.nextLeaf(it, true) }.count().toLong()
        }

        fun page(
            limit: Int,
            work: Long,
            remainder: SymbolDiscoveryRemainder?,
            bytes: Long = 100_000,
            nativeBoundary: Boolean = false,
            clock: IntellijReadNanoClock = SystemIntellijDiscoveryNanoClock,
        ): IntellijNativeDiscoveryExecution =
            ApplicationManager.getApplication().runReadAction<IntellijNativeDiscoveryExecution> {
                val request = request(limit, work, bytes, remainder)
                if (nativeBoundary)
                    discoverIncrementalScopedDeclarations(
                        scope.nativeScope.project!!,
                        scope,
                        request,
                        ReadLimits.Default,
                        IntellijDeclarationDiscoveryAllowance(request, clock),
                        observation,
                    )
                else
                    IntellijIncrementalDeclarationDiscovery(
                            scope,
                            request,
                            ReadLimits.Default,
                            IntellijDeclarationDiscoveryAllowance(request, clock),
                            initialPartitions = ::initialPartitions,
                            observePartition = ::observePartition,
                            environment = { IntellijDiscoveryEnvironmentState.READY },
                            cancellationCheck = {},
                            observation = observation,
                        )
                        .execute()
            }

        private fun request(limit: Int, work: Long, bytes: Long, remainder: SymbolDiscoveryRemainder?) =
            SymbolDiscoveryRequest(
                SymbolSearchScopeRequest(scope.lease, scope.scope),
                SymbolDiscoveryTarget.All(SymbolNameDiscoveryKind.SYMBOL),
                SymbolDiscoveryBudget(
                    ResourceBudget(
                        ResultLimit.parse(limit).refined(),
                        WorkUnitLimit.parse(work).refined(),
                        ElapsedTimeLimitMillis.parse(10_000).refined(),
                    ),
                    SymbolDiscoveryByteLimit.parse(bytes).refined(),
                ),
                SymbolDiscoveryConstraints(null, null, SymbolDiscoveryDeclarationKinds.from(kinds).refined()),
                remainder,
            )

        private fun initialPartitions(): List<SemanticFilePartition> =
            listOf(
                SemanticFilePartition.Directory(
                    SymbolDiscoveryFileIdentity.External(
                        DetachedVirtualFileUrl.parse("file://${scope.lease.workspaceRoot.value}").refined()
                    )
                )
            )

        private fun observePartition(partition: SemanticFilePartition): IntellijDeclarationPartitionObservation {
            val url =
                when (val identity = partition.location) {
                    is SymbolDiscoveryFileIdentity.Workspace -> "file://${identity.path.value}"
                    is SymbolDiscoveryFileIdentity.External -> identity.url.value
                }
            val native =
                VirtualFileManager.getInstance().findFileByUrl(url)
                    ?: return IntellijDeclarationPartitionObservation.Rejected(
                        SymbolDiscoveryBlockCause.PARTITION_UNAVAILABLE
                    )
            if (partition is SemanticFilePartition.Directory) {
                inventories++
                return IntellijDeclarationPartitionObservation.Directory(native.children.mapNotNull(::detachedChild))
            }
            return files.singleOrNull { it.virtualFile == native }?.let(IntellijDeclarationPartitionObservation::Source)
                ?: IntellijDeclarationPartitionObservation.OutsideUniverse
        }

        private fun detachedChild(child: com.intellij.openapi.vfs.VirtualFile): SemanticFilePartition? {
            val identity =
                SymbolDiscoveryFileIdentity.fromBoundary(scope.lease.workspaceRoot, Path.of(child.path), child.url)
                    .refined()
            return when {
                child.isDirectory -> SemanticFilePartition.Directory(identity)
                files.any { it.virtualFile == child } -> SemanticFilePartition.File(identity)
                else -> null
            }
        }
    }

    private fun fixture(
        project: Project,
        requestedRoot: Path,
        sources: Map<String, String>,
        kinds: Set<CompilerSymbolKind> =
            setOf(
                CompilerSymbolKind.CLASSLIKE,
                CompilerSymbolKind.FUNCTION,
                CompilerSymbolKind.PROPERTY,
                CompilerSymbolKind.TYPE_ALIAS,
            ),
    ): Fixture {
        Files.createDirectories(requestedRoot)
        val root = requestedRoot.toRealPath()
        val files = sources.map { (name, text) ->
            val path = root.resolve(name)
            Files.createDirectories(path.parent)
            Files.writeString(path, text)
            val virtual =
                requireNotNull(VirtualFileManager.getInstance().getFileSystem("file").findFileByPath(path.toString()))
            ApplicationManager.getApplication().runReadAction<KtFile> {
                requireNotNull(PsiManager.getInstance(project).findFile(virtual) as? KtFile)
            }
        }
        val authority =
            SemanticReadLease(
                CanonicalWorkspaceRoot.fromCanonicalPath(root.toRealPath()).refined(),
                EvidenceGeneration.parse(1).refined(),
            )
        val policy =
            SymbolSearchScope.Workspace(
                SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                SymbolGeneratedSourcePolicy.EXCLUDE,
                SymbolLibraryPolicy.EXCLUDE,
            )
        val native =
            object : GlobalSearchScope(project) {
                override fun contains(file: VirtualFile): Boolean = files.any { it.virtualFile == file }

                override fun isSearchInModuleContent(module: com.intellij.openapi.module.Module): Boolean = true

                override fun isSearchInLibraries(): Boolean = false
            }
        val admitted =
            WorkspaceSearchScopeModel.compile(
                authority.workspaceRoot,
                ImportedWorkspaceModelState.COMPLETE,
                listOf(
                    WorkspaceSourceRootBoundary(
                        "fixture",
                        root,
                        ":",
                        "main",
                        root,
                        WorkspaceSourceRootKind.PRODUCTION,
                        WorkspaceSourceRootProvenance.AUTHORED,
                    )
                ),
            ) as WorkspaceSearchScopeModelCompilation.Compiled
        return Fixture(CompiledIntellijSearchScope(authority, policy, admitted.model.sourceRoots, native), files, kinds)
    }

    @OptIn(
        CompilerConfiguration.Internals::class,
        org.jetbrains.kotlin.K1Deprecation::class,
        org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class,
    )
    private fun withParser(home: Path, assertion: (Project) -> Unit) {
        val properties =
            listOf("idea.home.path", "idea.config.path", "idea.system.path").associateWith(System::getProperty)
        Files.createDirectories(home.resolve("bin"))
        Files.writeString(home.resolve("bin/idea.properties"), "")
        System.setProperty("idea.home.path", home.toString())
        System.setProperty("idea.config.path", home.resolve("config").toString())
        System.setProperty("idea.system.path", home.resolve("system").toString())
        val disposable = Disposer.newDisposable()
        try {
            val environment =
                KotlinCoreEnvironment.createForTests(
                    disposable,
                    CompilerConfiguration().apply { extensionsStorage = CompilerPluginRegistrar.ExtensionStorage() },
                    EnvironmentConfigFiles.JVM_CONFIG_FILES,
                )
            assertion(environment.project)
        } finally {
            val application = ApplicationManager.getApplication()
            if (application == null) Disposer.dispose(disposable)
            else application.runWriteAction { Disposer.dispose(disposable) }
            properties.forEach { (key, value) ->
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
        }
    }

    private fun IntellijNativeDiscoveryExecution.outcome(): SymbolDiscoveryOutcome =
        (this as IntellijNativeDiscoveryExecution.Produced).outcome

    private fun SymbolDiscoveryOutcome.batch(): SymbolDiscoveryBatch =
        when (this) {
            is SymbolDiscoveryOutcome.Complete -> batch
            is SymbolDiscoveryOutcome.Qualified -> batch
        }

    private companion object {
        fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
            when (this) {
                is Refinement.Refined -> value
                is Refinement.Rejected -> error(failure.toString())
            }
    }
}
