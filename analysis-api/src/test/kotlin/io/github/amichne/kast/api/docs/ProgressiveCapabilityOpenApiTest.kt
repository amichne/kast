package io.github.amichne.kast.api.docs

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProgressiveCapabilityOpenApiTest {
    @Test
    fun `runtime readiness schema exposes eight independent closed lanes`() {
        val yaml = OpenApiDocument.renderYaml()
        val readiness = yaml.component("RuntimeReadiness")

        listOf(
            "runtime",
            "model",
            "workspaceFiles",
            "compiler",
            "sourceIndex",
            "references",
            "semanticGraph",
            "mutation",
        ).forEach { lane ->
            assertTrue("$lane:" in readiness, "RuntimeReadiness must expose $lane")
            assertTrue("- $lane" in readiness, "RuntimeReadiness must require $lane")
        }
        assertTrue("CurrentCapabilityLaneReadiness" in readiness)
        assertTrue("RetainedCapabilityLaneReadiness" in readiness)
        assertFalse("additionalProperties: true" in readiness)
    }

    @Test
    fun `lane schemas close availability building blocker and previous fallback variants`() {
        val yaml = OpenApiDocument.renderYaml()
        val current = yaml.component("CurrentCapabilityLaneReadiness")
        val retained = yaml.component("RetainedCapabilityLaneReadiness")
        val fallback = yaml.component("RetainedCapabilityLaneFallback")

        listOf("AVAILABLE", "BUILDING", "BLOCKED").forEach { variant ->
            assertTrue("$variant:" in current)
            assertTrue("$variant:" in retained)
        }
        assertTrue("propertyName: type" in current)
        assertTrue("propertyName: type" in retained)
        assertTrue("NONE:" in fallback)
        assertTrue("PREVIOUS:" in fallback)
        assertTrue("propertyName: type" in fallback)

        val currentFreshness = yaml.component("CurrentCapabilityLaneFreshness")
        val retainedFreshness = yaml.component("RetainedCapabilityLaneFreshness")
        val previousFreshness = yaml.component("PreviousCapabilityLaneFreshness")
        assertTrue("- CURRENT" in currentFreshness)
        assertFalse("- PREVIOUS" in currentFreshness)
        assertTrue("- CURRENT" in retainedFreshness)
        assertTrue("- PREVIOUS" in retainedFreshness)
        assertTrue("- PREVIOUS" in previousFreshness)
        assertFalse("- CURRENT" in previousFreshness)
    }

    @Test
    fun `runtime status schema labels retained publication as previous`() {
        val yaml = OpenApiDocument.renderYaml()
        val response = yaml.component("RuntimeStatusResponse")
        val retained = yaml.component("RetainedWorkspaceGenerationStatus")

        assertTrue("retainedWorkspaceGeneration:" in response)
        assertTrue("RetainedWorkspaceGenerationStatus" in response)
        assertTrue("NONE:" in retained)
        assertTrue("PREVIOUS:" in retained)
        assertTrue("propertyName: type" in retained)
    }

    private fun String.component(name: String): String {
        val marker = "    $name:"
        val body = substringAfter(marker, missingDelimiterValue = "")
        require(body.isNotEmpty()) { "OpenAPI component $name was not found" }
        val next = Regex("\\n {4}[A-Za-z0-9_.]+:").find(body)?.range?.first
        return next?.let { index -> body.substring(0, index) } ?: body
    }
}
