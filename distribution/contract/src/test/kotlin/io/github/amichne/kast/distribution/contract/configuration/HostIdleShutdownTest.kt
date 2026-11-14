package io.github.amichne.kast.distribution.contract.configuration

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostIdleShutdownTest {
    private fun minutes(raw: String): Int =
        ((HostIdleShutdown.parseSetting(raw) as Refinement.Refined).value as HostIdleShutdown.After).minutes

    @Test
    fun `the setting admits off and both range boundaries`() {
        assertEquals(Refinement.Refined(HostIdleShutdown.Disabled), HostIdleShutdown.parseSetting("off"))
        assertEquals(5, minutes("5"))
        assertEquals(1440, minutes("1440"))
        assertEquals(
            60_000L * 45,
            (HostIdleShutdown.parseSetting("45") as Refinement.Refined).value.let {
                (it as HostIdleShutdown.After).millis
            },
        )
    }

    @Test
    fun `values just outside the range and malformed text are rejected with distinct failures`() {
        for (raw in listOf("0", "4", "1441", "9999")) assertEquals(
            Refinement.Rejected(HostIdleShutdownFailure.OUT_OF_RANGE),
            HostIdleShutdown.parseSetting(raw),
        )
        for (raw in listOf("", "-5", "+5", " 5", "5m", "OFF", "10000", "99999999999", "1.5")) assertEquals(
            Refinement.Rejected(HostIdleShutdownFailure.INVALID_SETTING),
            HostIdleShutdown.parseSetting(raw),
        )
    }

    @Test
    fun `launch input carries only an admitted limit and the Host refuses off`() {
        val limit = (HostIdleShutdown.parseSetting("30") as Refinement.Refined).value
        assertEquals(listOf("-Dkast.host.idle_shutdown_minutes=30"), HostIdleShutdown.jvmOptions(limit))
        assertEquals(emptyList<String>(), HostIdleShutdown.jvmOptions(HostIdleShutdown.Disabled))
        assertEquals(Refinement.Refined(HostIdleShutdown.Disabled), HostIdleShutdown.parseJvmProperty(null))
        assertEquals(Refinement.Refined(limit), HostIdleShutdown.parseJvmProperty("30"))
        assertEquals(
            Refinement.Rejected(HostIdleShutdownFailure.INVALID_SETTING),
            HostIdleShutdown.parseJvmProperty("off"),
        )
        assertEquals(Refinement.Rejected(HostIdleShutdownFailure.OUT_OF_RANGE), HostIdleShutdown.parseJvmProperty("2"))
    }

    @Test
    fun `resolution defaults to off and rejects an invalid saved or explicit value`() {
        val key = "KAST_HOST_IDLE_SHUTDOWN"
        val default = ResolvedKastConfiguration.resolve(ConfigurationSources()) as Refinement.Refined
        assertEquals(HostIdleShutdown.Disabled, default.value.hostIdleShutdown)
        val explicit =
            ResolvedKastConfiguration.resolve(
                ConfigurationSources(savedWorkspace = listOf(key to "off"), commandLine = listOf(key to "60"))
            ) as Refinement.Refined
        assertEquals(HostIdleShutdown.parseSetting("60"), Refinement.Refined(explicit.value.hostIdleShutdown))
        val rejected =
            ResolvedKastConfiguration.resolve(ConfigurationSources(environment = mapOf(key to "3")))
                as Refinement.Rejected
        assertEquals(key, rejected.failure.key)
        assertEquals(ConfigurationFailure.INVALID_VALUE, rejected.failure.reason)
    }
}
