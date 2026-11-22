package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationReferenceContext
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
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
import io.github.amichne.kast.workspace.intellij.read.IntellijSemanticSourceFileAdmission
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/**
 * Reference reads through the production project-bound port over one `main` and one `test` source root. Every case
 * asserts the complete occurrence set that an independent reading of the fixture source predicts.
 */
class NativeReferenceDestinationTest : HeavyPlatformTestCase() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    override fun runInDispatchThread() = false

    fun testDefaultDestinationsIncludeTestSourceConstructionsOfAMainClass() {
        val fixture = fixture()
        val payload = fixture.declaration(PAYLOAD, "Payload")
        val compilation = runBlocking { fixture.read(payload, WORKSPACE) }
        assertEquals(
            setOf(
                Occurrence(PAYLOAD, PAYLOAD_SOURCE.offsetOf("Payload", "fun describe"), RelationReferenceContext.TYPE),
                Occurrence(PAYLOAD_TEST, PAYLOAD_TEST_SOURCE.offsetOf("Payload", ":"), RelationReferenceContext.TYPE),
                Occurrence(PAYLOAD_TEST, PAYLOAD_TEST_SOURCE.offsetOf("Payload(1)"), RelationReferenceContext.CODE),
            ),
            fixture.completeOccurrences(compilation),
        )
    }

    fun testTestDestinationAdmitsAMainSubjectAndReturnsOnlyTestOccurrences() {
        val fixture = fixture()
        val payload = fixture.declaration(PAYLOAD, "Payload")
        val testOnly =
            RelationSearchBoundary.Explicit(
                fixture.workspaceScope(),
                sourceSets = sourceSets("test"),
            )
        val compilation = runBlocking { fixture.read(payload, testOnly) }
        assertEquals(
            setOf(
                Occurrence(PAYLOAD_TEST, PAYLOAD_TEST_SOURCE.offsetOf("Payload", ":"), RelationReferenceContext.TYPE),
                Occurrence(PAYLOAD_TEST, PAYLOAD_TEST_SOURCE.offsetOf("Payload(1)"), RelationReferenceContext.CODE),
            ),
            fixture.completeOccurrences(compilation),
        )
    }

    fun testFileScopedSeedFindsLambdaTestAndConstructorReferences() {
        val fixture = fixture()
        val fileScoped =
            SymbolDiscoveryConstraints(
                fileDirectory(fixture.relative(BRIDGE)),
                null,
                sourceSets = sourceSets("main", "test"),
            )
        val execute = fixture.declaration(BRIDGE, "execute")
        assertEquals(
            setOf(
                Occurrence(SHUTDOWN, SHUTDOWN_SOURCE.offsetOf("execute(it)"), RelationReferenceContext.CODE),
                Occurrence(BRIDGE_TEST, BRIDGE_TEST_SOURCE.offsetOf("execute(1)"), RelationReferenceContext.CODE),
            ),
            fixture.completeOccurrences(runBlocking { fixture.read(execute, WORKSPACE, fileScoped) }),
        )
        val running = fixture.declaration(BRIDGE, "Running")
        assertEquals(
            setOf(
                Occurrence(BRIDGE, BRIDGE_SOURCE.offsetOf("Running(id)"), RelationReferenceContext.CODE),
                Occurrence(BRIDGE_TEST, BRIDGE_TEST_SOURCE.offsetOf("Running(2)"), RelationReferenceContext.CODE),
            ),
            fixture.completeOccurrences(runBlocking { fixture.read(running, WORKSPACE, fileScoped) }),
        )
    }

    private data class Occurrence(val file: String, val start: Int, val context: RelationReferenceContext)

    private inner class Fixture(
        private val base: VirtualFile,
        private val files: Map<String, VirtualFile>,
        private val model: WorkspaceSearchScopeModel,
        private val roots: Map<String, Path>,
    ) {
        private val root = CanonicalWorkspaceRoot.fromCanonicalPath(base.toNioPath()).nativeRefined()
        private val lease = SemanticReadLease(root, EvidenceGeneration.parse(1L).nativeRefined())

        fun workspaceScope(): SymbolSearchScope =
            SymbolSearchScope.Workspace(
                SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                SymbolGeneratedSourcePolicy.EXCLUDE,
                SymbolLibraryPolicy.EXCLUDE,
            )

        fun relative(name: String): String = base.toNioPath().relativize(files.getValue(name).toNioPath()).toString()

        fun declaration(file: String, name: String): KtNamedDeclaration =
            ApplicationManager.getApplication().runReadAction<KtNamedDeclaration> {
                PsiTreeUtil.findChildrenOfType(
                        psiManager.findFile(files.getValue(file)) as KtFile,
                        KtNamedDeclaration::class.java,
                    )
                    // A primary constructor shares its class name; the subject is the class or function.
                    .single { it.name == name && it !is KtConstructor<*> }
            }

        /** The subject keeps its own selection scope; [boundary] alone selects the destinations. */
        suspend fun read(
            target: KtNamedDeclaration,
            boundary: RelationSearchBoundary,
            subjectConstraints: SymbolDiscoveryConstraints = SymbolDiscoveryConstraints.None,
        ): RelationCompilation {
            val request = readAction {
                val evidence =
                    (IntellijK2RelationProjection(project, root).project(target)
                            as IntellijRelationDeclarationProjection.Projected)
                        .evidence
                RelationRequest.start(
                    selector =
                        SymbolSelector.issue(
                            lease = lease,
                            scope = workspaceScope(),
                            evidence = evidence,
                            constraints = subjectConstraints,
                        ),
                    meaning = RelationMeaning.References,
                    budget = BUDGET,
                    boundary = boundary,
                )
            }
            return ProjectBoundIntellijRelationPort.create(
                    project = project,
                    authority = lease,
                    model = model,
                    fileAdmission = admission(),
                )
                .read(request)
        }

        /** The imported owner gate: a path is admitted only under a root of a requested source set. */
        private fun admission() = IntellijSemanticSourceFileAdmission { path, sets ->
            val owner = roots.entries.singleOrNull { path.startsWith(it.value) }?.key
            owner != null &&
                when (sets) {
                    SymbolDiscoverySourceSets.All -> true
                    is SymbolDiscoverySourceSets.Exact -> sets.values.any { it.value == owner }
                }
        }

        fun completeOccurrences(compilation: RelationCompilation): Set<Occurrence> {
            assertTrue(
                "Expected a complete reference read, received $compilation",
                compilation is RelationCompilation.Complete,
            )
            val names = files.entries.associate { (name, file) -> file.toNioPath().toString() to name }
            return (compilation as RelationCompilation.Complete)
                .batch
                .referenceOccurrences
                .map { reference ->
                    val file = reference.occurrence.file as SymbolDiscoveryFileIdentity.Workspace
                    Occurrence(
                        names.getValue(file.path.value),
                        reference.occurrence.range.startInclusive,
                        reference.context,
                    )
                }
                .toSet()
        }
    }

    private fun fixture(): Fixture {
        lateinit var fixture: Fixture
        ApplicationManager.getApplication().invokeAndWait {
            val base = getOrCreateProjectBaseDir()
            val created =
                WriteCommandAction.writeCommandAction(project).compute<Map<String, VirtualFile>, RuntimeException> {
                    val main = VfsUtil.createDirectoryIfMissing(base, "src/main/kotlin")
                    val test = VfsUtil.createDirectoryIfMissing(base, "src/test/kotlin")
                    fun file(directory: VirtualFile, name: String, text: String): VirtualFile =
                        directory.createChildData(this, name).also { it.setBinaryContent(text.toByteArray()) }
                    mapOf(
                        "main" to main,
                        "test" to test,
                        PAYLOAD to file(main, PAYLOAD, PAYLOAD_SOURCE),
                        PAYLOAD_TEST to file(test, PAYLOAD_TEST, PAYLOAD_TEST_SOURCE),
                        BRIDGE to file(main, BRIDGE, BRIDGE_SOURCE),
                        SHUTDOWN to file(main, SHUTDOWN, SHUTDOWN_SOURCE),
                        BRIDGE_TEST to file(test, BRIDGE_TEST, BRIDGE_TEST_SOURCE),
                    )
                }
            val main = created.getValue("main")
            val test = created.getValue("test")
            val sdk =
                JavaSdk.getInstance().createJdk("reference-destination-jdk", System.getProperty("java.home"), false)
            registerTestProjectJdk(sdk)
            ModuleRootModificationUtil.updateModel(module) { model ->
                model.sdk = sdk
                val content = model.addContentEntry(base)
                content.addSourceFolder(main, false)
                content.addSourceFolder(test, true)
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            fixture =
                Fixture(
                    base = base,
                    files = created - "main" - "test",
                    model = importedModel(base, main, test),
                    roots = mapOf("main" to main.toNioPath(), "test" to test.toNioPath()),
                )
        }
        return fixture
    }

    /** One imported Gradle project with an authored `main` production root and `test` test root. */
    private fun importedModel(base: VirtualFile, main: VirtualFile, test: VirtualFile): WorkspaceSearchScopeModel {
        fun boundary(set: String, directory: VirtualFile, kind: WorkspaceSourceRootKind) =
            WorkspaceSourceRootBoundary(
                ideaModuleName = module.name,
                linkedBuildRoot = base.toNioPath(),
                gradleProjectPath = ":app",
                sourceSetName = set,
                sourceRoot = directory.toNioPath(),
                sourceKind = kind,
                provenance = WorkspaceSourceRootProvenance.AUTHORED,
            )
        return (WorkspaceSearchScopeModel.compile(
                CanonicalWorkspaceRoot.fromCanonicalPath(base.toNioPath()).nativeRefined(),
                ImportedWorkspaceModelState.COMPLETE,
                listOf(
                    boundary("main", main, WorkspaceSourceRootKind.PRODUCTION),
                    boundary("test", test, WorkspaceSourceRootKind.TEST),
                ),
            ) as WorkspaceSearchScopeModelCompilation.Compiled)
            .model
    }
}

/** The public `--all --in <file>` seed lowers the file path to a descendants directory constraint. */
private fun fileDirectory(path: String) =
    SymbolDiscoveryDirectoryConstraint(
        SymbolDiscoveryDirectory.parse(path).nativeRefined(),
        SymbolDiscoveryContainment.DESCENDANTS,
    )

private fun sourceSets(vararg names: String): SymbolDiscoverySourceSets =
    SymbolDiscoverySourceSets.Exact.from(names.map { WorkspaceSourceSetName.parse(it).nativeRefined() }.toSet())
        .nativeRefined()

/** Offset of [token], searched after the first occurrence of [after] when one is given. */
private fun String.offsetOf(token: String, after: String? = null): Int {
    val start = if (after == null) 0 else indexOf(after)
    check(start >= 0) { "Missing anchor $after" }
    val offset = indexOf(token, start)
    check(offset >= 0) { "Missing token $token" }
    return offset
}

private val WORKSPACE = RelationSearchBoundary.WORKSPACE_EXPANSION

private val BUDGET =
    RelationBudget(
        ResourceBudget(
            ResultLimit.parse(64).nativeRefined(),
            WorkUnitLimit.parse(100_000L).nativeRefined(),
            ElapsedTimeLimitMillis.parse(60_000L).nativeRefined(),
        ),
        RelationByteLimit.parse(1_000_000L).nativeRefined(),
    )

private const val PAYLOAD = "Payload.kt"
private const val PAYLOAD_TEST = "PayloadTest.kt"
private const val BRIDGE = "Bridge.kt"
private const val SHUTDOWN = "Shutdown.kt"
private const val BRIDGE_TEST = "BridgeTest.kt"

// The main class is constructed only by the test source; main uses it only as a type.
private val PAYLOAD_SOURCE =
    """
    package proof
    data class Payload(val value: Int)
    fun describe(payload: Payload): Int = payload.value
    """
        .trimIndent()

private val PAYLOAD_TEST_SOURCE =
    """
    package proof
    fun payloadCase(): Payload = Payload(1)
    """
        .trimIndent()

// A file-scoped seed: one top-level internal function and one nested class constructed in the same file.
private val BRIDGE_SOURCE =
    """
    package bridge
    internal sealed interface Target {
        data class Running(val id: Int) : Target
    }
    internal fun execute(id: Int): Target = Target.Running(id)
    """
        .trimIndent()

private val SHUTDOWN_SOURCE =
    """
    package bridge
    internal fun shutdown(ids: List<Int>): List<Target> = ids.map { execute(it) }
    """
        .trimIndent()

private val BRIDGE_TEST_SOURCE =
    """
    package bridge
    internal fun bridgeCase(): Target = execute(1)
    internal fun runningCase(): Target = Target.Running(2)
    """
        .trimIndent()
