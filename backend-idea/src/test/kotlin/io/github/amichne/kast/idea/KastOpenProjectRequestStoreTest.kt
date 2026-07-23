package io.github.amichne.kast.idea

import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.fields.PathsRuntimeDir
import io.github.amichne.kast.api.contract.RuntimeOpenProjectRequestId
import io.github.amichne.kast.api.contract.RuntimeOpenProjectRoot
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

class KastOpenProjectRequestStoreTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `request is exact-root target-process and one-shot`() {
        val firstRoot = Files.createDirectory(tempDir.resolve("first"))
        val otherRoot = Files.createDirectory(tempDir.resolve("other"))
        val requestId = writeRequest(firstRoot, targetPid = 41, expiresAt = 2_000)
        val wrongProcess = store(now = 1_000, pid = 42)
        val selectedProcess = store(now = 1_000, pid = 41)

        assertFalse(
            wrongProcess.consume(root(firstRoot), requestId, allowUntargeted = false),
        )
        assertFalse(
            selectedProcess.consume(root(otherRoot), requestId, allowUntargeted = false),
        )
        assertTrue(
            selectedProcess.consume(root(firstRoot), requestId, allowUntargeted = false),
        )
        assertFalse(
            selectedProcess.consume(root(firstRoot), requestId, allowUntargeted = false),
        )
    }

    @Test
    fun `stale and untargeted requests are ignored by warm hosts`() {
        val root = Files.createDirectory(tempDir.resolve("root"))
        val stale = writeRequest(root, targetPid = 41, expiresAt = 999)
        val untargeted = writeRequest(
            root,
            targetPid = null,
            targetProductCode = "IU",
            expiresAt = 2_000,
        )
        val wrongProduct = writeRequest(
            root,
            targetPid = null,
            targetProductCode = "AI",
            expiresAt = 2_000,
        )
        val store = store(now = 1_000, pid = 41)

        val canonicalRoot = root(root)
        assertFalse(store.consume(canonicalRoot, stale, allowUntargeted = false))
        assertFalse(store.consume(canonicalRoot, untargeted, allowUntargeted = false))
        assertFalse(store.consume(canonicalRoot, wrongProduct, allowUntargeted = true))
        assertTrue(store.consume(canonicalRoot, untargeted, allowUntargeted = true))
    }

    private fun store(now: Long, pid: Long): KastOpenProjectRequestStore {
        val defaults = KastConfig.defaults()
        return KastOpenProjectRequestStore(
            config = defaults.copy(
                paths = defaults.paths.copy(
                    runtimeDir = PathsRuntimeDir(tempDir.toString()),
                ),
            ),
            timeProvider = OpenProjectRequestTimeProvider {
                OpenProjectRequestInstant.fromEpochMillis(now)
            },
            processId = IdeaProcessId.of(pid),
            productCode = IdeaProductCode.of("IU"),
        )
    }

    private fun writeRequest(
        root: Path,
        targetPid: Long?,
        targetProductCode: String? = null,
        expiresAt: Long,
    ): RuntimeOpenProjectRequestId {
        val requestId = RuntimeOpenProjectRequestId.random()
        val directory = Files.createDirectories(tempDir.resolve("idea-open-requests"))
        val path = directory.resolve("$requestId.json")
        Files.writeString(
            path,
            """
            {
              "canonicalRoot": "${root.toRealPath()}",
              "requestId": "$requestId",
              "targetPid": ${targetPid ?: "null"},
              "targetProductCode": ${targetProductCode?.let { "\"$it\"" } ?: "null"},
              "expiresAtEpochMillis": $expiresAt
            }
            """.trimIndent(),
        )
        Files.setPosixFilePermissions(
            path,
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        )
        return requestId
    }

    private fun root(path: Path): RuntimeOpenProjectRoot =
        RuntimeOpenProjectRoot.of(path)
}
