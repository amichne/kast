package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.backend.KastIndexerBackend

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.SearchScope
import io.github.amichne.kast.api.contract.SearchScopeKind
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.RuntimeState
import io.github.amichne.kast.api.contract.TypeHierarchyDirection
import io.github.amichne.kast.api.contract.query.ImplementationsQuery
import io.github.amichne.kast.api.contract.query.ReferencesQuery
import io.github.amichne.kast.api.contract.query.SymbolQuery
import io.github.amichne.kast.api.contract.query.TypeHierarchyQuery
import io.github.amichne.kast.api.contract.query.WorkspaceFilesQuery
import io.github.amichne.kast.api.contract.query.WorkspaceSearchQuery
import io.github.amichne.kast.api.contract.result.ResultCardinality
import io.github.amichne.kast.api.contract.result.ReferenceOccurrence
import io.github.amichne.kast.api.contract.result.CallRelationsResult
import io.github.amichne.kast.api.contract.result.HierarchyRelationsResult
import io.github.amichne.kast.api.contract.result.ImplementationRelationsResult
import io.github.amichne.kast.api.contract.result.RelationshipResultEvidence
import io.github.amichne.kast.api.contract.result.RelationshipSearchCoverage
import io.github.amichne.kast.api.contract.result.RelationshipSearchLimitation
import io.github.amichne.kast.api.contract.skill.KastCallersQuery
import io.github.amichne.kast.api.contract.skill.KastExactSymbolSelector
import io.github.amichne.kast.api.contract.skill.KastHierarchyQuery
import io.github.amichne.kast.api.contract.skill.KastImplementationsQuery
import io.github.amichne.kast.api.contract.skill.WrapperCallDirection
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.idea.backend.semantic.WorkspaceSemanticReadAuthority
import io.github.amichne.kast.workspace.spi.WorkspaceTransitionPort
import io.github.amichne.kast.indexstore.api.reference.SymbolReferenceRow
import io.github.amichne.kast.indexstore.api.reference.SymbolReferencePage
import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.change.plan.service.AddDeclarationPlanPersistence
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.jetbrains.jps.model.java.JavaModuleSourceRootTypes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

internal abstract class KastIndexerBackendContractTestFixture {
    companion object {
        private val projectFixture: TestFixture<Project> = projectFixture()

        private val defaultLimits = ServerLimits(
            maxResults = 500,
            requestTimeoutMillis = 30_000L,
            maxConcurrentRequests = 4,
        )

        private const val sampleSource = """
            package demo

            fun greet(name: String): String = "Hello, ${'$'}name"
        """

        private const val sampleUsageSource = """
            package demo

            fun useGreeting(): String = greet("idea")
        """

        private const val memberSource = """
            package demo

            class Parser {
                fun parse(input: String): String = input
            }
        """

        private const val hierarchySource = """
            package demo.hierarchy

            interface Shape

            class Circle : Shape
        """

        private const val internalDeclarationSource = """
            package demo.internalvisibility

            internal fun internalName(): String = "internal"

            fun mainUse(): String = internalName()
        """

        private const val internalDependentSource = """
            package demo.internalvisibility

            fun dependentUse(): String = internalName()
        """

        private val highCardinalityUsageSource = buildString {
            appendLine("package demo")
            appendLine()
            appendLine("fun highCardinalityUses(): List<String> = listOf(")
            repeat(500) { index -> appendLine("    greet(\"$index\"),") }
            appendLine(")")
        }
    }

    protected val mainModuleFixture: TestFixture<Module> = projectFixture.moduleFixture("main")
    protected val secondaryModuleFixture: TestFixture<Module> = projectFixture.moduleFixture("secondary")
    protected val mainSourceRootFixture: TestFixture<PsiDirectory> = mainModuleFixture.sourceRootFixture()
    protected val secondarySourceRootFixture: TestFixture<PsiDirectory> =
        secondaryModuleFixture.sourceRootFixture(isTestSource = true)
    protected val sampleFileFixture: TestFixture<PsiFile> = mainSourceRootFixture.psiFileFixture(
        "Sample.kt",
        sampleSource
    )
    protected val sampleUsageFileFixture: TestFixture<PsiFile> =
        mainSourceRootFixture.psiFileFixture("SampleUsage.kt", sampleUsageSource)
    protected val memberFileFixture: TestFixture<PsiFile> =
        mainSourceRootFixture.psiFileFixture("Parser.kt", memberSource)
    protected val hierarchyFileFixture: TestFixture<PsiFile> = mainSourceRootFixture.psiFileFixture(
        "Hierarchy.kt",
        hierarchySource
    )
    protected val internalDeclarationFileFixture: TestFixture<PsiFile> =
        mainSourceRootFixture.psiFileFixture("InternalDeclaration.kt", internalDeclarationSource)
    protected val internalDependentFileFixture: TestFixture<PsiFile> =
        secondarySourceRootFixture.psiFileFixture("InternalDependent.kt", internalDependentSource)
    protected val project: Project
        get() = projectFixture.get()

    protected val sampleFile: PsiFile
        get() = sampleFileFixture.get()

    protected val hierarchyFile: PsiFile
        get() = hierarchyFileFixture.get()

    protected fun backend(
        workspaceRoot: Path = Path.of(project.basePath!!),
        limits: ServerLimits = defaultLimits,
        telemetry: IdeaBackendTelemetry = IdeaBackendTelemetry.disabled(),
        referenceIndexLookup: ReferenceIndexLookup = ReferenceIndexLookup.Unavailable,
        referenceSearchClock: ReferenceSearchClock = ReferenceSearchClock.System,
        psiGeneration: () -> Long = { 1L },
        readEpochObserver: IdeaReadEpochObserver = IdeaReadEpochObserver.Disabled,
        referenceTraversalObserver: ReferenceTraversalObserver = ReferenceTraversalObserver.Disabled,
        workspaceSemanticReadAuthority: WorkspaceSemanticReadAuthority = TestWorkspaceSemanticReadAuthority(),
        workspaceTransitionRequester: WorkspaceTransitionPort = TestWorkspaceTransitionRequester(),
        workspaceModelReader: () -> IdeaGradleProjectLoadBridge.GradleWorkspaceModel = {
            IdeaGradleProjectLoadBridge.GradleWorkspaceModel(
                emptyList(),
                true,
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
            )
        },
        relationshipCoverageAuthority: RelationshipCoverageAuthority =
            RelationshipCoverageAuthority.proven(),
        addDeclarationPlanPersistenceService: AddDeclarationPlanPersistence =
            TestAddDeclarationPlanPersistence,
    ): KastIndexerBackend = KastIndexerBackend(
        project = project,
        workspaceRoot = workspaceRoot,
        limits = limits,
        telemetry = telemetry,
        referenceIndexLookup = referenceIndexLookup,
        referenceSearchClock = referenceSearchClock,
        psiGeneration = psiGeneration,
        readEpochObserver = readEpochObserver,
        referenceTraversalObserver = referenceTraversalObserver,
        workspaceSemanticReadAuthority = workspaceSemanticReadAuthority,
        workspaceTransitionRequester = workspaceTransitionRequester,
        workspaceModelReader = workspaceModelReader,
        relationshipCoverageAuthority = relationshipCoverageAuthority,
        addDeclarationPlanPersistence = addDeclarationPlanPersistenceService,
    )

    protected fun ensureProjectReady() {
        mainModuleFixture.get()
        secondaryModuleFixture.get()
        sampleFileFixture.get()
        sampleUsageFileFixture.get()
        hierarchyFileFixture.get()
        waitUntilIndexesAreReady(project)
    }

    protected fun relationshipCoverageAuthority(
        sourceIndexStore: SqliteSourceIndexStore? = null,
        transform: (IdeaGradleProjectLoadBridge.GradleWorkspaceModel) ->
        IdeaGradleProjectLoadBridge.GradleWorkspaceModel = { model -> model },
    ): IdeaRelationshipCoverageAuthority {
        val sourceRoots = ApplicationManager.getApplication().runReadAction<List<Path>> {
            listOf(mainModuleFixture.get(), secondaryModuleFixture.get())
                .flatMap { module ->
                    ModuleRootManager.getInstance(module)
                        .getSourceRoots(JavaModuleSourceRootTypes.SOURCES)
                        .toList()
                }
                .map { root -> Path.of(root.path).toAbsolutePath().normalize() }
                .sortedBy(Path::toString)
        }
        val workspaceRoot = commonWorkspaceRoot(
            sourceRoots.first().toString(),
            sourceRoots.last().toString(),
        )
        val mainIdentity = IdeaGradleProjectLoadBridge.GradleModuleIdentity(workspaceRoot, ":main")
        val secondaryIdentity = IdeaGradleProjectLoadBridge.GradleModuleIdentity(workspaceRoot, ":secondary")
        val model = IdeaGradleProjectLoadBridge.GradleWorkspaceModel(
            listOf(workspaceRoot),
            true,
            listOf(mainIdentity, secondaryIdentity),
            listOf(
                IdeaGradleProjectLoadBridge.LoadedGradleModule("main", mainIdentity),
                IdeaGradleProjectLoadBridge.LoadedGradleModule("secondary", secondaryIdentity),
            ),
            sourceRoots.map(::authoredGradleSourceRoot),
            emptyList(),
        )
        return IdeaRelationshipCoverageAuthority(
            project = project,
            workspaceIdentity = IdeaWorkspaceIdentity.fromProject(project, workspaceRoot),
            indexSemanticAdmissionStatus = {
                IdeaIndexSemanticAdmission.Status.Ready(testPublishedWorkspaceGeneration())
            },
            workspaceModelReader = { transform(model) },
            sourceIndexStore = sourceIndexStore,
        )
    }

    protected suspend fun ensureInternalVisibilityProjectReady() {
        ensureProjectReady()
        internalDeclarationFileFixture.get()
        internalDependentFileFixture.get()
        val application = ApplicationManager.getApplication()
        application.invokeAndWait {
            application.runWriteAction {
                ModuleRootModificationUtil.addDependency(
                    secondaryModuleFixture.get(),
                    mainModuleFixture.get(),
                    DependencyScope.TEST,
                    false,
                    true,
                )
            }
        }
        waitUntilIndexesAreReady(project)
    }

    protected val contractLimits: ServerLimits
        get() = defaultLimits

    protected val highCardinalitySource: String
        get() = highCardinalityUsageSource

    protected fun commonWorkspaceRoot(
        first: String,
        second: String,
    ): Path {
        val firstPath = Path.of(first).toAbsolutePath().normalize()
        val secondPath = Path.of(second).toAbsolutePath().normalize()
        return generateSequence(firstPath.parent) { it.parent }
            .first { candidate -> secondPath.startsWith(candidate) }
    }

    protected fun createIrrelevantKotlinFiles(count: Int): List<String> {
        val suffix = System.nanoTime().toString()
        val fileNames = (0 until count).map { index -> "Irrelevant${suffix}_$index.kt" }
        val application = ApplicationManager.getApplication()
        application.invokeAndWait {
            application.runWriteAction {
                val sourceRoot = mainSourceRootFixture.get().virtualFile
                fileNames.forEachIndexed { index, fileName ->
                    val file = sourceRoot.createChildData(this, fileName)
                    VfsUtil.saveText(
                        file,
                        """
                        package demo

                        fun unrelated${suffix}_$index(): Int = $index
                        """.trimIndent(),
                    )
                }
            }
        }
        waitUntilIndexesAreReady(project)
        return fileNames
    }

    protected fun deleteKotlinFiles(fileNames: List<String>) {
        val application = ApplicationManager.getApplication()
        application.invokeAndWait {
            application.runWriteAction {
                val sourceRoot = mainSourceRootFixture.get().virtualFile
                fileNames.forEach { fileName -> sourceRoot.findChild(fileName)?.delete(this) }
            }
        }
        waitUntilIndexesAreReady(project)
    }

    protected suspend fun collectAllReferencePages(
        backend: KastIndexerBackend,
        position: FilePosition,
    ): List<ReferenceOccurrence> {
        val references = mutableListOf<ReferenceOccurrence>()
        var pageToken: String? = null
        do {
            val result = backend.findReferences(
                ReferencesQuery(
                    position = position,
                    includeDeclaration = false,
                    maxResults = 50,
                    pageToken = pageToken,
                ),
            )
            references += result.references
            pageToken = result.page?.nextPageToken
        } while (pageToken != null)
        return references
    }
}

internal data class RelationshipCoverageTestInputs(
    val workspaceRoot: Path,
    val greetPosition: FilePosition,
    val greetSelector: KastExactSymbolSelector,
    val shapeSelector: KastExactSymbolSelector,
)

internal data class IndexedReferenceTestData(
    val workspaceRoot: Path,
    val declarationFilePath: String,
    val declarationOffset: Int,
    val usageFilePath: String,
    val usageOffset: Int,
)

internal data class MidLeafReferenceTestData(
    val workspaceRoot: Path,
    val position: FilePosition,
    val usageFilePath: String,
    val usageLeafOffset: Int,
)
