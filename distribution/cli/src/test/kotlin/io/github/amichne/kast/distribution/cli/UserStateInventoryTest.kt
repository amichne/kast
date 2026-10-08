package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class UserStateInventoryTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `new state after admission prevents retirement`() {
        val home = home()
        val state = Files.createDirectory(home.resolve(".kast"))
        inventory(home).use { inventory ->
            val foreign = Files.writeString(state.resolve("unexpected"), "protected")
            assertOwnershipRejected(inventory.validateBeforeRetirement())
            assertOwnershipRejected(inventory.remove())
            assertEquals("protected", Files.readString(foreign))
        }
    }

    @Test
    fun `permission change after admission rejects cleanup`() {
        val home = home()
        val state = Files.createDirectory(home.resolve(".kast"))
        inventory(home).use { inventory ->
            Files.setPosixFilePermissions(state, Files.getPosixFilePermissions(state) + PosixFilePermission.GROUP_WRITE)
            assertOwnershipRejected(inventory.validateBeforeRetirement())
            assertTrue(Files.exists(state))
        }
    }

    @Test
    fun `home ancestry permissions remain part of cleanup authority`() {
        val home = home()
        val state = Files.createDirectory(home.resolve(".kast"))
        inventory(home).use { inventory ->
            Files.setPosixFilePermissions(home, Files.getPosixFilePermissions(home) + PosixFilePermission.GROUP_WRITE)
            assertOwnershipRejected(inventory.validateBeforeRetirement())
            assertTrue(Files.exists(state))
        }
    }

    @Test
    fun `file change after admission protects every captured artifact`() {
        val home = home()
        val endpoint = Files.createDirectories(home.resolve(".kast/ide-hosted/${"a".repeat(32)}"))
        val lock = Files.createFile(endpoint.resolve("owner.lock"))
        inventory(home).use { inventory ->
            Files.writeString(lock, "changed")
            assertOwnershipRejected(inventory.validateBeforeRetirement())
            assertOwnershipRejected(inventory.remove())
            assertEquals("changed", Files.readString(lock))
        }
    }

    @Test
    fun `closed directory membership accounts for earlier owned deletions`() {
        val home = home()
        val endpoint = Files.createDirectories(home.resolve(".kast/ide-hosted/${"a".repeat(32)}"))
        Files.createFile(endpoint.resolve("owner.lock"))
        inventory(home).use { inventory ->
            assertEquals(UserStateRemoval.Completed, inventory.validateBeforeRetirement())
            assertEquals(UserStateRemoval.Completed, inventory.remove())
        }
        assertTrue(Files.notExists(home.resolve(".kast")))
    }

    private fun home(): Path = Files.createDirectory(temporary.resolve("home")).toRealPath()

    private fun inventory(home: Path): UserStateInventory =
        when (val result = UserStateInventory.admit(home)) {
            is UserStateAdmission.Admitted -> result.inventory
            is UserStateAdmission.Rejected -> error("fixture rejected: ${result.failure}")
        }

    private fun assertOwnershipRejected(result: UserStateRemoval) {
        assertEquals(UserStateRemoval.Rejected(UserStateCleanupFailure.OWNERSHIP_UNPROVEN), result)
    }
}
