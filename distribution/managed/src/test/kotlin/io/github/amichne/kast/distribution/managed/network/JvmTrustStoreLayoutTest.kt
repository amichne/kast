package io.github.amichne.kast.distribution.managed.network

import io.github.amichne.kast.distribution.contract.network.TrustSelection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore

class JvmTrustStoreLayoutTest {
    @Test
    fun `Java 8 JDK donor is adopted without modifying its trust store`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val donor = writeStore(root.resolve("donor/jre/lib/security/cacerts"))
        writeStore(root.resolve("target/lib/security/cacerts"))
        val before = Files.readAllBytes(donor)

        val prepared = assertInstanceOf(NetworkBootstrapResult.Prepared::class.java, prepare(root))

        assertTrue(prepared.sidecar.trust is TrustSelection.Explicit)
        assertArrayEquals(before, Files.readAllBytes(donor))
    }

    @Test
    fun `Java 8 JDK target contributes its runtime trust store`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        writeStore(root.resolve("donor/lib/security/cacerts"))
        val target = writeStore(root.resolve("target/jre/lib/security/cacerts"))
        val before = Files.readAllBytes(target)

        assertInstanceOf(NetworkBootstrapResult.Prepared::class.java, prepare(root))

        assertArrayEquals(before, Files.readAllBytes(target))
    }

    @Test
    fun `Java 8 jssecacerts takes precedence over cacerts`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        writeStore(root.resolve("donor/jre/lib/security/jssecacerts"))
        Files.writeString(root.resolve("donor/jre/lib/security/cacerts"), "not a key store")
        writeStore(root.resolve("target/lib/security/cacerts"))

        assertInstanceOf(NetworkBootstrapResult.Prepared::class.java, prepare(root))
    }

    @Test
    fun `an unavailable explicit donor is not silently replaced with target trust`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        Files.createDirectory(root.resolve("donor"))
        writeStore(root.resolve("target/lib/security/cacerts"))

        assertEquals(
            NetworkBootstrapResult.Rejected(NetworkBootstrapFailure.Trust(DerivedTrustStoreFailure.DONOR_UNAVAILABLE)),
            prepare(root),
        )
    }

    @Test
    fun `an unreadable Java 8 jssecacerts does not silently fall back to cacerts`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        writeStore(root.resolve("donor/jre/lib/security/cacerts"))
        Files.writeString(root.resolve("donor/jre/lib/security/jssecacerts"), "invalid key store")
        writeStore(root.resolve("target/lib/security/cacerts"))
        assertEquals(
            NetworkBootstrapResult.Rejected(NetworkBootstrapFailure.Trust(DerivedTrustStoreFailure.DONOR_UNREADABLE)),
            prepare(root),
        )
    }

    private fun prepare(root: Path): NetworkBootstrapResult = InstalledNetworkBootstrap.prepare(
        root = Files.createDirectories(root.resolve("workspace")),
        cache = Files.createDirectories(root.resolve("cache")),
        targetJavaHome = root.resolve("target"),
        environment = mapOf(
            "GRADLE_USER_HOME" to root.resolve("gradle-user-home").toString(),
            "KAST_TRUST_DONOR_JAVA_HOME" to root.resolve("donor").toString(),
        ),
    )

    private fun writeStore(path: Path): Path {
        val defaults = KeyStore.getInstance("JKS")
        Files.newInputStream(Path.of(System.getProperty("java.home"), "lib/security/cacerts")).use {
            defaults.load(it, "changeit".toCharArray())
        }
        val donor = KeyStore.getInstance("JKS").apply {
            load(null, "changeit".toCharArray())
            setCertificateEntry("fixture", defaults.getCertificate(defaults.aliases().nextElement()))
        }
        Files.createDirectories(path.parent)
        Files.newOutputStream(path).use { donor.store(it, "changeit".toCharArray()) }
        return path
    }
}
