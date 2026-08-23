import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("kast.kotlin-library")
    id("kast.role.intellij-read")
}

group = "${rootProject.group}.topology"

base {
    archivesName.set("topology-intellij")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val ideaPlatformBuild = catalog.findVersion("idea-platform-build").get().requiredVersion
private val ideaDistributionVersion = catalog.findVersion("idea-indexer").get().requiredVersion

val topologyIdeaDistribution: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

private val extractedKotlinPluginDirectory = objects.directoryProperty().apply {
    set(file(gradle.gradleUserHomeDir.resolve("kast/topology-intellij-kotlin-plugin/$ideaDistributionVersion")))
}

val extractTopologyKotlinPlugin by tasks.registering(Sync::class) {
    from({ zipTree(topologyIdeaDistribution.singleFile) }) {
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
).builtBy(extractTopologyKotlinPlugin)

dependencies {
    implementation(project(":topology:contract"))
    implementation(project(":workspace:contract"))
    implementation(project(":symbol:contract"))

    topologyIdeaDistribution("com.jetbrains.intellij.idea:ideaIC:$ideaDistributionVersion@zip") {
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
