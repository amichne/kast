package io.github.amichne.kast.distribution.managed.network

import io.github.amichne.kast.distribution.contract.network.TrustProvenance
import io.github.amichne.kast.distribution.contract.network.TrustStorePassword
import java.nio.file.Path

class TrustStoreDonor(
    val path: Path,
    val provenance: TrustProvenance,
    val type: String = "JKS",
    val provider: String? = null,
    val password: TrustStorePassword = TrustStorePassword.fromBoundary("changeit"),
) {
    override fun toString(): String = "TrustStoreDonor(path=$path,provenance=$provenance,type=$type)"
}

sealed interface DerivedTrustStoreMaterialization {
    data class Complete(val path: Path, val digest: String, val sources: Set<TrustProvenance>) : DerivedTrustStoreMaterialization
    data class Rejected(val failure: DerivedTrustStoreFailure) : DerivedTrustStoreMaterialization
}

enum class DerivedTrustStoreFailure { TARGET_REJECTED, DONOR_UNAVAILABLE, DONOR_UNREADABLE, EMPTY_CERTIFICATES, PUBLICATION_REJECTED }

/** Certificate-only, content-addressed publication under an admitted private runtime directory. */
object DerivedTrustStoreMaterializer {
    private val permissions = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")

    /** Donor certificates become a verified reusable artifact; all expected I/O/JSSE failures close here. */
    @Synchronized
    fun materialize(directory: Path, donors: List<TrustStoreDonor>): DerivedTrustStoreMaterialization {
        if (!directory.isAbsolute || directory.normalize() != directory || donors.isEmpty()) {
            return rejected(DerivedTrustStoreFailure.TARGET_REJECTED)
        }
        return try {
            if (java.nio.file.Files.isSymbolicLink(directory)) return rejected(DerivedTrustStoreFailure.TARGET_REJECTED)
            privateNetworkDirectory(directory)
            val lock = directory.resolve("materialization.lock")
            if (java.nio.file.Files.isSymbolicLink(lock)) return rejected(DerivedTrustStoreFailure.TARGET_REJECTED)
            java.nio.channels.FileChannel.open(lock, setOf(java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE),
                *privateAttributes(directory)).use { channel ->
                channel.lock().use { materializeExclusively(directory, donors) }
            }
        } catch (_: java.io.IOException) {
            rejected(DerivedTrustStoreFailure.PUBLICATION_REJECTED)
        } catch (_: java.security.GeneralSecurityException) {
            rejected(DerivedTrustStoreFailure.DONOR_UNREADABLE)
        } catch (_: SecurityException) {
            rejected(DerivedTrustStoreFailure.TARGET_REJECTED)
        }
    }

    private fun materializeExclusively(directory: Path, donors: List<TrustStoreDonor>): DerivedTrustStoreMaterialization {
        val certificates = sortedMapOf<String, java.security.cert.Certificate>()
        for (donor in donors) {
            if (!java.nio.file.Files.isRegularFile(donor.path) || java.nio.file.Files.isSymbolicLink(donor.path)) {
                return rejected(DerivedTrustStoreFailure.DONOR_UNAVAILABLE)
            }
            val store = if (donor.provider == null) java.security.KeyStore.getInstance(donor.type)
                else java.security.KeyStore.getInstance(donor.type, donor.provider)
            java.nio.file.Files.newInputStream(donor.path).use { input ->
                donor.password.useAtJsseBoundary { store.load(input, it) }
            }
            for (alias in store.aliases().asSequence()) {
                if (store.isCertificateEntry(alias)) {
                    val certificate = store.getCertificate(alias)
                    certificates.putIfAbsent(digest(certificate.encoded), certificate)
                }
            }
        }
        if (certificates.isEmpty()) return rejected(DerivedTrustStoreFailure.EMPTY_CERTIFICATES)
        val identity = digest(certificates.keys.joinToString("\n").toByteArray(Charsets.US_ASCII))
        val target = directory.resolve("sha256-$identity.jks")
        if (java.nio.file.Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if (java.nio.file.Files.isSymbolicLink(target)) return rejected(DerivedTrustStoreFailure.TARGET_REJECTED)
            val existing = java.security.KeyStore.getInstance("JKS")
            java.nio.file.Files.newInputStream(target).use { existing.load(it, CharArray(0)) }
            val aliases = existing.aliases().asSequence().toList()
            if (aliases.any { !existing.isCertificateEntry(it) } ||
                aliases.map { digest(existing.getCertificate(it).encoded) }.toSet() != certificates.keys) {
                return rejected(DerivedTrustStoreFailure.PUBLICATION_REJECTED)
            }
            restrictFile(target)
        } else {
            // JKS certificate entries remain visible when JSSE passes a null password.
            // Empty-password PKCS12 encrypts certificates that JSSE otherwise skips.
            val derived = java.security.KeyStore.getInstance("JKS").apply { load(null, CharArray(0)) }
            certificates.forEach { (fingerprint, certificate) -> derived.setCertificateEntry("sha256-$fingerprint", certificate) }
            val temporary = java.nio.file.Files.createTempFile(directory, ".trust-", ".tmp", *privateAttributes(directory))
            try {
                java.nio.file.Files.newOutputStream(temporary).use { derived.store(it, CharArray(0)) }
                java.nio.file.Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } finally {
                java.nio.file.Files.deleteIfExists(temporary)
            }
        }
        return DerivedTrustStoreMaterialization.Complete(target, identity, donors.map { it.provenance }.toSet())
    }

    private fun digest(bytes: ByteArray): String = java.util.HexFormat.of().formatHex(
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes),
    )
    private fun rejected(failure: DerivedTrustStoreFailure) = DerivedTrustStoreMaterialization.Rejected(failure)
    private fun privateAttributes(path: Path): Array<java.nio.file.attribute.FileAttribute<*>> =
        if (java.nio.file.Files.getFileStore(path).supportsFileAttributeView("posix"))
            arrayOf(java.nio.file.attribute.PosixFilePermissions.asFileAttribute(permissions)) else emptyArray()
    private fun restrictFile(path: Path) {
        if (java.nio.file.Files.getFileStore(path).supportsFileAttributeView("posix")) java.nio.file.Files.setPosixFilePermissions(path, permissions)
    }
}
