import java.security.MessageDigest
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("kast.kotlin-library")
    kotlin("plugin.serialization")
    id("kast.role.ide-read-only")
}

group = "${rootProject.group}.workspace"

base {
    archivesName.set("workspace-intellij-read")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val hostedIdeaHome = providers.gradleProperty("hostedIdeaHome")
private val ideHostBuild =
    if (hostedIdeaHome.isPresent) {
        val metadata =
            providers
                .fileContents(layout.projectDirectory.file(hostedIdeaHome.get() + "/Resources/product-info.json"))
                .asText
                .get()
        checkNotNull((groovy.json.JsonSlurper().parseText(metadata) as Map<*, *>)["buildNumber"]) as String
    } else catalog.findVersion("ide-host-build").get().requiredVersion

val workspaceReadIdeaDistribution: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

private val extractedIdeaDistributionDirectory =
    objects.directoryProperty().apply {
        set(file(gradle.gradleUserHomeDir.resolve("kast/workspace-intellij-read-idea-distributions/$ideHostBuild")))
    }

val extractWorkspaceReadIdeaDistribution by
    tasks.registering(ExtractIdeaDistributionTask::class) {
        archives.from(workspaceReadIdeaDistribution)
        ideaVersion.set(ideHostBuild)
        outputDirectory.set(extractedIdeaDistributionDirectory)
    }

private fun extractedIdeaFiles(configure: ConfigurableFileTree.() -> Unit) =
    if (hostedIdeaHome.isPresent) files(fileTree(hostedIdeaHome.get()) { configure() })
    else
        files(
                extractedIdeaDistributionDirectory.map { directory ->
                    fileTree(directory) { configure() }
                }
            )
            .builtBy(extractWorkspaceReadIdeaDistribution)

private val ideaLibraries: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/lib/**/*.jar")
    exclude("**/plugins/**")
    exclude("**/lib/intellij.libraries.kotlinx.serialization.*.jar")
    exclude("**/lib/intellij.libraries.ktor.utils.jar")
}

private val kotlinPluginLibraries: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/plugins/Kotlin/lib/**/*.jar")
    exclude("**/plugins/Kotlin/lib/jps/**")
    exclude("**/plugins/Kotlin/lib/kotlinc/lib/kotlin-compiler.jar")
}

dependencies {
    implementation(project(":protocol:contract"))
    implementation(project(":workspace:contract"))
    implementation(project(":symbol:contract"))

    workspaceReadIdeaDistribution("com.jetbrains.intellij.idea:ideaIC:$ideHostBuild@zip") {
        isTransitive = false
    }
    compileOnly(ideaLibraries)
    compileOnly(kotlinPluginLibraries)
    compileOnly(extractedIdeaFiles { include("**/plugins/java/lib/**/*.jar") })
    compileOnly(extractedIdeaFiles { include("**/plugins/gradle*/lib/**/*.jar") })
    testImplementation(ideaLibraries)
    testImplementation(kotlinPluginLibraries)
    testImplementation(catalog.findLibrary("serialization-json").get())
}

private val workspaceContractFriendPath =
    project(":workspace:contract").layout.buildDirectory.dir("classes/kotlin/main")

tasks.withType<KotlinCompile>().configureEach {
    dependsOn(":workspace:contract:compileKotlin")
    compilerOptions.freeCompilerArgs.add(
        workspaceContractFriendPath.map { directory ->
            "-Xfriend-paths=${directory.asFile.absolutePath}"
        }
    )
}

tasks.withType<Test>().configureEach {
    exclude("**/EpochSignalApiContract.class")
}

// Opt-in manual semantic proof payload. The ordinary read library has no plugin descriptor.
val hostedQueryPluginJar by
    tasks.registering(Jar::class) {
        archiveBaseName.set("kast-hosted-query")
        archiveVersion.set("0.1.0")
        from(sourceSets.main.get().output)
        from(rootProject.layout.projectDirectory.dir("experiments/host-observation/hosted-plugin")) {
            expand(
                "ideBuild" to ideHostBuild,
                "kotlinBuild" to "$ideHostBuild-IJ",
                "registryDigest" to
                    "sha256:" +
                        MessageDigest.getInstance("SHA-256")
                            .digest(
                                rootProject
                                    .file(
                                        "protocol/contract/src/main/resources/ide-hosted/hosted-query.operations.json"
                                    )
                                    .readBytes()
                            )
                            .joinToString("") { "%02x".format(it) },
                "schemaDigest" to
                    "sha256:" +
                        MessageDigest.getInstance("SHA-256")
                            .digest(
                                rootProject
                                    .file("protocol/contract/src/main/resources/ide-hosted/hosted-query.schema.json")
                                    .readBytes()
                            )
                            .joinToString("") { "%02x".format(it) },
            )
        }
    }

val hostedQueryPlugin by
    tasks.registering(Zip::class) {
        group = "distribution"
        description = "Packages the manually activated existing-IDE semantic proof."
        archiveBaseName.set("kast-hosted-query")
        archiveVersion.set("0.1.0")
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
        into("kast-hosted-query/lib") {
            from(hostedQueryPluginJar)
            from(configurations.runtimeClasspath) {
                // Host owns Kotlin, coroutines, serialization, K2, IntelliJ, Java and Gradle libraries.
                include("kernel-*.jar", "workspace-contract-*.jar", "symbol-contract-*.jar", "contract-*.jar")
            }
        }
    }
