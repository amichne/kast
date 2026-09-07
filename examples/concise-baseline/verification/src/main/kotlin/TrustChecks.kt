package kast.baseline.verification

import kast.baseline.model.*
import kast.baseline.network.*
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException

/** Real loopback TLS with a newly generated certificate; no external endpoint or trust bypass. */
fun trustChecks(): List<String> {
    val checks = mutableListOf<String>()
    fun prove(name: String, predicate: Boolean) { check(predicate) { name }; checks += name }
    val directory = Files.createTempDirectory("kast-baseline-tls-")
    var server: HttpsServer? = null
    try {
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
        fun keytool(vararg args: String) {
            val log = directory.resolve("keytool.log")
            val process = ProcessBuilder(listOf(keytool) + args).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start()
            if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); error("keytool-timeout") }
            check(process.exitValue() == 0) { "keytool-failed" }
        }
        val keys = directory.resolve("server.p12")
        val certificate = directory.resolve("server.cer")
        val trust = directory.resolve("trust.p12")
        // Public, disposable fixture password; never an enterprise credential.
        val password = "fixture-password"
        keytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
            "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-keystore", keys.toString(),
            "-storetype", "PKCS12", "-storepass", password, "-noprompt")
        keytool("-exportcert", "-alias", "server", "-keystore", keys.toString(), "-storepass", password,
            "-file", certificate.toString())
        keytool("-importcert", "-alias", "server", "-keystore", trust.toString(), "-storetype", "PKCS12",
            "-storepass", password, "-file", certificate.toString(), "-noprompt")
        fun policy(path: Path): TrustPolicy = NetworkPolicyResolver.resolve(NetworkConsumer.SIDECAR_CLIENT,
            listOf(RawNetworkLayer(NetworkSource.JVM_PROPERTIES, mapOf(
                "javax.net.ssl.trustStore" to path.toString(), "javax.net.ssl.trustStoreType" to "PKCS12",
                "javax.net.ssl.trustStorePassword" to password)))).valueOrFail().trust
        val before = Files.readAllBytes(trust)
        val admitted = TrustStoreAdapter.admit(policy(trust)).valueOrFail()
        val store = KeyStore.getInstance("PKCS12")
        Files.newInputStream(keys).use { store.load(it, password.toCharArray()) }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        managers.init(store, password.toCharArray())
        val serverContext = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        val active = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = active
        active.httpsConfigurator = HttpsConfigurator(serverContext)
        active.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("ok".toByteArray()) }
        }
        active.start()
        fun request(host: String, context: SSLContext): Boolean {
            val connection = URI("https://$host:${active.address.port}/").toURL()
                .openConnection(Proxy.NO_PROXY) as HttpsURLConnection
            connection.sslSocketFactory = context.socketFactory
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            return try { connection.inputStream.use { it.readBytes().decodeToString() == "ok" } }
            catch (_: SSLException) { false }
            finally { connection.disconnect() }
        }
        val isolatedDefault = SSLContext.getInstance("TLS").apply { init(null, null, null) }
        prove("default-jvm-trust-rejects-private-certificate", !request("localhost", isolatedDefault))
        prove("admitted-trust-accepts-private-certificate", request("localhost", admitted.context))
        prove("hostname-verification-remains-enabled", !request("127.0.0.1", admitted.context))
        prove("source-truststore-is-not-modified", before.contentEquals(Files.readAllBytes(trust)))
        prove("missing-explicit-store-rejects", TrustStoreAdapter.admit(policy(directory.resolve("missing"))) ==
            Refinement.Rejected(TrustFailure.UNAVAILABLE))
        prove("private-key-store-rejects", TrustStoreAdapter.admit(policy(keys)) ==
            Refinement.Rejected(TrustFailure.PRIVATE_KEY_PRESENT))
        val donorHome = directory.resolve("donor").also { Files.createDirectories(it.resolve("lib/security")) }
        Files.copy(trust, donorHome.resolve("lib/security/cacerts"))
        // JKS supports certificate reads without an integrity password, as JSSE's default path does.
        keytool("-importcert", "-alias", "server", "-keystore", donorHome.resolve("lib/security/jssecacerts").toString(),
            "-storetype", "JKS", "-storepass", password, "-file", certificate.toString(), "-noprompt")
        val donor = TrustPolicy.JvmDefault(AbsolutePath.parse(donorHome.toString()).valueOrFail())
        prove("selected-donor-default-is-readable", TrustStoreAdapter.admit(donor) is Refinement.Refined)
        Files.writeString(donorHome.resolve("lib/security/jssecacerts"), "invalid")
        prove("invalid-preferred-donor-store-never-falls-back", TrustStoreAdapter.admit(donor) ==
            Refinement.Rejected(TrustFailure.INVALID_STORE))
        val projected = NetworkPolicyResolver.resolve(NetworkConsumer.SIDECAR_CLIENT,
            listOf(RawNetworkLayer(NetworkSource.JVM_PROPERTIES, mapOf("https.proxyHost" to "proxy.example",
                "https.proxyPort" to "8443", "java.security.properties" to "/untrusted")))).valueOrFail().proxy.jvmProperties()
        prove("proxy-projection-is-an-allowlist", projected.keys.all { it in setOf("https.proxyHost", "https.proxyPort", "http.nonProxyHosts") })
        prove("absent-bypass-preserves-java-default", "http.nonProxyHosts" !in projected)
        return checks
    } finally {
        server?.stop(0)
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}
