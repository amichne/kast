import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.JavaExec

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

val closedCliDocumentSources = files(
    layout.projectDirectory.file(
        "src/main/kotlin/io/github/amichne/kast/cli/KastCli.kt",
    ),
    layout.projectDirectory.file(
        "src/main/kotlin/io/github/amichne/kast/cli/bootstrap/InstalledKastCliComposition.kt",
    ),
    layout.projectDirectory.file(
        "src/main/kotlin/io/github/amichne/kast/cli/bootstrap/InstalledSchemaDocuments.kt",
    ),
    fileTree("src/main/kotlin/io/github/amichne/kast/cli/projection") {
        include("*.kt")
        exclude("CliLocalMetadata.kt")
    },
)

val verifyGeneratedCliSerialization =
    tasks.register<support.tasks.VerifyGeneratedSerializationSourcesTask>(
        "verifyGeneratedCliSerialization",
    ) {
        group = "verification"
        description = "Rejects hand-written JSON structure for closed CLI documents."
        sourceFiles.from(closedCliDocumentSources)
        forbiddenTokens.set(
            listOf(
                "KSerializer",
                "kotlinx.serialization.json.JsonArray",
                "kotlinx.serialization.json.JsonElement",
                "kotlinx.serialization.json.JsonObject",
                "kotlinx.serialization.json.JsonPrimitive",
                "JsonArray(",
                "Json {",
                "Json(",
                "JsonObject(",
                "JsonPrimitive(",
                "MapSerializer",
                "buildJsonArray",
                "buildJsonObject",
                "jsonPrimitive",
                "mapOf(",
                ".put(",
                "encodeToString(",
            ),
        )
        generatedAdapterNamePrefixes.set(
            listOf("Canonical", "Topology", "CliBoundary", "Installed"),
        )
        generatedAdapterNameSuffixes.set(
            listOf("Projectors.kt", "CliProjector.kt", "Documents.kt"),
        )
        requiredGeneratedAdapterTokens.set(
            listOf("CliJsonDocument.generated", ".serializer()"),
        )
        reportingRoot.set(layout.projectDirectory)
        reportFile.set(layout.buildDirectory.file("reports/generated-cli-serialization.txt"))
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
    shouldRunAfter(tasks.named("test"))
}

val verifyNoDefaultRuntimeFallbackNegative by tasks.registering(
    support.architecture.gradle.VerifyNoDefaultRuntimeFallbackNegativeTask::class,
) {
    group = "verification"
    description = "Proves the fixed KVP-027 fallback-linked composition is rejected."
    reportFile.set(layout.buildDirectory.file("reports/KVP-027-no-fallback-negative.json"))
}

val verifyNoDefaultRuntimeFallback by tasks.registering(
    support.architecture.gradle.VerifyNoDefaultRuntimeFallbackTask::class,
) {
    group = "verification"
    description = "Proves the installed CLI bytecode reaches only the IDE endpoint runtime path."
    dependsOn(tasks.named("classes"), verifyNoDefaultRuntimeFallbackNegative)
    compiledClassDirectories.from(sourceSets.main.get().output.classesDirs)
    reportFile.set(layout.buildDirectory.file("reports/KVP-027-no-fallback-evidence.json"))
}

val kvp027Packet = support.delivery.canonicalKvp027TaskPacket()
val proveKVP027Cases = tasks.register<support.delivery.Kvp027AtomicProofEvidenceTask>(
    "proveKVP027Cases",
) {
    group = "verification"
    description = "Admits KVP-027's graph-named misuse, legal, and CLI test evidence."
    dependsOn(tasks.named("test"), verifyNoDefaultRuntimeFallback)
    configureFrom(kvp027Packet)
    misuseReportFile.set(
        verifyNoDefaultRuntimeFallbackNegative.flatMap(
            support.architecture.gradle.VerifyNoDefaultRuntimeFallbackNegativeTask::reportFile,
        ),
    )
    legalReportFile.set(
        verifyNoDefaultRuntimeFallback.flatMap(
            support.architecture.gradle.VerifyNoDefaultRuntimeFallbackTask::reportFile,
        ),
    )
    testResultsDirectory.set(layout.buildDirectory.dir("test-results/test"))
    evidenceFile.set(layout.buildDirectory.file("reports/KVP-027-gate-evidence.json"))
}

val kvp026Packet = support.delivery.canonicalKvp026TaskPacket()
val proveKVP026Cases = tasks.register<support.delivery.Kvp026AtomicProofTestTask>(
    "proveKVP026Cases",
) {
    group = "verification"
    description = "Runs only KVP-026's graph-named misuse and legal-path cases."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    configureFrom(kvp026Packet)
    evidenceFile.set(layout.buildDirectory.file("reports/KVP-026-test-evidence.json"))
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
    dependsOn(verifyGeneratedCliSerialization)
    dependsOn(verifyNoDefaultRuntimeFallback)
}
