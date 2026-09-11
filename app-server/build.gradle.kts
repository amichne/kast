import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.process.CommandLineArgumentProvider

abstract class KastObserverSnapshotArguments : CommandLineArgumentProvider {
    @get:OutputFile abstract val manifestFile: RegularFileProperty

    override fun asArguments(): Iterable<String> = listOf(manifestFile.get().asFile.absolutePath)
}

abstract class CodexHostManifestArguments : CommandLineArgumentProvider {
    @get:OutputFile abstract val manifestFile: RegularFileProperty

    @get:InputFile abstract val installedAcceptanceFile: RegularFileProperty

    override fun asArguments(): Iterable<String> =
        listOf(
            manifestFile.get().asFile.absolutePath,
            installedAcceptanceFile.get().asFile.absolutePath,
        )
}

plugins {
    id("kast.kotlin-library")
    kotlin("plugin.serialization")
    id("kast.role.app-server")
    id("kast.public-query-contract")
}

dependencies {
    testImplementation(libs.jimfs)
    implementation(libs.bundles.coroutines)
    implementation(libs.serialization.json)
    implementation(libs.json.schema.validator)
    implementation(libs.bundles.ktor.broker)
    implementation(project(":kernel"))
    implementation(project(":distribution:contract"))
    implementation(project(":distribution:managed"))
    implementation(project(":protocol:contract"))
    implementation(project(":protocol:registry"))
}

val kastObserverSnapshotManifest = layout.buildDirectory.file("observer-snapshots/kast-observer-presentations.json")

val generateKastObserverSnapshotManifest by
    tasks.registering(JavaExec::class) {
        description = "Projects deterministic Kast observer fixtures without starting Codex."
        group = "documentation"
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass = "io.github.amichne.kast.appserver.provider.KastObserverSnapshotMain"
        workingDir = rootProject.projectDir
        dependsOn(tasks.named("testClasses"))
        argumentProviders.add(
            objects.newInstance<KastObserverSnapshotArguments>().apply {
                manifestFile.set(kastObserverSnapshotManifest)
            }
        )
    }

val codexHostIntegrationManifest = layout.buildDirectory.file("reports/codex-host/codex-host-integration.json")
val installedCodexHostReceipt = rootProject.layout.buildDirectory.file("reports/installed-product/codex-host.json")

tasks.register<JavaExec>("generateCodexHostIntegrationManifest") {
    description = "Binds Codex host modes, catalog projection, installed proof, and source state."
    group = "verification"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "io.github.amichne.kast.appserver.CodexHostIntegrationManifestMain"
    workingDir = rootProject.projectDir
    outputs.upToDateWhen { false }
    dependsOn(
        tasks.named("test"),
        rootProject.tasks.named("installedProductTest"),
        rootProject.tasks.named("installedCodexHostTest"),
        rootProject.tasks.named("verifyKastArchitecture"),
    )
    argumentProviders.add(
        objects.newInstance<CodexHostManifestArguments>().apply {
            manifestFile.set(codexHostIntegrationManifest)
            installedAcceptanceFile.set(installedCodexHostReceipt)
        }
    )
}

val kastObserverSnapshotScript = rootProject.layout.projectDirectory.file("docs/render_kast_observer_snapshots.py")
val kastObserverSnapshotStyles = rootProject.layout.projectDirectory.file("docs/kast-observer-snapshots.css")
val kastObserverSnapshotOutput = rootProject.layout.projectDirectory.dir("docs/public/images")

tasks.register<Exec>("renderKastObserverScreenshots") {
    description = "Renders offline Kast observer PNGs from deterministic projected fixtures."
    group = "documentation"
    dependsOn(generateKastObserverSnapshotManifest)
    inputs.file(kastObserverSnapshotManifest)
    inputs.file(kastObserverSnapshotScript)
    inputs.file(kastObserverSnapshotStyles)
    outputs.files(
        kastObserverSnapshotOutput.file("kast-observer-symbol-source.png"),
        kastObserverSnapshotOutput.file("kast-observer-semantic-impact.png"),
        kastObserverSnapshotOutput.file("kast-observer-change-lifecycle.png"),
    )
    commandLine(
        "python3",
        kastObserverSnapshotScript.asFile.absolutePath,
        "--manifest",
        kastObserverSnapshotManifest.get().asFile.absolutePath,
        "--styles",
        kastObserverSnapshotStyles.asFile.absolutePath,
        "--output-directory",
        kastObserverSnapshotOutput.asFile.absolutePath,
    )
}

val installedWorkspaceHarnessClasspath = layout.buildDirectory.file("acceptance/installed-workspace-harness.classpath")

tasks.register("writeInstalledWorkspaceHarnessClasspath") {
    group = "verification"
    description = "Projects the test-only installed routing harness classpath; never ships it in the product."
    dependsOn(tasks.named("testClasses"))
    val harnessClasspath = sourceSets.test.get().runtimeClasspath
    val classpathOutput = installedWorkspaceHarnessClasspath
    inputs.files(harnessClasspath)
    outputs.file(classpathOutput)
    doLast {
        classpathOutput.get().asFile.apply {
            parentFile.mkdirs()
            writeText(harnessClasspath.filter { it.exists() }.asPath)
        }
    }
}
