package io.github.amichne.kast.distribution.contract.configuration

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimitSource
import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ReadReliabilityConfigurationTest {
    @Test
    fun `reliability limits are discoverable and survive saved configuration and broker projection`() {
        val parameters = listOf(
            ReadLimitParameter.EXECUTION_MAX_MILLIS,
            ReadLimitParameter.EXECUTION_MAX_WORK,
            ReadLimitParameter.EXECUTION_MAX_RESULTS,
            ReadLimitParameter.EXECUTION_MAX_RETURNED_BYTES,
            ReadLimitParameter.HOST_ACCEPT_BACKLOG,
            ReadLimitParameter.HOST_CONNECTIONS,
            ReadLimitParameter.SOURCE_CONTINUATION_BYTES,
            ReadLimitParameter.SOURCE_CONTINUATION_TTL_MILLIS,
        )
        val declared = KastConfigurationCatalogue.declarations.map { it.key }.toSet()
        assertEquals(emptyList<String>(), parameters.filter { it.environmentKey !in declared }.map { it.name })
        val configured = assertInstanceOf(
            Refinement.Refined::class.java,
            ResolvedKastConfiguration.resolve(
                ConfigurationSources(savedInstallation = parameters.map { it.environmentKey to "1024" })
            ),
        ).value as ResolvedKastConfiguration
        parameters.forEach { parameter ->
            assertEquals(1024, configured.readLimits[parameter].value)
            assertEquals(ReadLimitSource.SAVED_INSTALLATION, configured.readLimits[parameter].source)
            assertEquals("1024", configured.childEnvironment(ConfigurationChild.BROKER)[parameter.environmentKey])
        }
    }
}
