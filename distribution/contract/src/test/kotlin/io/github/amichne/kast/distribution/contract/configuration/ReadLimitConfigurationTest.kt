package io.github.amichne.kast.distribution.contract.configuration

import io.github.amichne.kast.kernel.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReadLimitConfigurationTest {
    @Test fun `every read limit is discoverable and saved settings retain their source`() {
        for (parameter in ReadLimitParameter.entries) {
            val declaration = KastConfigurationCatalogue.declarations.single { it.key == parameter.environmentKey }
            assertEquals(parameter.defaultValue.toString(), declaration.defaultValue)
            assertEquals(parameter.unit.name, declaration.unit.name)
            assertEquals(parameter.minimum.toLong(), declaration.admittedRange.minimum)
            assertEquals(ConfigurationMutability.USER_SETTING, declaration.mutability)
        }
        val configured = (ResolvedKastConfiguration.resolve(ConfigurationSources(
            savedInstallation = listOf("KAST_READ_MODEL_MODULES" to "1024"),
        )) as Refinement.Refined).value
        assertEquals(1024, configured.readLimits[ReadLimitParameter.MODEL_MODULES].value)
        assertEquals(ReadLimitSource.SAVED_INSTALLATION, configured.readLimits[ReadLimitParameter.MODEL_MODULES].source)
        assertEquals("1024", configured.childEnvironment(ConfigurationChild.BROKER)["KAST_READ_MODEL_MODULES"])
        assertEquals(ConfigurationSemanticAdmission.ADMITTED, configured.inspection().single { it.key == "KAST_READ_MODEL_MODULES" }.semanticAdmission)
    }

    @Test fun `invalid shadowed values and impossible combined budgets fail closed`() {
        assertTrue(ResolvedKastConfiguration.resolve(ConfigurationSources(
            environment = mapOf("KAST_READ_MODEL_MODULES" to "1024"),
            savedInstallation = listOf("KAST_READ_MODEL_MODULES" to "private-invalid-value"),
        )) is Refinement.Rejected)
        assertTrue(ResolvedKastConfiguration.resolve(ConfigurationSources(
            savedInstallation = listOf("KAST_READ_HOST_QUERY_MILLIS" to "6000"),
        )) is Refinement.Rejected)
        assertTrue(ResolvedKastConfiguration.resolve(ConfigurationSources(
            savedInstallation = listOf("KAST_READ_SEMANTIC_MILLIS" to "4000", "KAST_READ_HOST_QUERY_MILLIS" to "4000"),
        )) is Refinement.Refined)
    }
}
