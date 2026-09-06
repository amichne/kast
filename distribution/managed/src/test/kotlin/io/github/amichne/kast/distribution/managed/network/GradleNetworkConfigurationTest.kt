package io.github.amichne.kast.distribution.managed.network

import io.github.amichne.kast.distribution.contract.network.NetworkConfiguration
import io.github.amichne.kast.distribution.contract.network.NetworkConfigurationFailure
import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

class GradleNetworkConfigurationTest {
    @Test
    fun `user then project then installation properties own each explicit network value`(@TempDir temporary: Path) {
        val installation = Files.createDirectory(temporary.resolve("gradle"))
        val project = Files.createDirectory(temporary.resolve("project"))
        val user = Files.createDirectory(temporary.resolve("user"))
        val authorities = listOf(installation to "installation", project to "project", user to "user")
        for ((directory, name) in authorities) {
            properties(directory, mapOf(
                "systemProp.javax.net.ssl.trustStore" to temporary.resolve("$name.jks").toString(),
                "systemProp.http.proxyHost" to "$name.proxy.example",
                "systemProp.http.proxyPort" to "8080",
            ))
        }
        val original = authorities.associate { (directory, _) ->
            directory to Files.readString(directory.resolve("gradle.properties"))
        }

        for ((directory, name) in authorities.reversed()) {
            val actual = GradleNetworkConfiguration.read(project, user, installation).refined().atJvmBoundary()
            assertEquals(mapOf(
                "javax.net.ssl.trustStore" to temporary.resolve("$name.jks").toString(),
                "http.proxyHost" to "$name.proxy.example",
                "http.proxyPort" to "8080",
            ), actual)
            assertEquals(original.getValue(directory), Files.readString(directory.resolve("gradle.properties")))
            Files.delete(directory.resolve("gradle.properties"))
        }
        assertTrue(GradleNetworkConfiguration.read(project, user, installation).refined().atJvmBoundary().isEmpty())
    }

    @Test
    fun `user JVM argument property replaces project arguments while systemProp values retain precedence`(
        @TempDir temporary: Path,
    ) {
        val project = Files.createDirectory(temporary.resolve("project"))
        val user = Files.createDirectory(temporary.resolve("user"))
        val explicitStore = temporary.resolve("system property trust.p12")
        properties(project, mapOf(
            "org.gradle.jvmargs" to "-Dhttps.proxyHost=project.proxy -Dhttps.proxyPort=9443",
            "systemProp.javax.net.ssl.trustStore" to explicitStore.toString(),
            "systemProp.javax.net.ssl.trustStoreType" to "PKCS12",
            "systemProp.javax.net.ssl.trustStorePassword" to "",
            "systemProp.http.proxyHost" to "system.proxy",
        ))
        properties(user, mapOf(
            "org.gradle.jvmargs" to """
                -Xmx2g -Djava.security.manager=allow
                "-Djavax.net.ssl.trustStore=${temporary.resolve("argument trust.jks")}"
                -Djavax.net.ssl.trustStoreType=JKS -Djavax.net.ssl.trustStorePassword=argument-password
                -Dhttp.proxyHost=argument.proxy -Dhttp.proxyPort=8123
            """.trimIndent().replace('\n', ' '),
        ))

        assertEquals(mapOf(
            "javax.net.ssl.trustStore" to explicitStore.toString(),
            "javax.net.ssl.trustStoreType" to "PKCS12",
            "javax.net.ssl.trustStorePassword" to "",
            "http.proxyHost" to "system.proxy",
            "http.proxyPort" to "8123",
        ), GradleNetworkConfiguration.read(project, user).refined().atJvmBoundary())
    }

    @Test
    fun `quoted JVM values preserve spaces literals empty passwords and last explicit property`() {
        val raw = """
            -Xmx1g "-Djavax.net.ssl.trustStore=/tmp/company trust.p12"
            -Djavax.net.ssl.trustStoreType=PKCS12 -Djavax.net.ssl.trustStorePassword=""
            -Dhttp.proxyHost=first.proxy -Dhttp.proxyHost=last.proxy
            '-Dhttp.nonProxyHosts=localhost|*.internal'
            -Dunrelated.secret=must-not-cross-boundary
        """.trimIndent()

        assertEquals(mapOf(
            "javax.net.ssl.trustStore" to "/tmp/company trust.p12",
            "javax.net.ssl.trustStoreType" to "PKCS12",
            "javax.net.ssl.trustStorePassword" to "",
            "http.proxyHost" to "last.proxy",
            "http.nonProxyHosts" to "localhost|*.internal",
        ), GradleNetworkConfiguration.jvmProperties(raw).refined())
        assertEquals(
            mapOf("javax.net.ssl.trustStorePassword" to "literal \$(command) = value"),
            GradleNetworkConfiguration.jvmProperties(
                "'-Djavax.net.ssl.trustStorePassword=literal \$(command) = value'",
            ).refined(),
        )
    }

    @Test
    fun `explicit proxy host suppresses fallback port only for its own protocol bundle`(@TempDir temporary: Path) {
        val project = Files.createDirectory(temporary.resolve("project"))
        val user = Files.createDirectory(temporary.resolve("user"))
        properties(project, mapOf("systemProp.http.proxyHost" to "owned.proxy"))
        val owner = GradleNetworkConfiguration.read(project, user).refined()
        val fallback = NetworkConfiguration.parse(mapOf(
            "http.proxyHost" to "fallback.http.proxy",
            "http.proxyPort" to "8080",
            "https.proxyHost" to "fallback.https.proxy",
            "https.proxyPort" to "8443",
            "http.nonProxyHosts" to "localhost|*.internal",
        )).refined()
        val missing = mapOf(
            "https.proxyHost" to "fallback.https.proxy",
            "https.proxyPort" to "8443",
            "http.nonProxyHosts" to "localhost|*.internal",
        )

        assertEquals(missing, fallback.missingFrom(owner).atJvmBoundary())
        assertEquals(missing + ("http.proxyHost" to "owned.proxy"), fallback.fallbackBehind(owner).atJvmBoundary())
        assertFalse("http.proxyPort" in fallback.fallbackBehind(owner).atJvmBoundary())
    }

    @Test
    fun `malformed quoting and trailing escapes reject the complete argument source`(@TempDir temporary: Path) {
        val project = Files.createDirectory(temporary.resolve("project"))
        val user = Files.createDirectory(temporary.resolve("user"))
        for (malformed in listOf(
            "-Dhttp.proxyHost=valid.proxy -Dunrelated='unterminated",
            "\"-Dhttp.proxyHost=unterminated.proxy",
            "-Dhttp.proxyHost=valid.proxy \\",
        )) {
            assertEquals(
                Refinement.Rejected(NetworkConfigurationFailure.MALFORMED_JVM_ARGUMENTS),
                GradleNetworkConfiguration.jvmProperties(malformed),
            )
            properties(project, mapOf("org.gradle.jvmargs" to malformed))
            assertEquals(
                Refinement.Rejected(NetworkConfigurationFailure.MALFORMED_JVM_ARGUMENTS),
                GradleNetworkConfiguration.read(project, user),
            )
        }
    }

    @Test
    fun `explicit incomplete or invalid proxy properties fail closed`(@TempDir temporary: Path) {
        val project = Files.createDirectory(temporary.resolve("project"))
        val user = Files.createDirectory(temporary.resolve("user"))
        properties(project, mapOf("systemProp.https.proxyPort" to "8443"))
        assertEquals(
            Refinement.Rejected(NetworkConfigurationFailure.INCOMPLETE_PROXY),
            GradleNetworkConfiguration.read(project, user),
        )
        properties(project, mapOf(
            "systemProp.https.proxyHost" to "proxy.example",
            "systemProp.https.proxyPort" to "65536",
        ))
        assertEquals(
            Refinement.Rejected(NetworkConfigurationFailure.INVALID_PORT),
            GradleNetworkConfiguration.read(project, user),
        )
    }

    private fun properties(directory: Path, values: Map<String, String>) {
        val properties = Properties().apply { putAll(values) }
        Files.newOutputStream(directory.resolve("gradle.properties")).use { properties.store(it, null) }
    }

    private fun <Value> Refinement<Value, NetworkConfigurationFailure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("network configuration fixture rejected: $failure")
    }
}
