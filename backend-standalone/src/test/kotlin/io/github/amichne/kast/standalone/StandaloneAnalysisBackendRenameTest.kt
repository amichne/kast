package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.FilePosition
import io.github.amichne.kast.api.FileHashing
import io.github.amichne.kast.api.MutationCapability
import io.github.amichne.kast.api.RenameQuery
import io.github.amichne.kast.api.ServerLimits
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

class StandaloneAnalysisBackendRenameTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `rename plans declaration and cross-file reference edits`() = runTest {
        val declarationFile = writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = """
                package sample

                fun greet(name: String): String = "hi ${'$'}name"
            """.trimIndent() + "\n",
        )
        val usageFile = writeFile(
            relativePath = "src/main/kotlin/sample/Use.kt",
            content = """
                package sample

                fun use(): String = greet("kast")
            """.trimIndent() + "\n",
        )
        val secondaryUsageFile = writeFile(
            relativePath = "src/main/kotlin/sample/SecondaryUse.kt",
            content = """
                package sample

                fun useAgain(): String = greet("again")
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(usageFile).indexOf("greet")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
        )
        try {
            val backend = StandaloneAnalysisBackend(
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(
                    maxResults = 100,
                    requestTimeoutMillis = 30_000,
                    maxConcurrentRequests = 4,
                ),
                session = session,
            )

            val result = backend.rename(
                RenameQuery(
                    position = FilePosition(
                        filePath = usageFile.toString(),
                        offset = queryOffset,
                    ),
                    newName = "welcome",
                ),
            )

            assertEquals(
                listOf(
                    normalizePath(declarationFile),
                    normalizePath(secondaryUsageFile),
                    normalizePath(usageFile),
                ),
                result.edits.map { edit -> edit.filePath },
            )
            assertEquals(listOf("welcome", "welcome", "welcome"), result.edits.map { edit -> edit.newText })
            assertEquals(
                listOf(normalizePath(declarationFile), normalizePath(secondaryUsageFile), normalizePath(usageFile)),
                result.affectedFiles,
            )
            assertEquals(
                listOf(
                    normalizePath(declarationFile) to FileHashing.sha256(declarationFile.readText()),
                    normalizePath(secondaryUsageFile) to FileHashing.sha256(secondaryUsageFile.readText()),
                    normalizePath(usageFile) to FileHashing.sha256(usageFile.readText()),
                ),
                result.fileHashes.map { hash -> hash.filePath to hash.hash },
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `capabilities advertise rename after implementation`() = runTest {
        writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = """
                package sample

                fun greet(name: String): String = "hi ${'$'}name"
            """.trimIndent() + "\n",
        )
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
        )
        try {
            val backend = StandaloneAnalysisBackend(
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(
                    maxResults = 100,
                    requestTimeoutMillis = 30_000,
                    maxConcurrentRequests = 4,
                ),
                session = session,
            )

            val capabilities = backend.capabilities()

            assertTrue(MutationCapability.RENAME in capabilities.mutationCapabilities)
        } finally {
            session.close()
        }
    }

    private fun writeFile(
        relativePath: String,
        content: String,
    ): Path {
        val path = workspaceRoot.resolve(relativePath)
        Files.createDirectories(path.parent)
        path.writeText(content)
        return path
    }

    private fun normalizePath(path: Path): String {
        val absolutePath = path.toAbsolutePath().normalize()
        return runCatching { absolutePath.toRealPath().normalize().toString() }.getOrDefault(absolutePath.toString())
    }
}
