package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.cli.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BrokerTrustEnrollmentTest {
    @TempDir lateinit var temporary: Path

    private fun home() = temporary.toRealPath()

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
                listOf("ide", "trust-broker"),
                home(),
                CanonicalRootDiscoverer { error("root discovery must not run") },
                ExistingIdeClient { _, _ -> error("host query must not run") },
                trustRegistrar =
                    BrokerTrustRegistrar {
                        enrollments++
                        BrokerTrustResult.Complete(BrokerTrustStatus.ENROLLED)
                    },
            )
        assertEquals(1, enrollments)
        assertTrue(result.document.value.contains("ENROLLED"))
    }
}
