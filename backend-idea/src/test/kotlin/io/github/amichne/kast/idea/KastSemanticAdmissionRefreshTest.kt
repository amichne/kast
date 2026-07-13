package io.github.amichne.kast.idea

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.api.contract.FileOperation
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.query.DiagnosticsQuery
import io.github.amichne.kast.api.contract.query.RefreshQuery
import io.github.amichne.kast.api.contract.result.AnalysisAvailabilityState
import io.github.amichne.kast.api.contract.result.FileAnalysisState
import io.github.amichne.kast.api.contract.result.FileSystemDiscoveryState
import io.github.amichne.kast.api.contract.result.IndexAdmissionState
import io.github.amichne.kast.api.contract.result.SemanticAnalysisOutcome
import io.github.amichne.kast.api.contract.result.SourceModuleOwnershipState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.system.measureTimeMillis

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

    private val projectFixture: TestFixture<Project> = projectFixture()
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
    ): KastPluginBackend = KastPluginBackend(
        project = project,
        workspaceRoot = workspaceRoot,
        limits = defaultLimits,
        semanticAdmissionAwaiter = admissionAwaiter,
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
        backend().refresh(RefreshQuery(filePaths = listOf(deletedFile.toString())))
        Files.delete(deletedFile)

        val refresh = backend().refresh(RefreshQuery(filePaths = listOf(deletedFile.toString())))
        val diagnostics = backend().diagnostics(DiagnosticsQuery(filePaths = listOf(deletedFile.toString())))

        assertEquals(SemanticAnalysisOutcome.COMPLETE, refresh.semanticOutcome)
        assertEquals(listOf(deletedFile.toString()), refresh.removedFiles)
        assertEquals(0, refresh.requestedFileCount)
        assertEquals(SemanticAnalysisOutcome.INCOMPLETE, diagnostics.semanticOutcome)
        assertEquals(FileAnalysisState.MISSING_ON_DISK, diagnostics.fileStatuses.single().state)
    }

    @Test
    fun `file created through Kast edit application crosses the admission barrier`() = runBlocking {
        ensureProjectReady()
        val createdFile = productionRoot.resolve("KastCreated.kt")
        val backend = backend()

        try {
            backend.applyEdits(
                ApplyEditsQuery(
                    edits = emptyList(),
                    fileHashes = emptyList(),
                    fileOperations = listOf(FileOperation.CreateFile(createdFile.toString(), newSource)),
                ),
            )
            val refresh = backend.refresh(RefreshQuery(filePaths = listOf(createdFile.toString())))
            val diagnostics = backend.diagnostics(DiagnosticsQuery(filePaths = listOf(createdFile.toString())))

            assertEquals(SemanticAnalysisOutcome.COMPLETE, refresh.semanticOutcome)
            assertEquals(FileAnalysisState.ANALYZED, diagnostics.fileStatuses.single().state)
        } finally {
            Files.deleteIfExists(createdFile)
        }
    }

    @Test
    fun `persistent IDEA indexing returns bounded incomplete admission evidence`() {
        ensureProjectReady()
        val seedFile = Path.of(seedFileFixture.get().virtualFile.path)
        val zeroWait = IdeaSemanticAdmissionAwaiter(
            maxWaitMillis = 0,
            pollIntervalMillis = 25,
        )

        val result = DumbModeTestUtils.computeInDumbModeSynchronously(project) {
            runBlocking {
                backend(zeroWait).refresh(RefreshQuery(filePaths = listOf(seedFile.toString())))
            }
        }

        assertEquals(SemanticAnalysisOutcome.INCOMPLETE, result.semanticOutcome)
        assertEquals(FileAnalysisState.PENDING_INDEX, result.fileStatuses.single().analysisStatus?.state)
        assertEquals(1, result.attemptCount)
        assertEquals(0, result.elapsedMillis)
    }

    @Test
    fun `clean focused refresh remains below one second`() = runBlocking {
        ensureProjectReady()
        val seedFile = Path.of(seedFileFixture.get().virtualFile.path)
        var resultState: FileAnalysisState? = null

        val elapsedMillis = measureTimeMillis {
            val result = backend().refresh(RefreshQuery(filePaths = listOf(seedFile.toString())))
            resultState = result.fileStatuses.single().analysisStatus?.state
        }

        assertEquals(FileAnalysisState.ANALYZED, resultState)
        assertTrue(elapsedMillis < 1_000) { "Focused refresh took ${elapsedMillis}ms" }
    }

    private fun commonWorkspaceRoot(first: Path, second: Path): Path =
        generateSequence(first) { it.parent }
            .first(second::startsWith)
}
