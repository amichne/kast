package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.OperationId
import io.github.amichne.kast.kernel.Refinement

private val IDE_BUILD_FORMAT = Regex("[0-9]{3}\\.[0-9]+\\.[0-9]+")
private val KOTLIN_PLUGIN_BUILD_FORMAT = Regex("[0-9]{3}\\.[0-9]+\\.[0-9]+-IJ")
private val KAST_PLUGIN_VERSION_FORMAT =
    Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9]+-g[0-9a-f]{7,40})?")
private val RUNTIME_PROTOCOL_FORMAT =
    Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*\\.v[1-9][0-9]*")
private val SHA256_FORMAT = Regex("sha256:[0-9a-f]{64}")

enum class IdeHostCompatibilityField {
    IDE_BUILD,
    KOTLIN_PLUGIN_BUILD,
    KAST_PLUGIN_VERSION,
    RUNTIME_PROTOCOL_IDENTITY,
    OPERATION_REGISTRY_DIGEST,
    WIRE_SCHEMA_DIGEST,
    CAPABILITIES,
}

enum class IdeHostCompatibilitySyntaxFailure {
    BLANK,
    TOO_LONG,
    INVALID_FORMAT,
}

sealed interface IdeHostCompatibilityFailure {
    data class Malformed(
        val field: IdeHostCompatibilityField,
        val syntax: IdeHostCompatibilitySyntaxFailure,
    ) : IdeHostCompatibilityFailure

    data class Mismatch(
        val mismatch: IdeHostCompatibilityMismatch,
    ) : IdeHostCompatibilityFailure

    data class UnknownCapability(
        val operationId: OperationId,
    ) : IdeHostCompatibilityFailure

    data class UnsupportedCapability(
        val operation: CanonicalOperation,
    ) : IdeHostCompatibilityFailure

    data class DuplicateCapability(
        val capability: IdeHostCapability,
    ) : IdeHostCompatibilityFailure
}

/** Exact refined values on both sides of one compatibility mismatch. */
sealed interface IdeHostCompatibilityMismatch {
    val field: IdeHostCompatibilityField

    data class IdeBuild internal constructor(
        val expected: IdeBuildIdentity,
        val observed: IdeBuildIdentity,
    ) : IdeHostCompatibilityMismatch {
        override val field: IdeHostCompatibilityField = IdeHostCompatibilityField.IDE_BUILD
    }

    data class KotlinPluginBuild internal constructor(
        val expected: KotlinPluginBuildIdentity,
        val observed: KotlinPluginBuildIdentity,
    ) : IdeHostCompatibilityMismatch {
        override val field: IdeHostCompatibilityField =
            IdeHostCompatibilityField.KOTLIN_PLUGIN_BUILD
    }

    data class KastPluginVersion internal constructor(
        val expected: io.github.amichne.kast.protocol.contract.KastPluginVersion,
        val observed: io.github.amichne.kast.protocol.contract.KastPluginVersion,
    ) : IdeHostCompatibilityMismatch {
        override val field: IdeHostCompatibilityField =
            IdeHostCompatibilityField.KAST_PLUGIN_VERSION
    }

    data class RuntimeProtocol internal constructor(
        val expected: RuntimeProtocolIdentity,
        val observed: RuntimeProtocolIdentity,
    ) : IdeHostCompatibilityMismatch {
        override val field: IdeHostCompatibilityField =
            IdeHostCompatibilityField.RUNTIME_PROTOCOL_IDENTITY
    }

    data class OperationRegistry internal constructor(
        val expected: OperationRegistryDigest,
        val observed: OperationRegistryDigest,
    ) : IdeHostCompatibilityMismatch {
        override val field: IdeHostCompatibilityField =
            IdeHostCompatibilityField.OPERATION_REGISTRY_DIGEST
    }

    data class WireSchema internal constructor(
        val expected: WireSchemaDigest,
        val observed: WireSchemaDigest,
    ) : IdeHostCompatibilityMismatch {
        override val field: IdeHostCompatibilityField =
            IdeHostCompatibilityField.WIRE_SCHEMA_DIGEST
    }

    data class Capabilities internal constructor(
        val expected: IdeHostCapabilitySet,
        val observed: IdeHostCapabilitySet,
    ) : IdeHostCompatibilityMismatch {
        override val field: IdeHostCompatibilityField = IdeHostCompatibilityField.CAPABILITIES
    }
}

@JvmInline
value class IdeBuildIdentity private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<IdeBuildIdentity, IdeHostCompatibilityFailure>`.
         *
         * Establishes an exact three-part numeric IntelliJ build identity. Raw text may be
         * extracted only at the endpoint metadata or generated-report boundary.
         */
        fun parse(raw: String): Refinement<IdeBuildIdentity, IdeHostCompatibilityFailure> =
            refineIdentity(raw, IdeHostCompatibilityField.IDE_BUILD, IDE_BUILD_FORMAT, ::IdeBuildIdentity)
    }
}

@JvmInline
value class KotlinPluginBuildIdentity private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<KotlinPluginBuildIdentity, IdeHostCompatibilityFailure>`.
         *
         * Establishes an exact IntelliJ-bundled Kotlin plugin build identity. Raw text may be
         * extracted only at the endpoint metadata or generated-report boundary.
         */
        fun parse(raw: String): Refinement<KotlinPluginBuildIdentity, IdeHostCompatibilityFailure> =
            refineIdentity(
                raw,
                IdeHostCompatibilityField.KOTLIN_PLUGIN_BUILD,
                KOTLIN_PLUGIN_BUILD_FORMAT,
                ::KotlinPluginBuildIdentity,
            )
    }
}

@JvmInline
value class KastPluginVersion private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<KastPluginVersion, IdeHostCompatibilityFailure>`.
         *
         * Establishes a release or Git-describe Kast plugin version. Raw text may be extracted
         * only at the endpoint metadata or generated-report boundary.
         */
        fun parse(raw: String): Refinement<KastPluginVersion, IdeHostCompatibilityFailure> =
            refineIdentity(
                raw,
                IdeHostCompatibilityField.KAST_PLUGIN_VERSION,
                KAST_PLUGIN_VERSION_FORMAT,
                ::KastPluginVersion,
            )
    }
}

@JvmInline
value class RuntimeProtocolIdentity private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<RuntimeProtocolIdentity, IdeHostCompatibilityFailure>`.
         *
         * Establishes a versioned permanent IDE-hosted runtime protocol identity. Raw text may be
         * extracted only at the endpoint metadata or generated-report boundary.
         */
        fun parse(raw: String): Refinement<RuntimeProtocolIdentity, IdeHostCompatibilityFailure> =
            refineIdentity(
                raw,
                IdeHostCompatibilityField.RUNTIME_PROTOCOL_IDENTITY,
                RUNTIME_PROTOCOL_FORMAT,
                ::RuntimeProtocolIdentity,
            )
    }
}

@JvmInline
value class OperationRegistryDigest private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<OperationRegistryDigest, IdeHostCompatibilityFailure>`.
         *
         * Establishes a lowercase SHA-256 identity for exact operation-registry bytes. Raw text
         * may be extracted only at the endpoint metadata or generated-report boundary.
         */
        fun parse(raw: String): Refinement<OperationRegistryDigest, IdeHostCompatibilityFailure> =
            refineIdentity(
                raw,
                IdeHostCompatibilityField.OPERATION_REGISTRY_DIGEST,
                SHA256_FORMAT,
                ::OperationRegistryDigest,
            )
    }
}

@JvmInline
value class WireSchemaDigest private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<WireSchemaDigest, IdeHostCompatibilityFailure>`.
         *
         * Establishes a lowercase SHA-256 identity for exact wire-schema bytes. Raw text may be
         * extracted only at the endpoint metadata or generated-report boundary.
         */
        fun parse(raw: String): Refinement<WireSchemaDigest, IdeHostCompatibilityFailure> =
            refineIdentity(
                raw,
                IdeHostCompatibilityField.WIRE_SCHEMA_DIGEST,
                SHA256_FORMAT,
                ::WireSchemaDigest,
            )
    }
}

data class IdeHostCompatibilityCandidate(
    val ideBuild: String,
    val kotlinPluginBuild: String,
    val kastPluginVersion: String,
    val runtimeProtocolIdentity: String,
    val operationRegistryDigest: String,
    val wireSchemaDigest: String,
    val capabilities: List<String>,
)

class AdmittedIdeHostCompatibility private constructor(
    val ideBuild: IdeBuildIdentity,
    val kotlinPluginBuild: KotlinPluginBuildIdentity,
    val kastPluginVersion: KastPluginVersion,
    val runtimeProtocolIdentity: RuntimeProtocolIdentity,
    val operationRegistryDigest: OperationRegistryDigest,
    val wireSchemaDigest: WireSchemaDigest,
    val capabilities: IdeHostCapabilitySet,
) {
    /**
     * Proof transition: `AdmittedIdeHostCompatibility + AdmittedIdeHostCompatibility ->
     * IdeHostCompatibilityComparison`.
     *
     * Establishes exact tuple equality or the first finite mismatch with both refined values.
     * Each capability set is already valid; differing membership or order remains an exact typed
     * mismatch. Raw values may be extracted only at endpoint or generated-report boundaries.
     */
    internal fun compareAgainst(
        expected: AdmittedIdeHostCompatibility,
    ): IdeHostCompatibilityComparison =
        when {
            ideBuild != expected.ideBuild -> mismatch(
                IdeHostCompatibilityMismatch.IdeBuild(expected.ideBuild, ideBuild),
            )
            kotlinPluginBuild != expected.kotlinPluginBuild -> mismatch(
                IdeHostCompatibilityMismatch.KotlinPluginBuild(
                    expected.kotlinPluginBuild,
                    kotlinPluginBuild,
                ),
            )
            kastPluginVersion != expected.kastPluginVersion -> mismatch(
                IdeHostCompatibilityMismatch.KastPluginVersion(
                    expected.kastPluginVersion,
                    kastPluginVersion,
                ),
            )
            runtimeProtocolIdentity != expected.runtimeProtocolIdentity -> mismatch(
                IdeHostCompatibilityMismatch.RuntimeProtocol(
                    expected.runtimeProtocolIdentity,
                    runtimeProtocolIdentity,
                ),
            )
            operationRegistryDigest != expected.operationRegistryDigest -> mismatch(
                IdeHostCompatibilityMismatch.OperationRegistry(
                    expected.operationRegistryDigest,
                    operationRegistryDigest,
                ),
            )
            wireSchemaDigest != expected.wireSchemaDigest -> mismatch(
                IdeHostCompatibilityMismatch.WireSchema(
                    expected.wireSchemaDigest,
                    wireSchemaDigest,
                ),
            )
            capabilities != expected.capabilities -> mismatch(
                IdeHostCompatibilityMismatch.Capabilities(expected.capabilities, capabilities),
            )
            else -> IdeHostCompatibilityComparison.Exact
        }

    companion object {
        /**
         * Proof transition: `IdeHostCompatibilityCandidate -> Refinement<AdmittedIdeHostCompatibility, IdeHostCompatibilityFailure>`.
         *
         * Establishes all six refined identities and the exact capability set. Every expected
         * syntax or capability failure remains closed data. Raw extraction is permitted only at
         * endpoint or generated-report boundaries.
         */
        internal fun parse(
            candidate: IdeHostCompatibilityCandidate,
        ): Refinement<AdmittedIdeHostCompatibility, IdeHostCompatibilityFailure> {
            val ideBuild = refinedOrReject(IdeBuildIdentity.parse(candidate.ideBuild)) { return it }
            val kotlinBuild = refinedOrReject(
                KotlinPluginBuildIdentity.parse(candidate.kotlinPluginBuild),
            ) { return it }
            val pluginVersion = refinedOrReject(
                KastPluginVersion.parse(candidate.kastPluginVersion),
            ) { return it }
            val runtimeProtocol = refinedOrReject(
                RuntimeProtocolIdentity.parse(candidate.runtimeProtocolIdentity),
            ) { return it }
            val registryDigest = refinedOrReject(
                OperationRegistryDigest.parse(candidate.operationRegistryDigest),
            ) { return it }
            val wireDigest = refinedOrReject(
                WireSchemaDigest.parse(candidate.wireSchemaDigest),
            ) { return it }
            val capabilities = refinedOrReject(
                IdeHostCapabilitySet.parse(candidate.capabilities),
            ) { return it }
            return Refinement.Refined(
                AdmittedIdeHostCompatibility(
                    ideBuild,
                    kotlinBuild,
                    pluginVersion,
                    runtimeProtocol,
                    registryDigest,
                    wireDigest,
                    capabilities,
                ),
            )
        }
    }
}

sealed interface IdeHostCompatibilityAdmission {
    data class Admitted(val compatibility: AdmittedIdeHostCompatibility) :
        IdeHostCompatibilityAdmission

    data class Rejected(val failure: IdeHostCompatibilityFailure) :
        IdeHostCompatibilityAdmission
}

class IdeHostCompatibilityPolicy private constructor(
    val supportedCompatibility: AdmittedIdeHostCompatibility,
) {
    companion object {
        /**
         * Proof transition: `IdeHostCompatibilityCandidate -> Refinement<IdeHostCompatibilityPolicy, IdeHostCompatibilityFailure>`.
         *
         * Establishes one fully refined supported tuple. Expected malformed policy input remains
         * finite [IdeHostCompatibilityFailure] data. Raw extraction is permitted only when the
         * build-report boundary projects the admitted policy.
         */
        fun define(
            supported: IdeHostCompatibilityCandidate,
        ): Refinement<IdeHostCompatibilityPolicy, IdeHostCompatibilityFailure> =
            when (val parsed = AdmittedIdeHostCompatibility.parse(supported)) {
                is Refinement.Refined -> Refinement.Refined(IdeHostCompatibilityPolicy(parsed.value))
                is Refinement.Rejected -> parsed
            }
    }

    /**
     * Proof transition: `IdeHostCompatibilityCandidate -> IdeHostCompatibilityAdmission`.
     *
     * Establishes exact equality with the one supported tuple and returns
     * [AdmittedIdeHostCompatibility]. Syntax, capability, and field mismatch failures remain
     * closed [IdeHostCompatibilityFailure] data. Raw extraction is permitted only at the endpoint
     * or generated-report boundary.
     */
    fun admit(candidate: IdeHostCompatibilityCandidate): IdeHostCompatibilityAdmission =
        when (val parsed = AdmittedIdeHostCompatibility.parse(candidate)) {
            is Refinement.Rejected -> IdeHostCompatibilityAdmission.Rejected(parsed.failure)
            is Refinement.Refined -> when (
                val comparison = parsed.value.compareAgainst(supportedCompatibility)
            ) {
                IdeHostCompatibilityComparison.Exact ->
                    IdeHostCompatibilityAdmission.Admitted(parsed.value)
                is IdeHostCompatibilityComparison.Mismatch ->
                    IdeHostCompatibilityAdmission.Rejected(
                        IdeHostCompatibilityFailure.Mismatch(comparison.mismatch),
                    )
            }
        }
}

internal sealed interface IdeHostCompatibilityComparison {
    data object Exact : IdeHostCompatibilityComparison

    data class Mismatch(
        val mismatch: IdeHostCompatibilityMismatch,
    ) : IdeHostCompatibilityComparison
}

private fun mismatch(
    mismatch: IdeHostCompatibilityMismatch,
): IdeHostCompatibilityComparison = IdeHostCompatibilityComparison.Mismatch(mismatch)

private fun malformed(
    field: IdeHostCompatibilityField,
    raw: String,
): IdeHostCompatibilityFailure.Malformed = IdeHostCompatibilityFailure.Malformed(
    field,
    if (raw.isBlank()) {
        IdeHostCompatibilitySyntaxFailure.BLANK
    } else {
        IdeHostCompatibilitySyntaxFailure.INVALID_FORMAT
    },
)

private inline fun <Strong> refineIdentity(
    raw: String,
    field: IdeHostCompatibilityField,
    format: Regex,
    construct: (String) -> Strong,
): Refinement<Strong, IdeHostCompatibilityFailure> = when {
    raw.isBlank() -> Refinement.Rejected(malformed(field, raw))
    !format.matches(raw) -> Refinement.Rejected(malformed(field, raw))
    else -> Refinement.Refined(construct(raw))
}

private inline fun <Strong> refinedOrReject(
    refinement: Refinement<Strong, IdeHostCompatibilityFailure>,
    rejected: (Refinement.Rejected<IdeHostCompatibilityFailure>) -> Nothing,
): Strong = when (refinement) {
    is Refinement.Refined -> refinement.value
    is Refinement.Rejected -> rejected(refinement)
}
