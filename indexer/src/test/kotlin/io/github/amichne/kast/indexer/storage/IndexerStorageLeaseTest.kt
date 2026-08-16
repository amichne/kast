package io.github.amichne.kast.indexer.storage

import io.github.amichne.kast.indexer.project.IndexerProjectLayout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class IndexerStorageLeaseTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `one JVM lifetime owner excludes a replacement and release permits reacquisition`() {
        val workspace = tempDir.resolve("workspace")
        Files.createDirectory(workspace)
        val layout = IndexerProjectLayout.create(
            workspaceRoot = workspace,
            storageRoot = tempDir.resolve("storage"),
        )

        IndexerStorageLease.acquire(layout).use {
            val failure = assertThrows(IndexerStorageInUseException::class.java) {
                IndexerStorageLease.acquire(layout)
            }
            assertEquals("INDEXER_STORAGE_IN_USE", failure.code)
        }
        IndexerStorageLease.acquire(layout).close()
    }

    @Test
    fun `inherited mode validates one existing descriptor without opening or closing the lease file`() {
        val workspace = tempDir.resolve("workspace")
        Files.createDirectory(workspace)
        val directLayout = IndexerProjectLayout.create(
            workspaceRoot = workspace,
            storageRoot = tempDir.resolve("storage"),
        )
        Files.createFile(directLayout.storageLeaseFile)
        FileChannel.open(
            directLayout.storageLeaseFile,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.lock().use { upstreamLock ->
                val inheritedDescriptor = openLeaseDescriptors(directLayout.storageLeaseFile).single()
                val inheritedLayout = IndexerProjectLayout.create(
                    workspaceRoot = workspace,
                    storageRoot = directLayout.storageRoot,
                    inheritedStorageLeaseFileDescriptor = inheritedDescriptor,
                )
                val descriptorsBefore = openLeaseDescriptors(inheritedLayout.storageLeaseFile)

                IndexerStorageLease.acquire(inheritedLayout).use { inheritedLease ->
                    assertEquals(descriptorsBefore, openLeaseDescriptors(inheritedLayout.storageLeaseFile))
                    assertTrue(upstreamLock.isValid)
                }

                assertEquals(descriptorsBefore, openLeaseDescriptors(inheritedLayout.storageLeaseFile))
                assertTrue(upstreamLock.isValid)
            }
        }
    }

    private fun openLeaseDescriptors(leaseFile: Path): Set<Int> = Files.list(Path.of("/dev/fd")).use { paths ->
        paths.iterator().asSequence().mapNotNull { path ->
            val descriptor = path.fileName.toString().toIntOrNull() ?: return@mapNotNull null
            descriptor.takeIf {
                PosixStorageLeaseIdentity.matches(descriptor, leaseFile)
            }
        }.toSet()
    }
}
