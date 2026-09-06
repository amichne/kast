import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.testing.Test
import org.gradle.process.CommandLineArgumentProvider

abstract class KastObserverSnapshotArguments : CommandLineArgumentProvider {
    @get:OutputFile
    abstract val manifestFile: RegularFileProperty

    override fun asArguments(): Iterable<String> = listOf(
        manifestFile.get().asFile.absolutePath,
    )
}

plugins {
    id("kast.runtime-serialization-app")
    id("kast.role.cli")
}

application {
    applicationName = "kast"
    mainClass = "io.github.amichne.kast.cli.KastCliMainKt"
}

dependencies {
    implementation(libs.clikt.core)
    implementation(libs.bundles.coroutines)
    implementation(libs.json.schema.validator)
    implementation(libs.bundles.ktor.broker)
    runtimeOnly(libs.logback.classic)
    implementation(project(":distribution:contract"))
    implementation(project(":distribution:managed"))
    implementation(project(":kernel"))
    implementation(project(":protocol:contract"))
    implementation(project(":protocol:registry"))
    implementation(project(":protocol:wire"))
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("native")
    }
}

val nativeTest by tasks.registering(Test::class) {
    description = "Runs native UDS CLI boundary tests."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("native")
    }
}

val kastObserverSnapshotManifest = layout.buildDirectory.file(
    "observer-snapshots/kast-observer-presentations.json",
)

val generateKastObserverSnapshotManifest by tasks.registering(JavaExec::class) {
    description = "Projects deterministic Kast observer fixtures without starting Codex."
    group = "documentation"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "io.github.amichne.kast.cli.broker.provider.KastObserverSnapshotMain"
    workingDir = rootProject.projectDir
    dependsOn(tasks.named("testClasses"))
    argumentProviders.add(
        objects.newInstance<KastObserverSnapshotArguments>().apply {
            manifestFile.set(kastObserverSnapshotManifest)
        },
    )
}

val kastObserverSnapshotScript = rootProject.layout.projectDirectory.file(
    "docs/render_kast_observer_snapshots.py",
)
val kastObserverSnapshotStyles = rootProject.layout.projectDirectory.file(
    "docs/kast-observer-snapshots.css",
)
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

tasks.named("check") {
    dependsOn(nativeTest)
}

val codexIntegrationStartScripts by tasks.registering(CreateStartScripts::class) {
    applicationName = "kast-codex"
    mainClass = "io.github.amichne.kast.cli.broker.KastCodexMain"
    outputDir = layout.buildDirectory.dir("codex-integration-scripts").get().asFile
    classpath = tasks.named<CreateStartScripts>("startScripts").get().classpath
    dependsOn(tasks.named("jar"))
}

distributions.main {
    contents {
        from(codexIntegrationStartScripts) {
            into("bin")
            exclude("*.bat")
            filePermissions { unix("755") }
        }
    }
}
