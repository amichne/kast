package io.github.amichne.kast.appserver.protocol

import io.github.amichne.kast.appserver.core.CatalogDigest
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileThreadCatalogStoreTest {
    @Test
    fun `unrelated missing workspace does not block opening or reading another binding`(@TempDir temporary: Path) =
        runBlocking {
            val state = Files.createDirectory(temporary.resolve("state")).toRealPath()
            Files.setPosixFilePermissions(state, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
            Files.setPosixFilePermissions(state, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
            val removed = Files.createDirectory(temporary.resolve("removed")).toRealPath()
            val surviving = Files.createDirectory(temporary.resolve("surviving")).toRealPath()
            val path = state.resolve("threads.json")
            val digest = CatalogDigest.admit("sha256:${"a".repeat(64)}")!!
            val store = (FileThreadCatalogStore.open(path) as FileThreadCatalogStoreOpen.Opened).store
            assertEquals(
                ThreadStoreWrite.Written,
                store.write(ThreadCatalogBinding.admit("removed", digest, removed).refinedValue()),
            )
            assertEquals(
                ThreadStoreWrite.Written,
                store.write(ThreadCatalogBinding.admit("surviving", digest, surviving).refinedValue()),
            )
            Files.delete(removed)
            val reopened =
                assertInstanceOf(FileThreadCatalogStoreOpen.Opened::class.java, FileThreadCatalogStore.open(path)).store
            assertInstanceOf(ThreadStoreRead.Found::class.java, reopened.read("surviving"))
            assertEquals(ThreadStoreRead.Rejected(ThreadCatalogStoreFailure.BINDING_REJECTED), reopened.read("removed"))
        }

    @Test
    fun `thread bindings survive restart through an atomic versioned store`(@TempDir temporary: Path) = runBlocking {
        val state = Files.createDirectory(temporary.resolve("state")).toRealPath()
        Files.setPosixFilePermissions(state, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        val cwd = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val path = state.resolve("threads.json")
        val child = Files.createDirectory(cwd.resolve("child"))
        val owner = ThreadBindingOwner.admit("installation", "00000000-0000-0000-0000-000000000001").refinedValue()
        val binding =
            ThreadCatalogBinding.admit(
                    "thread-1",
                    CatalogDigest.admit("sha256:${"a".repeat(64)}")!!,
                    child,
                    cwd,
                    owner,
                )
                .refinedValue()
        val opened = (FileThreadCatalogStore.open(path) as FileThreadCatalogStoreOpen.Opened).store

        assertEquals(ThreadStoreWrite.Written, opened.write(binding))

        val reopened = (FileThreadCatalogStore.open(path) as FileThreadCatalogStoreOpen.Opened).store
        val found = assertInstanceOf(ThreadStoreRead.Found::class.java, reopened.read("thread-1"))
        assertEquals(binding.threadId, found.binding.threadId)
        assertEquals(binding.catalogDigest, found.binding.catalogDigest)
        assertEquals(binding.workingDirectory.path, found.binding.workingDirectory.path)
        assertEquals(binding.workspace, found.binding.workspace)
        assertEquals(owner, found.binding.owner)
        assertEquals(
            "rw-------",
            Files.getPosixFilePermissions(
                    path
                        .resolveSibling("threads.json.d")
                        .resolve("records-v3")
                        .resolve(threadRecordDigest("thread-1").take(2))
                        .resolve("${threadRecordDigest("thread-1")}.json")
                )
                .permissionText(),
        )
    }

    @Test
    fun `duplicate thread identities fail closed`(@TempDir temporary: Path) {
        val state = Files.createDirectory(temporary.resolve("state")).toRealPath()
        Files.setPosixFilePermissions(state, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        val cwd = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val path = state.resolve("threads.json")
        val workspace =
            io.github.amichne.kast.appserver.WorkspaceRegistration(
                io.github.amichne.kast.appserver.core.CanonicalBrokerDirectory.admit(cwd)!!
            )
        val first =
            LegacyThreadBindingDocument(
                "thread-1",
                "sha256:${"a".repeat(64)}",
                cwd.toString(),
                cwd.toString(),
                workspace.id.value,
                "protocolFixture",
            )
        val document =
            LegacyThreadStoreDocument(2, listOf(first, first.copy(catalogDigest = "sha256:${"b".repeat(64)}")))
        Files.writeString(
            path,
            kotlinx.serialization.json
                .Json { explicitNulls = false }
                .encodeToString(LegacyThreadStoreDocument.serializer(), document),
        )
        Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))

        val rejection = FileThreadCatalogStore.open(path) as FileThreadCatalogStoreOpen.Rejected

        assertEquals(ThreadCatalogStoreFailure.DUPLICATE_THREAD_ID, rejection.failure)
    }

    @Test
    fun `existing bindings cannot be retargeted in either store`(@TempDir temporary: Path) = runBlocking {
        val root = temporary.toRealPath()
        val first = Files.createDirectory(root.resolve("first"))
        val second = Files.createDirectory(root.resolve("second"))
        val digest = CatalogDigest.admit("sha256:${"a".repeat(64)}")!!
        val original = ThreadCatalogBinding.admit("thread-1", digest, first).refinedValue()
        val replacement = ThreadCatalogBinding.admit("thread-1", digest, second).refinedValue()
        val file =
            (FileThreadCatalogStore.open(root.resolve("threads.json")) as FileThreadCatalogStoreOpen.Opened).store
        for (store in listOf(MemoryThreadCatalogStore(), file)) {
            assertEquals(ThreadStoreWrite.Written, store.write(original))
            assertEquals(ThreadStoreWrite.Written, store.write(original))
            assertEquals(
                ThreadStoreWrite.Rejected(ThreadCatalogStoreFailure.BINDING_CONFLICT),
                store.write(replacement),
            )
            assertEquals(first, (store.read("thread-1") as ThreadStoreRead.Found).binding.workingDirectory.path)
        }
    }

    @Test
    fun `catalog workspace and state epoch remain immutable`(@TempDir temporary: Path) = runBlocking {
        val root = temporary.toRealPath()
        val child = Files.createDirectory(root.resolve("child"))
        val digest = CatalogDigest.admit("sha256:${"a".repeat(64)}")!!
        val otherDigest = CatalogDigest.admit("sha256:${"b".repeat(64)}")!!
        val owner = ThreadBindingOwner.admit("installation", "00000000-0000-0000-0000-000000000001").refinedValue()
        val otherOwner = ThreadBindingOwner.admit("installation", "00000000-0000-0000-0000-000000000002").refinedValue()
        val original = ThreadCatalogBinding.admit("thread-1", digest, child, root, owner).refinedValue()
        val candidates =
            listOf(
                ThreadCatalogBinding.admit("thread-1", digest, child, child, owner).refinedValue(),
                ThreadCatalogBinding.admit("thread-1", otherDigest, child, root, owner).refinedValue(),
                ThreadCatalogBinding.admit("thread-1", digest, child, root, otherOwner).refinedValue(),
            )
        val file =
            (FileThreadCatalogStore.open(root.resolve("threads.json")) as FileThreadCatalogStoreOpen.Opened).store
        for (store in listOf(MemoryThreadCatalogStore(), file)) {
            assertEquals(ThreadStoreWrite.Written, store.write(original))
            candidates.forEach {
                assertEquals(ThreadStoreWrite.Rejected(ThreadCatalogStoreFailure.BINDING_CONFLICT), store.write(it))
            }
            val retained = (store.read("thread-1") as ThreadStoreRead.Found).binding
            assertEquals(original.workspace, retained.workspace)
            assertEquals(owner, retained.owner)
            assertEquals(digest, retained.catalogDigest)
        }
    }

    private fun Set<java.nio.file.attribute.PosixFilePermission>.permissionText(): String =
        java.nio.file.attribute.PosixFilePermissions.toString(this)

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> throw AssertionError("Expected refinement, received $failure")
        }
}
