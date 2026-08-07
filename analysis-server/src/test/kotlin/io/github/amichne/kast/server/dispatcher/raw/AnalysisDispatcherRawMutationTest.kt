package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.query.*
import io.github.amichne.kast.api.contract.result.*
import io.github.amichne.kast.api.protocol.*
import io.github.amichne.kast.api.validation.*
import io.github.amichne.kast.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.milliseconds

class AnalysisDispatcherRawMutationTest : AnalysisDispatcherTestSupport() {
    @Test
    fun `rename dispatches without HTTP`() {
        val file = sampleFile()

        val result = dispatchSuccess<RenameResult>(
            method = "raw/rename",
            params = json.encodeToJsonElement(
                RenameQuery.serializer(),
                RenameQuery(
                    position = FilePosition(filePath = file.toString(), offset = 20),
                    newName = "welcome",
                ),
            ),
        )

        assertEquals(listOf(file.toString()), result.affectedFiles)
        assertTrue(result.edits.all { edit -> edit.newText == "welcome" })
        assertEquals(result.affectedFiles, result.fileImages.map { image -> image.filePath.value })
        assertArrayEquals(Files.readAllBytes(file), result.fileImages.single().preimage.copyBytes())
    }

    @Test
    fun `imports optimize dispatches without HTTP`() {
        val file = sampleFile()

        val result = dispatchSuccess<ImportOptimizeResult>(
            method = "raw/optimize-imports",
            params = json.encodeToJsonElement(
                ImportOptimizeQuery.serializer(),
                ImportOptimizeQuery(
                    filePaths = listOf(file.toString()),
                ),
            ),
        )

        assertTrue(result.edits.isEmpty())
        assertTrue(result.affectedFiles.isEmpty())
    }

    @Test
    fun `apply edits dispatches without HTTP`() {
        dispatcher()
        val file = sampleFile()
        val originalContent = file.readText()
        val result = dispatchSuccess<ApplyEditsResult>(
            method = "raw/apply-edits",
            params = json.encodeToJsonElement(
                ApplyEditsQuery.serializer(),
                ApplyEditsQuery(
                    edits = listOf(
                        TextEdit(
                            filePath = file.toString(),
                            startOffset = 20,
                            endOffset = 25,
                            newText = "hello",
                        ),
                    ),
                    fileHashes = listOf(
                        FileHash(
                            filePath = file.toString(),
                            hash = FileHashing.sha256(originalContent),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(listOf(file.toString()), result.affectedFiles)
        assertTrue(file.readText().contains("hello"))
    }

    @Test
    fun `apply edits validates absolute file operation paths`() {
        val response = dispatchRaw(
            method = "raw/apply-edits",
            params = json.encodeToJsonElement(
                ApplyEditsQuery.serializer(),
                ApplyEditsQuery(
                    edits = emptyList(),
                    fileHashes = emptyList(),
                    fileOperations = listOf(
                        FileOperation.CreateFile(
                            filePath = "relative/New.kt",
                            content = "class New",
                        ),
                    ),
                ),
            ),
        )

        val error = json.decodeFromJsonElement(
            JsonRpcErrorResponse.serializer(),
            response,
        )
        assertEquals("VALIDATION_ERROR", error.error.data?.code)
    }

    @Test
    fun `imports optimize validates absolute file paths`() {
        val response = dispatchRaw(
            method = "raw/optimize-imports",
            params = json.encodeToJsonElement(
                ImportOptimizeQuery.serializer(),
                ImportOptimizeQuery(filePaths = listOf("relative/File.kt")),
            ),
        )

        val error = json.decodeFromJsonElement(
            JsonRpcErrorResponse.serializer(),
            response,
        )
        assertEquals("VALIDATION_ERROR", error.error.data?.code)
    }

    @Test
    fun `workspace refresh dispatches without HTTP`() {
        val file = sampleFile()

        val result = dispatchSuccess<RefreshResult>(
            method = "raw/workspace-refresh",
            params = json.encodeToJsonElement(
                RefreshQuery.serializer(),
                RefreshQuery(filePaths = listOf(file.toString())),
            ),
        )

        assertEquals(listOf(file.toString()), result.refreshedFiles)
        assertTrue(result.removedFiles.isEmpty())
        assertEquals(false, result.fullRefresh)
    }

    @Test
    fun `workspace refresh delegates its deadline to backend progress`() {
        val file = sampleFile()
        val result = dispatchSuccessWithBackend<RefreshResult>(
            backend = DispatcherProgressBoundRefreshBackend(
                delegate = FakeAnalysisBackend.sample(tempDir),
                delay = 25.milliseconds,
            ),
            config = AnalysisServerConfig(requestTimeoutMillis = 1),
            method = "raw/workspace-refresh",
            params = json.encodeToJsonElement(
                RefreshQuery.serializer(),
                RefreshQuery(filePaths = listOf(file.toString())),
            ),
        )

        assertEquals(listOf(file.toString()), result.refreshedFiles)
    }
}
