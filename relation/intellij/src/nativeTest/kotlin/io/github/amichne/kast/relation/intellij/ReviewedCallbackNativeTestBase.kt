@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteIdentity
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceSourceSetName
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallOutcome
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Each case owns its imported project and bounded diagnostic receipts. */
// JUnit3 must not instantiate the fixture base without test methods.
@Suppress("AbstractClassCanBeConcreteClass")
internal abstract class ReviewedCallbackNativeTestBase : HeavyPlatformTestCase() {
    protected val observation = ValueReturnTestObservation()

    override fun getModuleType() = JavaModuleType.getModuleType()

    override fun runInDispatchThread() = false

    protected suspend fun trace(
        fixture: ReviewedReturnFixture,
        beforeRead: (ValueSite) -> Unit = {},
        reject: (ValueFlowRead.Rejected) -> Unit = { error("Unexpected native rejection: $it") },
    ): List<ValueFlowStep> {
        val adapter = IntellijValueFlowCompilerAdapter(observation)
        val seeded =
            adapter.seed(project = project, current = fixture.lease, request = fixture.seed, model = fixture.model)
        assertTrue("Exact constructor seed must be admitted: $seeded", seeded is ValueProducerSeedRead.Seeded)
        val pending = ArrayDeque<ValueSite>().apply { add((seeded as ValueProducerSeedRead.Seeded).seed.site) }
        val visited = mutableSetOf<ValueSiteIdentity>()
        val steps = mutableListOf<ValueFlowStep>()
        while (pending.isNotEmpty()) {
            val site = pending.removeFirst()
            if (!visited.add(site.identity)) continue
            beforeRead(site)
            assertTrue("Fixture route must remain finite", visited.size < MAX_SITES)
            val read =
                adapter.read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(site, fixture.seed.budget, domain()),
                    model = fixture.model,
                )
            when (read) {
                is ValueFlowRead.Rejected -> reject(read)
                is ValueFlowRead.Observed -> {
                    steps.add(read.step)
                    read.step.transfers.forEach { pending.add(it.target) }
                    read.step.reviewedTransfers.forEach { pending.add(it.target) }
                }
                is ValueFlowRead.ContractRejected,
                is ValueFlowRead.Suspended -> error("Unexpected native read: $read")
            }
        }
        return steps
    }

    protected suspend fun fixture(variant: String, expectedHash: String): ReviewedReturnFixture {
        val bytes = Files.readAllBytes(Path.of("testData/r1-value-flow/$variant.kt"))
        assertEquals(
            expectedHash,
            java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
        )
        assertEquals("ClientValue(id = id)", bytes.toString(Charsets.UTF_8).substring(SEED_START, SEED_END))
        val base = getOrCreateProjectBaseDir()
        lateinit var root: VirtualFile
        lateinit var source: VirtualFile
        var mainRoot: VirtualFile? = null
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                root =
                    base
                        .createChildDirectory(this, "src")
                        .createChildDirectory(this, "test")
                        .createChildDirectory(this, "kotlin")
                source =
                    root
                        .createChildDirectory(this, "kast")
                        .createChildDirectory(this, "repro")
                        .createChildData(this, "Repro.kt")
                source.setBinaryContent(bytes)
                if (variant == "E8") {
                    val main =
                        checkNotNull(base.findChild("src"))
                            .createChildDirectory(this, "main")
                            .createChildDirectory(this, "kotlin")
                    mainRoot = main
                    val external = Files.readAllBytes(Path.of("testData/r1-value-flow/E8-external.kt"))
                    assertEquals(
                        "eb8c304394cd8fa359a8e0dfa3f8f951dfd75c52417b5081d4a4e975522d5fb2",
                        java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(external)),
                    )
                    main
                        .createChildDirectory(this, "outside")
                        .createChildData(this, "Supply.kt")
                        .setBinaryContent(external)
                }
            }
            prepareModule(root, mainRoot)
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        return readAction {
            if (variant == "E0") assertSelectedMap(psiManager.findFile(source) as KtFile)
            describeFixture(base = base, root = root, source = source, mainRoot = mainRoot)
        }
    }

    private fun assertSelectedMap(file: KtFile) {
        val call =
            PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java).single {
                it.calleeExpression?.text == "map"
            }
        IntellijReadObservation.None.observedAnalyze(call) {
            val symbol = checkNotNull(call.resolveCall()).signature.symbol
            assertEquals("kotlin/collections/Iterable<T>", symbol.receiverParameter?.returnType.toString())
            assertEquals(listOf("(T) -> R"), symbol.valueParameters.map { it.returnType.toString() })
            assertTrue(
                checkNotNull(symbol.psi?.containingFile?.virtualFile)
                    .url
                    .endsWith("!/kotlin/collections/CollectionsKt.class")
            )
        }
    }

    private fun prepareModule(root: VirtualFile, mainRoot: VirtualFile?) {
        val sdk =
            JavaSdk.getInstance().createJdk("reviewed-callback-return-jdk", System.getProperty("java.home"), false)
        registerTestProjectJdk(sdk)
        val library =
            System.getProperty("java.class.path").split(java.io.File.pathSeparator).map(Path::of).single {
                it.fileName.toString() == "kotlin-stdlib-2.4.21.jar"
            }
        val digest =
            java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(library)))
        assertEquals("bd8250210584cb659847dce9cda660f8e9906e5def7fbebcea93dc6dcb6a88a3", digest)
        ModuleRootModificationUtil.updateModel(module) { model ->
            model.sdk = sdk
            model.addContentEntry(root).addSourceFolder(root, true)
            mainRoot?.let { model.addContentEntry(it).addSourceFolder(it, false) }
            val roots = model.moduleLibraryTable.createLibrary("reviewed-callback-stdlib").modifiableModel
            roots.addRoot("jar://$library!/", OrderRootType.CLASSES)
            val sources =
                Path.of(
                    checkNotNull(javaClass.getResource("/reviewed-libraries/kotlin-stdlib-2.4.21-sources.jar")).toURI()
                )
            val ownedSources = getOrCreateProjectBaseDir().toNioPath().resolve("reviewed-stdlib-sources.jar")
            Files.copy(sources, ownedSources)
            checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(ownedSources))
            roots.addRoot("jar://$ownedSources!/", OrderRootType.SOURCES)
            roots.commit()
        }
    }

    private fun describeFixture(
        base: VirtualFile,
        root: VirtualFile,
        source: VirtualFile,
        mainRoot: VirtualFile?,
    ): ReviewedReturnFixture {
        val lease =
            SemanticReadLease(
                CanonicalWorkspaceRoot.fromCanonicalPath(base.toNioPath()).nativeRefined(),
                EvidenceGeneration.parse(1).nativeRefined(),
            )
        val model = sourceModel(lease = lease, base = base, root = root, mainRoot = mainRoot)
        val file = psiManager.findFile(source) as KtFile
        assertNoErrors(file)
        val projection = IntellijK2RelationProjection(project, lease.workspaceRoot)
        fun selector(declaration: com.intellij.psi.PsiNamedElement): SymbolSelector {
            val evidence = (projection.project(declaration) as IntellijRelationDeclarationProjection.Projected).evidence
            return SymbolSelector.issue(lease, domain().scope, evidence)
        }
        val enclosing =
            selector(file.declarations.filterIsInstance<KtNamedFunction>().single { it.name == "toClientValue" })
        val constructor =
            selector(
                checkNotNull(
                    file.declarations.filterIsInstance<KtClass>().single { it.name == "ClientValue" }.primaryConstructor
                )
            )
        val budget =
            RelationBudget(
                ResourceBudget.RunnerControlled,
                RelationByteLimit.parse(1_048_576).nativeRefined(),
                ResultLimit.parse(100).nativeRefined(),
            )
        val seed =
            ValueProducerSeedRequest.create(
                    enclosing = enclosing,
                    anchor = exactRange(SEED_START, SEED_END),
                    expectedCallable = constructor,
                    budget = budget,
                    boundary = domain(),
                )
                .nativeRefined()
        return ReviewedReturnFixture(
            lease = lease,
            model = model,
            seed = seed,
            file = source,
            sourceArtifact = base.toNioPath().resolve("reviewed-stdlib-sources.jar"),
        )
    }

    private fun sourceModel(
        lease: SemanticReadLease,
        base: VirtualFile,
        root: VirtualFile,
        mainRoot: VirtualFile?,
    ): WorkspaceSearchScopeModelCompilation {
        return WorkspaceSearchScopeModel.compile(
            lease.workspaceRoot,
            ImportedWorkspaceModelState.COMPLETE,
            listOf(
                WorkspaceSourceRootBoundary(
                    ideaModuleName = module.name,
                    linkedBuildRoot = base.toNioPath(),
                    gradleProjectPath = ":",
                    sourceSetName = "test",
                    sourceRoot = root.toNioPath(),
                    sourceKind = WorkspaceSourceRootKind.TEST,
                    provenance = WorkspaceSourceRootProvenance.AUTHORED,
                )
            ) +
                listOfNotNull(
                    mainRoot?.let { main ->
                        WorkspaceSourceRootBoundary(
                            ideaModuleName = module.name,
                            linkedBuildRoot = base.toNioPath(),
                            gradleProjectPath = ":",
                            sourceSetName = "main",
                            sourceRoot = main.toNioPath(),
                            sourceKind = WorkspaceSourceRootKind.PRODUCTION,
                            provenance = WorkspaceSourceRootProvenance.AUTHORED,
                        )
                    }
                ),
        )
    }

    private fun assertNoErrors(file: KtFile) {
        val errors =
            IntellijReadObservation.None.observedAnalyze(file) {
                file
                    .collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
                    .filter { it.severity == KaSeverity.ERROR }
                    .map { it.factoryName }
            }
        assertTrue("Fixture compiler errors: $errors", errors.isEmpty())
    }

    protected fun domain() =
        RelationSearchBoundary.Explicit(
            SymbolSearchScope.Workspace(
                SymbolSourceKindPolicy.TEST_ONLY,
                SymbolGeneratedSourcePolicy.EXCLUDE,
                SymbolLibraryPolicy.EXCLUDE,
            ),
            SymbolDiscoveryDirectoryConstraint(
                SymbolDiscoveryDirectory.parse("src/test/kotlin").nativeRefined(),
                SymbolDiscoveryContainment.DESCENDANTS,
            ),
            SymbolDiscoverySourceSets.Exact.from(setOf(WorkspaceSourceSetName.parse("test").nativeRefined()))
                .nativeRefined(),
        )

    protected fun exactRange(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).nativeRefined()

    companion object {
        protected const val SEED_START = 232
        protected const val SEED_END = 252
        protected const val CALL_START = 436
        protected const val CALL_END = 457
        protected const val BODY_START = 434
        protected const val BODY_END = 459
        protected const val PREFIX_SIZE = 5
        protected const val DIRECT_ROUTE_SIZE = 7
        protected const val MAX_SITES = 64
    }
}

internal data class ReviewedReturnFixture(
    val lease: SemanticReadLease,
    val model: WorkspaceSearchScopeModelCompilation,
    val seed: ValueProducerSeedRequest,
    val file: VirtualFile,
    val sourceArtifact: Path,
)

internal class ValueReturnTestObservation : IntellijReadObservation {
    private val counters = java.util.EnumMap<IntellijReadCounter, Int>(IntellijReadCounter::class.java)
    val calls = java.util.EnumMap<IntellijReadCall, Int>(IntellijReadCall::class.java)
    val returns = java.util.EnumMap<IntellijReadCall, Int>(IntellijReadCall::class.java)
    val terminations: java.util.EnumSet<IntellijReadTermination> =
        java.util.EnumSet.noneOf(IntellijReadTermination::class.java)

    fun count(counter: IntellijReadCounter): Int = counters[counter] ?: 0

    override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
        counters.merge(counter, amount, Int::plus)
    }

    override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) {
        terminations.add(reason)
    }

    override fun enterCall(call: IntellijReadCall): IntellijReadCallScope {
        calls.merge(call, 1, Int::plus)
        return object : IntellijReadCallScope {
            override fun finish(outcome: IntellijReadCallOutcome) {
                check(outcome == IntellijReadCallOutcome.RETURNED)
                returns.merge(call, 1, Int::plus)
            }
        }
    }
}
