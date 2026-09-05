import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.process.CommandLineArgumentProvider
import java.io.File

abstract class CodexAppServerEvaluationArguments : CommandLineArgumentProvider {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val requestFile: RegularFileProperty

    @get:OutputFile
    abstract val evidenceFile: RegularFileProperty

    override fun asArguments(): Iterable<String> = listOf(
        requestFile.get().asFile.absolutePath,
        evidenceFile.get().asFile.absolutePath,
    )
}

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

val codexEvaluationRequest = providers.gradleProperty("kastCodexEvaluationRequest")
val codexEvaluationEvidence = providers.gradleProperty("kastCodexEvaluationEvidence")
val codexEvaluationRequestFile = layout.file(codexEvaluationRequest.map(::File))
val codexEvaluationEvidenceFile = layout.file(codexEvaluationEvidence.map(::File))

val codexAppServerEvaluation by tasks.registering(JavaExec::class) {
    description = "Runs one configured Codex app-server dynamic-tools evaluation."
    group = "verification"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "io.github.amichne.kast.cli.codex.CodexAppServerEvaluationKt"
    workingDir = rootProject.projectDir
    dependsOn(tasks.named("testClasses"))
    outputs.upToDateWhen { false }
    argumentProviders.add(
        objects.newInstance<CodexAppServerEvaluationArguments>().apply {
            requestFile.set(codexEvaluationRequestFile)
            evidenceFile.set(codexEvaluationEvidenceFile)
        },
    )
}

val installedColdBrokerAcceptance by tasks.registering(JavaExec::class) {
    description = "Proves a cold installed broker read against real CLI processes without a cloud account."
    group = "verification"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "io.github.amichne.kast.cli.broker.provider.InstalledColdBrokerAcceptance"
    workingDir = rootProject.projectDir
    dependsOn(tasks.named("testClasses"))
    outputs.upToDateWhen { false }
    argumentProviders.add(objects.newInstance<CodexAppServerEvaluationArguments>().apply {
        requestFile.set(layout.file(providers.gradleProperty("kastBrokerAcceptanceRequest").map(::File)))
        evidenceFile.set(layout.file(providers.gradleProperty("kastBrokerAcceptanceEvidence").map(::File)))
    })
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
