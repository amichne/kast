import org.gradle.jvm.tasks.Jar
import org.gradle.language.jvm.tasks.ProcessResources
import support.plugin.BuildStandalonePluginTask
import support.plugin.GenerateIdeHostCompatibilityReportTask
import support.plugin.StandalonePluginNegativeProofTask
import support.plugin.VerifyIdeHostedPluginLayoutNegativeTask
import support.plugin.VerifyIdeHostedPluginLayoutTask
import org.gradle.api.tasks.testing.Test

plugins {
    id("kast.kotlin-serialization")
    id("kast.role.ide-read-only")
}

group = "${rootProject.group}.ide"

base {
    archivesName.set("kast-ide-plugin")
}

dependencies {
    api(project(":protocol:contract"))
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
    wireSchemaIdentity.set("kast-wire-v1")
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
    systemProperty(
        "kast.ide.compatibility.report",
        generateIdeHostCompatibilityReport.flatMap(
            GenerateIdeHostCompatibilityReportTask::reportFile,
        ).get().asFile.absolutePath,
    )
    systemProperty("kast.ide.compatibility.plugin-version", project.version.toString())
}

tasks.named("assemble") {
    dependsOn(buildPlugin)
}

tasks.named("check") {
    dependsOn(
        standalonePluginNegativeProof,
        verifyPluginLayoutNegative,
        generateIdeHostCompatibilityReport,
    )
}
