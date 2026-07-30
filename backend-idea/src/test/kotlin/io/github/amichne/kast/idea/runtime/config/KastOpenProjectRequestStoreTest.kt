package io.github.amichne.kast.idea

import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.fields.PathsRuntimeDir
import io.github.amichne.kast.api.contract.RuntimeOpenProjectRequestId
import io.github.amichne.kast.api.contract.RuntimeOpenProjectRoot
import org.junit.jupiter.api.Assertions.assertEquals
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
            wrongProcess.consume(root(firstRoot), requestId, OpenProjectRequestAudience.TARGET_PROCESS),
        )
        assertFalse(
            selectedProcess.consume(root(otherRoot), requestId, OpenProjectRequestAudience.TARGET_PROCESS),
        )
        assertTrue(
            selectedProcess.consume(root(firstRoot), requestId, OpenProjectRequestAudience.TARGET_PROCESS),
        )
        assertFalse(
            selectedProcess.consume(root(firstRoot), requestId, OpenProjectRequestAudience.TARGET_PROCESS),
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
        assertFalse(store.consume(canonicalRoot, stale, OpenProjectRequestAudience.TARGET_PROCESS))
        assertFalse(store.consume(canonicalRoot, untargeted, OpenProjectRequestAudience.TARGET_PROCESS))
        assertFalse(store.consume(canonicalRoot, wrongProduct, OpenProjectRequestAudience.TARGET_PRODUCT))
        assertTrue(store.consume(canonicalRoot, untargeted, OpenProjectRequestAudience.TARGET_PRODUCT))
    }

    @Test
    fun `project signal drains duplicate untargeted requests without stealing process request`() {
        val root = Files.createDirectory(tempDir.resolve("root"))
        val targeted = writeRequest(root, targetPid = 41, expiresAt = 2_000)
        val firstUntargeted = writeRequest(
            root,
            targetPid = null,
            targetProductCode = "IU",
            expiresAt = 2_000,
        )
        val secondUntargeted = writeRequest(
            root,
            targetPid = null,
            targetProductCode = "IU",
            expiresAt = 2_000,
        )
        val store = store(now = 1_000, pid = 41)
        val canonicalRoot = root(root)

        assertTrue(store.consumeUntargetedForProject(canonicalRoot))
        assertTrue(store.consume(canonicalRoot, targeted, OpenProjectRequestAudience.TARGET_PROCESS))
        assertFalse(store.consume(canonicalRoot, firstUntargeted, OpenProjectRequestAudience.TARGET_PRODUCT))
        assertFalse(store.consume(canonicalRoot, secondUntargeted, OpenProjectRequestAudience.TARGET_PRODUCT))
    }

    @Test
    fun `project observer starts an already-open project for a future product signal`() {
        val projectRoot = Files.createDirectory(tempDir.resolve("observed"))
        val canonicalRoot = root(projectRoot)
        var starts = 0
        val observer = KastOpenProjectRequestObserver(
            requests = store(now = 1_000, pid = 41),
            canonicalRoot = canonicalRoot,
            onSignal = { starts += 1 },
        )

        observer.poll()
        writeRequest(
            projectRoot,
            targetPid = null,
            targetProductCode = "IU",
            expiresAt = 2_000,
        )
        observer.poll()

        assertEquals(1, starts)
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
