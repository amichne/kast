import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("kast.kotlin-library")
    id("kast.role.intellij-read")
}

group = "${rootProject.group}.source"

base {
    archivesName.set("source-intellij")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val ideaPlatformBuild = catalog.findVersion("idea-platform-build").get().requiredVersion

private val extractedIdeaDistributionDirectory =
    objects.directoryProperty().apply {
        set(file(gradle.gradleUserHomeDir.resolve("kast/symbol-intellij-idea-distributions/$ideaPlatformBuild")))
    }

private val kotlinPluginLibs: ConfigurableFileCollection =
    files(
            extractedIdeaDistributionDirectory.map { directory ->
                fileTree(directory) {
                    include("**/plugins/Kotlin/lib/**/*.jar")
                    exclude("**/plugins/Kotlin/lib/jps/**")
                    exclude("**/plugins/Kotlin/lib/kotlinc/lib/kotlin-compiler.jar")
                }
            }
        )
        .builtBy(":symbol:intellij:extractSymbolIdeaDistribution")

private val javaPluginLibs: ConfigurableFileCollection =
    files(
            extractedIdeaDistributionDirectory.map { directory ->
                fileTree(directory) {
                    include("**/plugins/java/lib/**/*.jar")
                }
            }
        )
        .builtBy(":symbol:intellij:extractSymbolIdeaDistribution")

dependencies {
    implementation(project(":source:contract"))
    implementation(project(":symbol:contract"))
    implementation(project(":workspace:contract"))
    implementation(project(":workspace:intellij-read"))

    compileOnly("com.jetbrains.intellij.platform:core:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:core-impl:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:analysis:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:indexing:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:lang:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:lang-impl:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:project-model:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:util:$ideaPlatformBuild")
    compileOnly(kotlinPluginLibs)
    compileOnly(javaPluginLibs)

    testImplementation("com.jetbrains.intellij.platform:core:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:core-impl:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:syntax-psi:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:analysis:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:lang:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:util:$ideaPlatformBuild")
    testImplementation(javaPluginLibs)
    testImplementation(kotlinPluginLibs)
}
