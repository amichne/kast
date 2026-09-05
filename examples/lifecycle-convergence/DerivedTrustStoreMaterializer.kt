package lifecycle.convergence

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.Certificate
import java.util.HexFormat

/** Read-only donor; passwords remain process-local and never enter evidence or toString output. */
class TrustStoreDonor(
    val path: Path,
    val type: String,
    private val password: CharArray?,
) {
    fun <T> usePassword(block: (CharArray?) -> T): T = block(password?.copyOf())
    override fun toString(): String = "TrustStoreDonor(path=$path,type=$type)"
}

sealed interface DerivedTrustStoreMaterialization {
    data class Complete(
        val path: Path,
        val certificateFingerprints: Set<String>,
    ) : DerivedTrustStoreMaterialization

    data class Rejected(val failure: DerivedTrustStoreFailure) : DerivedTrustStoreMaterialization
}

enum class DerivedTrustStoreFailure {
    TargetRejected,
    DonorUnavailable,
    DonorUnreadable,
    CertificateRejected,
    PublicationRejected,
}

/**
 * Example physical adapter for a Kast-owned truststore.
 *
 * Only `Certificate` entries are copied. Private/secret key entries never cross the boundary.
 * Deduplication is by DER SHA-256 fingerprint, not donor alias. Publication is atomic when the
 * filesystem supports it; the destination remains inside a caller-admitted private directory.
 */
object DerivedTrustStoreMaterializer {
    fun materialize(
        target: Path,
        donors: List<TrustStoreDonor>,
    ): DerivedTrustStoreMaterialization {
        if (!target.isAbsolute || target.normalize() != target || donors.isEmpty()) {
            return DerivedTrustStoreMaterialization.Rejected(DerivedTrustStoreFailure.TargetRejected)
        }
        val parent = target.parent
            ?: return DerivedTrustStoreMaterialization.Rejected(DerivedTrustStoreFailure.TargetRejected)
        if (!Files.isDirectory(parent)) {
            return DerivedTrustStoreMaterialization.Rejected(DerivedTrustStoreFailure.TargetRejected)
        }

        val certificates = linkedMapOf<String, Certificate>()
        for (donor in donors) {
            if (!Files.isRegularFile(donor.path) || Files.isSymbolicLink(donor.path)) {
                return DerivedTrustStoreMaterialization.Rejected(
                    DerivedTrustStoreFailure.DonorUnavailable,
                )
            }
            val store = try {
                KeyStore.getInstance(donor.type).also { keyStore ->
                    Files.newInputStream(donor.path, StandardOpenOption.READ).use { input ->
                        donor.usePassword { password -> keyStore.load(input, password) }
                    }
                }
            } catch (_: Exception) {
                return DerivedTrustStoreMaterialization.Rejected(
                    DerivedTrustStoreFailure.DonorUnreadable,
                )
            }
            val aliases = store.aliases().toList().sorted()
            for (alias in aliases) {
                if (!store.isCertificateEntry(alias)) continue
                val certificate = store.getCertificate(alias)
                    ?: return DerivedTrustStoreMaterialization.Rejected(
                        DerivedTrustStoreFailure.CertificateRejected,
                    )
                val fingerprint = fingerprint(certificate)
                certificates.putIfAbsent(fingerprint, certificate)
            }
        }

        val derived = try {
            KeyStore.getInstance("PKCS12").also { it.load(null, EMPTY_PASSWORD) }
        } catch (_: Exception) {
            return DerivedTrustStoreMaterialization.Rejected(
                DerivedTrustStoreFailure.PublicationRejected,
            )
        }
        certificates.forEach { (fingerprint, certificate) ->
            derived.setCertificateEntry("sha256-$fingerprint", certificate)
        }

        val temporary = parent.resolve(".${target.fileName}.tmp")
        return try {
            Files.deleteIfExists(temporary)
            Files.newOutputStream(
                temporary,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { output -> derived.store(output, EMPTY_PASSWORD) }
            try {
                Files.setPosixFilePermissions(temporary, setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                ))
            } catch (_: UnsupportedOperationException) {
                // The production macOS target supports POSIX permissions; other filesystems may not.
            }
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            DerivedTrustStoreMaterialization.Complete(target, certificates.keys)
        } catch (_: Exception) {
            Files.deleteIfExists(temporary)
            DerivedTrustStoreMaterialization.Rejected(DerivedTrustStoreFailure.PublicationRejected)
        }
    }

    private fun fingerprint(certificate: Certificate): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded),
    )

    private val EMPTY_PASSWORD = CharArray(0)

    private fun <T> java.util.Enumeration<T>.toList(): List<T> = buildList {
        while (hasMoreElements()) add(nextElement())
    }
}
