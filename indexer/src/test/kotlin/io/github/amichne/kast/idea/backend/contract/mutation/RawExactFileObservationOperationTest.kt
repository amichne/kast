package io.github.amichne.kast.idea

import com.intellij.testFramework.junit5.TestApplication
import io.github.amichne.kast.api.contract.query.RawExactFileObservationQuery
import io.github.amichne.kast.api.contract.result.RawExactFileObservationResult
import io.github.amichne.kast.api.protocol.UnsafeWorkspaceMutationException
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.parsed
import io.github.amichne.kast.idea.backend.mutation.rawExactFileObservationOperation
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@TestApplication
internal class RawExactFileObservationOperationTest : KastIndexerBackendContractTestFixture() {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `present observation returns the unchanged exact byte image without writing`() = runBlocking {
        val relativePath = "src/raw-image.bin"
        val target = workspaceRoot.resolve(relativePath)
        Files.createDirectories(target.parent)
        val bytes = byteArrayOf(
            0xEF.toByte(),
            0xBB.toByte(),
            0xBF.toByte(),
            0x0D,
            0x0A,
            0xF0.toByte(),
            0x9F.toByte(),
            0x98.toByte(),
            0x80.toByte(),
            0x00,
            0xFF.toByte(),
        )
        Files.write(target, bytes)
        val modifiedBefore = Files.getLastModifiedTime(target)

        val result = backend(workspaceRoot).rawExactFileObservationOperation(
            RawExactFileObservationQuery(relativePath).parsed(),
        )

        val present = assertInstanceOf(RawExactFileObservationResult.Present::class.java, result)
        assertEquals(relativePath, present.filePath.value)
        assertEquals(FileHashing.sha256(bytes), present.image.sha256.value)
        assertArrayEquals(bytes, present.image.copyBytes())
        assertArrayEquals(bytes, Files.readAllBytes(target))
        assertEquals(modifiedBefore, Files.getLastModifiedTime(target))
    }

    @Test
    fun `absent observation is returned only for an absent final entry under an existing parent`() = runBlocking {
        Files.createDirectories(workspaceRoot.resolve("src"))
        val relativePath = "src/Absent.kt"

        val result = backend(workspaceRoot).rawExactFileObservationOperation(
            RawExactFileObservationQuery(relativePath).parsed(),
        )

        val absent = assertInstanceOf(RawExactFileObservationResult.Absent::class.java, result)
        assertEquals(relativePath, absent.filePath.value)
    }

    @Test
    fun `missing parent remains unsafe and cannot become absent`() {
        assertThrows(UnsafeWorkspaceMutationException::class.java) {
            runBlocking {
                backend(workspaceRoot).rawExactFileObservationOperation(
                    RawExactFileObservationQuery("missing/Absent.kt").parsed(),
                )
            }
        }
    }
}
