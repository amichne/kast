import java.security.MessageDigest
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import support.tasks.idea.IntellijSdkLibraryFamily
import support.tasks.idea.selectedIntellijTarget

plugins {
    `java-test-fixtures`
    id("kast.kotlin-library")
    kotlin("plugin.serialization")
    id("kast.role.ide-read-only")
}

val repositoryRoot = project.isolated.rootProject.projectDirectory

group = "${providers.gradleProperty("GROUP").get()}.workspace"

base {
    archivesName.set("workspace-intellij-read")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val intellij = selectedIntellijTarget(project).also { it.configureModule(project) }
private val ideHostBuild = intellij.profile.ideBuild

val workspaceReadIdeaDistribution =
    configurations.create("workspaceReadIdeaDistribution") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }

private val extractedIdeaDistributionDirectory = intellij.extractedSdkHome(project)

val extractWorkspaceReadIdeaDistribution =
    tasks.register<ExtractIdeaDistributionTask>("extractWorkspaceReadIdeaDistribution") {
        archives.from(workspaceReadIdeaDistribution)
        ideaVersion.set(ideHostBuild)
        libraryFamilies.set(
            setOf(
                IdeaLibraryFamily.PLATFORM,
                IdeaLibraryFamily.KOTLIN,
                IdeaLibraryFamily.JAVA,
                IdeaLibraryFamily.GRADLE,
            )
        )
        outputDirectory.set(extractedIdeaDistributionDirectory)
    }

private val ideaLibraries = intellij.libraries(project, IntellijSdkLibraryFamily.PLATFORM)
private val kotlinPluginLibraries = intellij.libraries(project, IntellijSdkLibraryFamily.KOTLIN)
private val javaPluginLibraries = intellij.libraries(project, IntellijSdkLibraryFamily.JAVA)
private val gradlePluginLibraries = intellij.libraries(project, IntellijSdkLibraryFamily.GRADLE)

dependencies {
    implementation(project(":protocol:contract"))
    implementation(project(":workspace:contract"))
    implementation(project(":symbol:contract"))
    compileOnly(catalog.findLibrary("serialization-json").get())

    workspaceReadIdeaDistribution("com.jetbrains.intellij.idea:ideaIC:$ideHostBuild@zip") {
        isTransitive = false
    }
    compileOnly(ideaLibraries)
    compileOnly(kotlinPluginLibraries)
    compileOnly(javaPluginLibraries)
    compileOnly(gradlePluginLibraries)
    testImplementation(gradlePluginLibraries)
    testImplementation(ideaLibraries)
    testImplementation(kotlinPluginLibraries)
    testImplementation(catalog.findLibrary("serialization-json").get())
    testImplementation(testFixtures(project(":workspace:contract")))
    testFixturesApi(project(":workspace:contract"))
    testFixturesApi(project(":protocol:contract"))
    testFixturesApi(catalog.findLibrary("coroutines-core").get())
    testFixturesCompileOnly(ideaLibraries)
    testFixturesCompileOnly(kotlinPluginLibraries)
    testFixturesCompileOnly(javaPluginLibraries)
    testFixturesImplementation(project(":symbol:contract"))
    testFixturesImplementation(testFixtures(project(":workspace:contract")))
}

private val workspaceContractFriendPath = providers.provider {
    repositoryRoot.dir("workspace/contract/build/classes/kotlin/main")
}

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
val hostedQueryPluginJar =
    tasks.register<Jar>("hostedQueryPluginJar") {
        archiveBaseName.set("kast-hosted-query")
        archiveVersion.set("0.1.0")
        from(sourceSets.main.get().output)
        from(repositoryRoot.dir("experiments/host-observation/hosted-plugin")) {
            expand(
                "ideBuild" to ideHostBuild,
                "kotlinBuild" to intellij.profile.kotlinPluginBuild,
                "registryDigest" to
                    "sha256:" +
                        MessageDigest.getInstance("SHA-256")
                            .digest(
                                File(
                                        repositoryRoot.asFile,
                                        "protocol/contract/src/main/resources/ide-hosted/hosted-query.operations.json",
                                    )
                                    .readBytes()
                            )
                            .joinToString("") { "%02x".format(it) },
                "schemaDigest" to
                    "sha256:" +
                        MessageDigest.getInstance("SHA-256")
                            .digest(
                                File(
                                        repositoryRoot.asFile,
                                        "protocol/contract/src/main/resources/ide-hosted/hosted-query.schema.json",
                                    )
                                    .readBytes()
                            )
                            .joinToString("") { "%02x".format(it) },
            )
        }
    }

val hostedQueryPlugin =
    tasks.register<Zip>("hostedQueryPlugin") {
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

// The actual encoder is compared with the exact bytes also validated by CLI schema tests.
tasks.named("test") {
    inputs
        .file(repositoryRoot.file("cli/src/test/resources/hosted-read-failure-encodings.json"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
