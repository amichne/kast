package io.github.amichne.kast.cli.broker.protocol

import io.github.amichne.kast.cli.broker.core.CatalogDigest
import io.github.amichne.kast.kernel.Refinement
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FileThreadCatalogStoreTest {
    @Test
    fun `thread bindings survive restart through an atomic versioned store`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val state = Files.createDirectory(temporary.resolve("state")).toRealPath()
        val cwd = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val path = state.resolve("threads.json")
        val binding = ThreadCatalogBinding.admit(
            "thread-1",
            CatalogDigest.admit("sha256:${"a".repeat(64)}")!!,
            cwd,
        ).refinedValue()
        val opened = (FileThreadCatalogStore.open(path) as FileThreadCatalogStoreOpen.Opened).store

        assertEquals(ThreadStoreWrite.WRITTEN, opened.write(binding))

        val reopened = (FileThreadCatalogStore.open(path) as FileThreadCatalogStoreOpen.Opened).store
        val found = assertInstanceOf(ThreadStoreRead.Found::class.java, reopened.read("thread-1"))
        assertEquals(binding.threadId, found.binding.threadId)
        assertEquals(binding.catalogDigest, found.binding.catalogDigest)
        assertEquals(binding.workingDirectory.path, found.binding.workingDirectory.path)
        assertEquals("rw-------", Files.getPosixFilePermissions(path).permissionText())
    }

    @Test
    fun `duplicate thread identities fail closed`(
        @TempDir temporary: Path,
    ) {
        val state = Files.createDirectory(temporary.resolve("state")).toRealPath()
        val cwd = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val path = state.resolve("threads.json")
        val document = """
            {
              "version": 1,
              "bindings": [
                {"threadId":"thread-1","catalogDigest":"sha256:${"a".repeat(64)}","cwd":"$cwd"},
                {"threadId":"thread-1","catalogDigest":"sha256:${"b".repeat(64)}","cwd":"$cwd"}
              ]
            }
        """.trimIndent()
        Files.writeString(path, document)

        val rejection = FileThreadCatalogStore.open(path) as FileThreadCatalogStoreOpen.Rejected

        assertEquals(ThreadCatalogStoreFailure.DUPLICATE_THREAD_ID, rejection.failure)
    }

    private fun Set<java.nio.file.attribute.PosixFilePermission>.permissionText(): String =
        java.nio.file.attribute.PosixFilePermissions.toString(this)

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> throw AssertionError("Expected refinement, received $failure")
    }
}
