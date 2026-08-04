package io.github.amichne.kast.idea.transition

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.validation.FileHashing
import org.jdom.Element
import org.jdom.output.Format
import org.jdom.output.XMLOutputter
import org.jetbrains.kotlin.arguments.CompilerArgumentsSerializerV5
import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.config.CompilerSettings
import org.jetbrains.kotlin.idea.compiler.configuration.Kotlin2JsCompilerArgumentsHolder
import org.jetbrains.kotlin.idea.compiler.configuration.Kotlin2JvmCompilerArgumentsHolder
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCompilerSettings
import org.jetbrains.kotlin.idea.facet.KotlinFacet
import io.github.amichne.kast.idea.SemanticPathContentIdentity
import java.io.File
import java.nio.file.Path

internal object IdeaKotlinCompilerIdentityResolver {
    fun resolve(project: Project, modules: List<Module>, isCancelled: () -> Boolean = { false }): String {
        val projectCompilerSettings = KotlinCompilerSettings.getInstance(project).settings
        val projectCommonArguments = KotlinCommonCompilerArgumentsHolder.getInstance(project).settings
        val projectJsArguments = Kotlin2JsCompilerArgumentsHolder.getInstance(project).settings
        val projectJvmArguments = Kotlin2JvmCompilerArgumentsHolder.getInstance(project).settings
        val records = buildList {
            add(compilerSettingsRecord("project-settings", projectCompilerSettings))
            add(record("project-common-arguments", serializeArguments(projectCommonArguments)))
            add(compilerArtifactRecord("project-common-plugins", projectCommonArguments.pluginClasspaths, isCancelled))
            add(
                record(
                    "project-js-arguments",
                    projectJsArguments.moduleKind.orEmpty(),
                    serializeArguments(projectJsArguments),
                ),
            )
            add(compilerArtifactRecord("project-js-plugins", projectJsArguments.pluginClasspaths, isCancelled))
            add(
                record(
                    "project-jvm-arguments",
                    projectJvmArguments.jvmTarget.orEmpty(),
                    serializeArguments(projectJvmArguments),
                ),
            )
            add(compilerArtifactRecord("project-jvm-plugins", projectJvmArguments.pluginClasspaths, isCancelled))
            add(
                compilerArtifactRecord(
                    "project-script-templates",
                    projectCompilerSettings.scriptTemplatesClasspath
                        .split(File.pathSeparatorChar)
                        .filter(String::isNotBlank)
                        .toTypedArray(),
                    isCancelled,
                ),
            )
            modules.asSequence()
                .filterNot(Module::isDisposed)
                .sortedBy { module -> module.name }
                .forEach { module -> add(moduleRecord(module, isCancelled)) }
        }
        return FileHashing.sha256(records.joinToString("\n"))
    }

    fun artifactRoots(project: Project): Set<Path> =
        ApplicationManager.getApplication().runReadAction<Set<Path>> {
            val roots = linkedSetOf<Path>()
            val compilerSettings = KotlinCompilerSettings.getInstance(project).settings
            roots.addAll(artifactPaths(KotlinCommonCompilerArgumentsHolder.getInstance(project).settings.pluginClasspaths))
            roots.addAll(artifactPaths(Kotlin2JsCompilerArgumentsHolder.getInstance(project).settings.pluginClasspaths))
            roots.addAll(artifactPaths(Kotlin2JvmCompilerArgumentsHolder.getInstance(project).settings.pluginClasspaths))
            roots.addAll(scriptTemplatePaths(compilerSettings))
            ModuleManager.getInstance(project).modules
                .asSequence()
                .filterNot(Module::isDisposed)
                .mapNotNull(KotlinFacet::get)
                .map { facet -> facet.configuration.settings }
                .forEach { settings ->
                    roots.addAll(artifactPaths(settings.mergedCompilerArguments?.pluginClasspaths))
                    roots.addAll(scriptTemplatePaths(settings.compilerSettings))
                }
            roots
        }

    private fun moduleRecord(module: Module, isCancelled: () -> Boolean): String {
        val settings = KotlinFacet.get(module)?.configuration?.settings
            ?: return record("module-without-kotlin-facet", module.name)
        return record(
            "module-kotlin-facet",
            module.name,
            settings.version.toString(),
            settings.useProjectSettings.toString(),
            serializeArguments(settings.compilerArguments),
            serializeArguments(settings.mergedCompilerArguments),
            compilerArtifactRecord("module-plugins", settings.mergedCompilerArguments?.pluginClasspaths, isCancelled),
            compilerSettingsRecord("module-settings", settings.compilerSettings),
            compilerArtifactRecord(
                "module-script-templates",
                settings.compilerSettings?.scriptTemplatesClasspath
                    .orEmpty()
                    .split(File.pathSeparatorChar)
                    .filter(String::isNotBlank)
                    .toTypedArray(),
                isCancelled,
            ),
            settings.languageLevel?.toString().orEmpty(),
            settings.apiLevel?.toString().orEmpty(),
            settings.targetPlatform?.toString().orEmpty(),
            settings.implementedModuleNames.sorted().joinToString(","),
            settings.dependsOnModuleNames.sorted().joinToString(","),
            settings.additionalVisibleModuleNames.sorted().joinToString(","),
            settings.productionOutputPath.orEmpty(),
            settings.testOutputPath.orEmpty(),
            settings.kind.toString(),
            settings.sourceSetNames.sorted().joinToString(","),
            settings.isTestModule.toString(),
            settings.externalProjectId,
            settings.isHmppEnabled.toString(),
            settings.mppVersion?.toString().orEmpty(),
            settings.pureKotlinSourceFolders.sorted().joinToString(","),
        )
    }

    private fun compilerSettingsRecord(label: String, settings: CompilerSettings?): String = record(
        label,
        settings?.additionalArguments.orEmpty(),
        settings?.scriptTemplates.orEmpty(),
        settings?.scriptTemplatesClasspath.orEmpty(),
        settings?.copyJsLibraryFiles.toString(),
        settings?.outputDirectoryForJsLibraryFiles.orEmpty(),
    )

    private fun serializeArguments(arguments: CommonCompilerArguments?): String {
        if (arguments == null) return ""
        val serialized = CompilerArgumentsSerializerV5(arguments).serializeTo(Element("arguments"))
        return XMLOutputter(Format.getCompactFormat()).outputString(serialized)
    }

    private fun compilerArtifactRecord(
        label: String,
        paths: Array<String>?,
        isCancelled: () -> Boolean,
    ): String = record(
        label,
        *paths.orEmpty().map { rawPath ->
            val path = runCatching { Path.of(rawPath).toAbsolutePath().normalize() }.getOrNull()
            "$rawPath=${path?.let { SemanticPathContentIdentity.resolve(it, isCancelled) } ?: "invalid"}"
        }.toTypedArray(),
    )

    private fun artifactPaths(paths: Array<String>?): List<Path> = paths.orEmpty().mapNotNull { rawPath ->
        runCatching { Path.of(rawPath).toAbsolutePath().normalize() }.getOrNull()
    }

    private fun scriptTemplatePaths(settings: CompilerSettings?): List<Path> = artifactPaths(
        settings?.scriptTemplatesClasspath
            .orEmpty()
            .split(File.pathSeparatorChar)
            .filter(String::isNotBlank)
            .toTypedArray(),
    )

    private fun record(label: String, vararg values: String): String = buildString {
        appendField(label)
        values.forEach { value -> appendField(value) }
    }

    private fun StringBuilder.appendField(value: String) {
        append(value.length).append(':').append(value)
    }
}
