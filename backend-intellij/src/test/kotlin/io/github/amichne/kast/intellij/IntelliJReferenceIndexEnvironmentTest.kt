package io.github.amichne.kast.intellij

import com.intellij.openapi.project.Project
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.shared.analysis.PsiReferenceScanner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
    }

    private val moduleFixture = projectFixture.moduleFixture("main")
    private val sourceRootFixture = moduleFixture.sourceRootFixture()
    private val targetFileFixture = sourceRootFixture.psiFileFixture("Target.kt", targetSource)
    private val callerFileFixture = sourceRootFixture.psiFileFixture("Caller.kt", callerSource)

    @Test
    fun `shared scanner emits references for IntelliJ Kotlin files`() {
        val project = projectFixture.get()
        val targetFile = targetFileFixture.get()
        val callerFile = callerFileFixture.get()
        IndexingTestUtil.waitUntilIndexesAreReady(project)

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
}
