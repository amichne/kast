import org.gradle.jvm.tasks.Jar
import org.gradle.language.jvm.tasks.ProcessResources
import support.plugin.BuildStandalonePluginTask
import support.plugin.GenerateIdeHostCompatibilityReportTask
import support.plugin.StandalonePluginNegativeProofTask
import support.plugin.VerifyIdeHostedPluginLayoutNegativeTask
import support.plugin.VerifyIdeHostedPluginLayoutTask
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.PathSensitivity

plugins {
    id("kast.kotlin-serialization")
    id("kast.role.ide-read-only")
}

group = "${rootProject.group}.ide"

base {
    archivesName.set("kast-ide-plugin")
}

dependencies {
    implementation(project(":protocol:contract"))
}

tasks.named<Jar>("jar") {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.named<ProcessResources>("processResources") {
    from(
        rootProject.layout.projectDirectory.file(
            "indexer/src/main/resources/META-INF/plugin.xml",
        ),
    ) {
        into("META-INF")
    }
}

val standalonePluginNegativeProof by tasks.registering(StandalonePluginNegativeProofTask::class) {
    group = "verification"
    description = "Proves the fixed missing, private-layout, and platform-class rejections."
}

val stagedIndexerPayload = rootProject.layout.projectDirectory.dir("indexer/build/plugin-payload")

val buildPlugin by tasks.registering(BuildStandalonePluginTask::class) {
    group = "build"
    description = "Builds the deterministic standalone Kast IntelliJ plugin ZIP and proof report."
    dependsOn(standalonePluginNegativeProof, tasks.named("jar"), ":indexer:syncIndexerPluginPayload")
    payloadJars.from(tasks.named<Jar>("jar").flatMap(Jar::getArchiveFile))
    payloadJars.from(fileTree(stagedIndexerPayload) { include("*.jar") })
    repositoryRoot.set(rootProject.layout.projectDirectory)
    pluginArchive.set(
        layout.buildDirectory.file("distributions/kast-ide-plugin-${project.version}.zip"),
    )
    reportFile.set(layout.buildDirectory.file("reports/KVP-010-plugin.json"))
}

val verifyPluginLayoutNegative by tasks.registering(
    VerifyIdeHostedPluginLayoutNegativeTask::class,
) {
    group = "verification"
    description = "Proves all fixed KVP-011 forbidden classpath injections are rejected."
}

val verifyPluginLayout by tasks.registering(VerifyIdeHostedPluginLayoutTask::class) {
    group = "verification"
    description = "Proves the plugin ZIP and every nested JAR satisfy the hosted read-only policy."
    dependsOn(buildPlugin, verifyPluginLayoutNegative)
    pluginArchive.set(buildPlugin.flatMap(BuildStandalonePluginTask::pluginArchive))
    repositoryRoot.set(rootProject.layout.projectDirectory)
    reportFile.set(layout.buildDirectory.file("reports/KVP-011-layout.json"))
}

val operationRegistryArtifact = project(":protocol:wire").layout.buildDirectory.file(
    "generated/operation-registry/operation-registry.json",
)

val generateIdeHostCompatibilityReport by tasks.registering(
    GenerateIdeHostCompatibilityReportTask::class,
) {
    group = "verification"
    description = "Projects the exact KVP-012 IDE-host compatibility tuple and artifact digests."
    dependsOn(":protocol:wire:generateOperationRegistry")
    ideBuild.set(libs.versions.ide.host.build)
    kotlinPluginBuild.set(libs.versions.ide.kotlin.plugin.build)
    kastPluginVersion.set(project.version.toString())
    runtimeProtocolIdentity.set("kast.ide-hosted.runtime.v1")
    capabilities.set(
        listOf(
            "workspace.inspect",
            "symbol.discover",
            "symbol.resolve",
            "symbol.describe",
        ),
    )
    operationRegistryFile.set(operationRegistryArtifact)
    reportFile.set(layout.buildDirectory.file("reports/KVP-012-compatibility.json"))
}

tasks.withType<Test>().configureEach {
    dependsOn(generateIdeHostCompatibilityReport)
    inputs.file(generateIdeHostCompatibilityReport.flatMap(
        GenerateIdeHostCompatibilityReportTask::reportFile,
    )).withPathSensitivity(PathSensitivity.NONE)
    systemProperty(
        "kast.ide.compatibility.report",
        generateIdeHostCompatibilityReport.flatMap(
            GenerateIdeHostCompatibilityReportTask::reportFile,
        ).get().asFile.absolutePath,
    )
    systemProperty("kast.ide.compatibility.plugin-version", project.version.toString())
}

val defaultTest = tasks.named<Test>("test")
val endpointPublicationReport = layout.buildDirectory.file("reports/KVP-024-endpoint.json")
val generateIdeEndpointPublicationReport =
    tasks.register<support.delivery.GenerateKvp024EndpointPublicationReportTask>(
        "generateIdeEndpointPublicationReport",
    ) {
        group = "verification"
        description = "Generates the exact predecessor-bound KVP-024 endpoint report."
        dependsOn(
            rootProject.tasks.named("verifyKVP013CompletionReceipt"),
            rootProject.tasks.named("verifyKVP023CompletionReceipt"),
        )
        kvp013CompletionReceipt.set(rootProject.layout.buildDirectory.file(
            "reports/delivery/receipts/KVP-013-COMPLETE.receipt.json",
        ))
        kvp023CompletionReceipt.set(rootProject.layout.buildDirectory.file(
            "reports/delivery/receipts/KVP-023-COMPLETE.receipt.json",
        ))
        reportFile.set(endpointPublicationReport)
    }

val verifyIdeEndpointPublicationReportNegative =
    tasks.register<support.delivery.VerifyKvp024EndpointPublicationReportNegativeTask>(
        "verifyIdeEndpointPublicationReportNegative",
    ) {
        group = "verification"
        description = "Rejects every fixed KVP-024 report, predecessor, and gate mutation."
        dependsOn(generateIdeEndpointPublicationReport)
        reportFile.set(endpointPublicationReport)
        kvp013CompletionReceipt.set(generateIdeEndpointPublicationReport.flatMap(
            support.delivery.GenerateKvp024EndpointPublicationReportTask::kvp013CompletionReceipt,
        ))
        kvp023CompletionReceipt.set(generateIdeEndpointPublicationReport.flatMap(
            support.delivery.GenerateKvp024EndpointPublicationReportTask::kvp023CompletionReceipt,
        ))
    }

fun support.delivery.Kvp024IdeEndpointPublicationGateTask.configureEndpointPublicationGate(
    command: support.delivery.Kvp024GateCommand,
    evidencePath: String,
) {
    group = "verification"
    testClassesDirs = defaultTest.get().testClassesDirs
    classpath = defaultTest.get().classpath
    filter.includeTestsMatching(command.selectorPattern)
    filter.setFailOnNoMatchingTests(true)
    dependsOn(verifyIdeEndpointPublicationReportNegative)
    repositoryRootPath.set(rootProject.layout.projectDirectory.asFile.absolutePath)
    declaredCommand.set(command.declaredCommand)
    gateEvidenceFile.set(layout.buildDirectory.file(evidencePath))
    doFirst { beginGateExecution() }
    doLast { completeGateExecution() }
    inputs.file(endpointPublicationReport).withPathSensitivity(PathSensitivity.NONE)
    systemProperty(
        "kast.ide.endpoint.report",
        endpointPublicationReport.get().asFile.absolutePath,
    )
}

val verifyIdeEndpointPublicationNegative =
    tasks.register<support.delivery.Kvp024IdeEndpointPublicationGateTask>(
        "verifyIdeEndpointPublicationNegative",
    ) {
        description = "Runs only the canonical KVP-024 negative selector at one exact head."
        configureEndpointPublicationGate(
            support.delivery.Kvp024GateCommand.RED,
            "reports/KVP-024-red-gate.json",
        )
    }

val verifyIdeEndpointPublication =
    tasks.register<support.delivery.Kvp024IdeEndpointPublicationGateTask>(
        "verifyIdeEndpointPublication",
    ) {
        description = "Runs only the canonical KVP-024 positive selector at one exact head."
        configureEndpointPublicationGate(
            support.delivery.Kvp024GateCommand.GREEN,
            "reports/KVP-024-green-gate.json",
        )
        mustRunAfter(verifyIdeEndpointPublicationNegative, ":recordKVP024RedReceipt")
    }

tasks.named("assemble") {
    dependsOn(buildPlugin)
}

tasks.named("check") {
    dependsOn(
        standalonePluginNegativeProof,
        verifyPluginLayoutNegative,
        generateIdeHostCompatibilityReport,
        verifyIdeEndpointPublicationReportNegative,
        verifyIdeEndpointPublicationNegative,
        verifyIdeEndpointPublication,
    )
}
