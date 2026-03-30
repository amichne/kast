package io.github.amichne.kast.server

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class AnalysisServerConfigTest {

    @Test
    fun `loopback host without token is accepted`() {
        assertDoesNotThrow { AnalysisServerConfig(host = "127.0.0.1", token = null) }
        assertDoesNotThrow { AnalysisServerConfig(host = "::1", token = null) }
        assertDoesNotThrow { AnalysisServerConfig(host = "localhost", token = null) }
        assertDoesNotThrow { AnalysisServerConfig(host = "LOCALHOST", token = null) }
    }

    @Test
    fun `loopback host with token is accepted`() {
        assertDoesNotThrow { AnalysisServerConfig(host = "127.0.0.1", token = "secret") }
    }

    @Test
    fun `non-loopback host with token is accepted`() {
        assertDoesNotThrow { AnalysisServerConfig(host = "0.0.0.0", token = "secret") }
        assertDoesNotThrow { AnalysisServerConfig(host = "192.168.1.10", token = "t") }
    }

    @Test
    fun `non-loopback host without token is rejected`() {
        assertThrows<IllegalArgumentException> { AnalysisServerConfig(host = "0.0.0.0", token = null) }
    }

    @Test
    fun `non-loopback host with blank token is rejected`() {
        assertThrows<IllegalArgumentException> { AnalysisServerConfig(host = "0.0.0.0", token = "") }
        assertThrows<IllegalArgumentException> { AnalysisServerConfig(host = "0.0.0.0", token = "   ") }
    }

    @Test
    fun `default config binds to loopback`() {
        assertDoesNotThrow { AnalysisServerConfig() }
    }
}
