package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.FileSystemLoopException
import java.nio.file.NoSuchFileException
import java.util.zip.ZipException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContainerFilesystemObservationTest {
    @Test
    fun `filesystem rejection carries bounded finite conditions without exception payloads`() {
        val cases =
            listOf(
                NoSuchFileException("secret") to ContainerIoCondition.MISSING_PATH,
                FileAlreadyExistsException("secret") to ContainerIoCondition.EXISTING_PATH,
                AccessDeniedException("secret") to ContainerIoCondition.ACCESS_DENIED,
                FileSystemLoopException("secret") to ContainerIoCondition.FILESYSTEM_LOOP,
                AtomicMoveNotSupportedException("secret", null, "secret") to
                    ContainerIoCondition.ATOMIC_MOVE_UNSUPPORTED,
                FileSystemException("secret", null, "No space left on device") to ContainerIoCondition.NO_SPACE,
                FileSystemException("secret", null, "File name too long") to ContainerIoCondition.PATH_TOO_LONG,
                FileSystemException("secret", null, "Operation not permitted") to ContainerIoCondition.ACCESS_DENIED,
                FileSystemException("secret", null, "Read-only file system") to ContainerIoCondition.READ_ONLY,
                FileSystemException("secret", null, "Too many open files") to ContainerIoCondition.OPEN_FILE_LIMIT,
                FileSystemException("secret", null, "Directory not empty") to ContainerIoCondition.NONEMPTY_DESTINATION,
                java.io.EOFException("secret") to ContainerIoCondition.UNEXPECTED_END,
                java.nio.channels.ClosedChannelException() to ContainerIoCondition.CLOSED_CHANNEL,
                IOException("secret") to ContainerIoCondition.IO_REJECTED,
            )
        for ((failure, expected) in cases) {
            val observation = containerFilesystemObservation(failure)
            assertEquals(expected, observation.condition)
            val encoded = containerJson.encodeToString(observation)
            assertFalse(encoded.contains("secret"))
            val fields = containerJson.parseToJsonElement(encoded).jsonObject
            assertEquals(setOf("condition", "stage", "outcome", "component"), fields.keys)
            assertEquals("FILESYSTEM_IO", fields.getValue("stage").jsonPrimitive.content)
            assertEquals("REJECTED", fields.getValue("outcome").jsonPrimitive.content)
            assertEquals("kast-container", fields.getValue("component").jsonPrimitive.content)
        }
    }

    @Test
    fun `adapter emits rejection evidence but success emits no invented failure`() {
        val observed = mutableListOf<ContainerFilesystemObservation>()
        assertEquals(Refinement.Refined(42), containerFiles(observe = { observed += it }) { Refinement.Refined(42) })
        assertTrue(observed.isEmpty())
        val rejected = containerFiles<Nothing>(observe = { observed += it }) { throw AccessDeniedException("secret") }
        assertEquals(Refinement.Rejected(ContainerFailure.FILESYSTEM_UNAVAILABLE), rejected)
        assertEquals(listOf(ContainerFilesystemObservation(ContainerIoCondition.ACCESS_DENIED)), observed)
    }

    @Test
    fun `invalid compressed data keeps its archive failure rather than becoming an IO failure`() {
        val observed = mutableListOf<ContainerFilesystemObservation>()
        val rejected =
            containerFiles<Nothing>(observe = { observed += it }) { throw ZipException("invalid compressed data") }
        assertEquals(Refinement.Rejected(ContainerFailure.INVALID_ARCHIVE), rejected)
        assertTrue(observed.isEmpty())
    }
}
