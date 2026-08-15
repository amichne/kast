package io.github.amichne.kast.api.docs

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MutationProofOpenApiContractTest {
    @Test
    fun `exact mutation proof fields retain complete discriminated schemas`() {
        val yaml = OpenApiDocument.renderYaml()

        assertPropertyRef(yaml, "ExactRenameProof", "evidence", "RelationshipResultEvidence.Complete")
        assertPropertyRef(yaml, "ExactReplacementProof", "evidence", "ReplacementOutboundEvidence.Complete")
        assertPropertyRef(
            yaml,
            "MutationPostconditionEvidence.Rename",
            "evidence",
            "RelationshipResultEvidence.Complete",
        )
        assertPropertyRef(
            yaml,
            "MutationPostconditionEvidence.Replacement",
            "outboundEvidence",
            "ReplacementOutboundEvidence.Complete",
        )
        assertPropertyRef(yaml, "ReplacementOutboundEvidence.Complete", "cardinality", "EXACT")

        assertVariant(yaml, "RelationshipResultEvidence.Complete", "COMPLETE")
        assertVariant(yaml, "ReplacementOutboundEvidence.Complete", "complete")
        assertVariant(yaml, "EXACT", "EXACT")
    }

    private fun assertPropertyRef(
        yaml: String,
        component: String,
        property: String,
        expectedTarget: String,
    ) {
        val schema = yaml.componentSchema(component)
        val propertyTail = schema.substringAfter("        $property:", missingDelimiterValue = "")
        val nextProperty = Regex("\n {8}[A-Za-z0-9_.]+:").find(propertyTail)?.range?.first
        val propertySection = nextProperty?.let { propertyTail.substring(0, it) } ?: propertyTail
        assertTrue(
            propertySection.contains("#/components/schemas/$expectedTarget"),
            "$component.$property must reference $expectedTarget:\n$schema",
        )
    }

    private fun assertVariant(yaml: String, component: String, type: String) {
        val schema = yaml.componentSchema(component)
        assertTrue(schema.contains("        type:"), "$component must define the type property")
        assertTrue(schema.contains("          const: $type"), "$component must fix type to $type")
        assertTrue(schema.contains("        - type"), "$component must require the type property")
    }

    private fun String.componentSchema(name: String): String {
        val start = "\n    $name:"
        val afterStart = substringAfter(start, missingDelimiterValue = "")
        require(afterStart.isNotEmpty()) { "OpenAPI component $name was not found" }
        val nextComponent = Regex("\n {4}[A-Za-z0-9_.]+:").find(afterStart)?.range?.first
        return nextComponent?.let { index -> afterStart.substring(0, index) } ?: afterStart
    }

}
