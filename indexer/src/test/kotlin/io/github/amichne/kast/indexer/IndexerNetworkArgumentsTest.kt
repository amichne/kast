package io.github.amichne.kast.indexer

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class IndexerNetworkArgumentsTest {
    @Test
    fun `network launch paths retain exact physical workspace and private cache`(@TempDir temporary: Path) {
        val root = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val cache = Files.createDirectory(temporary.resolve("cache")).toRealPath()
        val result =
            assertInstanceOf(
                IndexerNetworkArgumentAdmission.Admitted::class.java,
                IndexerNetworkArguments.admit(
                    arrayOf("--workspace-root=$root", "--cache-state-path=$cache/cache-state")
                ),
            )
        assertEquals(root, result.arguments.root)
        assertEquals(cache, result.arguments.cache)
    }

    @Test
    fun `ambiguous launch arguments reject before private writes`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val workspace = "--workspace-root=$root"
        val cache = "--cache-state-path=$root/cache-state"
        for ((arguments, failure) in
            listOf(
                arrayOf(cache) to IndexerNetworkArgumentFailure.ROOT_CARDINALITY,
                arrayOf(workspace, workspace, cache) to IndexerNetworkArgumentFailure.ROOT_CARDINALITY,
                arrayOf(workspace) to IndexerNetworkArgumentFailure.CACHE_CARDINALITY,
                arrayOf(workspace, cache, cache) to IndexerNetworkArgumentFailure.CACHE_CARDINALITY,
                arrayOf("--workspace-root=relative", cache) to IndexerNetworkArgumentFailure.INVALID_PATH,
                arrayOf(workspace, "--cache-state-path=$root/wrong-name") to IndexerNetworkArgumentFailure.INVALID_PATH,
            )) {
            assertEquals(IndexerNetworkArgumentAdmission.Rejected(failure), IndexerNetworkArguments.admit(arguments))
        }
        assertEquals(0L, Files.list(root).use { it.count() })
    }

    @Test
    fun `symbolic launch directories and state cannot select a write destination`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val target = Files.createDirectory(root.resolve("target"))
        val link = Files.createSymbolicLink(root.resolve("linked"), target)
        for (arguments in
            listOf(
                arrayOf("--workspace-root=$link", "--cache-state-path=$root/cache-state"),
                arrayOf("--workspace-root=$root", "--cache-state-path=$link/cache-state"),
            )) {
            assertEquals(
                IndexerNetworkArgumentAdmission.Rejected(IndexerNetworkArgumentFailure.INVALID_PATH),
                IndexerNetworkArguments.admit(arguments),
            )
        }
        Files.createSymbolicLink(root.resolve("cache-state"), target)
        assertEquals(
            IndexerNetworkArgumentAdmission.Rejected(IndexerNetworkArgumentFailure.INVALID_PATH),
            IndexerNetworkArguments.admit(arrayOf("--workspace-root=$root", "--cache-state-path=$root/cache-state")),
        )
    }
}
