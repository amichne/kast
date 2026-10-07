package io.github.amichne.kast.topology.intellij

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.PathMacroManager
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.WorkspaceModuleIdentity
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.compilerRunner.ArgumentUtils
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinPluginLayoutService
import org.jetbrains.kotlin.idea.facet.KotlinFacet

internal class SemanticNativeConfiguration(
    private val project: Project,
    private val limits: ReadLimits,
    private val budget: DependencyCaptureBudget,
    private val files: SemanticNativeFiles,
) {
    fun arguments(module: Module, digest: SemanticInputDigest): SemanticCapture<K2JVMCompilerArguments> {
        val settings = KotlinFacet.get(module)?.configuration?.settings ?: return configurationUnavailable()
        if (settings.useProjectSettings || settings.isHmppEnabled) return configurationUnavailable()
        val arguments = settings.mergedCompilerArguments as? K2JVMCompilerArguments ?: return configurationUnavailable()
        when (
            val distribution = admitSemanticCompilerDistribution(arguments.kotlinHome, arguments.intellijPluginRoot)
        ) {
            is Refinement.Rejected -> return distribution
            is Refinement.Refined -> Unit
        }
        if (unmodeledSources(arguments)) return configurationUnavailable()
        if (!arguments.pluginOptions.isNullOrEmpty() || !arguments.pluginConfigurations.isNullOrEmpty())
            return captureRejected(SemanticDependencyCaptureFailure.COMPILER_PLUGIN_INPUTS_UNMODELED)
        val values =
            when (val encoded = encode(arguments)) {
                is Refinement.Refined -> encoded.value
                is Refinement.Rejected -> return encoded
            }
        val language = settings.languageLevel?.versionString ?: return configurationUnavailable()
        val api = settings.apiLevel?.versionString ?: return configurationUnavailable()
        digest.text("MODULE")
        digest.text(module.name)
        digest.text(settings.version.toString())
        digest.text(KotlinCompilerVersion.VERSION)
        digest.text(language)
        digest.text(api)
        digest.text(settings.isTestModule.toString())
        digest.text(settings.kind.name)
        digest.text(values.size.toString())
        values.forEach(digest::text)
        return Refinement.Refined(arguments)
    }

    private fun encode(arguments: K2JVMCompilerArguments): SemanticCapture<List<String>> {
        val values =
            try {
                ArgumentUtils.convertArgumentsToStringList(arguments)
            } catch (_: ReflectiveOperationException) {
                return configurationUnavailable()
            }
        if (
            values.size > limits[ReadLimitParameter.MODEL_CLASSPATH_ENTRIES_PER_MODULE].value ||
                values.any { it.length > limits[ReadLimitParameter.MODEL_CLASSPATH_URL_CHARACTERS].value }
        )
            return captureRejected(SemanticDependencyCaptureFailure.CAPACITY_EXCEEDED)
        return Refinement.Refined(values)
    }

    private fun unmodeledSources(arguments: K2JVMCompilerArguments): Boolean =
        arguments.multiPlatform ||
            arguments.script ||
            arguments.freeArgs.isNotEmpty() ||
            !arguments.klibLibraries.isNullOrBlank() ||
            !arguments.buildFile.isNullOrBlank() ||
            unmodeledSourceArrays(arguments)

    private fun unmodeledSourceArrays(arguments: K2JVMCompilerArguments): Boolean =
        listOf(
                arguments.commonSources,
                arguments.fragments,
                arguments.fragmentSources,
                arguments.fragmentDependencies,
                arguments.fragmentFriendDependencies,
                arguments.fragmentIncrementalClasspath,
                arguments.fragmentRefines,
                arguments.scriptTemplates,
                arguments.scriptResolverEnvironment,
            )
            .any { !it.isNullOrEmpty() }

    fun passivePlugins(
        identity: WorkspaceModuleIdentity,
        arguments: K2JVMCompilerArguments,
        compiler: SemanticInputDigest,
        classpath: SemanticInputDigest,
    ): SemanticCapture<Unit> {
        val paths = arguments.pluginClasspaths.orEmpty()
        if (paths.isNotEmpty())
            when (val admitted = pluginProviderPolicy(compiler)) {
                is Refinement.Rejected -> return admitted
                is Refinement.Refined -> Unit
            }
        if (paths.size > limits[ReadLimitParameter.MODEL_CLASSPATH_ENTRIES_PER_MODULE].value)
            return captureRejected(SemanticDependencyCaptureFailure.CAPACITY_EXCEEDED)
        classpath.text("PASSIVE_PLUGIN_CLASSPATH")
        classpath.text(identity.value)
        classpath.text(paths.size.toString())
        for (path in paths) when (val observed = passivePlugin(path, classpath)) {
            is Refinement.Rejected -> return observed
            is Refinement.Refined -> Unit
        }
        return Refinement.Refined(Unit)
    }

    private fun pluginProviderPolicy(digest: SemanticInputDigest): SemanticCapture<Unit> {
        if (
            KotlinCompilerVersion.VERSION != QUALIFIED_KOTLIN_COMPILER ||
                ApplicationInfo.getInstance().build.asString() != QUALIFIED_IDEA_BUILD
        )
            return captureRejected(SemanticDependencyCaptureFailure.COMPILER_PLUGIN_INPUTS_UNMODELED)
        val providers =
            ExtensionPointName.create<Any>("org.jetbrains.kotlin.bundledFirCompilerPluginProvider").extensionList.map {
                it.javaClass.name
            }
        if (providers.size != QUALIFIED_PLUGIN_PROVIDERS.size || providers.toSet() != QUALIFIED_PLUGIN_PROVIDERS)
            return captureRejected(SemanticDependencyCaptureFailure.COMPILER_PLUGIN_INPUTS_UNMODELED)
        digest.text(ApplicationInfo.getInstance().build.asString())
        providers.forEach(digest::text)
        digest.text(Registry.`is`("kotlin.k2.only.bundled.compiler.plugins.enabled").toString())
        return Refinement.Refined(Unit)
    }

    private fun passivePlugin(path: String, digest: SemanticInputDigest): SemanticCapture<Unit> {
        when (val spent = budget.step()) {
            is Refinement.Rejected -> return spent
            is Refinement.Refined -> Unit
        }
        val raw = Path.of(path)
        if (
            PathMacroManager.getInstance(project).expandPath(path) != path ||
                KotlinPluginLayoutService.getInstance(project).resolveRelativeToRemoteKotlinc(raw) != raw ||
                !Files.isReadable(raw)
        )
            return captureRejected(SemanticDependencyCaptureFailure.COMPILER_PLUGIN_INPUTS_UNMODELED)
        if (!raw.isAbsolute) return captureRejected(SemanticDependencyCaptureFailure.INPUT_PROVIDER_UNSUPPORTED)
        val file =
            LocalFileSystem.getInstance().findFileByPath(path)
                ?: return captureRejected(SemanticDependencyCaptureFailure.INPUT_UNAVAILABLE)
        if (file.isDirectory) return captureRejected(SemanticDependencyCaptureFailure.COMPILER_PLUGIN_INPUTS_UNMODELED)
        val hash =
            when (val observed = files.hash(file)) {
                is Refinement.Refined -> observed.value
                is Refinement.Rejected -> return observed
            }
        when (val passive = observePassiveCompilerClasspathJar(raw, limits, budget)) {
            is Refinement.Rejected -> return passive
            is Refinement.Refined -> Unit
        }
        digest.text(file.url)
        digest.text(hash.value)
        return Refinement.Refined(Unit)
    }
}

private fun configurationUnavailable() =
    captureRejected(SemanticDependencyCaptureFailure.COMPILER_CONFIGURATION_UNAVAILABLE)

private const val QUALIFIED_KOTLIN_COMPILER = "2.4.20-ij262-52"
private const val QUALIFIED_IDEA_BUILD = "IU-262.10968.63"
private val QUALIFIED_PLUGIN_PROVIDERS =
    setOf(
        "org.jetbrains.kotlin.idea.fir.extensions.MainByRegistrarContentBundledFirCompilerPluginProvider",
        "org.jetbrains.kotlin.idea.fir.extensions.FromKotlinDistForIdeByNameFallbackBundledFirCompilerPluginProvider",
    )
