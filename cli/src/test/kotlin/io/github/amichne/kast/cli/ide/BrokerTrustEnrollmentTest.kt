package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.cli.CanonicalRootDiscoverer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BrokerTrustEnrollmentTest {
    @TempDir lateinit var temporary: Path

    private fun home() = temporary.toRealPath()

    @Test
    fun `plugin created parent with mode755 is preserved while approval remains private`() {
        val parent = home().resolve(".kast")
        val original = PosixFilePermissions.fromString("rwxr-xr-x")
        Files.createDirectory(parent, PosixFilePermissions.asFileAttribute(original))
        assertEquals(
            BrokerTrustResult.Complete(BrokerTrustStatus.ENROLLED),
            FilesystemBrokerTrustRegistrar(home()).enroll(),
        )
        assertEquals(original, Files.getPosixFilePermissions(parent))
        assertEquals(
            PosixFilePermissions.fromString("rwx------"),
            Files.getPosixFilePermissions(parent.resolve("approval")),
        )
    }

    @Test
    fun `group writable parent remains rejected without permission repair`() {
        val parent = home().resolve(".kast")
        Files.createDirectory(parent)
        val original = PosixFilePermissions.fromString("rwxrwxr-x")
        Files.setPosixFilePermissions(parent, original)
        assertEquals(
            BrokerTrustResult.Rejected(BrokerTrustFailure.UNSAFE_PATH),
            FilesystemBrokerTrustRegistrar(home()).enroll(),
        )
        assertEquals(original, Files.getPosixFilePermissions(parent))
        assertFalse(Files.exists(parent.resolve("approval")))
    }

    @Test
    fun `explicit enrollment creates private matching keys and preserves them on repeat`() {
        val registrar = FilesystemBrokerTrustRegistrar(home())
        assertEquals(BrokerTrustResult.Complete(BrokerTrustStatus.ENROLLED), registrar.enroll())
        val directory = home().resolve(".kast/approval")
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory))
        val key = directory.resolve("broker.pk8")
        val bytes = Files.readAllBytes(key)
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(key))
        assertEquals(BrokerTrustResult.Complete(BrokerTrustStatus.PRESERVED), registrar.enroll())
        assertArrayEquals(bytes, Files.readAllBytes(key))
    }

    @Test
    fun `partial key state is rejected without replacing surviving key`() {
        val registrar = FilesystemBrokerTrustRegistrar(home())
        registrar.enroll()
        val key = home().resolve(".kast/approval/broker.pk8")
        val bytes = Files.readAllBytes(key)
        Files.delete(home().resolve(".kast/approval/broker.pub"))
        assertEquals(BrokerTrustResult.Rejected(BrokerTrustFailure.INCOMPLETE_KEYS), registrar.enroll())
        assertArrayEquals(bytes, Files.readAllBytes(key))
    }

    @Test
    fun `mismatched pair is rejected without replacement`() {
        val registrar = FilesystemBrokerTrustRegistrar(home())
        registrar.enroll()
        val key = home().resolve(".kast/approval/broker.pub")
        val wrong = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded
        Files.write(key, wrong)
        assertEquals(BrokerTrustResult.Rejected(BrokerTrustFailure.INVALID_KEYS), registrar.enroll())
        assertArrayEquals(wrong, Files.readAllBytes(key))
    }

    @Test
    fun `symlinked approval directory is rejected`() {
        Files.createDirectory(
            home().resolve(".kast"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
        )
        val target = Files.createDirectory(home().resolve("elsewhere"))
        Files.createSymbolicLink(home().resolve(".kast/approval"), target)
        assertEquals(
            BrokerTrustResult.Rejected(BrokerTrustFailure.UNSAFE_PATH),
            FilesystemBrokerTrustRegistrar(home()).enroll(),
        )
        assertFalse(Files.exists(target.resolve("broker.pk8")))
    }

    @Test
    fun `trust command is explicit local effect without root or host acquisition`() {
        var enrollments = 0
        val result =
            executeExistingIdeCli(
                argv = listOf("ide", "trust-broker"),
                start = home(),
                capabilities =
                    ExistingIdeCliCapabilities(
                        CanonicalRootDiscoverer { error("root discovery must not run") },
                        ExistingIdeClient { _, _ -> error("host query must not run") },
                        BrokerTrustRegistrar {
                            enrollments++
                            BrokerTrustResult.Complete(BrokerTrustStatus.ENROLLED)
                        },
                    ),
            )
        assertEquals(1, enrollments)
        assertTrue(result.document.value.contains("ENROLLED"))
    }
}
