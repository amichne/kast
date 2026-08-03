package io.github.amichne.kast.api.contract.transformation.admission.repository

import java.util.Collections

class ResolvedBuildArtifact private constructor(
    val componentIdentity: BuildComponentIdentity,
    val selectedVariantIdentity: SelectedBuildVariantIdentity,
    val contentKind: ArtifactContentKind,
    val contentDigest: ArtifactContentDigest,
) {
    internal companion object {
        fun create(
            componentIdentity: BuildComponentIdentity,
            selectedVariantIdentity: SelectedBuildVariantIdentity,
            contentKind: ArtifactContentKind,
            contentDigest: ArtifactContentDigest,
        ): ResolvedBuildArtifact = ResolvedBuildArtifact(
            componentIdentity,
            selectedVariantIdentity,
            contentKind,
            contentDigest,
        )
    }
}

class CompilerToolchain private constructor(
    val targetPlatform: CompilerTargetPlatform,
    val version: CompilerToolchainVersion,
    val vendor: CompilerToolchainVendor,
    val implementation: CompilerToolchainImplementation,
    val contentDigest: ArtifactContentDigest,
) {
    internal companion object {
        fun create(
            targetPlatform: CompilerTargetPlatform,
            version: CompilerToolchainVersion,
            vendor: CompilerToolchainVendor,
            implementation: CompilerToolchainImplementation,
            contentDigest: ArtifactContentDigest,
        ): CompilerToolchain = CompilerToolchain(
            targetPlatform,
            version,
            vendor,
            implementation,
            contentDigest,
        )
    }
}

class CompilerPluginInvocation private constructor(
    val pluginId: CompilerPluginId,
    classpath: List<ResolvedBuildArtifact>,
    options: List<CompilerOptionToken>,
) {
    val classpath: List<ResolvedBuildArtifact> = immutableList(classpath)
    val options: List<CompilerOptionToken> = immutableList(options)

    init {
        require(this.classpath.isNotEmpty())
    }

    internal companion object {
        fun create(
            pluginId: CompilerPluginId,
            classpath: List<ResolvedBuildArtifact>,
            options: List<CompilerOptionToken>,
        ): CompilerPluginInvocation = CompilerPluginInvocation(pluginId, classpath, options)
    }
}

@JvmInline value class CompilerVersion private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = CompilerVersion(value) }
}

@JvmInline value class LanguageVersion private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = LanguageVersion(value) }
}

@JvmInline value class ApiVersion private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = ApiVersion(value) }
}

@JvmInline value class LanguageSettingName private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = LanguageSettingName(value) }
}

@JvmInline value class LanguageSettingValue private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = LanguageSettingValue(value) }
}

@JvmInline value class BuildComponentIdentity private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = BuildComponentIdentity(value) }
}

@JvmInline value class SelectedBuildVariantIdentity private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = SelectedBuildVariantIdentity(value) }
}

@JvmInline value class ArtifactContentDigest private constructor(val value: String) {
    init { require(value.matches(SHA_256_VALUE) && value == value.lowercase()) }
    internal companion object { fun fromValidated(value: String) = ArtifactContentDigest(value) }
}

@JvmInline value class CompilerTargetPlatform private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = CompilerTargetPlatform(value) }
}

@JvmInline value class CompilerToolchainVersion private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = CompilerToolchainVersion(value) }
}

@JvmInline value class CompilerToolchainVendor private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = CompilerToolchainVendor(value) }
}

@JvmInline value class CompilerToolchainImplementation private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = CompilerToolchainImplementation(value) }
}

@JvmInline value class CompilerOptionToken private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = CompilerOptionToken(value) }
}

@JvmInline value class CompilerPluginId private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = CompilerPluginId(value) }
}

@JvmInline value class SemanticConfigurationIdentity private constructor(val value: String) {
    init { require(value.matches(SHA_256_VALUE)) }
    internal companion object { fun fromValidated(value: String) = SemanticConfigurationIdentity(value) }
}

private val SHA_256_VALUE: Regex = Regex("[0-9a-f]{64}")

private fun <Value> immutableList(values: Collection<Value>): List<Value> =
    Collections.unmodifiableList(values.toList())
