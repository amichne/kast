package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.Validation
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RetiredSemanticStateTest {
    @Test
    fun `retirement consumes owned coordinator artifacts and preserves invocation state`() =
        withCoordinatorPayload { root, _ ->
            runBlocking {
                val command = semanticLaunchFixture(root)
                val run = Files.createDirectories(root.resolve("state/run"))
                val invocations = Files.createDirectory(run.resolve("one-shot"))
                val lock = Files.writeString(run.resolve("c.sock.lock"), "")
                Files.writeString(command.log, "coordinator log")
                Files.writeString(command.lock, "")
                Files.createFile(root.resolve(".lifecycle-transition.json"))
                val foreign = Files.writeString(command.state.resolve("foreign"), "preserve")
                assertEquals(
                    SemanticCoordinatorRetirement.OWNERSHIP_UNPROVEN,
                    retireFencedSemanticCoordinator(root, root),
                )
                assertTrue(Files.isRegularFile(lock))
                assertEquals("preserve", Files.readString(foreign))
                Files.delete(foreign)

                assertEquals(SemanticCoordinatorRetirement.ABSENT, retireFencedSemanticCoordinator(root, root))
                assertFalse(Files.exists(command.state))
                assertFalse(Files.exists(lock))
                assertTrue(Files.isDirectory(invocations))
                assertEquals(SemanticCoordinatorRetirement.ABSENT, retireFencedSemanticCoordinator(root, root))
            }
        }

    @Test
    fun `retirement completes after the exact alias was already detached`() = withCoordinatorPayload { root, _ ->
        val run = Files.createDirectories(root.resolve("n".repeat(110)).resolve("state/run"))
        Files.setPosixFilePermissions(run, PosixFilePermissions.fromString("rwx------"))
        val socket = run.resolve("c.sock")
        val selected = BrokerEndpointAliases.prepare(run) as Validation.Validated
        val alias = selected.value.alias
        try {
            assertTrue(Files.isSymbolicLink(alias))
            Files.delete(alias)
            assertTrue(Files.isRegularFile(run.resolve("endpoint-alias.json")))
            assertEquals(Validation.validated(Unit), BrokerEndpointAliases.retire(socket))
            assertFalse(Files.exists(run.resolve("endpoint-alias.json")))
            assertTrue(Files.isDirectory(run))
        } finally {
            if (selected.value.validate() is Validation.Validated) Files.delete(alias)
        }
    }
}

internal fun semanticLaunchFixture(root: Path): SemanticServiceLaunch {
    val executable = root.resolve("share/kast/libexec/kast-management")
    Files.createDirectories(executable.parent)
    Files.writeString(executable, "fixture")
    Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
    val command = checkNotNull(SemanticServiceLaunch.admit(root, root, emptyMap()))
    for (directory in listOf(command.state.parent.parent, command.state.parent, command.state)) {
        Files.createDirectories(directory)
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
    }
    return command
}
