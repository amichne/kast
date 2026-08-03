package io.github.amichne.kast.idea

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.query.RefreshQuery
import io.github.amichne.kast.api.contract.result.AnalysisAvailabilityState
import io.github.amichne.kast.api.contract.result.FileAnalysisState
import io.github.amichne.kast.api.contract.result.FileSystemDiscoveryState
import io.github.amichne.kast.api.contract.result.IndexAdmissionState
import io.github.amichne.kast.api.contract.result.SemanticAnalysisOutcome
import io.github.amichne.kast.api.contract.result.SourceModuleOwnershipState
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Path
import kotlin.system.measureTimeMillis

@TestApplication
class KastSemanticAdmissionFailureTest {
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
    }

    private val projectFixture: TestFixture<Project> = projectFixture(openAfterCreation = true)
    private val moduleFixture: TestFixture<Module> = projectFixture.moduleFixture("main")
    private val productionRootFixture: TestFixture<PsiDirectory> = moduleFixture.sourceRootFixture()
    private val testRootFixture: TestFixture<PsiDirectory> = moduleFixture.sourceRootFixture(isTestSource = true)
    private val seedFileFixture: TestFixture<PsiFile> = productionRootFixture.psiFileFixture("Seed.kt", seedSource)

    private val project: Project
        get() = projectFixture.get()

    private val workspaceRoot: Path
        get() = commonWorkspaceRoot(
            Path.of(productionRootFixture.get().virtualFile.path).toAbsolutePath().normalize(),
            Path.of(testRootFixture.get().virtualFile.path).toAbsolutePath().normalize(),
        )

    private fun backend(
        admissionAwaiter: IdeaSemanticAdmissionAwaiter = IdeaSemanticAdmissionAwaiter.forRequestBudget(
            defaultLimits.requestTimeoutMillis,
        ),
        admissionOperations: IdeaSemanticAdmissionOperations = IdeaSemanticAdmissionOperations.idea(),
    ): KastIndexerBackend = KastIndexerBackend(
        project = project,
        workspaceRoot = workspaceRoot,
        limits = defaultLimits,
        semanticAdmissionAwaiter = admissionAwaiter,
        semanticAdmissionOperations = admissionOperations,
        workspaceModelReader = { IdeaGradleProjectLoadBridge.readWorkspaceModel(project) },
    )

    private fun ensureProjectReady() {
        moduleFixture.get()
        productionRootFixture.get()
        testRootFixture.get()
        seedFileFixture.get()
        waitUntilIndexesAreReady(project)
    }

    @Test
    fun `persistent IDEA indexing returns bounded incomplete admission evidence`() {
        ensureProjectReady()
        val seedFile = Path.of(seedFileFixture.get().virtualFile.path)
        val zeroWait = IdeaSemanticAdmissionAwaiter(maxWaitMillis = 0, pollIntervalMillis = 25)

        val result = DumbModeTestUtils.computeInDumbModeSynchronously(project) {
            runBlocking {
                backend(zeroWait).refresh(RefreshQuery(filePaths = listOf(seedFile.toString())))
            }
        }

        assertEquals(SemanticAnalysisOutcome.INCOMPLETE, result.semanticOutcome)
        assertEquals(FileAnalysisState.PENDING_INDEX, result.fileStatuses.single().analysisStatus?.state)
        assertEquals(1, result.attemptCount)
        assertTrue(result.elapsedMillis < 1_000, "zero-wait probe took ${result.elapsedMillis}ms")
    }

    @Test
    fun `failure before index observation does not fabricate later admission stages`() {
        ensureProjectReady()
        val seedFile = Path.of(seedFileFixture.get().virtualFile.path)
        val ideaOperations = IdeaSemanticAdmissionOperations.idea()
        val failingDiscovery = object : IdeaSemanticAdmissionOperations by ideaOperations {
            override fun refreshAndFind(filePath: Path): VirtualFile? =
                throw IllegalStateException("VFS discovery failed")
        }

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                backend(admissionOperations = failingDiscovery).refresh(
                    RefreshQuery(filePaths = listOf(seedFile.toString())),
                )
            }
        }

        assertEquals("VFS discovery failed", failure.message)
    }

    @Test
    fun `analysis session failure preserves proven discovery ownership and index admission`() = runBlocking {
        ensureProjectReady()
        val seedFile = Path.of(seedFileFixture.get().virtualFile.path)
        val ideaOperations = IdeaSemanticAdmissionOperations.idea()
        val failingAnalysis = object : IdeaSemanticAdmissionOperations by ideaOperations {
            override fun collectDiagnostics(file: KtFile) {
                throw IllegalStateException("Analysis session failed")
            }
        }

        val result = backend(admissionOperations = failingAnalysis).refresh(
            RefreshQuery(filePaths = listOf(seedFile.toString())),
        )
        val status = result.fileStatuses.single()

        assertEquals(FileSystemDiscoveryState.DISCOVERED, status.fileSystemDiscovery)
        assertEquals(SourceModuleOwnershipState.OWNED, status.sourceModuleOwnership)
        assertEquals(IndexAdmissionState.ADMITTED, status.indexAdmission)
        assertEquals(AnalysisAvailabilityState.FAILED, status.analysisAvailability)
        assertEquals(FileAnalysisState.BACKEND_FAILURE, status.analysisStatus?.state)
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
