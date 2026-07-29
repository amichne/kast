package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.indexstore.api.index.FileStageLimitation
import io.github.amichne.kast.shared.analysis.PsiReferenceScanner
import io.github.amichne.kast.shared.analysis.PsiSourceIndexScanner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@TestApplication
class IdeaReferenceIndexEnvironmentTest {
    companion object {
        private val projectFixture: TestFixture<Project> = projectFixture()

        private const val targetSource = """
            package demo

            fun target(): String = "ok"
        """

        private const val callerSource = """
            package demo

            fun caller(): String = target()
        """

        private const val collectorsSource = """
            package demo

            import java.util.stream.Collectors

            fun collect(values: List<String>): List<String> {
                val collected = values.stream()
                    .map(String::trim)
                    .collect(Collectors.toList())
                JavaTarget.value()
                target()
                return collected
            }
        """

        private const val javaTargetSource = """
            package demo;

            public final class JavaTarget {
                public static String value() {
                    return "java";
                }
            }
        """

        private const val unresolvedSource = """
            package demo

            fun callerWithMissingTarget(): String {
                MissingTarget.value()
                return target()
            }
        """
    }

    private val moduleFixture = projectFixture.moduleFixture("main")
    private val sourceRootFixture = moduleFixture.sourceRootFixture()
    private val targetFileFixture = sourceRootFixture.psiFileFixture("Target.kt", targetSource)
    private val callerFileFixture = sourceRootFixture.psiFileFixture("Caller.kt", callerSource)
    private val collectorsFileFixture = sourceRootFixture.psiFileFixture("CollectorsCaller.kt", collectorsSource)
    private val javaTargetFileFixture = sourceRootFixture.psiFileFixture("JavaTarget.java", javaTargetSource)
    private val unresolvedFileFixture = sourceRootFixture.psiFileFixture("UnresolvedCaller.kt", unresolvedSource)

    @Test
    fun `shared scanner emits references for IDEA Kotlin files`() {
        val project = projectFixture.get()
        targetFileFixture.get()
        val callerFile = callerFileFixture.get()
        waitUntilIndexesAreReady(project)

        val workspaceRoot = Path.of(callerFile.virtualFile.path).root.toAbsolutePath().normalize()
        val environment = IdeaReferenceIndexEnvironment(
            project = project,
            workspaceRoot = workspaceRoot,
            cancelled = { false },
        )

        val rows = PsiReferenceScanner(environment).scanFileReferences(callerFile.virtualFile.path)

        assertTrue(rows.any { row -> row.targetFqName == "demo.target" && row.sourcePath == callerFile.virtualFile.path })
    }

    @Test
    fun `shared scanner tolerates compiled JDK PSI mirror failures`() {
        val project = projectFixture.get()
        targetFileFixture.get()
        javaTargetFileFixture.get()
        val collectorsFile = collectorsFileFixture.get()
        waitUntilIndexesAreReady(project)

        val workspaceRoot = Path.of(collectorsFile.virtualFile.path).root.toAbsolutePath().normalize()
        val environment = IdeaReferenceIndexEnvironment(
            project = project,
            workspaceRoot = workspaceRoot,
            cancelled = { false },
        )

        val rows = assertDoesNotThrow {
            PsiReferenceScanner(environment).scanFileReferences(collectorsFile.virtualFile.path)
        }

        assertTrue(
            rows.any { row -> row.targetFqName == "demo.target" && row.sourcePath == collectorsFile.virtualFile.path },
            "scanner should continue past compiled PSI failures and still index later source references",
        )
        assertFalse(
            rows.any { row -> row.targetFqName.startsWith("demo.JavaTarget") },
            "symbol relationship indexing should not persist non-Kotlin targets",
        )
    }

    @Test
    fun `shared scanner preserves valid facts and reports unresolved relationship coverage`() {
        val project = projectFixture.get()
        targetFileFixture.get()
        val unresolvedFile = unresolvedFileFixture.get()
        waitUntilIndexesAreReady(project)

        val workspaceRoot = Path.of(unresolvedFile.virtualFile.path).root.toAbsolutePath().normalize()
        val environment = IdeaReferenceIndexEnvironment(
            project = project,
            workspaceRoot = workspaceRoot,
            cancelled = { false },
        )

        val result = PsiReferenceScanner(environment).scanFileRelationships(unresolvedFile.virtualFile.path)

        assertTrue(
            result.references.any { row ->
                row.targetFqName == "demo.target" && row.sourcePath == unresolvedFile.virtualFile.path
            },
        )
        assertTrue(FileStageLimitation.UNRESOLVED_RELATIONSHIP in result.limitations)
    }

    @Test
    fun `exclusive reference indexing read yields to pending EDT write actions`() {
        val project = projectFixture.get()
        val callerFile = callerFileFixture.get()
        waitUntilIndexesAreReady(project)

        val workspaceRoot = Path.of(callerFile.virtualFile.path).root.toAbsolutePath().normalize()
        val environment = IdeaReferenceIndexEnvironment(
            project = project,
            workspaceRoot = workspaceRoot,
            cancelled = { false },
        )
        val executor = Executors.newFixedThreadPool(2)
        val readStarted = CountDownLatch(1)
        val writeCompleted = CountDownLatch(1)
        val stopRead = AtomicBoolean(false)

        val readFuture = executor.submit {
            environment.withExclusiveAccess {
                readStarted.countDown()
                while (writeCompleted.count > 0 && !stopRead.get()) {
                    ProgressManager.checkCanceled()
                    Thread.sleep(10)
                }
            }
        }
        assertTrue(readStarted.await(1, TimeUnit.SECONDS), "test read action did not start")

        val writeFuture = executor.submit {
            ApplicationManager.getApplication().invokeAndWait {
                ApplicationManager.getApplication().runWriteAction {
                    writeCompleted.countDown()
                }
            }
        }

        try {
            assertTrue(
                writeCompleted.await(2, TimeUnit.SECONDS),
                "Kast reference indexing read action should yield when the EDT needs a write action",
            )
        } finally {
            stopRead.set(true)
            readFuture.get(2, TimeUnit.SECONDS)
            writeFuture.get(2, TimeUnit.SECONDS)
            executor.shutdownNow()
        }
    }

    @Test
    fun `VFS cache miss resolves before scanner read access`() {
        val project = projectFixture.get()
        val callerFile = callerFileFixture.get()
        waitUntilIndexesAreReady(project)

        val workspaceRoot = Path.of(callerFile.virtualFile.path).root.toAbsolutePath().normalize()
        val refreshStarted = CountDownLatch(1)
        val writeCompleted = CountDownLatch(1)
        val environment = IdeaReferenceIndexEnvironment(
            project = project,
            workspaceIdentity = WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot),
            cancelled = { false },
            findVirtualFile = { null },
            refreshVirtualFile = {
                refreshStarted.countDown()
                assertTrue(
                    writeCompleted.await(2, TimeUnit.SECONDS),
                    "VFS refresh must not hold IDEA read access while an EDT write is queued",
                )
                callerFile.virtualFile
            },
        )
        val executor = Executors.newFixedThreadPool(2)
        val scanFuture = executor.submit {
            PsiSourceIndexScanner(environment).scanFile(callerFile.virtualFile.path)
        }
        assertTrue(refreshStarted.await(1, TimeUnit.SECONDS), "VFS cache-miss refresh did not start")
        val writeFuture = executor.submit {
            ApplicationManager.getApplication().invokeAndWait {
                ApplicationManager.getApplication().runWriteAction {
                    writeCompleted.countDown()
                }
            }
        }

        try {
            assertTrue(
                scanFuture.get(5, TimeUnit.SECONDS) != null,
                "scanner should resume after the queued write completes",
            )
        } finally {
            scanFuture.cancel(true)
            writeFuture.get(2, TimeUnit.SECONDS)
            executor.shutdownNow()
        }
    }
}
