package io.github.amichne.kast.distribution.managed.network

import io.github.amichne.kast.distribution.contract.network.NetworkConfiguration
import io.github.amichne.kast.distribution.contract.network.TrustProvenance
import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore

class EnterpriseNetworkPolicyTest {
    @Test
    fun `certificate only materialization is reusable private and content addressed`(@TempDir root: Path) {
        val defaults = KeyStore.getInstance("JKS")
        Files.newInputStream(Path.of(System.getProperty("java.home"), "lib/security/cacerts")).use {
            defaults.load(it, "changeit".toCharArray())
        }
        val certificate = defaults.getCertificate(defaults.aliases().nextElement())
        val donor = KeyStore.getInstance("PKCS12").apply {
            load(null, "changeit".toCharArray())
            setCertificateEntry("first", certificate)
            setCertificateEntry("duplicate", certificate)
            setEntry("secret", KeyStore.SecretKeyEntry(javax.crypto.spec.SecretKeySpec(ByteArray(16), "AES")),
                KeyStore.PasswordProtection("changeit".toCharArray()))
        }
        val donorPath = root.resolve("donor.p12")
        Files.newOutputStream(donorPath).use { donor.store(it, "changeit".toCharArray()) }
        val before = Files.readAllBytes(donorPath)
        val donors = listOf(TrustStoreDonor(donorPath, TrustProvenance.DONOR_JVM, "PKCS12"))
        val target = root.toRealPath().resolve("private")
        val first = assertInstanceOf(DerivedTrustStoreMaterialization.Complete::class.java,
            DerivedTrustStoreMaterializer.materialize(target, donors))
        val bytes = Files.readAllBytes(first.path)
        val second = DerivedTrustStoreMaterializer.materialize(target, donors)
        assertEquals(first, second)
        assertArrayEquals(bytes, Files.readAllBytes(first.path))
        assertArrayEquals(before, Files.readAllBytes(donorPath))
        val derived = KeyStore.getInstance("JKS")
        Files.newInputStream(first.path).use { derived.load(it, null) }
        assertEquals(1, derived.size())
        assertTrue(derived.isCertificateEntry(derived.aliases().nextElement()))
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(first.path)))
        assertTrue(first.path.fileName.toString().contains(first.digest))
    }

    @Test
    fun `derived trust cannot bypass absolute path admission`() {
        assertTrue(NetworkConfiguration.Empty.withDerivedTrust(Path.of("relative.jks")) is Refinement.Rejected)
    }

    @Test
    fun `closed parser rejects injection and malformed proxy ports`() {
        assertTrue(NetworkConfiguration.parse(mapOf("java.security.manager" to "allow")) is Refinement.Rejected)
        assertTrue(NetworkConfiguration.parse(mapOf("https.proxyHost" to "proxy", "https.proxyPort" to "70000")) is Refinement.Rejected)
        assertTrue(NetworkConfiguration.parse(mapOf("javax.net.ssl.trustStoreType" to "PKCS12")) is Refinement.Rejected)
    }

    @Test
    fun `consumer trust bundle suppresses derived trust and preserves proxy override`(@TempDir root: Path) {
        val fallback = (NetworkConfiguration.Empty.withDerivedTrust(root.resolve("derived.jks")) as Refinement.Refined).value
        val owner = (NetworkConfiguration.parse(mapOf("javax.net.ssl.trustStore" to root.resolve("owner.jks").toString())) as Refinement.Refined).value
        assertEquals(owner.atJvmBoundary(), fallback.fallbackBehind(owner).atJvmBoundary())
        assertTrue(fallback.missingFrom(owner).atJvmBoundary().isEmpty())
    }

    @Test
    fun `explicit empty password opens encrypted PKCS12 donor certificates`(@TempDir root: Path) {
        val donorPath = emptyPasswordPkcs12(root)
        val configuration = assertInstanceOf(Refinement.Refined::class.java, NetworkConfiguration.parse(mapOf(
            "javax.net.ssl.trustStore" to donorPath.toString(),
            "javax.net.ssl.trustStoreType" to "PKCS12",
            "javax.net.ssl.trustStorePassword" to "",
        ))).value as NetworkConfiguration
        val trust = assertInstanceOf(io.github.amichne.kast.distribution.contract.network.TrustSelection.Explicit::class.java,
            configuration.trust)
        trust.password.useAtJsseBoundary { password ->
            assertNotNull(password)
            assertArrayEquals(CharArray(0), password)
        }
        val materialized = assertInstanceOf(DerivedTrustStoreMaterialization.Complete::class.java,
            DerivedTrustStoreMaterializer.materialize(root.toRealPath().resolve("explicit-empty"), listOf(
                TrustStoreDonor(trust.path, TrustProvenance.EXPLICIT_KAST, trust.type, trust.provider, trust.password),
            )))
        val derived = KeyStore.getInstance("JKS")
        Files.newInputStream(materialized.path).use { derived.load(it, null) }
        assertEquals(1, derived.size())
        assertTrue(derived.isCertificateEntry(derived.aliases().nextElement()))
    }

    @Test
    fun `omitted password stays unspecified and cannot read encrypted PKCS12 certificates`(@TempDir root: Path) {
        val donorPath = emptyPasswordPkcs12(root)
        val configuration = assertInstanceOf(Refinement.Refined::class.java, NetworkConfiguration.parse(mapOf(
            "javax.net.ssl.trustStore" to donorPath.toString(),
            "javax.net.ssl.trustStoreType" to "PKCS12",
        ))).value as NetworkConfiguration
        val trust = assertInstanceOf(io.github.amichne.kast.distribution.contract.network.TrustSelection.Explicit::class.java,
            configuration.trust)
        trust.password.useAtJsseBoundary { password -> assertNull(password) }
        assertEquals(DerivedTrustStoreMaterialization.Rejected(DerivedTrustStoreFailure.EMPTY_CERTIFICATES),
            DerivedTrustStoreMaterializer.materialize(root.toRealPath().resolve("omitted"), listOf(
                TrustStoreDonor(trust.path, TrustProvenance.EXPLICIT_KAST, trust.type, trust.provider, trust.password),
            )))
    }

    private fun emptyPasswordPkcs12(root: Path): Path {
        val defaults = KeyStore.getInstance("JKS")
        Files.newInputStream(Path.of(System.getProperty("java.home"), "lib/security/cacerts")).use {
            defaults.load(it, "changeit".toCharArray())
        }
        val certificate = defaults.getCertificate(defaults.aliases().nextElement())
        val donor = KeyStore.getInstance("PKCS12").apply {
            load(null, CharArray(0))
            setCertificateEntry("fixture", certificate)
        }
        return root.toRealPath().resolve("empty-password.p12").also { path ->
            Files.newOutputStream(path).use { donor.store(it, CharArray(0)) }
        }
    }

}
