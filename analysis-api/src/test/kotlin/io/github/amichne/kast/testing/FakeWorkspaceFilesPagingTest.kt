package io.github.amichne.kast.testing

import io.github.amichne.kast.api.contract.FileOperation
import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.query.WorkspaceFileKindDomain
import io.github.amichne.kast.api.contract.query.WorkspaceFilesQuery
import io.github.amichne.kast.api.protocol.InvalidWorkspaceFileCursorException
import io.github.amichne.kast.api.protocol.InvalidWorkspaceFileCursorScope
import io.github.amichne.kast.api.protocol.WorkspaceInventoryStaleException
import io.github.amichne.kast.api.validation.parsed
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FakeWorkspaceFilesPagingTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `workspace files page one coherent sorted snapshot without overlap`() = runTest {
        val backend = fiveFileBackend()
        try {
            val metadata = backend.workspaceFiles(
                WorkspaceFilesQuery(kindDomain = WorkspaceFileKindDomain.SOURCE_ONLY).parsed(),
            )
            val first = backend.workspaceFiles(
                pageQuery(snapshotToken = metadata.snapshotToken).parsed(),
            )
            val second = backend.workspaceFiles(
                pageQuery(
                    snapshotToken = metadata.snapshotToken,
                    pageToken = first.modules.single().nextPageToken,
                ).parsed(),
            )
            val third = backend.workspaceFiles(
                pageQuery(
                    snapshotToken = metadata.snapshotToken,
                    pageToken = second.modules.single().nextPageToken,
                ).parsed(),
            )
            val pages = listOf(first, second, third)

            assertTrue(pages.all { result -> result.snapshotToken == metadata.snapshotToken })
            assertTrue(pages.all { result -> result.modules.single().fileCount == 5 })
            assertNotNull(first.modules.single().nextPageToken)
            assertNotNull(second.modules.single().nextPageToken)
            assertNull(third.modules.single().nextPageToken)
            val returnedFiles = pages.flatMap { result -> result.modules.single().files }
            assertEquals(returnedFiles.sorted(), returnedFiles)
            assertEquals(5, returnedFiles.distinct().size)

            val validation = backend.workspaceFiles(
                WorkspaceFilesQuery(
                    kindDomain = WorkspaceFileKindDomain.SOURCE_ONLY,
                    snapshotToken = metadata.snapshotToken,
                ).parsed(),
            )
            assertEquals(metadata.snapshotToken, validation.snapshotToken)
            assertEquals(5, validation.modules.single().fileCount)
            assertTrue(validation.modules.single().files.isEmpty())
        } finally {
            backend.close()
        }
    }

    @Test
    fun `workspace page handle is scoped to its exact module query`() = runTest {
        val backend = fiveFileBackend()
        try {
            val metadata = backend.workspaceFiles(sourceMetadataQuery().parsed())
            val first = backend.workspaceFiles(pageQuery(metadata.snapshotToken).parsed())

            val failure = try {
                backend.workspaceFiles(
                    pageQuery(
                        snapshotToken = metadata.snapshotToken,
                        pageToken = first.modules.single().nextPageToken,
                        moduleName = "other-module",
                    ).parsed(),
                )
                fail("Expected the page handle to be rejected for another module")
            } catch (failure: InvalidWorkspaceFileCursorException) {
                failure
            }

            assertEquals(InvalidWorkspaceFileCursorScope.PAGE_HANDLE, failure.scope)
            val consumedFailure = try {
                backend.workspaceFiles(
                    pageQuery(
                        snapshotToken = metadata.snapshotToken,
                        pageToken = first.modules.single().nextPageToken,
                    ).parsed(),
                )
                fail("Expected a query-mismatched page handle to be consumed")
            } catch (failure: InvalidWorkspaceFileCursorException) {
                failure
            }
            assertEquals(InvalidWorkspaceFileCursorScope.PAGE_HANDLE, consumedFailure.scope)
        } finally {
            backend.close()
        }
    }

    @Test
    fun `workspace snapshot rejects inventory mutation before the next page`() = runTest {
        val backend = fiveFileBackend()
        try {
            val metadata = backend.workspaceFiles(sourceMetadataQuery().parsed())
            val addedFile = workspaceRoot.resolve("src/Added.kt")
            backend.applyEdits(
                ApplyEditsQuery(
                    edits = emptyList(),
                    fileHashes = emptyList(),
                    fileOperations = listOf(
                        FileOperation.CreateFile(
                            filePath = addedFile.toString(),
                            content = "package sample\n\nclass Added\n",
                        ),
                    ),
                ).parsed(),
            )

            try {
                backend.workspaceFiles(pageQuery(metadata.snapshotToken).parsed())
                fail("Expected the mutated workspace inventory to be stale")
            } catch (_: WorkspaceInventoryStaleException) {
                // Expected: the snapshot is invalidated rather than mixing generations.
            }
        } finally {
            backend.close()
        }
    }

    @Test
    fun `workspace kind domain isolates Kotlin scripts from sources`() = runTest {
        Files.createDirectories(workspaceRoot)
        Files.writeString(workspaceRoot.resolve("build.gradle.kts"), "plugins {}\n")
        val backend = FakeAnalysisBackend.sample(workspaceRoot)
        try {
            val source = backend.workspaceFiles(
                WorkspaceFilesQuery(kindDomain = WorkspaceFileKindDomain.SOURCE_ONLY).parsed(),
            )
            val scripts = backend.workspaceFiles(
                WorkspaceFilesQuery(kindDomain = WorkspaceFileKindDomain.SCRIPT_ONLY).parsed(),
            )

            assertEquals(2, source.modules.single().fileCount)
            assertEquals(1, scripts.modules.single().fileCount)
        } finally {
            backend.close()
        }
    }

    private fun fiveFileBackend(): FakeAnalysisBackend {
        val sourceDirectory = workspaceRoot.resolve("src")
        Files.createDirectories(sourceDirectory)
        listOf("Alpha.kt", "Beta.kt", "Gamma.kt").forEach { fileName ->
            Files.writeString(sourceDirectory.resolve(fileName), "package sample\n")
        }
        return FakeAnalysisBackend.sample(workspaceRoot)
    }

    private fun pageQuery(
        snapshotToken: String,
        pageToken: String? = null,
        moduleName: String = "fake-module",
    ): WorkspaceFilesQuery = WorkspaceFilesQuery(
        kindDomain = WorkspaceFileKindDomain.SOURCE_ONLY,
        moduleName = moduleName,
        includeFiles = true,
        maxFilesPerModule = 2,
        snapshotToken = snapshotToken,
        pageToken = pageToken,
    )

    private fun sourceMetadataQuery(): WorkspaceFilesQuery = WorkspaceFilesQuery(
        kindDomain = WorkspaceFileKindDomain.SOURCE_ONLY,
    )
}
