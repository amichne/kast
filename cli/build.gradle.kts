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
    implementation(project(":distribution:contract"))
    implementation(project(":distribution:managed"))
    implementation(project(":kernel"))
    implementation(project(":protocol:contract"))
    implementation(project(":protocol:registry"))
    implementation(project(":protocol:wire"))
    testImplementation(libs.json.schema.validator)
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

tasks.named("check") {
    dependsOn(nativeTest)
}
