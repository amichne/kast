import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test

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

val codexAppServerEvaluation by tasks.registering(JavaExec::class) {
    description = "Runs one configured Codex app-server dynamic-tools evaluation."
    group = "verification"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "io.github.amichne.kast.cli.codex.CodexAppServerEvaluationKt"
    workingDir = rootProject.projectDir
    dependsOn(tasks.named("testClasses"))
    inputs.file(codexEvaluationRequest)
    outputs.file(codexEvaluationEvidence)
    outputs.upToDateWhen { false }
    args(codexEvaluationRequest.get(), codexEvaluationEvidence.get())
}

tasks.named("check") {
    dependsOn(nativeTest)
}
