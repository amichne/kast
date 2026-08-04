package io.github.amichne.kast.idea.transition

import com.intellij.compiler.CompilerConfiguration
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.LanguageLevelUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.idea.SemanticPathContentIdentity
import org.jetbrains.jps.model.java.compiler.AnnotationProcessingConfiguration
import java.io.File
import java.nio.file.InvalidPathException
import java.nio.file.Path

@JvmInline
internal value class JavaCompilerIdentity private constructor(val value: String) {
    companion object {
        fun hash(records: Iterable<JavaCompilerIdentityRecord>): JavaCompilerIdentity =
            JavaCompilerIdentity(FileHashing.sha256(records.joinToString("\n", transform = JavaCompilerIdentityRecord::value)))
    }
}

@JvmInline
internal value class JavaCompilerIdentityRecord(val value: String)

internal object IdeaJavaCompilerIdentityResolver {
    fun resolve(
        project: Project,
        workspaceIdentity: WorkspaceIdentity,
        modules: List<Module>,
        isCancelled: () -> Boolean = { false },
    ): JavaCompilerIdentity {
        SemanticPathContentIdentity.requireActive(isCancelled)
        val configuration = CompilerConfiguration.getInstance(project)
        val records = modules.asSequence()
            .filterNot(Module::isDisposed)
            .sortedBy(Module::getName)
            .map { module ->
                SemanticPathContentIdentity.requireActive(isCancelled)
                moduleIdentity(configuration, workspaceIdentity, module, isCancelled).record()
            }
            .toList()
        return JavaCompilerIdentity.hash(records)
    }

    fun artifactRoots(project: Project, workspaceIdentity: WorkspaceIdentity): Set<Path> =
        ApplicationManager.getApplication().runReadAction<Set<Path>> {
            val configuration = CompilerConfiguration.getInstance(project)
            ModuleManager.getInstance(project).modules
                .asSequence()
                .filterNot(Module::isDisposed)
                .sortedBy(Module::getName)
                .flatMap { module ->
                    configuration.getAnnotationProcessingConfiguration(module)
                        .processorPath
                        .split(File.pathSeparatorChar)
                        .asSequence()
                        .filter(String::isNotBlank)
                }
                .mapNotNull { rawPath -> normalizedProcessorPath(rawPath, workspaceIdentity) }
                .toCollection(linkedSetOf())
        }

    private fun moduleIdentity(
        configuration: CompilerConfiguration,
        workspaceIdentity: WorkspaceIdentity,
        module: Module,
        isCancelled: () -> Boolean,
    ): JavaModuleCompilerIdentity = JavaModuleCompilerIdentity(
        moduleName = JavaModuleName(module.name),
        effectiveLanguageLevel = EffectiveJavaLanguageLevel(LanguageLevelUtil.getEffectiveLanguageLevel(module).name),
        effectiveBytecodeTarget = OptionalJavaCompilerValue.of(configuration.getBytecodeTargetLevel(module)),
        effectiveJavacOptions = configuration.getAdditionalOptions(module).mapIndexed { index, option ->
            SemanticPathContentIdentity.requireActive(isCancelled)
            OrderedJavacOption(index, option)
        },
        annotationProcessing = annotationProcessingIdentity(
            configuration.getAnnotationProcessingConfiguration(module),
            workspaceIdentity,
            isCancelled,
        ),
    )

    private fun annotationProcessingIdentity(
        configuration: AnnotationProcessingConfiguration,
        workspaceIdentity: WorkspaceIdentity,
        isCancelled: () -> Boolean,
    ): JavaAnnotationProcessingIdentity {
        SemanticPathContentIdentity.requireActive(isCancelled)
        val rawProcessorPath = configuration.processorPath
        val processorPathEntries = rawProcessorPath
            .split(File.pathSeparatorChar)
            .filter(String::isNotBlank)
            .mapIndexed { index, rawPath ->
                SemanticPathContentIdentity.requireActive(isCancelled)
                processorPathIdentity(index, rawPath, workspaceIdentity, isCancelled)
            }
        val processors = configuration.processors.sorted().map { processor ->
            SemanticPathContentIdentity.requireActive(isCancelled)
            AnnotationProcessorName(processor)
        }
        val processorOptions = configuration.processorOptions.entries
            .sortedWith(compareBy(Map.Entry<String, String>::key, Map.Entry<String, String>::value))
            .map { (key, value) ->
                SemanticPathContentIdentity.requireActive(isCancelled)
                AnnotationProcessorOption(key, value)
            }
        return JavaAnnotationProcessingIdentity(
            enabled = configuration.isEnabled,
            rawProcessorPath = rawProcessorPath,
            processorPathEntries = processorPathEntries,
            useProcessorModulePath = configuration.isUseProcessorModulePath,
            productionGeneratedSourcesDirectory = configuration.getGeneratedSourcesDirectoryName(false),
            testGeneratedSourcesDirectory = configuration.getGeneratedSourcesDirectoryName(true),
            outputRelativeToContentRoot = configuration.isOutputRelativeToContentRoot,
            processors = processors,
            processorOptions = processorOptions,
            obtainProcessorsFromClasspath = configuration.isObtainProcessorsFromClasspath,
            procOnly = configuration.isProcOnly,
        )
    }

    private fun processorPathIdentity(
        index: Int,
        rawPath: String,
        workspaceIdentity: WorkspaceIdentity,
        isCancelled: () -> Boolean,
    ): AnnotationProcessorPathIdentity {
        val absolutePath = normalizedProcessorPath(rawPath, workspaceIdentity)
        if (absolutePath == null) {
            return AnnotationProcessorPathIdentity.invalid(index, rawPath)
        }
        return AnnotationProcessorPathIdentity(
            index = index,
            rawPath = rawPath,
            stablePath = stablePath(workspaceIdentity, absolutePath),
            contentIdentity = SemanticPathContentIdentity.resolve(absolutePath, isCancelled),
        )
    }

    private fun normalizedProcessorPath(rawPath: String, workspaceIdentity: WorkspaceIdentity): Path? {
        val parsedPath = try {
            Path.of(rawPath)
        } catch (_: InvalidPathException) {
            return null
        }
        return (if (parsedPath.isAbsolute) parsedPath else workspaceIdentity.workspaceRootPath.resolve(parsedPath))
            .toAbsolutePath()
            .normalize()
    }

    private fun stablePath(workspaceIdentity: WorkspaceIdentity, path: Path): String =
        workspaceIdentity.relativizeIfContained(path)?.toString()?.replace('\\', '/')
            ?: "\$EXTERNAL/${path.toString().replace('\\', '/')}"
}

private data class JavaModuleCompilerIdentity(
    val moduleName: JavaModuleName,
    val effectiveLanguageLevel: EffectiveJavaLanguageLevel,
    val effectiveBytecodeTarget: OptionalJavaCompilerValue,
    val effectiveJavacOptions: List<OrderedJavacOption>,
    val annotationProcessing: JavaAnnotationProcessingIdentity,
) {
    fun record(): JavaCompilerIdentityRecord = identityRecord(
        "module-java-compiler",
        moduleName.value,
        effectiveLanguageLevel.value,
        effectiveBytecodeTarget.record().value,
        orderedIdentityRecord("javac-options", effectiveJavacOptions.map(OrderedJavacOption::record)).value,
        annotationProcessing.record().value,
    )
}

@JvmInline
private value class JavaModuleName(val value: String)

@JvmInline
private value class EffectiveJavaLanguageLevel(val value: String)

private class OptionalJavaCompilerValue private constructor(
    val state: State,
    val value: String,
) {
    enum class State {
        ABSENT,
        PRESENT,
    }

    fun record(): JavaCompilerIdentityRecord = identityRecord("optional-java-compiler-value", state.name, value)

    companion object {
        fun of(value: String?): OptionalJavaCompilerValue = if (value == null) {
            OptionalJavaCompilerValue(State.ABSENT, "")
        } else {
            OptionalJavaCompilerValue(State.PRESENT, value)
        }
    }
}

private data class OrderedJavacOption(
    val index: Int,
    val value: String,
) {
    fun record(): JavaCompilerIdentityRecord = identityRecord("javac-option", index.toString(), value)
}

private data class JavaAnnotationProcessingIdentity(
    val enabled: Boolean,
    val rawProcessorPath: String,
    val processorPathEntries: List<AnnotationProcessorPathIdentity>,
    val useProcessorModulePath: Boolean,
    val productionGeneratedSourcesDirectory: String,
    val testGeneratedSourcesDirectory: String,
    val outputRelativeToContentRoot: Boolean,
    val processors: List<AnnotationProcessorName>,
    val processorOptions: List<AnnotationProcessorOption>,
    val obtainProcessorsFromClasspath: Boolean,
    val procOnly: Boolean,
) {
    fun record(): JavaCompilerIdentityRecord = identityRecord(
        "annotation-processing",
        enabled.toString(),
        rawProcessorPath,
        orderedIdentityRecord("processor-path", processorPathEntries.map(AnnotationProcessorPathIdentity::record)).value,
        useProcessorModulePath.toString(),
        productionGeneratedSourcesDirectory,
        testGeneratedSourcesDirectory,
        outputRelativeToContentRoot.toString(),
        orderedIdentityRecord("processors", processors.map(AnnotationProcessorName::record)).value,
        orderedIdentityRecord("processor-options", processorOptions.map(AnnotationProcessorOption::record)).value,
        obtainProcessorsFromClasspath.toString(),
        procOnly.toString(),
    )
}

private data class AnnotationProcessorPathIdentity(
    val index: Int,
    val rawPath: String,
    val stablePath: String,
    val contentIdentity: String,
) {
    fun record(): JavaCompilerIdentityRecord = identityRecord(
        "annotation-processor-path",
        index.toString(),
        rawPath,
        stablePath,
        contentIdentity,
    )

    companion object {
        fun invalid(index: Int, rawPath: String): AnnotationProcessorPathIdentity = AnnotationProcessorPathIdentity(
            index = index,
            rawPath = rawPath,
            stablePath = "invalid",
            contentIdentity = "invalid",
        )
    }
}

@JvmInline
private value class AnnotationProcessorName(val value: String) {
    fun record(): JavaCompilerIdentityRecord = identityRecord("annotation-processor", value)
}

private data class AnnotationProcessorOption(
    val key: String,
    val value: String,
) {
    fun record(): JavaCompilerIdentityRecord = identityRecord("annotation-processor-option", key, value)
}

private fun orderedIdentityRecord(
    label: String,
    records: List<JavaCompilerIdentityRecord>,
): JavaCompilerIdentityRecord = identityRecord(label, *records.map(JavaCompilerIdentityRecord::value).toTypedArray())

private fun identityRecord(label: String, vararg values: String): JavaCompilerIdentityRecord =
    JavaCompilerIdentityRecord(
        buildString {
            appendIdentityField(label)
            values.forEach(::appendIdentityField)
        },
    )

private fun StringBuilder.appendIdentityField(value: String) {
    append(value.length).append(':').append(value)
}
