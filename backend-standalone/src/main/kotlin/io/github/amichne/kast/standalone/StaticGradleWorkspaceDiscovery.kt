package io.github.amichne.kast.standalone

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

internal object StaticGradleWorkspaceDiscovery {
    private const val supportedDependencyConfigurations =
        "api|implementation|compileOnly|runtimeOnly|" +
            "testApi|testImplementation|testCompileOnly|testRuntimeOnly|" +
            "testFixturesApi|testFixturesImplementation|testFixturesCompileOnly|testFixturesRuntimeOnly"
    private val scopedProjectDependencyPattern = Regex(
        """(?s)\b($supportedDependencyConfigurations)\s*\(\s*project\(\s*(?:path\s*=\s*)?[\"'](:?[^\"')]+)[\"'][^)]*\)\s*\)""",
    )
    private val addedProjectDependencyPattern = Regex(
        """(?s)\badd\s*\(\s*[\"']($supportedDependencyConfigurations)[\"']\s*,\s*project\(\s*(?:path\s*=\s*)?[\"'](:?[^\"')]+)[\"'][^)]*\)\s*\)""",
    )
    private val scopedFileDependencyPattern = Regex(
        """(?s)\b($supportedDependencyConfigurations)\s*\(\s*files\((.*?)\)\s*\)""",
    )
    private val addedFileDependencyPattern = Regex(
        """(?s)\badd\s*\(\s*[\"']($supportedDependencyConfigurations)[\"']\s*,\s*files\((.*?)\)\s*\)""",
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
        val dependencies = (
            buildFiles.flatMap { buildFile ->
                parseDependencies(
                    buildText = buildFile.readText(),
                    workspaceRoot = workspaceRoot,
                    projectDirectory = projectDirectory,
                )
            } + discoverClasspathFromBuildOutput(projectDirectory)
            ).distinct()

        return GradleModuleModel(
            gradlePath = projectPath,
            ideaModuleName = projectPath,
            mainSourceRoots = sourceRoots(projectDirectory, GradleSourceSet.MAIN),
            testSourceRoots = sourceRoots(projectDirectory, GradleSourceSet.TEST),
            testFixturesSourceRoots = sourceRoots(projectDirectory, GradleSourceSet.TEST_FIXTURES),
            mainOutputRoots = outputRoots(projectDirectory, GradleSourceSet.MAIN),
            testOutputRoots = outputRoots(projectDirectory, GradleSourceSet.TEST),
            testFixturesOutputRoots = outputRoots(projectDirectory, GradleSourceSet.TEST_FIXTURES),
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

    private fun discoverClasspathFromBuildOutput(projectDirectory: Path): List<GradleDependency.LibraryDependency> = runCatching {
        val mainBinaryRoots = resolveClasspathEntriesFromFiles(
            projectDirectory,
            buildOutputClasspathFiles(projectDirectory, GradleSourceSet.MAIN),
        )
        val testBinaryRoots = resolveClasspathEntriesFromFiles(
            projectDirectory,
            buildOutputClasspathFiles(projectDirectory, GradleSourceSet.TEST),
        )

        val buildLibDirectory = projectDirectory.resolve("build/libs")
        val libJars = if (buildLibDirectory.isDirectory()) {
            Files.list(buildLibDirectory).use { paths ->
                paths.filter { path ->
                    Files.isRegularFile(path) && path.fileName.toString().endsWith(".jar")
                }.map(::normalizeStandalonePath).toList()
            }
        } else {
            emptyList()
        }

        val allMainRoots = (mainBinaryRoots + libJars).distinct().sorted()
        val testOnlyRoots = (testBinaryRoots - allMainRoots.toSet()).distinct().sorted()

        allMainRoots.map { binaryRoot ->
            GradleDependency.LibraryDependency(
                binaryRoot = binaryRoot,
                scope = GradleDependencyScope.COMPILE,
            )
        } + testOnlyRoots.map { binaryRoot ->
            GradleDependency.LibraryDependency(
                binaryRoot = binaryRoot,
                scope = GradleDependencyScope.TEST,
            )
        }
    }.getOrElse { emptyList() }

    private fun resolveClasspathEntriesFromFiles(
        projectDirectory: Path,
        classpathFiles: List<Path>,
    ): List<Path> = classpathFiles
        .filter(Path::isRegularFile)
        .flatMap { classpathFile -> parseBuildOutputClasspathEntries(classpathFile.readText()) }
        .mapNotNull { rawEntry -> existingJarPathOrNull(resolveBuildOutputClasspathEntry(rawEntry, projectDirectory)) }
        .distinct()

    private fun buildOutputClasspathFiles(
        projectDirectory: Path,
        sourceSet: GradleSourceSet,
    ): List<Path> = when (sourceSet) {
        GradleSourceSet.MAIN -> listOf(
            projectDirectory.resolve("build/tmp/compileKotlin/classpath"),
            projectDirectory.resolve("build/tmp/compileJava/classpath"),
        )
        GradleSourceSet.TEST -> listOf(
            projectDirectory.resolve("build/tmp/compileTestKotlin/classpath"),
            projectDirectory.resolve("build/tmp/compileTestJava/classpath"),
        )
        GradleSourceSet.TEST_FIXTURES -> listOf(
            projectDirectory.resolve("build/tmp/compileTestFixturesKotlin/classpath"),
            projectDirectory.resolve("build/tmp/compileTestFixturesJava/classpath"),
        )
    }

    private fun parseBuildOutputClasspathEntries(rawClasspath: String): List<String> = rawClasspath.lineSequence()
        .flatMap { line ->
            val trimmedLine = line.trim()
            when {
                trimmedLine.isBlank() -> emptySequence()
                trimmedLine.contains(File.pathSeparator) -> trimmedLine.split(File.pathSeparator).asSequence()
                else -> sequenceOf(trimmedLine)
            }
        }
        .map(String::trim)
        .filter(String::isNotBlank)
        .toList()

    private fun resolveBuildOutputClasspathEntry(
        rawPath: String,
        projectDirectory: Path,
    ): Path {
        val candidatePath = Path.of(rawPath)
        return if (candidatePath.isAbsolute) {
            candidatePath
        } else {
            projectDirectory.resolve(rawPath)
        }
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

    private fun existingJarPathOrNull(path: Path): Path? = existingPathOrNull(path)
        ?.takeIf { normalizedPath -> normalizedPath.fileName.toString().endsWith(".jar") }

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
    ): List<Path> = conventionalGradleSourceRootCandidates(projectDirectory, sourceSet)
        .filter(Path::isDirectory)
        .map(::normalizeStandalonePath)
        .distinct()
        .sorted()

    private fun outputRoots(
        projectDirectory: Path,
        sourceSet: GradleSourceSet,
    ): List<Path> = conventionalGradleOutputRootCandidates(projectDirectory, sourceSet)
        .filter(Path::isDirectory)
        .map(::normalizeStandalonePath)
        .distinct()
        .sorted()

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
        configurationName.startsWith("testFixtures", ignoreCase = true) -> GradleDependencyScope.TEST_FIXTURES
        configurationName.startsWith("testCompile", ignoreCase = true) -> GradleDependencyScope.TEST
        configurationName.startsWith("testRuntime", ignoreCase = true) -> GradleDependencyScope.TEST
        configurationName.startsWith("test", ignoreCase = true) -> GradleDependencyScope.TEST
        configurationName.contains("compileOnly", ignoreCase = true) -> GradleDependencyScope.PROVIDED
        configurationName.contains("runtimeOnly", ignoreCase = true) -> GradleDependencyScope.RUNTIME
        configurationName in setOf("implementation", "api", "compile") -> GradleDependencyScope.COMPILE
        else -> null
    }
}

internal fun conventionalGradleSourceRootCandidates(
    projectDirectory: Path,
    sourceSet: GradleSourceSet,
): List<Path> = when (sourceSet) {
    GradleSourceSet.MAIN -> listOf(
        projectDirectory.resolve("src/main/kotlin"),
        projectDirectory.resolve("src/main/java"),
    )
    GradleSourceSet.TEST -> listOf(
        projectDirectory.resolve("src/test/kotlin"),
        projectDirectory.resolve("src/test/java"),
    )
    GradleSourceSet.TEST_FIXTURES -> listOf(
        projectDirectory.resolve("src/testFixtures/kotlin"),
        projectDirectory.resolve("src/testFixtures/java"),
    )
}

internal fun conventionalGradleOutputRootCandidates(
    projectDirectory: Path,
    sourceSet: GradleSourceSet,
): List<Path> = when (sourceSet) {
    GradleSourceSet.MAIN -> listOf(
        projectDirectory.resolve("build/classes/main"),
        projectDirectory.resolve("build/classes/java/main"),
        projectDirectory.resolve("build/classes/kotlin/main"),
        projectDirectory.resolve("build/resources/main"),
    )
    GradleSourceSet.TEST -> listOf(
        projectDirectory.resolve("build/classes/test"),
        projectDirectory.resolve("build/classes/java/test"),
        projectDirectory.resolve("build/classes/kotlin/test"),
        projectDirectory.resolve("build/resources/test"),
    )
    GradleSourceSet.TEST_FIXTURES -> listOf(
        projectDirectory.resolve("build/classes/testFixtures"),
        projectDirectory.resolve("build/classes/java/testFixtures"),
        projectDirectory.resolve("build/classes/kotlin/testFixtures"),
        projectDirectory.resolve("build/resources/testFixtures"),
    )
}
