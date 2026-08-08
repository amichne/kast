package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.api.client.KastConfig

import com.intellij.openapi.module.Module
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.api.contract.FileOperation
import io.github.amichne.kast.api.contract.ExactFileImageBase64
import io.github.amichne.kast.api.contract.ExactFileImagePath
import io.github.amichne.kast.api.contract.ExactFileImageSha256
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.query.DiagnosticsQuery
import io.github.amichne.kast.api.contract.query.ExactFileImageQuery
import io.github.amichne.kast.api.contract.query.RefreshQuery
import io.github.amichne.kast.api.contract.result.FileAnalysisState
import io.github.amichne.kast.api.contract.result.FileSystemDiscoveryState
import io.github.amichne.kast.api.contract.result.ExactFileImageStatus
import io.github.amichne.kast.api.contract.result.AnalysisAvailabilityState
import io.github.amichne.kast.api.contract.result.IndexAdmissionState
import io.github.amichne.kast.api.contract.result.SemanticAnalysisOutcome
import io.github.amichne.kast.api.contract.result.SourceModuleOwnershipState
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.idea.transition.WorkspaceTransitionRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64

@TestApplication
class KastSemanticAdmissionRefreshTest {
    companion object {
        private val defaultLimits = ServerLimits(
            maxResults = 500,
            requestTimeoutMillis = 30_000L,
            maxConcurrentRequests = 4,
        )

        private const val seedSource = """
            package admission

            fun seed(): Int = 1
        """

        private const val newSource = """
            package admission

            fun newlyAdmitted(): Int = 2
        """

    }

    private val projectFixture: TestFixture<Project> = projectFixture(openAfterCreation = true)
    private val moduleFixture: TestFixture<Module> = projectFixture.moduleFixture("main")
    private val productionRootFixture: TestFixture<PsiDirectory> = moduleFixture.sourceRootFixture()
    private val testRootFixture: TestFixture<PsiDirectory> = moduleFixture.sourceRootFixture(isTestSource = true)
    private val seedFileFixture: TestFixture<PsiFile> = productionRootFixture.psiFileFixture("Seed.kt", seedSource)

    private val project: Project
        get() = projectFixture.get()

    private val productionRoot: Path
        get() = Path.of(productionRootFixture.get().virtualFile.path).toAbsolutePath().normalize()

    private val testRoot: Path
        get() = Path.of(testRootFixture.get().virtualFile.path).toAbsolutePath().normalize()

    private val workspaceRoot: Path
        get() = commonWorkspaceRoot(productionRoot, testRoot)

    private fun backend(
        admissionAwaiter: IdeaSemanticAdmissionAwaiter = IdeaSemanticAdmissionAwaiter.forRequestBudget(
            defaultLimits.requestTimeoutMillis,
        ),
        admissionOperations: IdeaSemanticAdmissionOperations = IdeaSemanticAdmissionOperations.idea(),
        semanticGraphStore: SqliteSourceIndexStore? = null,
        workspaceTransitionRequester: WorkspaceTransitionRequester = TestWorkspaceTransitionRequester(),
        workspaceModelReader: () -> IdeaGradleProjectLoadBridge.GradleWorkspaceModel = {
            IdeaGradleProjectLoadBridge.readWorkspaceModel(project)
        },
    ): KastIndexerBackend = KastIndexerBackend(
        project = project,
        workspaceRoot = workspaceRoot,
        limits = defaultLimits,
        semanticAdmissionAwaiter = admissionAwaiter,
        semanticAdmissionOperations = admissionOperations,
        semanticGraphStore = semanticGraphStore,
        workspaceSemanticReadAuthority = TestWorkspaceSemanticReadAuthority(),
        workspaceTransitionRequester = workspaceTransitionRequester,
        workspaceModelReader = workspaceModelReader,
    )

    private fun ensureProjectReady() {
        moduleFixture.get()
        productionRootFixture.get()
        testRootFixture.get()
        seedFileFixture.get()
        waitUntilIndexesAreReady(project)
    }

    @Test
    fun `new production file is admitted before immediate diagnostics`() = runBlocking {
        ensureProjectReady()
        val newFile = productionRoot.resolve("NewProduction.kt")
        Files.writeString(newFile, newSource)

        try {
            val refresh = backend().refresh(RefreshQuery(filePaths = listOf(newFile.toString())))
            val diagnostics = backend().diagnostics(DiagnosticsQuery(filePaths = listOf(newFile.toString())))

            assertEquals(SemanticAnalysisOutcome.COMPLETE, refresh.semanticOutcome)
            assertEquals(listOf(newFile.toString()), refresh.refreshedFiles)
            assertEquals(FileAnalysisState.ANALYZED, refresh.fileStatuses.single().analysisStatus?.state)
            assertEquals(SemanticAnalysisOutcome.COMPLETE, diagnostics.semanticOutcome)
            assertEquals(FileAnalysisState.ANALYZED, diagnostics.fileStatuses.single().state)
        } finally {
            Files.deleteIfExists(newFile)
        }
    }

    @Test
    fun `focused refresh updates the persisted relationship index`() = runBlocking {
        ensureProjectReady()
        val newFile = productionRoot.resolve("RefreshedCaller.kt")
        Files.writeString(
            newFile,
            """
            package admission

            fun refreshedCaller(): Int = seed()
            """.trimIndent(),
        )
        val completeGradleModel = completeGradleModel()

        try {
            SqliteSourceIndexStore(workspaceRoot).use { store ->
                val result = backend(
                    semanticGraphStore = store,
                    workspaceTransitionRequester = reconcilingRequester(store, completeGradleModel),
                    workspaceModelReader = { completeGradleModel },
                ).refresh(RefreshQuery(filePaths = listOf(newFile.toString())))

                assertEquals(SemanticAnalysisOutcome.COMPLETE, result.semanticOutcome)
                assertTrue(
                    store.referencesFromFile(newFile.toString()).any { reference ->
                        reference.targetFqName == "admission.seed"
                    },
                )
            }
        } finally {
            Files.deleteIfExists(newFile)
        }
    }

    @Test
    fun `new test file is admitted to its test source module`() = runBlocking {
        ensureProjectReady()
        val newFile = testRoot.resolve("NewTest.kt")
        Files.writeString(newFile, newSource)

        try {
            val result = backend().refresh(RefreshQuery(filePaths = listOf(newFile.toString())))

            assertEquals(SemanticAnalysisOutcome.COMPLETE, result.semanticOutcome)
            assertEquals(SourceModuleOwnershipState.OWNED, result.fileStatuses.single().sourceModuleOwnership)
            assertEquals(IndexAdmissionState.ADMITTED, result.fileStatuses.single().indexAdmission)
            assertEquals(AnalysisAvailabilityState.AVAILABLE, result.fileStatuses.single().analysisAvailability)
        } finally {
            Files.deleteIfExists(newFile)
        }
    }

    @Test
    fun `moved file reports old removal and new semantic admission`() = runBlocking {
        ensureProjectReady()
        val oldFile = productionRoot.resolve("BeforeMove.kt")
        val newFile = productionRoot.resolve("AfterMove.kt")
        Files.writeString(oldFile, newSource)
        backend().refresh(RefreshQuery(filePaths = listOf(oldFile.toString())))
        Files.move(oldFile, newFile, StandardCopyOption.REPLACE_EXISTING)

        try {
            val result = backend().refresh(
                RefreshQuery(filePaths = listOf(oldFile.toString(), newFile.toString())),
            )

            assertEquals(SemanticAnalysisOutcome.COMPLETE, result.semanticOutcome)
            assertEquals(listOf(oldFile.toString()), result.removedFiles)
            assertEquals(listOf(newFile.toString()), result.refreshedFiles)
            assertEquals(FileSystemDiscoveryState.REMOVED, result.fileStatuses.first().fileSystemDiscovery)
            assertEquals(FileAnalysisState.ANALYZED, result.fileStatuses.last().analysisStatus?.state)
        } finally {
            Files.deleteIfExists(oldFile)
            Files.deleteIfExists(newFile)
        }
    }

    @Test
    fun `deleted file is a terminal removal while later diagnostics fail closed`() = runBlocking {
        ensureProjectReady()
        val deletedFile = productionRoot.resolve("Deleted.kt")
        Files.writeString(deletedFile, newSource)
        val completeGradleModel = completeGradleModel()

        try {
            SqliteSourceIndexStore(workspaceRoot).use { store ->
                val backend = backend(
                    semanticGraphStore = store,
                    workspaceTransitionRequester = reconcilingRequester(store, completeGradleModel),
                    workspaceModelReader = { completeGradleModel },
                )
                backend.refresh(RefreshQuery(filePaths = listOf(deletedFile.toString())))
                assertTrue(store.loadManifest().orEmpty().containsKey(deletedFile.toString()))
                Files.delete(deletedFile)

                val refresh = backend.refresh(RefreshQuery(filePaths = listOf(deletedFile.toString())))
                val diagnostics = backend.diagnostics(DiagnosticsQuery(filePaths = listOf(deletedFile.toString())))

                assertEquals(SemanticAnalysisOutcome.COMPLETE, refresh.semanticOutcome)
                assertEquals(listOf(deletedFile.toString()), refresh.removedFiles)
                assertEquals(0, refresh.requestedFileCount)
                assertEquals(SemanticAnalysisOutcome.INCOMPLETE, diagnostics.semanticOutcome)
                assertEquals(FileAnalysisState.MISSING_ON_DISK, diagnostics.fileStatuses.single().state)
                assertFalse(store.loadManifest().orEmpty().containsKey(deletedFile.toString()))
                assertTrue(store.referencesFromFile(deletedFile.toString()).isEmpty())
            }
        } finally {
            Files.deleteIfExists(deletedFile)
        }
    }

    private fun reconcilingRequester(
        store: SqliteSourceIndexStore,
        model: IdeaGradleProjectLoadBridge.GradleWorkspaceModel,
    ): WorkspaceTransitionRequester = TestWorkspaceTransitionRequester(
        onReconcile = {
            ApplicationManager.getApplication().invokeAndWait {
                VirtualFileManager.getInstance().syncRefresh()
            }
            waitUntilIndexesAreReady(project)
            IdeaProjectIndexer(
                project = project,
                workspaceRoot = workspaceRoot,
                store = store,
                cancelled = { false },
                readGradleWorkspaceModel = { model },
            ).indexProject(KastConfig.defaults())
            testPublishedWorkspaceGeneration()
        },
    )

    private fun completeGradleModel(): IdeaGradleProjectLoadBridge.GradleWorkspaceModel {
        val identity = IdeaGradleProjectLoadBridge.GradleModuleIdentity(workspaceRoot, ":")
        val association = IdeaGradleProjectLoadBridge.GradleModuleAssociation(
            "main",
            workspaceRoot,
            workspaceRoot,
            ":",
            true,
            false,
            listOf(
                IdeaGradleProjectLoadBridge.GradleSourceSetAssociation("main", listOf(productionRoot)),
                IdeaGradleProjectLoadBridge.GradleSourceSetAssociation("test", listOf(testRoot)),
            ),
        )
        return IdeaGradleProjectLoadBridge.GradleWorkspaceModel(
            listOf(workspaceRoot),
            true,
            listOf(identity),
            listOf(IdeaGradleProjectLoadBridge.LoadedGradleModule("main", identity)),
            listOf(productionRoot, testRoot),
            listOf(association),
        )
    }

    @Test
    fun `file created through Kast edit application crosses the admission barrier`() = runBlocking {
        ensureProjectReady()
        val createdFile = productionRoot.resolve("KastCreated.kt")
        val createdSource = "package admission\n\nfun caller(): Int = callee()\nfun callee(): Int = 2\n"
        val backend = backend()

        try {
            backend.applyEdits(
                ApplyEditsQuery(
                    edits = emptyList(),
                    fileHashes = emptyList(),
                    fileOperations = listOf(FileOperation.CreateFile(createdFile.toString(), createdSource)),
                ),
            )
            val refresh = backend.refresh(RefreshQuery(filePaths = listOf(createdFile.toString())))
            val diagnostics = backend.diagnostics(DiagnosticsQuery(filePaths = listOf(createdFile.toString())))

            assertEquals(SemanticAnalysisOutcome.COMPLETE, refresh.semanticOutcome)
            assertEquals(FileAnalysisState.ANALYZED, diagnostics.fileStatuses.single().state)
            assertEquals(0, diagnostics.severityCounts.error)
        } finally {
            Files.deleteIfExists(createdFile)
        }
    }

    @Test
    fun `focused refresh retains path keyed freshness at the transition boundary`() = runBlocking {
        ensureProjectReady()
        val seedFile = Path.of(seedFileFixture.get().virtualFile.path).toAbsolutePath().normalize()
        val requests = mutableListOf<WorkspaceTransitionRequest>()
        val requester = TestWorkspaceTransitionRequester(onReconcile = { request ->
            requests += request
            testPublishedWorkspaceGeneration()
        })

        backend(workspaceTransitionRequester = requester).refresh(
            RefreshQuery(filePaths = listOf(seedFile.toString())),
        )

        assertTrue(requests.single() is WorkspaceTransitionRequest.SourceFiles)
    }

    @Test
    fun `exact image CAS refreshes committed compiler PSI`() = runBlocking {
        ensureProjectReady()
        val seedFile = seedFileFixture.get().virtualFile
        val filePath = Path.of(seedFile.path).toAbsolutePath().normalize()
        val before = Files.readAllBytes(filePath)
        val afterText = "package admission\n\nfun caller(): Int = callee()\nfun callee(): Int = 2\n"
        val after = afterText.toByteArray()
        val backend = backend()
        readAction {
            requireNotNull(FileDocumentManager.getInstance().getDocument(seedFile))
        }
        assertEquals(
            SemanticAnalysisOutcome.COMPLETE,
            backend.diagnostics(DiagnosticsQuery(filePaths = listOf(filePath.toString()))).semanticOutcome,
        )

        val result = backend.exactFileImageCas(
            ExactFileImageQuery(
                filePath = ExactFileImagePath(filePath.toString()),
                expectedCurrentSha256 = ExactFileImageSha256(FileHashing.sha256(before)),
                contentBase64 = ExactFileImageBase64(Base64.getEncoder().encodeToString(after)),
                expectedResultSha256 = ExactFileImageSha256(FileHashing.sha256(after)),
            ),
        )
        assertEquals(ExactFileImageStatus.COMMITTED, result.status)
        assertArrayEquals(after, Files.readAllBytes(filePath))
        assertEquals(
            afterText,
            readAction {
                val current = requireNotNull(PsiManager.getInstance(project).findFile(seedFile))
                (current as KtFile).text
            },
        )

        val refresh = backend.refresh(RefreshQuery(filePaths = listOf(filePath.toString())))
        val diagnostics = backend.diagnostics(DiagnosticsQuery(filePaths = listOf(filePath.toString())))

        assertEquals(SemanticAnalysisOutcome.COMPLETE, refresh.semanticOutcome)
        assertEquals(SemanticAnalysisOutcome.COMPLETE, diagnostics.semanticOutcome)
        assertEquals(0, diagnostics.severityCounts.error)
    }

    private fun commonWorkspaceRoot(first: Path, second: Path): Path =
        generateSequence(first) { it.parent }
            .first(second::startsWith)
}
