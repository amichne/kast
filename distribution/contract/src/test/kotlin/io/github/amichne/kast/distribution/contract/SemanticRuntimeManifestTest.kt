package io.github.amichne.kast.distribution.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class SemanticRuntimeManifestTest {
    @Test
    fun `canonical manifest admits every runtime identity invariant`() {
        val admitted = SemanticRuntimeManifest.admit(VALID_MANIFEST)

        val manifest = assertInstanceOf(
            SemanticRuntimeManifestAdmission.Admitted::class.java,
            admitted,
        ).manifest
        assertEquals(RUNTIME_ID, manifest.runtimeId.value)
        assertEquals(VALID_MANIFEST, manifest.canonicalJson.value)
        assertEquals("0.24.2", manifest.productVersion.value)
        assertEquals("261.25134.95", manifest.ideaBuild.value)
        assertEquals("2.4.10", manifest.kotlinPluginBuild.value)
        assertEquals("kast-wire-v1", manifest.wireSchemaId.value)
        assertEquals(123, manifest.archive.size.bytes)
        assertEquals("kast-indexer", manifest.layout.executable.value)
    }

    @Test
    fun `manifest identity mismatch is a closed rejection`() {
        val admitted = SemanticRuntimeManifest.admit(
            VALID_MANIFEST.replace(RUNTIME_ID, "sha256:${"0".repeat(64)}"),
        )

        assertEquals(
            SemanticRuntimeManifestAdmission.Rejected(SemanticRuntimeFailure.MANIFEST_INVALID),
            admitted,
        )
    }

    @Test
    fun `primitive compatibility and size fields cannot survive admission`() {
        val invalidDocuments = listOf(
            VALID_MANIFEST.replace("\"productVersion\":\"0.24.2\"", "\"productVersion\":\"\""),
            VALID_MANIFEST.replace("\"wireSchemaId\":\"kast-wire-v1\"", "\"wireSchemaId\":\"bad schema\""),
            VALID_MANIFEST.replace("\"bytes\":123", "\"bytes\":0"),
        )

        invalidDocuments.forEach { raw ->
            assertEquals(
                SemanticRuntimeManifestAdmission.Rejected(
                    SemanticRuntimeFailure.MANIFEST_INVALID,
                ),
                SemanticRuntimeManifest.admit(raw),
            )
        }
    }

    @Test
    fun `runtime source alternatives fail closed`() {
        assertInstanceOf(
            SemanticRuntimeSourceSelection.Managed::class.java,
            SemanticRuntimeSource.select(null),
        )
        assertInstanceOf(
            SemanticRuntimeSourceSelection.Preseeded::class.java,
            SemanticRuntimeSource.select("/tmp/runtime.zip"),
        )
        assertEquals(
            SemanticRuntimeSourceSelection.Rejected(SemanticRuntimeFailure.SOURCE_INVALID),
            SemanticRuntimeSource.select("relative/runtime.zip"),
        )
    }

    private companion object {
        const val RUNTIME_ID =
            "sha256:6cfad2c4d851942791feb6272b8d5149e9630d9275b8a317e0d3a84bf2ef2986"
        val VALID_MANIFEST =
            "{\"schemaVersion\":1,\"runtimeId\":\"$RUNTIME_ID\",\"productVersion\":\"0.24.2\",\"platform\":\"macos\",\"architecture\":\"aarch64\",\"ideaBuild\":\"261.25134.95\",\"kotlinPluginBuild\":\"2.4.10\",\"kastPluginSha256\":\"sha256:${"1".repeat(64)}\",\"wireSchemaId\":\"kast-wire-v1\",\"archive\":{\"fileName\":\"kast-semantic-runtime.zip\",\"url\":\"https://example.invalid/kast-semantic-runtime.zip\",\"sha256\":\"sha256:${"2".repeat(64)}\",\"bytes\":123},\"layout\":{\"executable\":\"kast-indexer\",\"requiredEntries\":[\"kast-indexer\",\"runtime-libs/\",\"idea-home/product-info.json\",\"idea-home/plugins/kast-indexer/\"],\"executableEntries\":[\"kast-indexer\"]}}"
    }
}
