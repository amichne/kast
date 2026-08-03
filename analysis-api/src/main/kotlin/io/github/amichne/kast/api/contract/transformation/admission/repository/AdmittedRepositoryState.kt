package io.github.amichne.kast.api.contract.transformation.admission.repository

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections

class AdmittedRepositoryState private constructor(
    val canonicalRoot: CanonicalRepositoryRoot,
    val sourceState: ExactSourceState,
    val semanticConfiguration: CoherentSemanticConfiguration,
    compilationUnits: List<AdmittedCompilationUnit>,
    val identity: RepositoryStateIdentity,
) {
    val compilationUnits: List<AdmittedCompilationUnit> = immutableList(compilationUnits)

    init {
        require(this.compilationUnits.isNotEmpty())
        require(this.compilationUnits.map { unit -> unit.id }.distinct().size == this.compilationUnits.size)
    }

    internal companion object {
        fun create(
            canonicalRoot: CanonicalRepositoryRoot,
            sourceState: ExactSourceState,
            semanticConfiguration: CoherentSemanticConfiguration,
            compilationUnits: List<AdmittedCompilationUnit>,
        ): AdmittedRepositoryState {
            val immutableUnits = immutableList(compilationUnits.sortedBy { unit -> unit.id.value })
            require(
                immutableUnits.any { unit ->
                    unit.semanticConfiguration.identity == semanticConfiguration.identity
                },
            )
            return AdmittedRepositoryState(
                canonicalRoot = canonicalRoot,
                sourceState = sourceState,
                semanticConfiguration = semanticConfiguration,
                compilationUnits = immutableUnits,
                identity = AdmissionIdentity.repository(canonicalRoot, sourceState, immutableUnits),
            )
        }
    }
}

class CoherentSemanticConfiguration private constructor(
    val compilerVersion: CompilerVersion,
    val languageVersion: LanguageVersion,
    val apiVersion: ApiVersion,
    val variantName: AdmittedVariantName,
    languageSettings: Map<LanguageSettingName, LanguageSettingValue>,
    val compilerImplementation: ResolvedBuildArtifact,
    val toolchain: CompilerToolchain,
    compilerOptions: List<CompilerOptionToken>,
    resolvedDependencies: List<ResolvedBuildArtifact>,
    compilerPlugins: List<CompilerPluginInvocation>,
    val identity: SemanticConfigurationIdentity,
) {
    val languageSettings: Map<LanguageSettingName, LanguageSettingValue> = immutableMap(languageSettings)
    val compilerOptions: List<CompilerOptionToken> = immutableList(compilerOptions)
    val resolvedDependencies: List<ResolvedBuildArtifact> = immutableList(resolvedDependencies)
    val compilerPlugins: List<CompilerPluginInvocation> = immutableList(compilerPlugins)

    internal companion object {
        fun create(
            compilerVersion: CompilerVersion,
            languageVersion: LanguageVersion,
            apiVersion: ApiVersion,
            variantName: AdmittedVariantName,
            languageSettings: Map<LanguageSettingName, LanguageSettingValue>,
            compilerImplementation: ResolvedBuildArtifact,
            toolchain: CompilerToolchain,
            compilerOptions: List<CompilerOptionToken>,
            resolvedDependencies: List<ResolvedBuildArtifact>,
            compilerPlugins: List<CompilerPluginInvocation>,
        ): CoherentSemanticConfiguration {
            val immutableSettings = immutableMap(
                languageSettings.entries
                    .sortedBy { (key, _) -> key.value }
                    .associate { (key, value) -> key to value },
            )
            val immutableOptions = immutableList(compilerOptions)
            val immutableDependencies = immutableList(resolvedDependencies)
            val immutablePlugins = immutableList(compilerPlugins)
            return CoherentSemanticConfiguration(
                compilerVersion = compilerVersion,
                languageVersion = languageVersion,
                apiVersion = apiVersion,
                variantName = variantName,
                languageSettings = immutableSettings,
                compilerImplementation = compilerImplementation,
                toolchain = toolchain,
                compilerOptions = immutableOptions,
                resolvedDependencies = immutableDependencies,
                compilerPlugins = immutablePlugins,
                identity = AdmissionIdentity.semanticConfiguration(
                    compilerVersion = compilerVersion,
                    languageVersion = languageVersion,
                    apiVersion = apiVersion,
                    variantName = variantName,
                    languageSettings = immutableSettings,
                    compilerImplementation = compilerImplementation,
                    toolchain = toolchain,
                    compilerOptions = immutableOptions,
                    resolvedDependencies = immutableDependencies,
                    compilerPlugins = immutablePlugins,
                ),
            )
        }
    }
}

class EstablishedResourceBounds private constructor(
    val timeLimitMillis: AnalysisTimeLimitMillis,
    val memoryLimitBytes: AnalysisMemoryLimitBytes,
    val traversalDepthLimit: TraversalDepthLimit,
    val pathLimit: AnalysisPathLimit,
    val resultLimit: AnalysisResultLimit,
) {
    internal companion object {
        fun create(
            timeLimitMillis: AnalysisTimeLimitMillis,
            memoryLimitBytes: AnalysisMemoryLimitBytes,
            traversalDepthLimit: TraversalDepthLimit,
            pathLimit: AnalysisPathLimit,
            resultLimit: AnalysisResultLimit,
        ): EstablishedResourceBounds = EstablishedResourceBounds(
            timeLimitMillis = timeLimitMillis,
            memoryLimitBytes = memoryLimitBytes,
            traversalDepthLimit = traversalDepthLimit,
            pathLimit = pathLimit,
            resultLimit = resultLimit,
        )
    }
}

@JvmInline value class AnalysisTimeLimitMillis private constructor(val value: Long) {
    init { require(value > 0) }
    internal companion object { fun fromValidated(value: Long) = AnalysisTimeLimitMillis(value) }
}

@JvmInline value class AnalysisMemoryLimitBytes private constructor(val value: Long) {
    init { require(value > 0) }
    internal companion object { fun fromValidated(value: Long) = AnalysisMemoryLimitBytes(value) }
}

@JvmInline value class TraversalDepthLimit private constructor(val value: Int) {
    init { require(value > 0) }
    internal companion object { fun fromValidated(value: Int) = TraversalDepthLimit(value) }
}

@JvmInline value class AnalysisPathLimit private constructor(val value: Int) {
    init { require(value > 0) }
    internal companion object { fun fromValidated(value: Int) = AnalysisPathLimit(value) }
}

@JvmInline value class AnalysisResultLimit private constructor(val value: Int) {
    init { require(value > 0) }
    internal companion object { fun fromValidated(value: Int) = AnalysisResultLimit(value) }
}

private object AdmissionIdentity {
    fun semanticConfiguration(
        compilerVersion: CompilerVersion,
        languageVersion: LanguageVersion,
        apiVersion: ApiVersion,
        variantName: AdmittedVariantName,
        languageSettings: Map<LanguageSettingName, LanguageSettingValue>,
        compilerImplementation: ResolvedBuildArtifact,
        toolchain: CompilerToolchain,
        compilerOptions: List<CompilerOptionToken>,
        resolvedDependencies: List<ResolvedBuildArtifact>,
        compilerPlugins: List<CompilerPluginInvocation>,
    ): SemanticConfigurationIdentity {
        val evidence = buildList<List<String>> {
            add(listOf("compiler", compilerVersion.value))
            add(listOf("language", languageVersion.value))
            add(listOf("api", apiVersion.value))
            add(listOf("variant", variantName.value))
            languageSettings.entries.sortedBy { (key, _) -> key.value }
                .forEach { (key, value) -> add(listOf("setting", key.value, value.value)) }
            add(
                listOf(
                    "compilerImplementation",
                    compilerImplementation.componentIdentity.value,
                    compilerImplementation.selectedVariantIdentity.value,
                    compilerImplementation.contentKind.name,
                    compilerImplementation.contentDigest.value,
                ),
            )
            add(
                listOf(
                    "toolchain",
                    toolchain.targetPlatform.value,
                    toolchain.version.value,
                    toolchain.vendor.value,
                    toolchain.implementation.value,
                    toolchain.contentDigest.value,
                ),
            )
            compilerOptions.forEachIndexed { index, option ->
                add(listOf("compilerOption", index.toString(), option.value))
            }
            resolvedDependencies.forEachIndexed { index, dependency ->
                add(
                    listOf(
                        "dependency",
                        index.toString(),
                        dependency.componentIdentity.value,
                        dependency.selectedVariantIdentity.value,
                        dependency.contentKind.name,
                        dependency.contentDigest.value,
                    ),
                )
            }
            compilerPlugins.forEachIndexed { pluginIndex, plugin ->
                add(listOf("plugin", pluginIndex.toString(), plugin.pluginId.value))
                plugin.classpath.forEachIndexed { artifactIndex, artifact ->
                    add(
                        listOf(
                            "pluginArtifact",
                            pluginIndex.toString(),
                            artifactIndex.toString(),
                            artifact.componentIdentity.value,
                            artifact.selectedVariantIdentity.value,
                            artifact.contentKind.name,
                            artifact.contentDigest.value,
                        ),
                    )
                }
                plugin.options.forEachIndexed { optionIndex, option ->
                    add(
                        listOf(
                            "pluginOption",
                            pluginIndex.toString(),
                            optionIndex.toString(),
                            option.value,
                        ),
                    )
                }
            }
        }
        return SemanticConfigurationIdentity.fromValidated(digest(evidence))
    }

    fun repository(
        root: CanonicalRepositoryRoot,
        sourceState: ExactSourceState,
        units: List<AdmittedCompilationUnit>,
    ): RepositoryStateIdentity {
        val evidence = buildList<List<String>> {
            add(listOf("root", root.value))
            add(listOf("revision", sourceState.revision.value))
            sourceState.inputs.sortedBy { input -> input.path.value }.forEach { input ->
                add(
                    listOf(
                        "source",
                        input.path.value,
                        input.kind.name,
                        input.presence.name,
                        input.disposition.name,
                        input.contentDigest?.value.orEmpty(),
                    ),
                )
            }
            units.sortedBy { unit -> unit.id.value }.forEach { unit ->
                add(listOf("unit", unit.id.value))
                add(listOf("moduleIdentity", unit.moduleIdentity.value))
                add(listOf("module", unit.moduleName.value))
                add(listOf("sourceSet", unit.sourceSetName.value))
                add(listOf("variant", unit.variantName.value))
                unit.sourceRoots.sortedBy(RepositoryRelativePath::value)
                    .forEach { sourceRoot -> add(listOf("sourceRoot", sourceRoot.value)) }
                unit.generatedSourceRoots.sortedBy(RepositoryRelativePath::value)
                    .forEach { sourceRoot -> add(listOf("generatedSourceRoot", sourceRoot.value)) }
                unit.declarations.sortedWith(
                    compareBy({ declaration -> declaration.name.value }, { declaration -> declaration.path.value }),
                ).forEach { declaration ->
                    add(listOf("declaration", declaration.name.value, declaration.path.value))
                }
                unit.families.sortedBy(SemanticFamilyName::value)
                    .forEach { family -> add(listOf("family", family.value)) }
                unit.sourceSetRelationships.sortedWith(
                    compareBy(
                        { relationship -> relationship.kind.name },
                        { relationship -> relationship.targetCompilationUnitId.value },
                    ),
                ).forEach { relationship ->
                    add(
                        listOf(
                            "sourceSetRelationship",
                            relationship.kind.name,
                            relationship.targetCompilationUnitId.value,
                        ),
                    )
                }
                add(listOf("configuration", unit.semanticConfiguration.identity.value))
            }
        }
        return RepositoryStateIdentity.fromValidated(digest(evidence))
    }

    private fun digest(records: List<List<String>>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.updateInt(records.size)
        records.forEach { record ->
            digest.updateInt(record.size)
            record.forEach { field ->
                val bytes = field.toByteArray(StandardCharsets.UTF_8)
                digest.updateInt(bytes.size)
                digest.update(bytes)
            }
        }
        return digest.digest()
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun MessageDigest.updateInt(value: Int) {
        update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array())
    }
}

private fun <Value> immutableList(values: Collection<Value>): List<Value> =
    Collections.unmodifiableList(values.toList())

private fun <Key, Value> immutableMap(values: Map<Key, Value>): Map<Key, Value> =
    Collections.unmodifiableMap(LinkedHashMap(values))
