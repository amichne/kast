import org.gradle.jvm.tasks.Jar
import org.gradle.language.jvm.tasks.ProcessResources
import support.plugin.BuildStandalonePluginTask
import support.plugin.StandalonePluginNegativeProofTask

plugins {
    id("kast.kotlin-library")
    id("kast.role.ide-read-only")
}

group = "${rootProject.group}.ide"

base {
    archivesName.set("kast-ide-plugin")
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

tasks.named("assemble") {
    dependsOn(buildPlugin)
}

tasks.named("check") {
    dependsOn(standalonePluginNegativeProof)
}
