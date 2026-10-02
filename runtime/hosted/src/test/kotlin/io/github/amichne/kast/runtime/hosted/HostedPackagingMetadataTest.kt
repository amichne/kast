package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.HostedContractDocument
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.protocol.wire.CanonicalHostedContract
import java.util.Properties
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class HostedPackagingMetadataTest {
    @Test
    fun `host package retains semantic model admission metadata alongside the complete exchange contract`() {
        val properties = Properties()
        checkNotNull(javaClass.getResourceAsStream("/kast-hosted-query.properties")).use(properties::load)
        val candidate =
            IdeHostCompatibilityCandidate(
                properties.getProperty("ideBuild"),
                properties.getProperty("kotlinBuild"),
                properties.getProperty("version"),
                "kast.ide-hosted.runtime.v1",
                properties.getProperty("registryDigest"),
                properties.getProperty("schemaDigest"),
                emptyList(),
            )
        assertInstanceOf(Refinement.Refined::class.java, IdeHostCompatibilityPolicy.define(candidate))
        val provided =
            Json.decodeFromString(
                HostedContractDocument.serializer(),
                checkNotNull(javaClass.getResource("/hosted-contract.json")).readText(),
            )
        assertEquals(CanonicalHostedContract.document, provided)
    }
}
