package io.github.amichne.kast.idea

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import java.nio.file.Path

internal abstract class KastDiagnosticsCompletenessFixture {
    private companion object {
        val defaultLimits = ServerLimits(
            maxResults = 500,
            requestTimeoutMillis = 30_000L,
            maxConcurrentRequests = 4,
        )

        const val VALID_SOURCE = """
            package diagnostics

            fun valid(): Int = 42
        """

        const val BROKEN_SOURCE = """
            package diagnostics

            fun broken(): Int = "not an integer"
        """
    }

    protected val validSource: String = VALID_SOURCE
    private val projectFixture: TestFixture<Project> = projectFixture()
    protected val moduleFixture: TestFixture<Module> = projectFixture.moduleFixture("main")
    private val sourceRootFixture: TestFixture<PsiDirectory> = moduleFixture.sourceRootFixture()
    protected val validFileFixture: TestFixture<PsiFile> =
        sourceRootFixture.psiFileFixture("Valid.kt", validSource)
    protected val brokenFileFixture: TestFixture<PsiFile> =
        sourceRootFixture.psiFileFixture("Broken.kt", BROKEN_SOURCE)
    protected val nonKotlinFileFixture: TestFixture<PsiFile> =
        sourceRootFixture.psiFileFixture("Notes.txt", "Semantic analysis requires Kotlin source.")

    protected val project: Project
        get() = projectFixture.get()

    protected val sourceRoot: Path
        get() = Path.of(sourceRootFixture.get().virtualFile.path).toAbsolutePath().normalize()

    protected val workspaceRoot: Path
        get() = sourceRoot.parent

    protected fun backend(
        psiGeneration: () -> Long = { 1L },
        readEpochObserver: IdeaReadEpochObserver = IdeaReadEpochObserver.Disabled,
    ): KastIndexerBackend = KastIndexerBackend(
        project = project,
        workspaceRoot = workspaceRoot,
        limits = defaultLimits,
        psiGeneration = psiGeneration,
        readEpochObserver = readEpochObserver,
        workspaceSemanticReadAuthority = TestWorkspaceSemanticReadAuthority(),
        workspaceTransitionRequester = TestWorkspaceTransitionRequester(),
    )

    protected fun ensureProjectReady() {
        moduleFixture.get()
        validFileFixture.get()
        brokenFileFixture.get()
        nonKotlinFileFixture.get()
        waitUntilIndexesAreReady(project)
    }
}
