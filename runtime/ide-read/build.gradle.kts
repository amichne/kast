import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("kast.kotlin-library")
    kotlin("plugin.serialization")
    id("kast.role.ide-read-only")
}

group = "${rootProject.group}.runtime"

base {
    archivesName.set("runtime-ide-read")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val ideHostBuild = catalog.findVersion("ide-host-build").get().requiredVersion

private val workspaceIdeaDistributionDirectory = layout.dir(
    providers.provider {
        gradle.gradleUserHomeDir.resolve(
            "kast/workspace-intellij-read-idea-distributions/$ideHostBuild",
        )
    },
)

private val workspaceIdeaLibraries = files(
    workspaceIdeaDistributionDirectory.map { directory ->
        fileTree(directory) {
            include("**/lib/**/*.jar")
            exclude("**/plugins/**")
            exclude("**/lib/intellij.libraries.kotlinx.serialization.*.jar")
            exclude("**/lib/intellij.libraries.ktor.utils.jar")
        }
    },
).builtBy(":workspace:intellij-read:extractWorkspaceReadIdeaDistribution")

dependencies {
    implementation(project(":protocol:wire"))
    implementation(project(":symbol:contract"))
    implementation(project(":symbol:intellij"))
    implementation(project(":workspace:contract"))
    implementation(project(":workspace:intellij-read"))
    compileOnly(workspaceIdeaLibraries)
    testImplementation(workspaceIdeaLibraries)
    testImplementation(catalog.findLibrary("serialization-json").get())
}

private val workspaceContractFriendPath =
    project(":workspace:contract").layout.buildDirectory.dir("classes/kotlin/main")
private val workspaceIntellijReadFriendPath =
    project(":workspace:intellij-read").layout.buildDirectory.dir("classes/kotlin/main")

tasks.withType<KotlinCompile>().configureEach {
    dependsOn(
        ":workspace:contract:compileKotlin",
        ":workspace:intellij-read:compileKotlin",
        ":workspace:intellij-read:extractWorkspaceReadIdeaDistribution",
    )
    compilerOptions.freeCompilerArgs.add(
        workspaceContractFriendPath.zip(workspaceIntellijReadFriendPath) { contract, intellijRead ->
            "-Xfriend-paths=${contract.asFile.absolutePath},${intellijRead.asFile.absolutePath}"
        },
    )
}
