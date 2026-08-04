package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.idea.edit.IdeaEditApplier
import io.github.amichne.kast.idea.mutation.*

import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.testFramework.junit5.fixture.testFixture
import io.github.amichne.kast.api.client.workspaceDataDirectory
import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.FileOperation
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.PartialApplyException
import io.github.amichne.kast.api.protocol.ValidationException
import io.github.amichne.kast.api.protocol.UnsafeWorkspaceMutationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

internal abstract class IdeaEditApplicationTestFixture {
    companion object {
        private val projectPathFixture: TestFixture<Path> = testFixture {
            val path = tempPathFixture().init()
            val configDirectory = workspaceDataDirectory(path)
            Files.createDirectories(configDirectory)
            Files.writeString(
                configDirectory.resolve("config.toml"),
                """
                    [backends.idea]
                    enabled = false
                """.trimIndent(),
            )
            initialized(path) {}
        }

        private val projectFixture: TestFixture<Project> = projectFixture(
            pathFixture = projectPathFixture,
            openAfterCreation = true,
        )

        private val defaultLimits = ServerLimits(
            maxResults = 500,
            requestTimeoutMillis = 30_000L,
            maxConcurrentRequests = 4,
        )

        private val originalSource = """
            package demo

            fun oldName(x: Int): Int = x * 2
        """.trimIndent()
    }

    protected val moduleFixture: TestFixture<Module> = projectFixture.moduleFixture("main")
    protected val sourceRootFixture: TestFixture<PsiDirectory> = moduleFixture.sourceRootFixture()
    protected val testFileFixture: TestFixture<PsiFile> = sourceRootFixture.psiFileFixture("Test.kt", originalSource)

    protected val project: Project
        get() = projectFixture.get()

    protected val testFile: PsiFile
        get() = testFileFixture.get()

    protected fun backend(
        workspaceRoot: Path = Path.of(sourceRootFixture.get().virtualFile.path).toAbsolutePath().normalize(),
    ): KastIndexerBackend = KastIndexerBackend(
        project = project,
        workspaceRoot = workspaceRoot,
        limits = defaultLimits,
        workspaceSemanticReadAuthority = TestWorkspaceSemanticReadAuthority(),
        workspaceTransitionRequester = TestWorkspaceTransitionRequester(),
    )

    protected fun ensureProjectReady() {
        moduleFixture.get()
        testFileFixture.get()
        waitUntilIndexesAreReady(project)
    }

    protected suspend fun expectValidationFailure(query: ApplyEditsQuery): ValidationException {
        val failure = runCatching {
            backend().applyEdits(query)
        }.exceptionOrNull()
        assertTrue(
            failure is ValidationException,
            "Expected ValidationException, got ${failure?.let { it::class.qualifiedName } ?: "success"}",
        )
        return failure as ValidationException
    }


    protected val originalSourceText: String
        get() = originalSource
}
