package io.github.amichne.kast.standalone

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

internal object StaticGradleWorkspaceDiscovery {
    private val scopedProjectDependencyPattern = Regex(
        """(?s)\b(api|implementation|compileOnly|runtimeOnly|testApi|testImplementation|testCompileOnly|testRuntimeOnly)\s*\(\s*project\(\s*(?:path\s*=\s*)?[\"'](:?[^\"')]+)[\"'][^)]*\)\s*\)""",
    )
    private val addedProjectDependencyPattern = Regex(
        """(?s)\badd\s*\(\s*[\"'](api|implementation|compileOnly|runtimeOnly|testApi|testImplementation|testCompileOnly|testRuntimeOnly)[\"']\s*,\s*project\(\s*(?:path\s*=\s*)?[\"'](:?[^\"')]+)[\"'][^)]*\)\s*\)""",
    )
    private val scopedFileDependencyPattern = Regex(
        """(?s)\b(api|implementation|compileOnly|runtimeOnly|testApi|testImplementation|testCompileOnly|testRuntimeOnly)\s*\(\s*files\((.*?)\)\s*\)""",
    )
    private val addedFileDependencyPattern = Regex(
        """(?s)\badd\s*\(\s*[\"'](api|implementation|compileOnly|runtimeOnly|testApi|testImplementation|testCompileOnly|testRuntimeOnly)[\"']\s*,\s*files\((.*?)\)\s*\)""",
    )
    private val rootProjectFilePattern = Regex(
        """(?:rootProject\.)?layout\.projectDirectory\.file\(\s*[\"']([^\"']+)[\"']\s*\)""",
    )
    private val fileCallPattern = Regex(
        """\bfile\(\s*[\"']([^\"']+) [\"']\s*\)""".replace(" ", ""),
    )
    private val quotedArchivePattern = Regex(
        """[\"']([^\"']+\.(?:jar|zip))[\"']""",
    )

    fun discoverModules(
        workspaceRoot: Path,
        settingsSnapshot: GradleSettingsSnapshot,
    ): List<GradleModuleModel> {
        return settingsSnapshot.projectPathsForStaticDiscovery()
            .map { projectPath ->
                toGradleModuleModel(
                    workspaceRoot = workspaceRoot,
                    projectPath = projectPath,
                )
            }
            .sortedBy(GradleModuleModel::gradlePath)
    }

    private fun toGradleModuleModel(
        workspaceRoot: Path,
        projectPath: String,
    ): GradleModuleModel {
        val projectDirectory = projectDirectoryFor(workspaceRoot, projectPath)
        val buildFiles = buildFileCandidates(projectDirectory).filter(Path::isRegularFile)
        val dependencies = buildFiles
            .flatMap { buildFile ->
                parseDependencies(
                    buildText = buildFile.readText(),
                    workspaceRoot = workspaceRoot,
                    projectDirectory = projectDirectory,
                )
            }
            .distinct()

        return GradleModuleModel(
            gradlePath = projectPath,
            ideaModuleName = projectPath,
            mainSourceRoots = sourceRoots(projectDirectory, GradleSourceSet.MAIN),
            testSourceRoots = sourceRoots(projectDirectory, GradleSourceSet.TEST),
            mainOutputRoots = outputRoots(projectDirectory, GradleSourceSet.MAIN),
            testOutputRoots = outputRoots(projectDirectory, GradleSourceSet.TEST),
            dependencies = dependencies,
        )
    }

    private fun parseDependencies(
        buildText: String,
        workspaceRoot: Path,
        projectDirectory: Path,
    ): List<GradleDependency> = buildList {
        collectProjectDependencies(buildText, scopedProjectDependencyPattern)
            .forEach(::add)
        collectProjectDependencies(buildText, addedProjectDependencyPattern)
            .forEach(::add)
        collectFileDependencies(buildText, scopedFileDependencyPattern, workspaceRoot, projectDirectory)
            .forEach(::add)
        collectFileDependencies(buildText, addedFileDependencyPattern, workspaceRoot, projectDirectory)
            .forEach(::add)
    }

    private fun collectProjectDependencies(
        buildText: String,
        pattern: Regex,
    ): List<GradleDependency.ModuleDependency> {
        return pattern.findAll(buildText)
            .mapNotNull { match ->
                val scope = configurationNameToScope(match.groupValues[1]) ?: return@mapNotNull null
                val targetProjectPath = normalizeGradleProjectPath(match.groupValues[2])
                GradleDependency.ModuleDependency(
                    targetIdeaModuleName = targetProjectPath,
                    scope = scope,
                )
            }
            .toList()
    }

    private fun collectFileDependencies(
        buildText: String,
        pattern: Regex,
        workspaceRoot: Path,
        projectDirectory: Path,
    ): List<GradleDependency.LibraryDependency> {
        return pattern.findAll(buildText)
            .flatMap { match ->
                val scope = configurationNameToScope(match.groupValues[1]) ?: return@flatMap emptySequence()
                extractBinaryRoots(
                    argumentText = match.groupValues[2],
                    workspaceRoot = workspaceRoot,
                    projectDirectory = projectDirectory,
                ).asSequence().map { binaryRoot ->
                    GradleDependency.LibraryDependency(
                        binaryRoot = binaryRoot,
                        scope = scope,
                    )
                }
            }
            .toList()
    }

    private fun extractBinaryRoots(
        argumentText: String,
        workspaceRoot: Path,
        projectDirectory: Path,
    ): List<Path> = buildList {
        rootProjectFilePattern.findAll(argumentText)
            .map { match -> workspaceRoot.resolve(match.groupValues[1]) }
            .mapNotNull(::existingPathOrNull)
            .forEach(::add)

        fileCallPattern.findAll(argumentText)
            .map { match -> resolveRelativeBinaryRoot(match.groupValues[1], projectDirectory, workspaceRoot) }
            .mapNotNull(::existingPathOrNull)
            .forEach(::add)

        quotedArchivePattern.findAll(argumentText)
            .map { match -> resolveRelativeBinaryRoot(match.groupValues[1], projectDirectory, workspaceRoot) }
            .mapNotNull(::existingPathOrNull)
            .forEach(::add)
    }.distinct()

    private fun existingPathOrNull(path: Path): Path? = path
        .takeIf(Path::exists)
        ?.let(::normalizeStandalonePath)

    private fun resolveRelativeBinaryRoot(
        rawPath: String,
        projectDirectory: Path,
        workspaceRoot: Path,
    ): Path {
        val candidatePath = Path.of(rawPath)
        if (candidatePath.isAbsolute) {
            return candidatePath
        }

        val projectRelativePath = projectDirectory.resolve(rawPath)
        return if (projectRelativePath.exists()) {
            projectRelativePath
        } else {
            workspaceRoot.resolve(rawPath)
        }
    }

    private fun sourceRoots(
        projectDirectory: Path,
        sourceSet: GradleSourceSet,
    ): List<Path> = listOf(
        projectDirectory.resolve("src/${sourceSet.id}/kotlin"),
        projectDirectory.resolve("src/${sourceSet.id}/java"),
    )
        .filter(Path::isDirectory)
        .map(::normalizeStandalonePath)
        .distinct()
        .sorted()

    private fun outputRoots(
        projectDirectory: Path,
        sourceSet: GradleSourceSet,
    ): List<Path> = listOf(
        projectDirectory.resolve("build/classes/${sourceSet.id}"),
        projectDirectory.resolve("build/classes/java/${sourceSet.id}"),
        projectDirectory.resolve("build/classes/kotlin/${sourceSet.id}"),
        projectDirectory.resolve("build/resources/${sourceSet.id}"),
    ).filter(Path::isDirectory).map(::normalizeStandalonePath).distinct().sorted()

    private fun projectDirectoryFor(
        workspaceRoot: Path,
        projectPath: String,
    ): Path {
        if (projectPath == ":") {
            return workspaceRoot
        }
        val relativePath = projectPath.removePrefix(":").replace(':', '/')
        return normalizeStandalonePath(workspaceRoot.resolve(relativePath))
    }

    private fun buildFileCandidates(projectDirectory: Path): List<Path> = listOf(
        projectDirectory.resolve("build.gradle.kts"),
        projectDirectory.resolve("build.gradle"),
    )

    private fun configurationNameToScope(configurationName: String): GradleDependencyScope? = when {
        configurationName.startsWith("testCompile", ignoreCase = true) -> GradleDependencyScope.TEST
        configurationName.startsWith("testRuntime", ignoreCase = true) -> GradleDependencyScope.TEST
        configurationName.startsWith("test", ignoreCase = true) -> GradleDependencyScope.TEST
        configurationName.contains("compileOnly", ignoreCase = true) -> GradleDependencyScope.PROVIDED
        configurationName.contains("runtimeOnly", ignoreCase = true) -> GradleDependencyScope.RUNTIME
        configurationName in setOf("implementation", "api", "compile") -> GradleDependencyScope.COMPILE
        else -> null
    }
}
