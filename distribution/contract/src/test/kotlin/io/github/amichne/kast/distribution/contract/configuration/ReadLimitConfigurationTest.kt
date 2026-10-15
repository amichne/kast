package io.github.amichne.kast.distribution.contract.configuration

import io.github.amichne.kast.kernel.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReadLimitConfigurationTest {
    @Test
    fun `execution settings distinguish defaults from ceilings in configuration help`() {
        val declarations = KastConfigurationCatalogue.declarations.associateBy { it.key }
        for ((key, expected) in mapOf("KAST_READ_SEMANTIC_MILLIS" to "15000", "KAST_READ_SEMANTIC_WORK" to "2000000")) {
            val declaration = declarations.getValue(key)
            assertEquals(expected, declaration.defaultValue)
            assertTrue(declaration.description.contains("default", ignoreCase = true), key)
            assertTrue(declaration.description.contains("executionBudget"), key)
        }
        for (key in listOf("KAST_READ_EXECUTION_MAX_MILLIS", "KAST_READ_EXECUTION_MAX_WORK")) {
            assertTrue(declarations.getValue(key).description.contains("ceiling", ignoreCase = true), key)
        }
    }

    @Test
    fun `saved execution overrides keep their provenance through child projection`() {
        val configured =
            (ResolvedKastConfiguration.resolve(
                    ConfigurationSources(
                        savedInstallation =
                            listOf("KAST_READ_SEMANTIC_MILLIS" to "9000", "KAST_READ_SEMANTIC_WORK" to "600000")
                    )
                ) as Refinement.Refined)
                .value
        for ((parameter, expected) in
            mapOf(ReadLimitParameter.SEMANTIC_MILLIS to 9000, ReadLimitParameter.SEMANTIC_WORK to 600000)) {
            assertEquals(expected, configured.readLimits[parameter].value)
            assertEquals(ReadLimitSource.SAVED_INSTALLATION, configured.readLimits[parameter].source)
            assertEquals(
                expected.toString(),
                configured.childEnvironment(ConfigurationChild.BROKER)[parameter.environmentKey],
            )
        }
        assertEquals(128, configured.readLimits[ReadLimitParameter.SEMANTIC_RESULTS].value)
        assertEquals(49_152, configured.readLimits[ReadLimitParameter.SEMANTIC_RETURNED_BYTES].value)
    }

    @Test
    fun `every read limit is discoverable and saved settings retain their source`() {
        for (parameter in ReadLimitParameter.entries) {
            val declaration = KastConfigurationCatalogue.declarations.single { it.key == parameter.environmentKey }
            assertEquals(parameter.defaultValue.toString(), declaration.defaultValue)
            assertEquals(parameter.unit.name, declaration.unit.name)
            assertEquals(parameter.minimum.toLong(), declaration.admittedRange.minimum)
            assertEquals(ConfigurationMutability.USER_SETTING, declaration.mutability)
        }
        val configured =
            (ResolvedKastConfiguration.resolve(
                    ConfigurationSources(savedInstallation = listOf("KAST_READ_MODEL_MODULES" to "1024"))
                ) as Refinement.Refined)
                .value
        assertEquals(1024, configured.readLimits[ReadLimitParameter.MODEL_MODULES].value)
        assertEquals(ReadLimitSource.SAVED_INSTALLATION, configured.readLimits[ReadLimitParameter.MODEL_MODULES].source)
        assertEquals("1024", configured.childEnvironment(ConfigurationChild.BROKER)["KAST_READ_MODEL_MODULES"])
        assertEquals(
            ConfigurationSemanticAdmission.ADMITTED,
            configured.inspection().single { it.key == "KAST_READ_MODEL_MODULES" }.semanticAdmission,
        )
    }

    @Test
    fun `invalid shadowed values and impossible combined budgets fail closed`() {
        assertTrue(
            ResolvedKastConfiguration.resolve(
                ConfigurationSources(
                    environment = mapOf("KAST_READ_MODEL_MODULES" to "1024"),
                    savedInstallation = listOf("KAST_READ_MODEL_MODULES" to "private-invalid-value"),
                )
            ) is Refinement.Rejected
        )
        assertTrue(
            ResolvedKastConfiguration.resolve(
                ConfigurationSources(savedInstallation = listOf("KAST_READ_HOST_QUERY_MILLIS" to "32000"))
            ) is Refinement.Rejected
        )
        assertTrue(
            ResolvedKastConfiguration.resolve(
                ConfigurationSources(
                    savedInstallation =
                        listOf("KAST_READ_SEMANTIC_MILLIS" to "4000", "KAST_READ_HOST_QUERY_MILLIS" to "4000")
                )
            ) is Refinement.Refined
        )
    }
}
