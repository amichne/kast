package kast.baseline.network

import kast.baseline.model.*
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** No exception text, file name, credential, or credential digest crosses this failure boundary. */
enum class TrustFailure { UNAVAILABLE, TOO_LARGE, INVALID_STORE, EMPTY_STORE, PRIVATE_KEY_PRESENT }
class AdmittedTrust private constructor(val context: SSLContext) {
    companion object {
        internal fun fromLoadedContext(context: SSLContext): AdmittedTrust = AdmittedTrust(context)
    }
    override fun toString(): String = "AdmittedTrust"
}

/** File reads and JCA construction are confined to this adapter; no store is modified or combined. */
object TrustStoreAdapter {
    fun admit(policy: TrustPolicy): Refinement<AdmittedTrust, TrustFailure> {
        if (policy is TrustPolicy.PlatformDefault) return try {
            Refinement.Refined(AdmittedTrust.fromLoadedContext(SSLContext.getDefault()))
        } catch (_: GeneralSecurityException) { Refinement.Rejected(TrustFailure.INVALID_STORE) }
        val file: Path
        val format: StoreFormat
        val password: StorePassword
        when (policy) {
            is TrustPolicy.Store -> {
                file = Path.of(policy.file.value)
                format = policy.format
                password = policy.password
            }
            is TrustPolicy.JvmDefault -> {
                val directory = Path.of(policy.home.value).resolve("lib/security")
                // An existing but unreadable jssecacerts rejects; it never falls through to cacerts.
                val preferred = directory.resolve("jssecacerts")
                file = if (Files.notExists(preferred, LinkOption.NOFOLLOW_LINKS)) directory.resolve("cacerts") else preferred
                format = StoreFormat.PLATFORM_DEFAULT
                password = StorePassword.Unspecified
            }
            TrustPolicy.PlatformDefault -> error("handled above")
        }
        val bytes = try {
            val physical = file.toRealPath()
            if (!Files.isRegularFile(physical)) return Refinement.Rejected(TrustFailure.UNAVAILABLE)
            Files.newInputStream(physical).use { it.readNBytes(MAXIMUM_BYTES + 1) }
        } catch (_: IOException) { return Refinement.Rejected(TrustFailure.UNAVAILABLE) }
          catch (_: SecurityException) { return Refinement.Rejected(TrustFailure.UNAVAILABLE) }
        if (bytes.size > MAXIMUM_BYTES) return Refinement.Rejected(TrustFailure.TOO_LARGE)
        return try {
            val store = KeyStore.getInstance(when (format) {
                StoreFormat.PLATFORM_DEFAULT -> KeyStore.getDefaultType()
                StoreFormat.JKS -> "JKS"
                StoreFormat.PKCS12 -> "PKCS12"
            })
            when (password) {
                StorePassword.Unspecified -> store.load(ByteArrayInputStream(bytes), null)
                is StorePassword.Supplied -> password.secret.useAtBoundary {
                    store.load(ByteArrayInputStream(bytes), it)
                }
            }
            val aliases = store.aliases().toList()
            if (aliases.any(store::isKeyEntry)) return Refinement.Rejected(TrustFailure.PRIVATE_KEY_PRESENT)
            if (aliases.none(store::isCertificateEntry)) return Refinement.Rejected(TrustFailure.EMPTY_STORE)
            val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            managers.init(store)
            val context = SSLContext.getInstance("TLS")
            context.init(null, managers.trustManagers, null)
            Refinement.Refined(AdmittedTrust.fromLoadedContext(context))
        } catch (_: GeneralSecurityException) { Refinement.Rejected(TrustFailure.INVALID_STORE) }
          catch (_: IOException) { Refinement.Rejected(TrustFailure.INVALID_STORE) }
    }
    private const val MAXIMUM_BYTES = 16 * 1024 * 1024
}

/** Projection for a process-specific adapter. Never call System.setProperties with this map. */
fun ProxyPolicy.jvmProperties(): Map<String, String> = when (this) {
    ProxyPolicy.PlatformDefault -> emptyMap()
    is ProxyPolicy.Configured -> buildMap {
        fun route(scheme: String, route: ProxyRoute) {
            when (route) {
                ProxyRoute.Unspecified -> Unit
                is ProxyRoute.Forward -> {
                    put("$scheme.proxyHost", route.host.value)
                    put("$scheme.proxyPort", route.port.value.toString())
                }
            }
        }
        route("http", http)
        route("https", https)
        when (val selected = bypass) {
            ProxyBypass.Unspecified -> Unit
            is ProxyBypass.Configured -> put("http.nonProxyHosts", selected.hosts.value)
        }
    }
}
