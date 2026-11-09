package support.tasks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class DependencyBootstrapEnvironmentTest {
    @Test
    fun `dependency download keeps proxy and certificate settings without unrelated credentials`() {
        val supplied = mapOf(
            "HTTPS_PROXY" to "http://approved-proxy.invalid:8080",
            "no_proxy" to "localhost,127.0.0.1,::1",
            "SSL_CERT_FILE" to "/approved/ca.pem",
            "NODE_EXTRA_CA_CERTS" to "/approved/node-ca.pem",
            "GITHUB_TOKEN" to "unrelated-secret",
            "JAVA_TOOL_OPTIONS" to "unrelated-options",
            "HOME" to "/unrelated/home",
        )
        val queries = mutableListOf<String>()
        val actual = DependencyBootstrapEnvironment.network { name ->
            queries += name
            supplied[name]
        }
        assertEquals(mapOf(
            "HTTPS_PROXY" to "http://approved-proxy.invalid:8080",
            "no_proxy" to "localhost,127.0.0.1,::1",
            "SSL_CERT_FILE" to "/approved/ca.pem",
            "NODE_EXTRA_CA_CERTS" to "/approved/node-ca.pem",
        ), actual)
        assertFalse(queries.any { it in setOf("GITHUB_TOKEN", "JAVA_TOOL_OPTIONS", "HOME") })
        assertEquals(queries.size, queries.toSet().size)
    }

    @Test
    fun `absent and empty settings add no transport configuration`() {
        assertEquals(emptyMap<String, String>(), DependencyBootstrapEnvironment.network { null })
        assertEquals(emptyMap<String, String>(), DependencyBootstrapEnvironment.network { "" })
    }
}
