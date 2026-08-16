import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("kast.kotlin-library")
    id("kast.role.intellij-read")
}

group = "${rootProject.group}.diagnostic"

base {
    archivesName.set("diagnostic-intellij")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val ideaPlatformBuild = catalog.findVersion("idea-platform-build").get().requiredVersion
private val ideaDistributionVersion = catalog.findVersion("idea-indexer").get().requiredVersion

val diagnosticIdeaDistribution: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

private val extractedKotlinPluginDirectory = objects.directoryProperty().apply {
    set(file(gradle.gradleUserHomeDir.resolve("kast/diagnostic-intellij-kotlin-plugin/$ideaDistributionVersion")))
}

val extractDiagnosticKotlinPlugin by tasks.registering(Sync::class) {
    from({ zipTree(diagnosticIdeaDistribution.singleFile) }) {
        include("**/plugins/Kotlin/lib/**/*.jar")
        include("**/plugins/java/lib/**/*.jar")
        exclude("**/plugins/Kotlin/lib/jps/**")
        exclude("**/plugins/Kotlin/lib/kotlinc/lib/kotlin-compiler.jar")
        eachFile {
            relativePath = RelativePath(
                true,
                *relativePath.segments
                    .dropWhile { segment -> segment != "lib" }
                    .drop(1)
                    .toTypedArray(),
            )
        }
    }
    includeEmptyDirs = false
    into(extractedKotlinPluginDirectory)
}

private val kotlinPluginLibs: ConfigurableFileCollection = files(
    extractedKotlinPluginDirectory.map { directory ->
        fileTree(directory) {
            include("**/*.jar")
        }
    },
).builtBy(extractDiagnosticKotlinPlugin)

dependencies {
    implementation(project(":diagnostic:contract"))
    implementation(project(":workspace:contract"))

    diagnosticIdeaDistribution("com.jetbrains.intellij.idea:ideaIC:$ideaDistributionVersion@zip") {
        isTransitive = false
    }

    compileOnly("com.jetbrains.intellij.platform:core:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:core-impl:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:analysis:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:indexing:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:lang:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:lang-impl:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:util:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:project-model:$ideaPlatformBuild")
    compileOnly(kotlinPluginLibs)

    testImplementation("com.jetbrains.intellij.platform:core:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:core-impl:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:analysis:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:indexing:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:lang:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:lang-impl:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:util:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:project-model:$ideaPlatformBuild")
}
