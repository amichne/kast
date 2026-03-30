package io.github.amichne.kast.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.impl.PsiDocumentManagerBase
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase5
import com.intellij.testFramework.PsiTestUtil
import io.github.amichne.kast.api.ServerLimits
import org.junit.jupiter.api.AfterEach
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

abstract class IntelliJFixtureTestCase : LightJavaCodeInsightFixtureTestCase5() {
    private var workspaceRoot: Path? = null
    private var workspaceVirtualRoot: VirtualFile? = null

    override fun getRelativePath(): String = ""

    override fun getTestDataPath(): String = ""

    protected fun createBackend(): IntelliJAnalysisBackend = IntelliJAnalysisBackend(
        project = fixture.project,
        limits = ServerLimits(
            maxResults = 100,
            requestTimeoutMillis = 30_000,
            maxConcurrentRequests = 4,
        ),
    )

    protected fun workspaceRoot(): Path = workspaceRoot ?: initializeWorkspace()

    protected fun writeWorkspaceFile(
        relativePath: String,
        content: String,
    ): Path {
        val path = workspaceRoot().resolve(relativePath)
        Files.createDirectories(path.parent)
        path.writeText(content)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        return path.toRealPath().normalize()
    }

    protected fun updateDocumentWithoutCommit(
        filePath: Path,
        content: String,
    ) {
        (PsiDocumentManager.getInstance(fixture.project) as PsiDocumentManagerBase)
            .disableBackgroundCommit(fixture.projectDisposable)

        val document = ApplicationManager.getApplication().runReadAction<com.intellij.openapi.editor.Document> {
            val virtualFile = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(filePath)) {
                "Expected virtual file for $filePath"
            }
            checkNotNull(FileDocumentManager.getInstance().getDocument(virtualFile)) {
                "Expected document for $filePath"
            }
        }

        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                document.setText(content)
            }
        }

    }

    @AfterEach
    fun cleanWorkspace() {
        val workspace = workspaceRoot
        val virtualRoot = workspaceVirtualRoot
        workspaceRoot = null
        workspaceVirtualRoot = null

        if (virtualRoot != null) {
            ApplicationManager.getApplication().invokeAndWait {
                ApplicationManager.getApplication().runWriteAction {
                    PsiTestUtil.removeContentEntry(fixture.module, virtualRoot)
                }
            }
        }

        workspace?.toFile()?.deleteRecursively()
    }

    private fun initializeWorkspace(): Path {
        val rootPath = Files.createTempDirectory("kast-intellij-test").toRealPath().normalize()
        Files.createDirectories(rootPath.resolve("src/main/kotlin"))
        Files.createDirectories(rootPath.resolve("src/main/java"))

        val fileSystem = LocalFileSystem.getInstance()
        val root = checkNotNull(fileSystem.refreshAndFindFileByNioFile(rootPath)) {
            "Failed to register workspace root $rootPath"
        }
        val kotlinSourceRoot = checkNotNull(fileSystem.refreshAndFindFileByNioFile(rootPath.resolve("src/main/kotlin"))) {
            "Failed to register Kotlin source root under $rootPath"
        }
        val javaSourceRoot = checkNotNull(fileSystem.refreshAndFindFileByNioFile(rootPath.resolve("src/main/java"))) {
            "Failed to register Java source root under $rootPath"
        }

        fixture.allowTreeAccessForFile(root)
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                PsiTestUtil.addContentRoot(fixture.module, root)
                PsiTestUtil.addSourceRoot(fixture.module, kotlinSourceRoot)
                PsiTestUtil.addSourceRoot(fixture.module, javaSourceRoot)
            }
        }

        workspaceRoot = rootPath
        workspaceVirtualRoot = root
        return rootPath
    }
}
