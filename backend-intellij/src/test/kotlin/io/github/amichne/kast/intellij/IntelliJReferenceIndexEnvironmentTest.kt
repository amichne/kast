package io.github.amichne.kast.intellij

import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.shared.analysis.PsiReferenceScanner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.nio.file.Path

@TestApplication
class IntelliJReferenceIndexEnvironmentTest {
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
                target()
                return collected
            }
        """
    }

    private val moduleFixture = projectFixture.moduleFixture("main")
    private val sourceRootFixture = moduleFixture.sourceRootFixture()
    private val targetFileFixture = sourceRootFixture.psiFileFixture("Target.kt", targetSource)
    private val callerFileFixture = sourceRootFixture.psiFileFixture("Caller.kt", callerSource)
    private val collectorsFileFixture = sourceRootFixture.psiFileFixture("CollectorsCaller.kt", collectorsSource)

    @Test
    fun `shared scanner emits references for IntelliJ Kotlin files`() {
        val project = projectFixture.get()
        val targetFile = targetFileFixture.get()
        val callerFile = callerFileFixture.get()
        waitUntilIndexesAreReady(project)

        val workspaceRoot = Path.of(callerFile.virtualFile.path).root.toAbsolutePath().normalize()
        val environment = IntelliJReferenceIndexEnvironment(
            project = project,
            workspaceRoot = workspaceRoot,
            cancelled = { false },
        )

        val rows = PsiReferenceScanner(environment).scanFileReferences(callerFile.virtualFile.path)

        assertTrue(environment.allFilePaths().contains(Path.of(targetFile.virtualFile.path).toAbsolutePath().normalize().toString()))
        assertTrue(rows.any { row -> row.targetFqName == "demo.target" && row.sourcePath == callerFile.virtualFile.path })
    }

    @Test
    fun `shared scanner tolerates compiled JDK PSI mirror failures`() {
        val project = projectFixture.get()
        targetFileFixture.get()
        val collectorsFile = collectorsFileFixture.get()
        waitUntilIndexesAreReady(project)

        val workspaceRoot = Path.of(collectorsFile.virtualFile.path).root.toAbsolutePath().normalize()
        val environment = IntelliJReferenceIndexEnvironment(
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
    }
}
