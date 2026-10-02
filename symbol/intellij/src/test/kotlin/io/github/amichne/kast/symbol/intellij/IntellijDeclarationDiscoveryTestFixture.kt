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
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDeclarationKinds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail

/** Case-owned real PSI and VFS observations for the declaration discovery adapter. */
internal object IntellijDeclarationDiscoveryTestFixture {
    fun exhaust(fixture: Fixture, limit: Int, work: Long, nativeBoundary: Boolean = false): Exhaustion {
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

    data class Exhaustion(
        val names: List<String>,
        val inventories: Int,
        val leaves: Long,
        val pages: List<SymbolDiscoveryOutcome>,
    )

    class Fixture(
        val scope: CompiledIntellijSearchScope,
        val files: List<KtFile>,
        val kinds: Set<CompilerSymbolKind>,
        val directory: SymbolDiscoveryDirectoryConstraint?,
    ) {
        var inventories = 0
        var initialInventories = 0
        val counters = mutableMapOf<IntellijReadCounter, Int>()
        val terminations = mutableListOf<IntellijReadTermination>()
        private val observation =
            object : IntellijReadObservation {
                override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
                    counters[counter] = (counters[counter] ?: 0) + amount
                }

                override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) {
                    terminations += reason
                }
            }
        val leafCount: Long = files.sumOf { file ->
            generateSequence(file.findElementAt(0)) { PsiTreeUtil.nextLeaf(it, true) }.count().toLong()
        }

        fun adapter(
            project: Project,
            clock: IntellijReadNanoClock = SystemIntellijDiscoveryNanoClock,
        ): IntellijDeclarationSourceAdapter {
            val request = request(20, 100, 100_000, null)
            return IntellijDeclarationSourceAdapter(
                project,
                scope,
                request,
                ReadLimits.Default,
                IntellijDeclarationDiscoveryAllowance(request, clock),
                observation,
            )
        }

        fun page(
            limit: Int,
            work: Long,
            remainder: SymbolDiscoveryRemainder?,
            bytes: Long = 100_000,
            nativeBoundary: Boolean = false,
            clock: IntellijReadNanoClock = SystemIntellijDiscoveryNanoClock,
            limits: ReadLimits = ReadLimits.Default,
            cancellationCheck: () -> Unit = {},
        ): IntellijNativeDiscoveryExecution =
            ApplicationManager.getApplication().runReadAction<IntellijNativeDiscoveryExecution> {
                val request = request(limit, work, bytes, remainder)
                if (nativeBoundary)
                    discoverIncrementalScopedDeclarations(
                        scope.nativeScope.project!!,
                        scope,
                        request,
                        limits,
                        IntellijDeclarationDiscoveryAllowance(request, clock),
                        observation,
                    )
                else
                    IntellijIncrementalDeclarationDiscovery(
                            scope,
                            request,
                            limits,
                            IntellijDeclarationDiscoveryAllowance(request, clock),
                            initialPartitions = ::initialPartitions,
                            observePartition = ::observePartition,
                            environment = { IntellijDiscoveryEnvironmentState.READY },
                            cancellationCheck = cancellationCheck,
                            observation = observation,
                        )
                        .execute()
            }

        fun request(limit: Int, work: Long, bytes: Long, remainder: SymbolDiscoveryRemainder?) =
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
                SymbolDiscoveryConstraints(directory, null, SymbolDiscoveryDeclarationKinds.from(kinds).refined()),
                remainder,
            )

        fun initialPartitions(): IntellijDeclarationInitialInventory {
            initialInventories++
            return IntellijDeclarationInitialInventory.Complete(
                listOf(
                    SemanticFilePartition.Directory(
                        SymbolDiscoveryFileIdentity.External(
                            DetachedVirtualFileUrl.parse("file://${scope.lease.workspaceRoot.value}").refined()
                        )
                    )
                )
            )
        }

        fun observePartition(partition: SemanticFilePartition): IntellijDeclarationPartitionObservation {
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

        fun detachedChild(child: com.intellij.openapi.vfs.VirtualFile): SemanticFilePartition? {
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

    fun fixture(
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
        onNativeFileAdmission: () -> Unit = {},
        sourceRootDirectory: String = ".",
        directory: String? = null,
    ): Fixture {
        Files.createDirectories(requestedRoot)
        val root = requestedRoot.toRealPath()
        val files = sourceFiles(project, root, sources)
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
                override fun contains(file: VirtualFile): Boolean {
                    onNativeFileAdmission()
                    return files.any { it.virtualFile == file }
                }

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
                        root.resolve(sourceRootDirectory).normalize(),
                        WorkspaceSourceRootKind.PRODUCTION,
                        WorkspaceSourceRootProvenance.AUTHORED,
                    )
                ),
            ) as WorkspaceSearchScopeModelCompilation.Compiled
        return Fixture(
            CompiledIntellijSearchScope(authority, policy, admitted.model.sourceRoots, native),
            files,
            kinds,
            directory?.let {
                SymbolDiscoveryDirectoryConstraint(
                    SymbolDiscoveryDirectory.parse(it).refined(),
                    SymbolDiscoveryContainment.DESCENDANTS,
                )
            },
        )
    }

    private fun sourceFiles(project: Project, root: Path, sources: Map<String, String>): List<KtFile> =
        sources.map { (name, text) ->
            val path = root.resolve(name)
            Files.createDirectories(path.parent)
            Files.writeString(path, text)
            val virtual =
                requireNotNull(VirtualFileManager.getInstance().getFileSystem("file").findFileByPath(path.toString()))
            ApplicationManager.getApplication().runReadAction<KtFile> {
                requireNotNull(PsiManager.getInstance(project).findFile(virtual) as? KtFile)
            }
        }

    @OptIn(
        CompilerConfiguration.Internals::class,
        org.jetbrains.kotlin.K1Deprecation::class,
        org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class,
    )
    fun withParser(home: Path, assertion: (Project) -> Unit) {
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

    fun IntellijNativeDiscoveryExecution.outcome(): SymbolDiscoveryOutcome =
        (this as IntellijNativeDiscoveryExecution.Produced).outcome

    fun SymbolDiscoveryOutcome.batch(): SymbolDiscoveryBatch =
        when (this) {
            is SymbolDiscoveryOutcome.Complete -> batch
            is SymbolDiscoveryOutcome.Qualified -> batch
        }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
