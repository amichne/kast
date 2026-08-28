package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.SemanticRuntimeFailure
import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

internal sealed interface InstalledProtocolResourcesConstruction {
    data class Constructed(
        val resources: InstalledProtocolResources,
    ) : InstalledProtocolResourcesConstruction

    data class Rejected(
        val failure: InstalledCompositionFailure,
    ) : InstalledProtocolResourcesConstruction
}

/** Exact installed protocol bytes paired with their derived identities. */
internal data class InstalledProtocolResources(
    val operationRegistry: String,
    val wireSchema: String,
    val operationRegistryDigest: InstalledProtocolDigest,
    val wireSchemaDigest: InstalledProtocolDigest,
)

@JvmInline
internal value class InstalledProtocolDigest private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `installed protocol resource text -> InstalledProtocolDigest`.
         *
         * Establishes the lowercase SHA-256 identity of the exact installed resource bytes. The
         * raw digest string leaves only at the compatibility candidate boundary.
         */
        fun derive(raw: String): InstalledProtocolDigest = InstalledProtocolDigest(
            "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    raw.toByteArray(StandardCharsets.UTF_8),
                ),
            ),
        )
    }
}

internal fun compatibilityCandidate(
    productVersion: String,
    protocol: InstalledProtocolResources,
): IdeHostCompatibilityCandidate = IdeHostCompatibilityCandidate(
    SUPPORTED_IDE_BUILD,
    SUPPORTED_KOTLIN_PLUGIN_BUILD,
    productVersion,
    IDE_RUNTIME_PROTOCOL,
    protocol.operationRegistryDigest.value,
    protocol.wireSchemaDigest.value,
    IDE_CAPABILITIES,
)

/**
 * Proof transition: `KastPluginVersion + InstalledProtocolResources ->
 * Refinement<SemanticRuntimeId, SemanticRuntimeFailure>`.
 *
 * Establishes an opaque exact identity for the IDE-hosted compatibility tuple without reading or
 * naming an archive. The closed failure remains [SemanticRuntimeFailure]; raw identity material
 * is confined to this endpoint-construction boundary.
 */
internal fun installedHostedRuntimeId(
    productVersion: String,
    protocol: InstalledProtocolResources,
): Refinement<SemanticRuntimeId, SemanticRuntimeFailure> {
    val candidate = compatibilityCandidate(productVersion, protocol)
    val material = listOf(
        candidate.ideBuild,
        candidate.kotlinPluginBuild,
        candidate.kastPluginVersion,
        candidate.runtimeProtocolIdentity,
        candidate.operationRegistryDigest,
        candidate.wireSchemaDigest,
        candidate.capabilities.joinToString("\u0000"),
    ).joinToString("\n")
    val digest = "sha256:" + HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(material.toByteArray(StandardCharsets.UTF_8)),
    )
    return SemanticRuntimeId.parse(digest)
}
