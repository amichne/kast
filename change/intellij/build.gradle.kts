import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("kast.kotlin-library")
    kotlin("plugin.serialization")
    id("kast.role.intellij-write")
}

group = "${rootProject.group}.change"

base {
    archivesName.set("change-intellij")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val ideaDistributionVersion = catalog.findVersion("idea-platform-build").get().requiredVersion

private val extractedIdeaDistributionDirectory =
    objects.directoryProperty().apply {
        set(file(gradle.gradleUserHomeDir.resolve("kast/symbol-intellij-idea-distributions/$ideaDistributionVersion")))
    }

private fun extractedIdeaFiles(configure: ConfigurableFileTree.() -> Unit) =
    files(
            extractedIdeaDistributionDirectory.map { directory ->
                fileTree(directory) {
                    configure()
                }
            }
        )
        .builtBy(":symbol:intellij:extractSymbolIdeaDistribution")

private val ideaLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/lib/**/*.jar")
    exclude("**/plugins/**")
    exclude("**/lib/intellij.libraries.kotlinx.serialization.*.jar")
    exclude("**/lib/intellij.libraries.ktor.utils.jar")
}

private val kotlinPluginLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/plugins/Kotlin/lib/**/*.jar")
    exclude("**/plugins/Kotlin/lib/jps/**")
    exclude("**/plugins/Kotlin/lib/kotlinc/lib/kotlin-compiler.jar")
}

private val javaPluginLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/plugins/java/lib/**/*.jar")
}

dependencies {
    implementation(project(":protocol:contract"))
    implementation(project(":change:apply"))
    implementation(project(":change:contract"))
    implementation(project(":change:recovery"))
    implementation(project(":change:verify"))
    implementation(project(":evidence:contract"))
    implementation(project(":workspace:contract"))
    implementation(project(":workspace:intellij-read"))

    compileOnly(catalog.findLibrary("serialization-json").get())
    testImplementation(catalog.findLibrary("serialization-json").get())
    compileOnly(ideaLibs)
    compileOnly(kotlinPluginLibs)
    compileOnly(javaPluginLibs)
}
