import org.gradle.api.artifacts.VersionCatalogsExtension
import support.tasks.idea.IntellijSdkLibraryFamily
import support.tasks.idea.selectedIntellijTarget

plugins {
    id("kast.kotlin-library")
    id("kast.role.intellij-read")
}

group = "${providers.gradleProperty("GROUP").get()}.diagnostic"

base {
    archivesName.set("diagnostic-intellij")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val intellij = selectedIntellijTarget(project).also { it.configureModule(project) }
private val ideaPlatformBuild = intellij.profile.ideBuild
private val sdkLibraries =
    intellij.libraries(
        project,
        IntellijSdkLibraryFamily.PLATFORM,
        IntellijSdkLibraryFamily.KOTLIN,
        IntellijSdkLibraryFamily.JAVA,
    )

// Preserve the existing task entry point without another mutable SDK extraction.
val extractDiagnosticKotlinPlugin =
    tasks.register("extractDiagnosticKotlinPlugin") {
        dependsOn(sdkLibraries)
    }

dependencies {
    implementation(catalog.findLibrary("serialization-json").get())
    implementation(project(":protocol:contract"))
    implementation(project(":diagnostic:contract"))
    implementation(project(":symbol:contract"))
    implementation(project(":workspace:contract"))
    implementation(project(":workspace:intellij-read"))

    compileOnly(sdkLibraries)
    testImplementation(sdkLibraries)
}
